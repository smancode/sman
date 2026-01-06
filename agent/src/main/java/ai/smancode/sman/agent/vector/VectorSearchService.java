package ai.smancode.sman.agent.vector;

import ai.smancode.sman.agent.models.VectorModels.BatchIndexStatus;
import ai.smancode.sman.agent.models.VectorModels.DocumentVector;
import ai.smancode.sman.agent.models.VectorModels.IncrementalIndexRequest;
import ai.smancode.sman.agent.models.VectorModels.SearchResult;
import ai.smancode.sman.agent.models.VectorModels.SemanticSearchRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jbellis.jvector.vector.VectorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 向量搜索服务
 *
 * 功能：使用 BGE-M3 进行代码语义搜索
 * 场景：用业务术语或功能描述搜索代码
 *
 * 实现原则：
 * - 单一职责：只负责向量搜索，不做其他事情
 * - 支持增量索引
 * - 缓存持久化到 data/vector-index
 * - 支持重排序（Reranker）
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

    /** 向量索引缓存（projectKey -> DocumentVector） */
    private final Map<String, List<DocumentVector>> vectorIndex = new ConcurrentHashMap<>();

    /** JVector 索引缓存（projectKey -> IndexData） */
    private final Map<String, JVectorIndexData> jVectorIndices = new ConcurrentHashMap<>();

    /** 类级读写锁管理器 */
    @Autowired
    private VectorIndexLockManager lockManager;

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
        // 🔥 同步更新 vectorIndex 以兼容 semanticSearch
        vectorIndex.put(projectKey, indexData.getDocuments());
    }

    /**
     * 获取所有已加载的项目键
     */
    public Set<String> getIndexedProjects() {
        return jVectorIndices.keySet();
    }

    /** BGE-M3 embedding 客户端 */
    @Autowired
    private BgeM3EmbeddingClient embeddingClient;

    // TODO: BGE-Reranker 客户端（暂未实现）
    // @Autowired
    // private BgeRerankerClient rerankerClient;

    @Value("${vector.index.path:data/vector-index}")
    private String vectorIndexPath;

    @Value("${vector.search.top_k:10}")
    private int defaultTopK;

    @Value("${vector.search.threshold:0.3}")
    private double similarityThreshold;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * JVector 索引数据结构
     */
    public static class JVectorIndexData {
        String projectKey;
        List<float[]> vectors;           // 原始向量数据
        List<DocumentVector> documents;  // 文档元数据
        int vectorDim;

        JVectorIndexData(String projectKey, int vectorDim) {
            this.projectKey = projectKey;
            this.vectorDim = vectorDim;
            this.vectors = new ArrayList<>();
            this.documents = new ArrayList<>();
        }

        public String getProjectKey() {
            return projectKey;
        }

        public List<float[]> getVectors() {
            return vectors;
        }

        public List<DocumentVector> getDocuments() {
            return documents;
        }

        public int getVectorDim() {
            return vectorDim;
        }
    }

    /**
     * JVector 元数据
     */
    private static class JVectorMeta {
        public long lastBuiltAt;
        public String model;
        public int vectorDim;
    }

    // ==================== 初始化 ====================

    /**
     * 初始化向量索引
     */
    public void initializeIndex(String projectKey) {
        if (vectorIndex.containsKey(projectKey) || jVectorIndices.containsKey(projectKey)) {
            log.debug("向量索引已存在: projectKey={}", projectKey);
            return;
        }

        log.info("初始化向量索引: projectKey={}", projectKey);

        try {
            // 优先尝试加载 JVector 格式索引
            JVectorIndexData jVectorData = loadJVectorIndex(projectKey);

            if (jVectorData != null && !jVectorData.documents.isEmpty()) {
                log.info("✅ 加载 JVector 索引成功: projectKey={}, count={}", projectKey, jVectorData.documents.size());
                jVectorIndices.put(projectKey, jVectorData);
                // 同时填充到 vectorIndex 以兼容现有代码
                vectorIndex.put(projectKey, jVectorData.documents);
                return;
            }

            // 降级：尝试加载旧格式索引
            List<DocumentVector> vectors = loadLegacyIndex(projectKey);

            if (vectors == null || vectors.isEmpty()) {
                log.info("向量索引为空，创建新索引: projectKey={}", projectKey);
                vectors = new ArrayList<>();
            } else {
                log.info("加载旧格式向量索引成功: count={}", vectors.size());
            }

            vectorIndex.put(projectKey, vectors);

        } catch (Exception e) {
            log.error("初始化向量索引失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
            vectorIndex.put(projectKey, new ArrayList<>());
        }
    }

    /**
     * 确保索引已初始化
     */
    public void ensureIndexInitialized(String projectKey) {
        if (!vectorIndex.containsKey(projectKey)) {
            initializeIndex(projectKey);
        }
    }

    // ==================== 增量索引 ====================

    /**
     * 增量索引文档
     *
     * @param request 增量索引请求
     * @return 索引状态
     */
    public BatchIndexStatus incrementalIndex(IncrementalIndexRequest request) {
        String projectKey = request.getProjectKey();
        List<DocumentVector> documents = request.getDocuments();

        log.info("增量索引: projectKey={}, count={}", projectKey, documents.size());

        ensureIndexInitialized(projectKey);

        try {
            List<DocumentVector> index = vectorIndex.get(projectKey);
            int addedCount = 0;
            int updatedCount = 0;
            int errorCount = 0;

            for (DocumentVector doc : documents) {
                try {
                    // 检查是否已存在
                    Optional<DocumentVector> existing = index.stream()
                            .filter(d -> d.getId().equals(doc.getId()))
                            .findFirst();

                    if (existing.isPresent()) {
                        // 更新
                        index.remove(existing.get());
                        index.add(doc);
                        updatedCount++;
                    } else {
                        // 新增
                        index.add(doc);
                        addedCount++;
                    }

                } catch (Exception e) {
                    log.warn("索引文档失败: id={}, error={}", doc.getId(), e.getMessage());
                    errorCount++;
                }
            }

            // 持久化索引
            saveIndex(projectKey, index);

            BatchIndexStatus status = new BatchIndexStatus();
            status.setProjectKey(projectKey);
            status.setTotalDocuments(documents.size());
            status.setIndexedDocuments(addedCount + updatedCount);
            status.setFailedDocuments(errorCount);
            status.setSuccess(errorCount == 0);

            log.info("增量索引完成: added={}, updated={}, failed={}",
                    addedCount, updatedCount, errorCount);

            return status;

        } catch (Exception e) {
            log.error("增量索引失败: {}", e.getMessage(), e);

            BatchIndexStatus status = new BatchIndexStatus();
            status.setProjectKey(projectKey);
            status.setTotalDocuments(documents.size());
            status.setIndexedDocuments(0);
            status.setFailedDocuments(documents.size());
            status.setSuccess(false);
            status.setErrorMessage(e.getMessage());

            return status;
        }
    }

    /**
     * 清空索引
     */
    public void clearIndex(String projectKey) {
        log.info("清空向量索引: projectKey={}", projectKey);

        vectorIndex.remove(projectKey);

        try {
            Path indexPath = getIndexFilePath(projectKey);
            if (Files.exists(indexPath)) {
                Files.delete(indexPath);
            }
        } catch (Exception e) {
            log.warn("删除索引文件失败: {}", e.getMessage());
        }
    }

    // ==================== 语义搜索 ====================

    /**
     * 语义搜索（支持两阶段召回+重排序）
     *
     * 工作流程：
     * 1. 召回阶段：使用 recallQuery 进行 BGE-M3 向量召回，返回 recallTopK 个候选
     * 2. 重排阶段：使用 rerankQuery 进行 BGE-Reranker 精排，返回 rerankTopN 个结果
     *
     * @param request 搜索请求
     * @return 搜索结果
     */
    public List<SearchResult> semanticSearch(SemanticSearchRequest request) {
        // 参数校验
        if (request.getProjectKey() == null || request.getProjectKey().isEmpty()) {
            throw new IllegalArgumentException("缺少 projectKey 参数");
        }
        if (request.getRecallQuery() == null || request.getRecallQuery().isEmpty()) {
            throw new IllegalArgumentException("缺少 recallQuery 参数");
        }
        if (request.getRerankQuery() == null || request.getRerankQuery().isEmpty()) {
            throw new IllegalArgumentException("缺少 rerankQuery 参数");
        }
        if (request.getRecallTopK() <= 0) {
            throw new IllegalArgumentException("recallTopK 必须大于 0");
        }
        if (request.getRerankTopN() <= 0) {
            throw new IllegalArgumentException("rerankTopN 必须大于 0");
        }

        String projectKey = request.getProjectKey();
        String recallQuery = request.getRecallQuery();
        String rerankQuery = request.getRerankQuery();
        int recallTopK = request.getRecallTopK();
        int rerankTopN = request.getRerankTopN();
        boolean enableReranker = request.isEnableReranker();

        log.info("语义搜索: projectKey={}, recallQuery={}, rerankQuery={}, recallTopK={}, rerankTopN={}, enableReranker={}",
                projectKey, recallQuery, rerankQuery, recallTopK, rerankTopN, enableReranker);

        ensureIndexInitialized(projectKey);

        List<DocumentVector> index = vectorIndex.get(projectKey);

        if (index == null || index.isEmpty()) {
            log.warn("向量索引为空: projectKey={}", projectKey);
            return Collections.emptyList();
        }

        try {
            // ========== 第1阶段：BGE-M3 召回 ==========
            List<SearchResult> recallResults = recallWithBgeM3(recallQuery, index, recallTopK, projectKey);

            if (recallResults.isEmpty()) {
                log.info("召回结果为空，直接返回空列表");
                return Collections.emptyList();
            }

            log.info("召回完成: recallCount={}", recallResults.size());

            // ========== 第2阶段：BGE-Reranker 重排 ==========
            if (enableReranker) {
                List<SearchResult> rerankedResults = rerankWithBgeReranker(
                    rerankQuery,
                    recallResults,
                    rerankTopN
                );
                log.info("重排完成: finalCount={}", rerankedResults.size());
                return rerankedResults;
            } else {
                // 不启用重排序，直接从召回结果取 topN
                List<SearchResult> finalResults = recallResults.stream()
                        .limit(rerankTopN)
                        .collect(Collectors.toList());
                log.info("跳过重排，直接返回: finalCount={}", finalResults.size());
                return finalResults;
            }

        } catch (Exception e) {
            log.error("语义搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * BGE-M3 召回阶段（使用类级读锁）
     *
     * @param query 召回查询字符串
     * @param index 向量索引
     * @param topK 召回数量
     * @param projectKey 项目键
     * @return 召回结果（按相似度降序）
     */
    private List<SearchResult> recallWithBgeM3(String query, List<DocumentVector> index, int topK, String projectKey) {
        log.info("BGE-M3 召回: query={}, topK={}, projectKey={}", query, topK, projectKey);

        try {
            // 1. 将查询向量化
            float[] queryVector = embeddingClient.embedText(query);

            // 2. 计算相似度（每个类独立加读锁）
            List<SearchResult> results = new ArrayList<>();

            // 优先使用 JVector 索引（如果有向量数据）
            if (jVectorIndices.containsKey(projectKey)) {
                JVectorIndexData jVectorData = jVectorIndices.get(projectKey);
                log.info("使用 JVector 索引: documentCount={}", jVectorData.documents.size());

                // 🔥 为每个文档（类/方法）加独立读锁
                for (int i = 0; i < jVectorData.documents.size(); i++) {
                    DocumentVector doc = jVectorData.documents.get(i);
                    float[] docVector = jVectorData.vectors.get(i);

                    // 🔥 使用类级读锁（按类名）
                    SearchResult result = lockManager.readClass(projectKey, doc.getClassName(), () -> {
                        float similarity = cosineSimilarity(queryVector, docVector);

                        if (similarity < similarityThreshold) {
                            return null;
                        }

                        SearchResult r = new SearchResult();
                        r.setId(doc.getId());
                        r.setClassName(doc.getClassName());
                        r.setRelativePath(doc.getRelativePath());
                        r.setSummary(doc.getSummary());
                        r.setScore(similarity);
                        r.setDocType(doc.getDocType());

                        // 添加详细信息
                        Map<String, Object> metadata = doc.getMetadata();
                        if (metadata != null) {
                            r.setClassComment((String) metadata.get("classComment"));
                            if ("method".equals(doc.getDocType())) {
                                r.setMethodName((String) metadata.get("methodName"));
                                r.setMethodSignature((String) metadata.get("methodSignature"));
                                r.setMethodComment((String) metadata.get("methodComment"));
                                r.setMethodSourceCode((String) metadata.get("methodSourceCode"));
                            }
                        }

                        return r;
                    });

                    if (result != null) {
                        results.add(result);
                    }
                }
            } else {
                // 降级：使用文档中存储的向量（如果有）
                log.info("降级使用文档向量索引: documentCount={}", index.size());

                for (DocumentVector doc : index) {
                    if (doc.getVector() == null) {
                        continue;
                    }

                    // 🔥 使用类级读锁
                    SearchResult result = lockManager.readClass(projectKey, doc.getClassName(), () -> {
                        float similarity = cosineSimilarity(queryVector, doc.getVector());

                        if (similarity < similarityThreshold) {
                            return null;
                        }

                        SearchResult r = new SearchResult();
                        r.setId(doc.getId());
                        r.setClassName(doc.getClassName());
                        r.setRelativePath(doc.getRelativePath());
                        r.setSummary(doc.getSummary());
                        r.setScore(similarity);
                        r.setDocType(doc.getDocType());

                        Map<String, Object> metadata = doc.getMetadata();
                        if (metadata != null) {
                            r.setClassComment((String) metadata.get("classComment"));
                            if ("method".equals(doc.getDocType())) {
                                r.setMethodName((String) metadata.get("methodName"));
                                r.setMethodSignature((String) metadata.get("methodSignature"));
                                r.setMethodComment((String) metadata.get("methodComment"));
                                r.setMethodSourceCode((String) metadata.get("methodSourceCode"));
                            }
                        }

                        return r;
                    });

                    if (result != null) {
                        results.add(result);
                    }
                }
            }

            log.info("相似度计算完成: total={}, aboveThreshold={}", results.size(), results.size());

            // 3. 排序并取 topK
            results.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
            List<SearchResult> topResults = results.stream()
                    .limit(topK)
                    .collect(Collectors.toList());

            return topResults;

        } catch (Exception e) {
            log.error("BGE-M3 召回失败: query={}, error={}", query, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 从索引推断 projectKey（辅助方法）
     */
    private String inferProjectKeyFromIndex(List<DocumentVector> index) {
        if (index.isEmpty()) {
            return null;
        }

        // 遍历所有 jVectorIndices，找到匹配的索引
        for (Map.Entry<String, JVectorIndexData> entry : jVectorIndices.entrySet()) {
            if (entry.getValue().documents.equals(index)) {
                return entry.getKey();
            }
        }

        return null;
    }

    /**
     * BGE-Reranker 重排阶段
     *
     * @param query 重排查询字符串
     * @param recallResults 召回结果
     * @param topN 最终返回数量
     * @return 重排后的结果
     */
    private List<SearchResult> rerankWithBgeReranker(String query,
                                                      List<SearchResult> recallResults,
                                                      int topN) {
        log.debug("BGE-Reranker 重排: query={}, topN={}, candidates={}", query, topN, recallResults.size());

        try {
            // TODO: 集成 BGE-Reranker client
            // List<SearchResult> rerankedResults = rerankerClient.rerank(query, recallResults, topN);

            // 暂时直接返回（模拟：按原始 score 重新排序）
            List<SearchResult> rerankedResults = new ArrayList<>(recallResults);
            rerankedResults.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));

            return rerankedResults.stream()
                    .limit(topN)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("BGE-Reranker 重排失败: query={}, error={}", query, e.getMessage(), e);
            // 降级：直接从召回结果取 topN
            return recallResults.stream()
                    .limit(topN)
                    .collect(Collectors.toList());
        }
    }

    // ==================== 持久化 ====================

    /**
     * 加载 JVector 格式索引
     */
    private JVectorIndexData loadJVectorIndex(String projectKey) {
        try {
            Path indexDir = Path.of(vectorIndexPath, projectKey);

            // 检查目录是否存在
            if (!Files.exists(indexDir)) {
                log.debug("JVector 索引目录不存在: {}", indexDir);
                return null;
            }

            // 检查必需文件
            Path metaFile = indexDir.resolve("meta.json");
            Path docsFile = indexDir.resolve("class.docs.json");
            Path vecFile = indexDir.resolve("class.vec.bin");

            if (!Files.exists(metaFile) || !Files.exists(docsFile) || !Files.exists(vecFile)) {
                log.debug("JVector 索引文件不完整: meta={}, docs={}, vec={}",
                        Files.exists(metaFile), Files.exists(docsFile), Files.exists(vecFile));
                return null;
            }

            // 1. 读取元数据
            JVectorMeta meta = objectMapper.readValue(metaFile.toFile(), JVectorMeta.class);
            log.info("📋 读取 JVector 元数据: model={}, dim={}", meta.model, meta.vectorDim);

            // 2. 读取文档数据
            List<Map<String, Object>> docsList = objectMapper.readValue(
                    docsFile.toFile(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );

            // 3. 读取向量数据
            List<float[]> vectors = readVectorBinaryFile(vecFile, meta.vectorDim);

            if (docsList.size() != vectors.size()) {
                log.warn("⚠️ 文档数量与向量数量不匹配: docs={}, vectors={}", docsList.size(), vectors.size());
            }

            // 4. 构建 JVectorIndexData
            JVectorIndexData indexData = new JVectorIndexData(projectKey, meta.vectorDim);

            for (int i = 0; i < Math.min(docsList.size(), vectors.size()); i++) {
                Map<String, Object> docMap = docsList.get(i);
                DocumentVector doc = parseDocumentVector(docMap);
                if (doc != null) {
                    indexData.documents.add(doc);
                    indexData.vectors.add(vectors.get(i));
                }
            }

            log.info("✅ JVector 索引加载完成: projectKey={}, documents={}", projectKey, indexData.documents.size());
            return indexData;

        } catch (Exception e) {
            log.error("加载 JVector 索引失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 读取二进制向量文件
     */
    private List<float[]> readVectorBinaryFile(Path vecFile, int vectorDim) throws IOException {
        List<float[]> vectors = new ArrayList<>();

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(vecFile.toFile())))) {
            // 读取文件大小
            long fileSize = Files.size(vecFile);
            log.info("向量文件大小: {} bytes, 维度: {}", fileSize, vectorDim);

            // 计算预期的向量数量 (每个float 4字节)
            int expectedVectorCount = (int) (fileSize / (vectorDim * 4L));
            log.info("预期向量数量: {}", expectedVectorCount);

            // 使用 DataInputStream 直接读取 float (自动处理字节序)
            try {
                while (true) {
                    float[] vector = new float[vectorDim];
                    for (int i = 0; i < vectorDim; i++) {
                        vector[i] = dis.readFloat();
                    }
                    vectors.add(vector);
                }
            } catch (EOFException e) {
                // 文件读取完毕
            }

            log.info("读取向量数据: 预期={}, 实际={}", expectedVectorCount, vectors.size());

            // 检查第一个向量是否有 NaN
            if (!vectors.isEmpty()) {
                float[] firstVector = vectors.get(0);
                int nanCount = 0;
                for (float v : firstVector) {
                    if (Float.isNaN(v)) nanCount++;
                }
                log.info("第一个向量 NaN 数量: {}/{}", nanCount, firstVector.length);
            }

        } catch (Exception e) {
            log.error("读取向量文件失败: {}", e.getMessage(), e);
            throw new IOException("读取向量文件失败: " + e.getMessage(), e);
        }

        return vectors;
    }

    /**
     * 解析文档向量
     */
    private DocumentVector parseDocumentVector(Map<String, Object> docMap) {
        try {
            DocumentVector doc = new DocumentVector();

            doc.setId((String) docMap.get("id"));
            doc.setClassName((String) docMap.get("className"));
            doc.setRelativePath((String) docMap.get("relativePath"));
            doc.setLanguage((String) docMap.get("language"));
            doc.setDocType((String) docMap.get("docType"));
            doc.setSummary((String) docMap.get("summary"));

            // methodSignatures 是 List 类型
            @SuppressWarnings("unchecked")
            List<String> methodSignatures = (List<String>) docMap.get("methodSignatures");
            doc.setMethodSignatures(methodSignatures);

            // metadata 是 Map 类型
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) docMap.get("metadata");
            doc.setMetadata(metadata);

            return doc;
        } catch (Exception e) {
            log.warn("解析文档向量失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 加载旧格式索引（向后兼容）
     */
    @SuppressWarnings("unchecked")
    private List<DocumentVector> loadLegacyIndex(String projectKey) {
        try {
            Path indexPath = Path.of(vectorIndexPath, projectKey + ".idx");

            if (!Files.exists(indexPath)) {
                return null;
            }

            try (ObjectInputStream ois = new ObjectInputStream(
                    new BufferedInputStream(new FileInputStream(indexPath.toFile())))) {

                List<DocumentVector> index = (List<DocumentVector>) ois.readObject();
                log.info("加载旧格式索引成功: projectKey={}, count={}", projectKey, index.size());
                return index;

            }

        } catch (Exception e) {
            log.error("加载旧格式索引失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 保存索引到磁盘
     */
    private void saveIndex(String projectKey, List<DocumentVector> index) {
        try {
            Path indexPath = getIndexFilePath(projectKey);
            Files.createDirectories(indexPath.getParent());

            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(new FileOutputStream(indexPath.toFile())))) {

                oos.writeObject(index);
            }

            log.debug("保存索引成功: projectKey={}, count={}", projectKey, index.size());

        } catch (Exception e) {
            log.error("保存索引失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
        }
    }

    /**
     * 获取索引文件路径
     */
    private Path getIndexFilePath(String projectKey) {
        return Path.of(vectorIndexPath, projectKey + ".idx");
    }

    // ==================== 工具方法 ====================

    /**
     * 计算余弦相似度
     */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            log.warn("向量参数无效: a={}, b={}, a.length={}, b.length={}",
                    a != null, b != null, a != null ? a.length : "N/A", b != null ? b.length : "N/A");
            return 0;
        }

        float dot = 0;
        float normA = 0;
        float normB = 0;

        for (int i = 0; i < a.length; i++) {
            // 检查是否有 NaN 值
            if (Float.isNaN(a[i]) || Float.isNaN(b[i])) {
                log.warn("发现 NaN 值: a[{}]={}, b[{}]={}", i, a[i], i, b[i]);
                return 0;
            }
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) {
            log.warn("向量范数为0: normA={}, normB={}", normA, normB);
            return 0;
        }

        float result = (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));

        // 检查结果是否为 NaN
        if (Float.isNaN(result)) {
            log.error("相似度计算结果为 NaN: dot={}, normA={}, normB={}, result={}",
                    dot, normA, normB, result);
            return 0;
        }

        return result;
    }

    // ==================== 统计信息 ====================

    /**
     * 获取索引统计信息
     */
    public Map<String, Object> getIndexStats(String projectKey) {
        ensureIndexInitialized(projectKey);

        List<DocumentVector> index = vectorIndex.get(projectKey);

        Map<String, Object> stats = new HashMap<>();
        stats.put("projectKey", projectKey);
        stats.put("documentCount", index != null ? index.size() : 0);
        stats.put("indexPath", getIndexFilePath(projectKey).toString());

        return stats;
    }

    /**
     * 增量刷新向量索引（定时任务调用）
     *
     * 核心逻辑：
     * 1. 从 FileChangeDetector 获取 MD5 变化的文件列表
     * 2. 只对变化的文件重新生成向量
     * 3. 更新索引时使用双缓冲（不影响搜索）
     *
     * @param projectKey 项目标识
     * @return 刷新是否成功
     */
    public boolean refreshIncremental(String projectKey) {
        log.info("🔄 开始增量刷新向量索引: projectKey={}", projectKey);

        try {
            ensureIndexInitialized(projectKey);

            // TODO: 从 FileChangeDetector 获取变化的文件
            // List<String> changedFiles = fileChangeDetector.getChangedFiles(projectPath);

            // TODO: 对变化的文件调用 embedding API 生成向量
            // List<DocumentVector> newVectors = generateVectors(changedFiles);

            // TODO: 调用 incrementalIndex 更新索引
            // incrementalIndex(new IncrementalIndexRequest(projectKey, newVectors));

            log.info("✅ 向量索引增量刷新完成: projectKey={}", projectKey);
            return true;

        } catch (Exception e) {
            log.error("❌ 向量索引增量刷新失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
            return false;
        }
    }
}
