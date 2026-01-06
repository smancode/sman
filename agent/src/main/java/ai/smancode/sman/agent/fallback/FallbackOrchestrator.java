package ai.smancode.sman.agent.fallback;

import ai.smancode.sman.agent.ast.SpoonAstService;
import ai.smancode.sman.agent.callchain.CallChainService;
import ai.smancode.sman.agent.config.ProjectConfigService;
import ai.smancode.sman.agent.models.CallChainModels.CallChainRequest;
import ai.smancode.sman.agent.models.CallChainModels.CallChainResult;
import ai.smancode.sman.agent.models.SpoonModels.ClassInfo;
import ai.smancode.sman.agent.models.VectorModels.SearchResult;
import ai.smancode.sman.agent.models.VectorModels.SemanticSearchRequest;
import ai.smancode.sman.agent.vector.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 降级编排器
 *
 * 功能：
 * - 在降级模式下处理用户请求
 * - 分析用户意图（基于关键词匹配）
 * - 调用后端工具直接处理
 * - 组装降级模式响应
 *
 * @author SiliconMan Team
 * @since 2.0
 */
@Component
public class FallbackOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FallbackOrchestrator.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private SpoonAstService spoonAstService;

    @Autowired(required = false)
    private CallChainService callChainService;

    @Autowired
    private ProjectConfigService projectConfigService;

    /**
     * 处理用户请求（降级模式）
     */
    public String processRequest(String userMessage, String projectKey, String sessionId) {
        log.info("🔴 降级模式处理请求: projectKey={}, message={}", projectKey, userMessage);

        long startTime = System.currentTimeMillis();

        try {
            // 🆕 关键步骤: 查询 projectPath
            String projectPath = projectConfigService.getProjectPath(projectKey);
            log.info("📋 查询到 projectPath: {}", projectPath);

            // 1. 分析用户意图
            Intent intent = analyzeIntent(userMessage);
            log.info("📊 分析用户意图: {} ({})", intent.getDescription(), intent.name());

            // 2. 根据意图调用对应工具（传递 projectPath）
            String result = executeTool(intent, userMessage, projectKey, projectPath);

            // 3. 组装响应（带降级提示）
            String response = buildResponse(result, intent, projectKey, projectPath);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("✅ 降级模式处理完成 (耗时: {} ms)", elapsed);

            return response;

        } catch (IllegalArgumentException e) {
            log.error("❌ projectKey 映射未找到: projectKey={}, error={}", projectKey, e.getMessage());
            return buildErrorResponse(e.getMessage(), projectKey);
        } catch (Exception e) {
            log.error("❌ 降级模式处理失败", e);
            return buildErrorResponse(e.getMessage(), projectKey);
        }
    }

    /**
     * 分析用户意图
     */
    private Intent analyzeIntent(String message) {
        String lowerMessage = message.toLowerCase();

        // 搜索相关代码（优先级最高）
        if (containsAny(lowerMessage, "搜索", "查找", "相关", "有没有", "存在", "列出", "list")) {
            return Intent.SEARCH;
        }

        // 分析类结构
        if (containsAny(lowerMessage, "类", "structure") &&
            containsAny(lowerMessage, "结构", "方法", "字段", "成员")) {
            return Intent.READ_CLASS;
        }

        // 调用链分析
        if (containsAny(lowerMessage, "调用", "call", "invoke") &&
            containsAny(lowerMessage, "关系", "链", "谁调用", "被谁")) {
            return Intent.CALL_CHAIN;
        }

        // 查找引用
        if (containsAny(lowerMessage, "引用", "usage", "在哪用", "在哪里使用")) {
            return Intent.FIND_USAGES;
        }

        // 默认：搜索
        return Intent.SEARCH;
    }

    /**
     * 检查字符串是否包含任意关键词
     */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行工具
     */
    private String executeTool(Intent intent, String message, String projectKey, String projectPath) {
        try {
            switch (intent) {
                case SEARCH:
                    // 提取搜索关键词
                    String query = extractQuery(message);
                    int topK = 10;  // 降级模式默认值
                    int topN = 10;

                    log.info("🔍 执行向量搜索: query={}, recallTopK={}, rerankTopN={}", query, topK, topN);

                    // 构建请求（所有参数必须提供）
                    SemanticSearchRequest searchRequest = new SemanticSearchRequest();
                    searchRequest.setProjectKey(projectKey);
                    searchRequest.setRecallQuery(query);
                    searchRequest.setRerankQuery(query);
                    searchRequest.setRecallTopK(topK);
                    searchRequest.setRerankTopN(topN);
                    searchRequest.setEnableReranker(false);

                    return formatSearchResults(vectorSearchService.semanticSearch(searchRequest));

                case READ_CLASS:
                    // 提取类名
                    String className = extractClassName(message);
                    if (className != null) {
                        log.info("📖 读取类结构: className={}, projectPath={}", className, projectPath);
                        return formatClassInfo(spoonAstService.getClassInfo(projectKey, className));
                    } else {
                        return "❌ 无法识别类名，请提供完整的类名（如：BankService）";
                    }

                case CALL_CHAIN:
                    // 提取方法签名
                    String method = extractMethod(message);
                    if (method != null && callChainService != null) {
                        log.info("🔗 分析调用链: method={}, projectPath={}", method, projectPath);
                        CallChainRequest request = buildCallChainRequest(projectKey, method, "both", 2);
                        CallChainResult result = callChainService.analyzeCallChain(request);
                        if (result.isSuccess()) {
                            return result.getResult();
                        } else {
                            return "❌ 调用链分析失败: " + result.getError();
                        }
                    } else if (callChainService == null) {
                        return "❌ 调用链分析服务不可用";
                    } else {
                        return "❌ 无法识别方法签名，请提供完整的方法签名（如：BankService.transfer）";
                    }

                case FIND_USAGES:
                    // 提取目标
                    String target = extractTarget(message);
                    if (target != null && callChainService != null) {
                        log.info("🔍 查找引用: target={}, projectPath={}", target, projectPath);
                        CallChainRequest request = buildCallChainRequest(projectKey, target, "callers", 1);
                        CallChainResult result = callChainService.analyzeCallChain(request);
                        if (result.isSuccess()) {
                            return result.getResult();
                        } else {
                            return "❌ 查找引用失败: " + result.getError();
                        }
                    } else if (callChainService == null) {
                        return "❌ 调用链分析服务不可用";
                    } else {
                        return "❌ 无法识别查找目标，请提供完整的类名或方法签名";
                    }

                default:
                    // 无法识别，执行默认搜索
                    int defaultTopK = 10;  // 降级模式默认值
                    int defaultTopN = 10;

                    log.info("🔍 执行默认搜索: query={}, recallTopK={}, rerankTopN={}", message, defaultTopK, defaultTopN);

                    SemanticSearchRequest defaultRequest = new SemanticSearchRequest();
                    defaultRequest.setProjectKey(projectKey);
                    defaultRequest.setRecallQuery(message);
                    defaultRequest.setRerankQuery(message);
                    defaultRequest.setRecallTopK(defaultTopK);
                    defaultRequest.setRerankTopN(defaultTopN);
                    defaultRequest.setEnableReranker(false);

                    return formatSearchResults(vectorSearchService.semanticSearch(defaultRequest));
            }

        } catch (Exception e) {
            log.error("❌ 工具执行失败: intent={}, projectPath={}", intent, projectPath, e);
            return "❌ 工具执行失败: " + e.getMessage();
        }
    }

    /**
     * 构建调用链请求
     */
    private CallChainRequest buildCallChainRequest(String projectKey, String method, String direction, int depth) {
        CallChainRequest request = new CallChainRequest();
        request.setProjectKey(projectKey);
        request.setMethod(method);
        request.setDirection(direction);
        request.setDepth(depth);
        request.setIncludeSource(false);
        return request;
    }

    /**
     * 格式化搜索结果
     */
    private String formatSearchResults(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "❌ 未找到相关代码";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 搜索结果\n\n");
        sb.append("找到 **").append(results.size()).append("** 个相关结果:\n\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            sb.append("### ").append(i + 1).append(". ").append(result.getClassName()).append("\n\n");
            sb.append("- **路径**: `").append(result.getRelativePath()).append("`\n");
            sb.append("- **相关性**: ").append(String.format("%.2f", result.getScore())).append("\n");
            if (result.getSummary() != null && !result.getSummary().isEmpty()) {
                sb.append("- **摘要**: ").append(result.getSummary()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 格式化类信息
     */
    private String formatClassInfo(ClassInfo classInfo) {
        if (classInfo == null) {
            return "❌ 未找到类信息";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(classInfo.getClassName()).append("\n\n");
        sb.append("- **路径**: `").append(classInfo.getRelativePath()).append("`\n");
        sb.append("- **类型**: ").append(classInfo.getType()).append("\n\n");

        if (classInfo.getFields() != null && !classInfo.getFields().isEmpty()) {
            sb.append("### 字段\n\n");
            for (String field : classInfo.getFields()) {
                sb.append("- ").append(field).append("\n");
            }
            sb.append("\n");
        }

        if (classInfo.getMethods() != null && !classInfo.getMethods().isEmpty()) {
            sb.append("### 方法\n\n");
            for (var method : classInfo.getMethods()) {
                sb.append("- `").append(method.getReturnType()).append(" ")
                  .append(method.getName()).append("(")
                  .append(String.join(", ", method.getParameters()))
                  .append(")`\n");
            }
        }

        return sb.toString();
    }

    /**
     * 提取搜索关键词
     */
    private String extractQuery(String message) {
        // 移除常用词
        return message
            .replaceAll("搜索|查找|相关|代码|类|方法|列出|list|所有|all", "")
            .trim()
            .replaceAll("\\s+", " ");
    }

    /**
     * 提取类名
     */
    private String extractClassName(String message) {
        // 使用正则表达式提取类名（支持简单类名和全限定类名）
        // 1. 尝试提取全限定类名（如 com.bank.service.BankService）
        Pattern fullPattern = Pattern.compile("([a-z][a-z0-9]*\\.)*[A-Z][a-zA-Z0-9]*");
        Matcher fullMatcher = fullPattern.matcher(message);

        if (fullMatcher.find()) {
            return fullMatcher.group();
        }

        // 2. 尝试提取简单类名（如 BankService）
        Pattern simplePattern = Pattern.compile("\\b[A-Z][a-zA-Z0-9]*\\b");
        Matcher simpleMatcher = simplePattern.matcher(message);

        if (simpleMatcher.find()) {
            return simpleMatcher.group();
        }

        return null;
    }

    /**
     * 提取方法签名
     */
    private String extractMethod(String message) {
        // 尝试提取方法签名（如 BankService.transfer 或 com.bank.Service.method）
        Pattern pattern = Pattern.compile("([a-z][a-z0-9]*\\.)*[A-Z][a-zA-Z0-9]*\\.[a-zA-Z0-9]+");
        Matcher matcher = pattern.matcher(message);

        if (matcher.find()) {
            return matcher.group();
        }

        return null;
    }

    /**
     * 提取目标
     */
    private String extractTarget(String message) {
        // 尝试提取类名或方法签名
        String method = extractMethod(message);
        if (method != null) {
            return method;
        }

        String className = extractClassName(message);
        if (className != null) {
            return className;
        }

        return null;
    }

    /**
     * 组装响应
     */
    private String buildResponse(String result, Intent intent, String projectKey, String projectPath) {
        StringBuilder sb = new StringBuilder();

        // 降级提示
        sb.append("## ⚠️ 降级模式提示\n\n");
        sb.append("当前系统运行在**降级模式**，Claude Code CLI 不可用。\n");
        sb.append("以下结果由**规则引擎**生成，功能可能受限。\n\n");

        // 🆕 项目信息
        sb.append("**项目信息**:\n");
        sb.append("- projectKey: `").append(projectKey).append("`\n");
        sb.append("- projectPath: `").append(projectPath).append("`\n");
        sb.append("- 分析类型: ").append(intent.getDescription()).append("\n\n");

        // 结果
        sb.append("---\n\n");
        sb.append(result);

        // 建议
        sb.append("\n\n---\n\n");
        sb.append("### 💡 建议\n\n");
        sb.append("1. 检查 Claude Code CLI 是否正确安装\n");
        sb.append("2. 查看后端日志了解降级原因\n");
        sb.append("3. 联系管理员恢复 Claude Code 服务\n");
        sb.append("4. 查看降级状态: `GET /api/fallback/status`\n");

        return sb.toString();
    }

    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String errorMessage, String projectKey) {
        StringBuilder sb = new StringBuilder();

        sb.append("## ⚠️ 降级模式提示\n\n");
        sb.append("当前系统运行在**降级模式**，处理请求时发生错误。\n\n");

        sb.append("---\n\n");
        sb.append("### ❌ 错误信息\n\n");
        sb.append("```\n");
        sb.append(errorMessage);
        sb.append("\n```\n\n");

        // 🆕 如果是 projectKey 映射错误，提供可用的 projectKeys
        if (errorMessage.contains("未找到 projectKey 映射")) {
            List<String> availableKeys = projectConfigService.getAllProjectKeys();
            sb.append("### 📋 可用的 projectKeys\n\n");
            if (availableKeys.isEmpty()) {
                sb.append("⚠️ 当前没有配置任何项目映射\n\n");
            } else {
                for (String key : availableKeys) {
                    sb.append("- `").append(key).append("`\n");
                }
                sb.append("\n请检查 `application.yml` 中的 `agent.projects` 配置。\n\n");
            }
        }

        sb.append("---\n\n");
        sb.append("### 💡 建议\n\n");
        sb.append("1. 检查请求格式是否正确\n");
        sb.append("2. 查看后端日志了解详细错误信息\n");
        sb.append("3. 联系管理员处理问题\n");

        return sb.toString();
    }

    /**
     * 用户意图枚举
     */
    public enum Intent {
        SEARCH("语义搜索"),
        READ_CLASS("类结构分析"),
        CALL_CHAIN("调用链分析"),
        FIND_USAGES("查找引用");

        private final String description;

        Intent(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
