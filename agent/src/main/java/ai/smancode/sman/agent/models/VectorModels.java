package ai.smancode.sman.agent.models;

import java.util.List;
import java.util.Map;

/**
 * 向量搜索数据模型
 */
public class VectorModels {

    /**
     * 搜索结果
     */
    public static class SearchResult {
        private String id;
        private String className;
        private String relativePath;
        private String summary;
        private float score;

        // 🔥 新增：详细信息
        private String classComment;           // 类注释
        private String methodName;             // 方法名（如果是方法级别的结果）
        private String methodSignature;        // 方法签名
        private String methodComment;          // 方法注释
        private String methodSourceCode;       // 方法源码
        private String docType;                // "class" 或 "method"

        public SearchResult() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public String getRelativePath() { return relativePath; }
        public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }

        public float getScore() { return score; }
        public void setScore(float score) { this.score = score; }

        public String getClassComment() { return classComment; }
        public void setClassComment(String classComment) { this.classComment = classComment; }

        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }

        public String getMethodSignature() { return methodSignature; }
        public void setMethodSignature(String methodSignature) { this.methodSignature = methodSignature; }

        public String getMethodComment() { return methodComment; }
        public void setMethodComment(String methodComment) { this.methodComment = methodComment; }

        public String getMethodSourceCode() { return methodSourceCode; }
        public void setMethodSourceCode(String methodSourceCode) { this.methodSourceCode = methodSourceCode; }

        public String getDocType() { return docType; }
        public void setDocType(String docType) { this.docType = docType; }

        @Override
        public String toString() {
            if ("method".equals(docType)) {
                return String.format("SearchResult[method=%s.%s(), score=%.3f, comment=%s]",
                        className, methodName != null ? methodName : "?", score,
                        methodComment != null ? methodComment.substring(0, Math.min(30, methodComment.length())) + "..." : "null");
            } else {
                return String.format("SearchResult[class=%s, score=%.3f, comment=%s]",
                        className, score,
                        classComment != null ? classComment.substring(0, Math.min(30, classComment.length())) + "..." : "null");
            }
        }
    }

    /**
     * 语义搜索请求
     *
     * 支持两阶段搜索策略：
     * 1. 召回阶段（Recall）：使用 recallQuery 进行 BGE-M3 向量召回，返回 recallTopK 个候选
     * 2. 重排阶段（Rerank）：使用 rerankQuery 进行 BGE-Reranker 精排，返回 rerankTopN 个结果
     *
     * 典型使用场景：
     * - recallQuery: 宽泛的业务需求（如 "文件过滤"）
     * - rerankQuery: 精确的业务描述（如 "按扩展名过滤文件"）
     * - 先宽后紧策略：可以多次调用，先用宽泛查询召回，再用精确查询过滤
     */
    public static class SemanticSearchRequest {
        // 召回参数（BGE-M3）
        private String recallQuery;      // 召回字符串（业务需求或关键词，可以先宽后紧）
        private int recallTopK;          // 召回数量

        // 重排参数（BGE-Reranker）
        private String rerankQuery;      // 重排字符串（一般直接就是业务需求）
        private int rerankTopN;          // 重排数量
        private boolean enableReranker;  // 是否启用重排序

        // 通用参数
        private String projectKey;

        public SemanticSearchRequest() {}

        public String getRecallQuery() { return recallQuery; }
        public void setRecallQuery(String recallQuery) { this.recallQuery = recallQuery; }

        public int getRecallTopK() { return recallTopK; }
        public void setRecallTopK(int recallTopK) { this.recallTopK = recallTopK; }

        public String getRerankQuery() { return rerankQuery; }
        public void setRerankQuery(String rerankQuery) { this.rerankQuery = rerankQuery; }

        public int getRerankTopN() { return rerankTopN; }
        public void setRerankTopN(int rerankTopN) { this.rerankTopN = rerankTopN; }

        public boolean isEnableReranker() { return enableReranker; }
        public void setEnableReranker(boolean enableReranker) { this.enableReranker = enableReranker; }

        public String getProjectKey() { return projectKey; }
        public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    }

    /**
     * 文档向量
     */
    public static class DocumentVector {
        private String id;
        private String content;
        private float[] embedding;
        private Map<String, Object> metadata;
        private String className;
        private String relativePath;
        private String language;
        private String docType;
        private String summary;
        private List<String> methodSignatures;

        public DocumentVector() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public float[] getEmbedding() { return embedding; }
        public void setEmbedding(float[] embedding) { this.embedding = embedding; }

        // Java 兼容性：提供 getVector() 方法
        public float[] getVector() { return embedding; }
        public void setVector(float[] vector) { this.embedding = vector; }

        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public String getRelativePath() { return relativePath; }
        public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }

        public String getDocType() { return docType; }
        public void setDocType(String docType) { this.docType = docType; }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }

        public List<String> getMethodSignatures() { return methodSignatures; }
        public void setMethodSignatures(List<String> methodSignatures) { this.methodSignatures = methodSignatures; }
    }

    /**
     * 增量索引请求
     */
    public static class IncrementalIndexRequest {
        private String projectKey;
        private List<DocumentVector> documents;

        public IncrementalIndexRequest() {}

        public String getProjectKey() { return projectKey; }
        public void setProjectKey(String projectKey) { this.projectKey = projectKey; }

        public List<DocumentVector> getDocuments() { return documents; }
        public void setDocuments(List<DocumentVector> documents) { this.documents = documents; }
    }

    /**
     * 批量索引状态
     */
    public static class BatchIndexStatus {
        private String projectKey;
        private int totalDocuments;
        private int indexedDocuments;
        private int failedDocuments;
        private boolean success;
        private String errorMessage;

        public BatchIndexStatus() {}

        public String getProjectKey() { return projectKey; }
        public void setProjectKey(String projectKey) { this.projectKey = projectKey; }

        public int getTotalDocuments() { return totalDocuments; }
        public void setTotalDocuments(int totalDocuments) { this.totalDocuments = totalDocuments; }

        public int getIndexedDocuments() { return indexedDocuments; }
        public void setIndexedDocuments(int indexedDocuments) { this.indexedDocuments = indexedDocuments; }

        public int getFailedDocuments() { return failedDocuments; }
        public void setFailedDocuments(int failedDocuments) { this.failedDocuments = failedDocuments; }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }

    /**
     * 索引状态枚举
     */
    public enum IndexStatus {
        IDLE,
        INDEXING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
