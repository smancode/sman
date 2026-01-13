package com.smancode.smanagent.service;

import com.smancode.smanagent.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * 业务图谱服务
 *
 * 核心功能：
 * 1. 分析代码，提取业务术语
 * 2. 建立术语关系
 * 3. 映射代码元素
 * 4. 理解用户问题，返回相关业务信息
 */
@Service
public class BusinessGraphService {

    private static final Logger logger = LoggerFactory.getLogger(BusinessGraphService.class);

    // 业务术语缓存
    private final Map<String, BusinessTerm> termCache = new HashMap<>();
    // 术语关系缓存
    private final List<TermRelation> relationCache = new ArrayList<>();
    // 代码元素索引
    private final Map<String, List<CodeElement>> codeIndex = new HashMap<>();

    /**
     * 分析项目代码，构建业务图谱
     *
     * @param projectPath 项目路径
     */
    public void analyzeProject(String projectPath) {
        if (projectPath == null || projectPath.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 projectPath 参数");
        }

        logger.info("开始分析项目: {}", projectPath);

        Path rootPath = Paths.get(projectPath);
        if (!Files.exists(rootPath)) {
            throw new IllegalArgumentException("项目路径不存在: " + projectPath);
        }

        // 清空缓存
        termCache.clear();
        relationCache.clear();
        codeIndex.clear();

        // 1. 扫描 Java 文件
        List<File> javaFiles = findJavaFiles(rootPath);
        logger.info("找到 {} 个 Java 文件", javaFiles.size());

        // 2. 分析每个文件，提取业务术语和代码元素
        for (File file : javaFiles) {
            analyzeJavaFile(file);
        }

        // 3. 建立术语关系
        buildTermRelations();

        logger.info("业务图谱构建完成: {} 个术语, {} 个关系", termCache.size(), relationCache.size());
    }

    /**
     * 业务问答接口
     *
     * @param question 用户问题
     * @return 业务分析结果
     */
    public BusinessAnalysis ask(String question) {
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 question 参数");
        }

        logger.info("处理用户问题: {}", question);

        BusinessAnalysis analysis = new BusinessAnalysis();
        analysis.setUserQuestion(question);

        // 1. 识别问题中的业务术语
        List<String> terms = extractTermsFromQuestion(question);
        analysis.setIdentifiedTerms(terms);

        // 2. 为每个术语生成解释
        Map<String, String> explanations = new HashMap<>();
        for (String term : terms) {
            BusinessTerm businessTerm = termCache.get(term);
            if (businessTerm != null) {
                explanations.put(term, generateTermExplanation(businessTerm));
            } else {
                explanations.put(term, "未知术语，需要从代码中推断含义");
            }
        }
        analysis.setTermExplanations(explanations);

        // 3. 查找相关术语关系
        List<TermRelation> relations = findRelationsForTerms(terms);
        analysis.setRelations(relations);

        // 4. 查找相关代码
        List<CodeElement> relevantCode = findRelevantCode(terms);
        analysis.setRelevantCode(relevantCode);

        // 5. 标记缺失信息
        List<String> missingInfo = identifyMissingInfo(question, terms);
        analysis.setMissingInfo(missingInfo);

        return analysis;
    }

    /**
     * 查询术语关系
     */
    public List<TermRelation> queryTermRelations(String term, int maxDepth) {
        if (term == null || term.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 term 参数");
        }
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth 必须大于 0");
        }

        List<TermRelation> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        // BFS 搜索关系
        Queue<String> queue = new LinkedList<>();
        queue.add(term);
        visited.add(term);

        while (!queue.isEmpty() && maxDepth > 0) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                String current = queue.poll();

                // 查找与当前术语相关的关系
                for (TermRelation relation : relationCache) {
                    if (relation.getFromTerm().equals(current) && !visited.contains(relation.getToTerm())) {
                        result.add(relation);
                        visited.add(relation.getToTerm());
                        queue.add(relation.getToTerm());
                    }
                }
            }
            maxDepth--;
        }

        return result;
    }

    /**
     * 根据术语查询代码
     */
    public List<CodeElement> queryCodeByTerm(String term) {
        if (term == null || term.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 term 参数");
        }

        BusinessTerm businessTerm = termCache.get(term);
        if (businessTerm != null && businessTerm.getCodeMappings() != null) {
            return businessTerm.getCodeMappings();
        }

        // 如果术语不存在，尝试从代码索引中搜索
        return codeIndex.getOrDefault(term, new ArrayList<>());
    }

    /**
     * 查询方法调用链
     */
    public List<CodeElement> queryCallChain(String methodName) {
        if (methodName == null || methodName.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 methodName 参数");
        }

        // 简化实现：查找所有包含该方法名的代码元素
        List<CodeElement> result = new ArrayList<>();
        for (List<CodeElement> elements : codeIndex.values()) {
            for (CodeElement element : elements) {
                if (element.getName().contains(methodName)) {
                    result.add(element);
                }
            }
        }
        return result;
    }

    /**
     * 获取术语列表
     */
    public List<BusinessTerm> getTerms(Double minConfidence, Boolean topByFreq) {
        List<BusinessTerm> terms = new ArrayList<>(termCache.values());

        if (minConfidence != null) {
            terms = terms.stream()
                .filter(t -> t.getConfidence() != null && t.getConfidence() >= minConfidence)
                .collect(Collectors.toList());
        }

        // TODO: 实现按频率排序
        return terms;
    }

    /**
     * 获取图谱统计信息
     */
    public Map<String, Object> getGraphStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTerms", termCache.size());
        stats.put("totalRelations", relationCache.size());
        stats.put("totalCodeMappings", codeIndex.values().stream().mapToInt(List::size).sum());
        stats.put("lastUpdateTimestamp", System.currentTimeMillis());
        return stats;
    }

    // ==================== 私有方法 ====================

    /**
     * 查找所有 Java 文件
     */
    private List<File> findJavaFiles(Path rootPath) {
        try {
            return Files.walk(rootPath)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("/test/")) // 排除测试文件
                .map(Path::toFile)
                .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("扫描 Java 文件失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 分析 Java 文件
     */
    private void analyzeJavaFile(File file) {
        try {
            String content = Files.readString(file.toPath());

            // 提取类名
            Pattern classPattern = Pattern.compile("class\\s+(\\w+)");
            Matcher classMatcher = classPattern.matcher(content);
            if (classMatcher.find()) {
                String className = classMatcher.group(1);
                extractBusinessTermsFromClass(className, content, file.getAbsolutePath());
            }

            // 提取方法名
            Pattern methodPattern = Pattern.compile("(?:public|private|protected)?\\s*(?:static)?\\s*\\w+\\s+(\\w+)\\s*\\(");
            Matcher methodMatcher = methodPattern.matcher(content);
            while (methodMatcher.find()) {
                String methodName = methodMatcher.group(1);
                indexCodeElement(methodName, "METHOD", methodName, file.getAbsolutePath(), content);
            }

        } catch (IOException e) {
            logger.error("读取文件失败: {}", file.getAbsolutePath(), e);
        }
    }

    /**
     * 从类中提取业务术语
     */
    private void extractBusinessTermsFromClass(String className, String content, String filePath) {
        // 简化的术语提取逻辑
        // 1. 类名本身可能是业务术语
        if (isBusinessTerm(className)) {
            BusinessTerm term = new BusinessTerm(className, "ENTITY");
            term.setDescription("业务实体类");
            term.setConfidence(0.8);

            CodeElement element = new CodeElement("CLASS", className, className, filePath, 1);
            term.setCodeMappings(List.of(element));

            termCache.putIfAbsent(className, term);
            indexCodeElement(className, "CLASS", className, filePath, content);
        }

        // 2. 从注释中提取术语
        Pattern commentPattern = Pattern.compile("/\\*\\*?(.*?)\\*/", Pattern.DOTALL);
        Matcher commentMatcher = commentPattern.matcher(content);
        while (commentMatcher.find()) {
            String comment = commentMatcher.group(1);
            extractTermsFromComment(comment, filePath, content);
        }

        // 3. 从字段中提取术语
        Pattern fieldPattern = Pattern.compile("private\\s+(?:\\w+)\\s+(\\w+);");
        Matcher fieldMatcher = fieldPattern.matcher(content);
        while (fieldMatcher.find()) {
            String fieldName = fieldMatcher.group(1);
            if (isBusinessTerm(fieldName)) {
                BusinessTerm term = new BusinessTerm(fieldName, "ENTITY");
                term.setDescription("业务字段");
                term.setConfidence(0.7);
                termCache.putIfAbsent(fieldName, term);
            }
        }
    }

    /**
     * 判断是否为业务术语
     */
    private boolean isBusinessTerm(String name) {
        // 简化的判断逻辑：非通用技术术语
        String[] techTerms = {"Service", "Controller", "Repository", "Config", "Util", "Helper",
                            "Request", "Response", "DTO", "VO", "Entity", "Manager"};
        for (String tech : techTerms) {
            if (name.endsWith(tech)) {
                return false;
            }
        }
        return name.length() > 2; // 至少3个字符
    }

    /**
     * 从注释中提取术语
     */
    private void extractTermsFromComment(String comment, String filePath, String content) {
        // 简化实现：提取中文词汇
        Pattern chinesePattern = Pattern.compile("([\u4e00-\u9fa5]{2,})");
        Matcher matcher = chinesePattern.matcher(comment);
        while (matcher.find()) {
            String term = matcher.group(1);
            if (!termCache.containsKey(term)) {
                BusinessTerm businessTerm = new BusinessTerm(term, "ENTITY");
                businessTerm.setDescription("从注释中提取的业务术语");
                businessTerm.setConfidence(0.6);
                termCache.put(term, businessTerm);
            }
        }
    }

    /**
     * 索引代码元素
     */
    private void indexCodeElement(String term, String type, String name, String filePath, String content) {
        CodeElement element = new CodeElement(type, name, name, filePath, 1);
        element.setCode(content);

        codeIndex.computeIfAbsent(term, k -> new ArrayList<>()).add(element);
    }

    /**
     * 建立术语关系
     */
    private void buildTermRelations() {
        // 简化实现：基于命名推断关系
        for (BusinessTerm term : termCache.values()) {
            String name = term.getName();

            // 查找可能相关的术语
            for (BusinessTerm other : termCache.values()) {
                if (name.equals(other.getName())) {
                    continue;
                }

                // 如果名称包含关系（如 CustomerLoan 包含 Customer 和 Loan）
                if (name.contains(other.getName())) {
                    TermRelation relation = new TermRelation(name, "HAS_A", other.getName());
                    relation.setConfidence(0.7);
                    relationCache.add(relation);
                }
            }
        }
    }

    /**
     * 从问题中提取业务术语
     */
    private List<String> extractTermsFromQuestion(String question) {
        List<String> terms = new ArrayList<>();

        // 简化实现：匹配已知的业务术语
        for (String term : termCache.keySet()) {
            if (question.contains(term)) {
                terms.add(term);
            }
        }

        return terms;
    }

    /**
     * 生成术语解释
     */
    private String generateTermExplanation(BusinessTerm term) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(term.getCategory()).append("] ");
        sb.append(term.getDescription() != null ? term.getDescription() : "暂无描述");

        if (term.getCodeMappings() != null && !term.getCodeMappings().isEmpty()) {
            sb.append("\n  映射到代码: ").append(term.getCodeMappings().size()).append(" 个元素");
        }

        return sb.toString();
    }

    /**
     * 查找术语相关的关系
     */
    private List<TermRelation> findRelationsForTerms(List<String> terms) {
        return relationCache.stream()
            .filter(r -> terms.contains(r.getFromTerm()) || terms.contains(r.getToTerm()))
            .collect(Collectors.toList());
    }

    /**
     * 查找相关代码
     */
    private List<CodeElement> findRelevantCode(List<String> terms) {
        List<CodeElement> result = new ArrayList<>();
        for (String term : terms) {
            result.addAll(codeIndex.getOrDefault(term, new ArrayList<>()));
        }
        return result;
    }

    /**
     * 识别缺失信息
     */
    private List<String> identifyMissingInfo(String question, List<String> terms) {
        List<String> missing = new ArrayList<>();

        // 检查是否有未识别的关键词
        Pattern chinesePattern = Pattern.compile("([\u4e00-\u9fa5]{2,})");
        Matcher matcher = chinesePattern.matcher(question);
        while (matcher.find()) {
            String word = matcher.group(1);
            if (!termCache.containsKey(word)) {
                missing.add("术语 '" + word + "' 未在业务图谱中定义");
            }
        }

        return missing;
    }
}
