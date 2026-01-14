package com.smancode.smanagent.vector;

import com.smancode.smanagent.models.VectorModels.*;
import io.github.jbellis.jvector.vector.VectorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 向量搜索服务（简化版）
 *
 * 功能：使用 BGE-M3 进行代码语义搜索
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    /** 向量索引缓存（projectKey -> DocumentVector） */
    private final Map<String, List<DocumentVector>> vectorIndex = new ConcurrentHashMap<>();

    /** JVector 索引缓存（projectKey -> IndexData） */
    private final Map<String, JVectorIndexData> jVectorIndices = new ConcurrentHashMap<>();

    /**
     * 获取 JVector 索引数据
     */
    public JVectorIndexData getJVectorIndex(String projectKey) {
        return jVectorIndices.get(projectKey);
    }

    /**
     * 设置 JVector 索引数据
     */
    public void setJVectorIndex(String projectKey, JVectorIndexData indexData) {
        jVectorIndices.put(projectKey, indexData);
        vectorIndex.put(projectKey, indexData.getDocuments());
    }

    /**
     * 获取所有已加载的项目键
     */
    public Set<String> getIndexedProjects() {
        return jVectorIndices.keySet();
    }

    /**
     * 检查项目索引是否存在
     */
    public boolean hasIndex(String projectKey) {
        return jVectorIndices.containsKey(projectKey);
    }

    /**
     * 语义搜索（简化版，不使用 JVector）
     *
     * @param request 搜索请求
     * @return 搜索结果
     */
    public List<SearchResult> semanticSearch(SemanticSearchRequest request) {
        String projectKey = request.getProjectKey();
        String query = request.getRecallQuery();
        int topK = request.getRecallTopK();

        logger.info("🔍 语义搜索: projectKey={}, query={}, topK={}", projectKey, query, topK);

        List<DocumentVector> documents = vectorIndex.get(projectKey);
        if (documents == null || documents.isEmpty()) {
            logger.warn("⚠️ 项目索引不存在: {}", projectKey);
            return Collections.emptyList();
        }

        // TODO: 调用 BGE-M3 嵌入服务获取查询向量
        // 这里暂时使用简单的关键词匹配
        List<SearchResult> results = documents.stream()
            .filter(doc -> containsKeyword(doc, query))
            .limit(topK)
            .map(this::toSearchResult)
            .collect(Collectors.toList());

        logger.info("✅ 搜索完成: 找到 {} 个结果", results.size());
        return results;
    }

    /**
     * 简单的关键词匹配（临时方案）
     */
    private boolean containsKeyword(DocumentVector doc, String query) {
        String content = (doc.getContent() + " " + doc.getSummary() + " " + doc.getClassName()).toLowerCase();
        String[] keywords = query.toLowerCase().split("\\s+");
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 转换为搜索结果
     */
    private SearchResult toSearchResult(DocumentVector doc) {
        SearchResult result = new SearchResult();
        result.setId(doc.getId());
        result.setClassName(doc.getClassName());
        result.setRelativePath(doc.getRelativePath());
        result.setSummary(doc.getSummary());
        result.setScore(1.0f);
        result.setDocType(doc.getDocType());
        return result;
    }

    /**
     * 批量添加文档到索引
     */
    public void addDocuments(String projectKey, List<DocumentVector> documents) {
        List<DocumentVector> existing = vectorIndex.computeIfAbsent(projectKey, k -> new ArrayList<>());
        existing.addAll(documents);
        logger.info("📝 添加文档: projectKey={}, count={}", projectKey, documents.size());
    }

    /**
     * 清空项目索引
     */
    public void clearIndex(String projectKey) {
        vectorIndex.remove(projectKey);
        jVectorIndices.remove(projectKey);
        logger.info("🗑️ 清空索引: projectKey={}", projectKey);
    }

    /**
     * JVector 索引数据
     */
    public static class JVectorIndexData {
        private final List<DocumentVector> documents;
        private final int dimension;

        public JVectorIndexData(List<DocumentVector> documents, int dimension) {
            this.documents = documents;
            this.dimension = dimension;
        }

        public List<DocumentVector> getDocuments() {
            return documents;
        }

        public int getDimension() {
            return dimension;
        }
    }
}
