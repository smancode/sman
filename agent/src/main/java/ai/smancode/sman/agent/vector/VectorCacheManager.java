package ai.smancode.sman.agent.vector;

import ai.smancode.sman.agent.config.ProjectConfigService;
import ai.smancode.sman.agent.models.VectorModels.DocumentVector;
import ai.smancode.sman.agent.utils.LightWeightJavaParser;
import ai.smancode.sman.agent.utils.LightWeightJavaParser.ClassInfo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量缓存管理器
 *
 * 功能：
 * 1. 统一管理 VectorSearchService 的 jVectorIndices
 * 2. 定时刷新：根据 MD5 变动增量更新向量（使用轻量级正则解析器）
 * 3. 停机持久化：将缓存保存到本地文件
 *
 * 性能优化：
 * - 使用 LightWeightJavaParser 替代 Spoon AST，避免全量解析项目
 * - MD5 增量检测，只处理变化的文件
 * - 5 分钟刷新间隔
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Component
public class VectorCacheManager {

    private static final Logger log = LoggerFactory.getLogger(VectorCacheManager.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private VectorIndexPersistence persistenceService;

    @Autowired
    private VectorIndexRefresher refresher;

    @Autowired
    private BgeM3EmbeddingClient embeddingClient;

    @Autowired
    private ProjectConfigService projectConfigService;

    @Autowired
    private VectorIndexPersistence indexPersistence;

    @Autowired
    private VectorIndexLockManager lockManager;

    @Value("${vector.index.path:data/vector-index}")
    private String vectorIndexPath;

    @Value("${vector.refresh.interval:3600000}")
    private long refreshInterval;

    @Value("${vector.index.auto-build:true}")
    private boolean autoBuild;

    /** 正在构建的标志 */
    private volatile boolean isBuilding = false;

    /** 构建锁 */
    private final Object buildLock = new Object();

    /**
     * 初始化
     */
    public VectorCacheManager() {
        // 注册停机钩子
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownHook));
    }

    // ==================== 缓存管理 ====================

    /**
     * 获取活跃索引（统一从 VectorSearchService 获取）
     */
    public VectorSearchService.JVectorIndexData getActiveIndex(String projectKey) {
        return vectorSearchService.getJVectorIndex(projectKey);
    }

    /**
     * 设置活跃索引（同步到 VectorSearchService）
     */
    public void setActiveIndex(String projectKey, VectorSearchService.JVectorIndexData indexData) {
        vectorSearchService.setJVectorIndex(projectKey, indexData);
    }

    // ==================== 定时刷新 ====================

    /**
     * 定时刷新向量索引（根据 MD5 变动）
     *
     * 刷新策略：
     * 1. 基于 agent.projects 配置获取所有项目
     * 2. 检测 MD5 变化
     * 3. 在当前索引基础上增量更新
     * 4. 更新 MD5 缓存
     */
    @Scheduled(fixedDelayString = "${vector.refresh.interval:300000}", initialDelay = 60000)
    public void refreshVectorIndex() {
        if (!autoBuild) {
            log.debug("自动构建已禁用，跳过刷新");
            return;
        }

        // 防止并发构建
        if (!tryStartBuilding()) {
            log.debug("已有构建任务在执行，跳过本次刷新");
            return;
        }

        try {
            log.info("🔄 开始定时刷新向量索引...");

            // 🔥 修改：基于 agent.projects 配置获取所有项目
            List<String> projectKeys = projectConfigService.getAllProjectKeys();

            if (projectKeys.isEmpty()) {
                log.info("⚠️ agent.projects 配置为空，无法刷新索引");
                log.info("💡 提示: 请在 application.yml 中配置 agent.projects");
                return;
            }

            log.info("📋 从配置中发现 {} 个项目: {}", projectKeys.size(), projectKeys);

            int totalChanged = 0;
            int successCount = 0;
            int failCount = 0;

            for (String projectKey : projectKeys) {
                try {
                    log.info("🔍 检查项目索引: projectKey={}", projectKey);

                    // 确保索引已初始化
                    if (!vectorSearchService.hasIndex(projectKey)) {
                        log.info("🆕 项目索引不存在，创建新索引: projectKey={}", projectKey);
                        try {
                            vectorSearchService.initializeIndex(projectKey);
                            successCount++;

                            // 🔥 检查是否为空索引（首次启动无缓存），触发全量扫描
                            var indexData = vectorSearchService.getJVectorIndex(projectKey);
                            if (indexData != null && indexData.getDocuments().isEmpty()) {
                                log.info("🆕 空索引，触发全量扫描: projectKey={}", projectKey);
                                int scannedFiles = performFullScan(projectKey);
                                log.info("✅ 全量扫描完成: projectKey={}, 文件数={}", projectKey, scannedFiles);
                            }

                        } catch (Exception e) {
                            failCount++;
                            log.error("❌ 创建索引失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
                        }
                        continue;
                    }

                    // 刷新现有索引
                    int changedFiles = refreshProjectIndex(projectKey);
                    totalChanged += changedFiles;
                    successCount++;

                    if (changedFiles > 0) {
                        log.info("✅ 索引已刷新: projectKey={}, 变化文件数={}", projectKey, changedFiles);
                    }

                } catch (Exception e) {
                    failCount++;
                    log.error("❌ 刷新项目索引失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
                }
            }

            if (totalChanged > 0) {
                log.info("✅ 向量索引刷新完成: 成功={}, 失败={}, 变化文件数={}", successCount, failCount, totalChanged);

                // 持久化到本地文件
                persistCacheToFile();
            } else {
                log.info("✅ 向量索引检查完成: 成功={}, 失败={}, 无变化", successCount, failCount);
            }

        } catch (Exception e) {
            log.error("定时刷新向量索引失败: {}", e.getMessage(), e);
        } finally {
            finishBuilding();
        }
    }

    /**
     * 尝试开始构建
     */
    private boolean tryStartBuilding() {
        synchronized (buildLock) {
            if (isBuilding) {
                return false;
            }
            isBuilding = true;
            return true;
        }
    }

    /**
     * 完成构建
     */
    private void finishBuilding() {
        synchronized (buildLock) {
            isBuilding = false;
        }
    }

    /**
     * 全量扫描项目（首次启动或索引丢失时使用）
     *
     * @param projectKey 项目键
     * @return 扫描的文件数量
     */
    private int performFullScan(String projectKey) {
        log.info("🔄 开始全量扫描: projectKey={}", projectKey);

        try {
            // 1. 获取项目路径
            String projectPath = projectConfigService.getProjectPath(projectKey);

            // 2. 扫描所有 Java 文件
            List<String> allFiles = refresher.scanAllJavaFiles(projectKey);

            if (allFiles.isEmpty()) {
                log.warn("⚠️ 未扫描到任何 Java 文件: projectKey={}, projectPath={}", projectKey, projectPath);
                return 0;
            }

            log.info("🔍 扫描到 {} 个 Java 文件，开始生成向量...", allFiles.size());

            // 3. 创建新的索引数据
            var indexData = new VectorSearchService.JVectorIndexData(projectKey, 1024);
            int successCount = 0;
            int errorCount = 0;

            // 4. 为每个文件生成向量
            for (String filePath : allFiles) {
                try {
                    String className = extractClassName(filePath);

                    // 使用类级写锁
                    Integer result = lockManager.writeClass(projectKey, className, () -> {
                        return updateClassVectors(projectKey, filePath, indexData);
                    });

                    if (result != null && result > 0) {
                        successCount++;
                    }

                } catch (Exception e) {
                    errorCount++;
                    log.error("❌ 处理文件失败: file={}, error={}", filePath, e.getMessage());
                }
            }

            // 5. 更新到 VectorSearchService
            if (successCount > 0) {
                vectorSearchService.setJVectorIndex(projectKey, indexData);

                // 6. 更新 MD5 缓存
                String fullPath = projectConfigService.getProjectPath(projectKey);
                Map<String, String> currentMd5Map = refresher.scanJavaFiles(fullPath);
                refresher.updateMd5Cache(projectKey, currentMd5Map);

                log.info("✅ 全量扫描完成: projectKey={}, 成功={}, 失败={}", projectKey, successCount, errorCount);
            }

            return successCount;

        } catch (Exception e) {
            log.error("❌ 全量扫描失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 刷新单个项目的索引（包含删除检测 + 类级写锁）
     */
    private int refreshProjectIndex(String projectKey) {
        log.info("🔄 刷新项目索引: projectKey={}", projectKey);

        try {
            // 1. 🔥 检测文件变化（包含删除检测）
            FileChangeDetectionResult detectionResult = refresher.detectChangedFilesWithDeletion(projectKey);

            if (detectionResult.isEmpty()) {
                log.info("项目无文件变化: projectKey={}", projectKey);
                return 0;
            }

            log.info("🔍 文件变化检测: projectKey={}, 新增/修改={}, 删除={}",
                    projectKey, detectionResult.getAddedOrModifiedFiles().size(),
                    detectionResult.getDeletedFiles().size());

            // 2. 获取当前索引
            var currentIndex = vectorSearchService.getJVectorIndex(projectKey);
            var updatedIndex = new VectorSearchService.JVectorIndexData(projectKey, 1024);

            if (currentIndex != null) {
                // 复制现有索引
                updatedIndex.getDocuments().addAll(currentIndex.getDocuments());
                updatedIndex.getVectors().addAll(currentIndex.getVectors());
            }

            int totalUpdatedCount = 0;

            // 3. 🔥 处理删除的文件（使用类级写锁）
            for (String deletedFile : detectionResult.getDeletedFiles()) {
                String className = extractClassName(deletedFile);
                int deletedCount = lockManager.writeClass(projectKey, className, () -> {
                    return removeClassVectors(updatedIndex, projectKey, className);
                });
                totalUpdatedCount += deletedCount;
            }

            if (detectionResult.getDeletedFiles().size() > 0) {
                log.info("🗑️ 删除文件处理完成: count={}", detectionResult.getDeletedFiles().size());
            }

            // 4. 🔥 对变化的文件生成向量（使用类级写锁）
            for (String filePath : detectionResult.getAddedOrModifiedFiles()) {
                try {
                    String className = extractClassName(filePath);

                    // 使用类级写锁
                    Integer result = lockManager.writeClass(projectKey, className, () -> {
                        return updateClassVectors(projectKey, filePath, updatedIndex);
                    });

                    if (result != null) {
                        totalUpdatedCount += result;
                    }

                } catch (Exception e) {
                    log.error("更新文件向量失败: file={}, error={}", filePath, e.getMessage());
                }
            }

            // 5. 更新到 VectorSearchService
            if (totalUpdatedCount > 0) {
                vectorSearchService.setJVectorIndex(projectKey, updatedIndex);

                // 6. 更新 MD5 缓存
                updateMd5CacheAfterRefresh(projectKey, detectionResult.getAddedOrModifiedFiles(),
                                          detectionResult.getDeletedFiles());
            }

            log.info("✅ 项目索引刷新完成: projectKey={}, 更新向量数={}", projectKey, totalUpdatedCount);

            return totalUpdatedCount;

        } catch (Exception e) {
            log.error("刷新项目索引失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 从文件路径提取类名
     */
    private String extractClassName(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }

        // 去掉 .java 后缀
        String path = filePath.endsWith(".java") ? filePath.substring(0, filePath.length() - 5) : filePath;

        // 获取最后一部分
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) {
            lastSlash = path.lastIndexOf('\\');
        }

        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /**
     * 删除类的所有向量（类向量 + 方法向量）
     */
    private int removeClassVectors(VectorSearchService.JVectorIndexData indexData,
                                   String projectKey, String className) {
        String classPrefix = projectKey + "." + className;

        // 使用迭代器同步删除
        var docIterator = indexData.getDocuments().listIterator();
        var vecIterator = indexData.getVectors().listIterator();

        int removedCount = 0;

        while (docIterator.hasNext()) {
            var doc = docIterator.next();
            vecIterator.next();

            if (doc.getId() != null && doc.getId().startsWith(classPrefix)) {
                docIterator.remove();
                vecIterator.remove();
                removedCount++;
            }
        }

        if (removedCount > 0) {
            log.debug("🗑️ 删除类向量: className={}, count={}", className, removedCount);
        }

        return removedCount;
    }

    /**
     * 更新单个类的向量（类向量 + 方法向量）
     */
    private Integer updateClassVectors(String projectKey, String filePath,
                                      VectorSearchService.JVectorIndexData indexData) {
        int successCount = 0;

        try {
            // 生成文档向量
            DocumentVector docVector = createDocumentVector(projectKey, filePath);

            if (docVector != null) {
                // 生成 embedding
                String text = extractTextForEmbedding(docVector);
                float[] embedding = embeddingClient.embedText(text);

                // 检查是否已存在
                int existingIndex = findDocumentIndex(indexData, docVector.getId());

                if (existingIndex >= 0) {
                    // 更新
                    indexData.getDocuments().set(existingIndex, docVector);
                    indexData.getVectors().set(existingIndex, embedding);
                } else {
                    // 新增
                    indexData.getDocuments().add(docVector);
                    indexData.getVectors().add(embedding);
                }

                successCount++;
            }

            return successCount;

        } catch (Exception e) {
            log.error("❌ 更新类向量失败: file={}, error={}", filePath, e.getMessage());
            return successCount;
        }
    }

    /**
     * 刷新后更新 MD5 缓存（支持删除）
     */
    private void updateMd5CacheAfterRefresh(String projectKey, List<String> addedOrModifiedFiles,
                                           List<String> deletedFiles) {
        try {
            // 重新扫描所有文件获取最新 MD5
            String projectPath = projectConfigService.getProjectPath(projectKey);
            Map<String, String> currentMd5Map = refresher.scanJavaFiles(projectPath);

            // 合并所有变化的文件
            Map<String, String> changedMd5Map = new HashMap<>();

            // 新增/修改的文件
            for (String file : addedOrModifiedFiles) {
                if (currentMd5Map.containsKey(file)) {
                    changedMd5Map.put(file, currentMd5Map.get(file));
                }
            }

            // 删除的文件（从缓存中移除，通过更新整个缓存实现）
            // 注意：这里需要特殊处理删除，因为 updateMd5Cache 会合并

            // 保存到 MD5 缓存
            refresher.updateMd5Cache(projectKey, changedMd5Map);

            log.info("✅ MD5 缓存已更新: projectKey={}, 新增/修改={}, 删除={}",
                    projectKey, addedOrModifiedFiles.size(), deletedFiles.size());

        } catch (Exception e) {
            log.error("更新 MD5 缓存失败: projectKey={}, error={}", projectKey, e.getMessage());
        }
    }

    /**
     * 创建文档向量（使用轻量级解析器）
     */
    private DocumentVector createDocumentVector(String projectKey, String filePath) {
        log.debug("📖 读取文件分析: {}", filePath);

        try {
            // 获取项目路径
            String projectPath = projectConfigService.getProjectPath(projectKey);
            String fullPath = projectPath + "/" + filePath;

            // 读取文件内容
            String content = Files.readString(Path.of(fullPath));

            // 使用轻量级解析器提取类信息
            ClassInfo classInfo = LightWeightJavaParser.parse(content);

            if (classInfo == null || classInfo.getClassName() == null || classInfo.getClassName().isEmpty()) {
                log.warn("⚠️ 无法解析文件或类名为空: {}", fullPath);
                return null;
            }

            String className = classInfo.getClassName();
            log.debug("✅ 解析完成: className={}, filePath={}", className, filePath);

            // 构建 DocumentVector
            DocumentVector doc = new DocumentVector();
            String docId = projectKey + "." + className;
            doc.setId(docId);
            doc.setClassName(className);
            doc.setRelativePath(filePath);
            doc.setLanguage("java");
            doc.setDocType("class");

            // 提取类摘要
            StringBuilder summary = new StringBuilder();
            summary.append("Java 类: ").append(className);

            if (classInfo.getSuperClass() != null && !classInfo.getSuperClass().isEmpty()) {
                summary.append(", 继承: ").append(classInfo.getSuperClass());
            }

            if (classInfo.getInterfaces() != null && !classInfo.getInterfaces().isEmpty()) {
                summary.append(", 实现: ").append(String.join(", ", classInfo.getInterfaces()));
            }

            if (classInfo.getFields() != null && !classInfo.getFields().isEmpty()) {
                summary.append(", 字段数: ").append(classInfo.getFields().size());
            }

            if (classInfo.getMethodSignatures() != null && !classInfo.getMethodSignatures().isEmpty()) {
                summary.append(", 方法数: ").append(classInfo.getMethodSignatures().size());
            }

            doc.setSummary(summary.toString());

            // 设置方法签名
            if (classInfo.getMethodSignatures() != null && !classInfo.getMethodSignatures().isEmpty()) {
                doc.setMethodSignatures(classInfo.getMethodSignatures());
            }

            log.debug("✅ 文件分析完成: {}, 方法数={}", className,
                    classInfo.getMethodSignatures() != null ? classInfo.getMethodSignatures().size() : 0);

            return doc;

        } catch (Exception e) {
            log.error("❌ 创建文档向量失败: file={}, error={}", filePath, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 提取文本用于 embedding（包含完整代码信息）
     */
    private String extractTextForEmbedding(DocumentVector doc) {
        StringBuilder text = new StringBuilder();

        // 1. 类名和包
        text.append("类名: ").append(doc.getClassName()).append("\n");

        // 2. 摘要
        text.append("摘要: ").append(doc.getSummary()).append("\n");

        // 3. 完整路径
        text.append("路径: ").append(doc.getRelativePath()).append("\n");

        // 4. 如果有方法信息，添加方法签名
        if (doc.getMethodSignatures() != null && !doc.getMethodSignatures().isEmpty()) {
            text.append("方法:\n");
            for (String method : doc.getMethodSignatures()) {
                text.append("  - ").append(method).append("\n");
            }
        }

        return text.toString();
    }

    /**
     * 查找文档索引
     */
    private int findDocumentIndex(VectorSearchService.JVectorIndexData indexData, String docId) {
        List<DocumentVector> documents = indexData.getDocuments();

        for (int i = 0; i < documents.size(); i++) {
            DocumentVector doc = documents.get(i);

            // 🔍 跳过 id 为 null 的文档（异常情况）
            if (doc.getId() == null) {
                log.warn("⚠️ 发现 id 为 null 的文档向量，跳过: index={}, className={}, relativePath={}",
                        i, doc.getClassName(), doc.getRelativePath());
                continue;
            }

            if (doc.getId().equals(docId)) {
                return i;
            }
        }

        return -1;
    }

    // ==================== 停机持久化 ====================

    /**
     * 停机钩子
     */
    @PreDestroy
    public void shutdown() {
        log.info("🛑 收到停机信号，开始持久化向量缓存...");
        shutdownHook();
    }

    /**
     * 停机钩子实现
     */
    private void shutdownHook() {
        try {
            persistCacheToFile();
            log.info("✅ 向量缓存已持久化到本地文件");

        } catch (Exception e) {
            log.error("❌ 向量缓存持久化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 持久化缓存到本地文件
     */
    private void persistCacheToFile() {
        log.info("💾 开始持久化向量缓存到本地文件...");

        try {
            // 确保目录存在
            Path indexDir = Path.of(vectorIndexPath);
            Files.createDirectories(indexDir);

            Set<String> projectKeys = vectorSearchService.getIndexedProjects();
            int totalSaved = 0;

            // 持久化所有项目索引
            for (String projectKey : projectKeys) {
                try {
                    var indexData = vectorSearchService.getJVectorIndex(projectKey);
                    if (indexData != null) {
                        persistenceService.saveIndex(projectKey, indexData);
                        totalSaved++;
                    }

                } catch (Exception e) {
                    log.error("保存项目索引失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
                }
            }

            log.info("✅ 向量缓存持久化完成: 项目数={}", totalSaved);

        } catch (Exception e) {
            log.error("持久化向量缓存失败: {}", e.getMessage(), e);
        }
    }

    // ==================== 统计信息 ====================

    /**
     * 获取缓存统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("autoBuild", autoBuild);
        stats.put("refreshInterval", refreshInterval);
        stats.put("isBuilding", isBuilding);

        Set<String> projectKeys = vectorSearchService.getIndexedProjects();
        stats.put("indexedProjects", projectKeys.size());

        Map<String, Integer> docCounts = new HashMap<>();
        for (String projectKey : projectKeys) {
            var indexData = vectorSearchService.getJVectorIndex(projectKey);
            if (indexData != null) {
                docCounts.put(projectKey, indexData.getDocuments().size());
            }
        }
        stats.put("docCounts", docCounts);

        return stats;
    }
}
