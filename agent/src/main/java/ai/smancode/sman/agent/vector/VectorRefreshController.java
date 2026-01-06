package ai.smancode.sman.agent.vector;

import ai.smancode.sman.agent.ast.SpoonAstService;
import ai.smancode.sman.agent.config.ProjectConfigService;
import ai.smancode.sman.agent.models.SpoonModels.ClassInfo;
import ai.smancode.sman.agent.models.VectorModels.DocumentVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 向量索引刷新控制器
 *
 * 功能：提供手动触发向量索引刷新的 REST API
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/vector")
public class VectorRefreshController {

    private static final Logger log = LoggerFactory.getLogger(VectorRefreshController.class);

    @Autowired
    private VectorCacheManager cacheManager;

    @Autowired
    private VectorIndexRefresher refresher;

    @Autowired
    private BgeM3EmbeddingClient embeddingClient;

    @Autowired
    private ProjectConfigService projectConfigService;

    @Autowired
    private SpoonAstService spoonAstService;

    @Autowired
    private VectorIndexPersistence indexPersistence;

    @Autowired
    private VectorIndexLockManager lockManager;

    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vector-refresh-thread");
        t.setDaemon(true);
        return t;
    });

    /**
     * 手动触发向量索引刷新
     *
     * @param request 刷新请求
     * @return 刷新结果
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshVectorIndex(@RequestBody RefreshRequest request) {
        log.info("📥 收到手动刷新请求: projectKey={}, force={}", request.getProjectKey(), request.isForce());

        // 参数校验
        if (request.getProjectKey() == null || request.getProjectKey().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "缺少 projectKey 参数"
            ));
        }

        // 异步执行刷新
        CompletableFuture.supplyAsync(() -> {
            try {
                return doRefresh(request.getProjectKey(), request.isForce());
            } catch (Exception e) {
                log.error("向量索引刷新失败: {}", e.getMessage(), e);
                return Map.of(
                        "success", false,
                        "projectKey", request.getProjectKey(),
                        "error", e.getMessage()
                );
            }
        }, refreshExecutor).thenAccept(result -> {
            log.info("✅ 向量索引刷新完成: projectKey={}, success={}",
                    result.get("projectKey"), result.get("success"));
        });

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "向量索引刷新已启动",
                "projectKey", request.getProjectKey(),
                "force", request.isForce()
        ));
    }

    /**
     * 获取刷新状态
     *
     * @param projectKey 项目键
     * @return 刷新状态
     */
    @GetMapping("/refresh/status")
    public ResponseEntity<Map<String, Object>> getRefreshStatus(@RequestParam String projectKey) {
        var stats = cacheManager.getStats();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "projectKey", projectKey,
                "indexedProjects", stats.get("indexedProjects"),
                "docCounts", stats.get("docCounts"),
                "autoBuild", stats.get("autoBuild"),
                "isBuilding", stats.get("isBuilding")
        ));
    }

    /**
     * 执行刷新逻辑（包含删除检测 + 类级写锁）
     */
    private Map<String, Object> doRefresh(String projectKey, boolean force) {
        log.info("🔄 开始刷新向量索引: projectKey={}, force={}", projectKey, force);

        try {
            // 1. 检测文件变化（包含删除检测）
            FileChangeDetectionResult detectionResult;

            if (force) {
                // 强制模式：扫描所有 Java 文件（不检测删除）
                log.info("⚠️ 强制刷新模式：扫描所有 Java 文件");
                List<String> allFiles = refresher.scanAllJavaFiles(projectKey);
                detectionResult = new FileChangeDetectionResult(allFiles, Collections.emptyList());
            } else {
                // 增量模式：检测新增/修改/删除
                detectionResult = refresher.detectChangedFilesWithDeletion(projectKey);
            }

            if (detectionResult.isEmpty()) {
                log.info("没有需要刷新的文件: projectKey={}", projectKey);
                return Map.of(
                        "success", true,
                        "projectKey", projectKey,
                        "message", "没有需要刷新的文件",
                        "changedFiles", 0
                );
            }

            log.info("🔍 文件变化检测: 新增/修改={}, 删除={}",
                    detectionResult.getAddedOrModifiedFiles().size(),
                    detectionResult.getDeletedFiles().size());

            // 2. 获取活跃缓存作为基础
            var activeIndex = cacheManager.getActiveIndex(projectKey);
            var buildingIndex = new VectorSearchService.JVectorIndexData(projectKey, 1024);

            if (activeIndex != null && !force) {
                // 增量模式：复制现有索引
                buildingIndex.getDocuments().addAll(activeIndex.getDocuments());
                buildingIndex.getVectors().addAll(activeIndex.getVectors());
            }

            // 3. 🔥 处理删除的文件（使用类级写锁）
            int deletedCount = 0;
            for (String deletedFile : detectionResult.getDeletedFiles()) {
                String className = extractClassName(deletedFile);
                deletedCount += lockManager.writeClass(projectKey, className, () -> {
                    return removeClassVectors(buildingIndex, projectKey, className);
                });
            }

            if (deletedCount > 0) {
                log.info("🗑️ 删除文件处理完成: count={}", deletedCount);
            }

            // 4. 对每个文件生成向量（使用类级写锁）
            int successCount = 0;
            int failCount = 0;

            for (String filePath : detectionResult.getAddedOrModifiedFiles()) {
                try {
                    String className = extractClassName(filePath);

                    // 🔥 使用类级写锁
                    Integer result = lockManager.writeClass(projectKey, className, () -> {
                        return updateClassVectors(projectKey, filePath, buildingIndex);
                    });

                    if (result != null) {
                        successCount += result;
                    }

                } catch (Exception e) {
                    failCount++;
                    log.error("处理文件失败: file={}, error={}", filePath, e.getMessage());
                }
            }

            // 5. 保存到活跃缓存
            cacheManager.setActiveIndex(projectKey, buildingIndex);

            // 6. 持久化到磁盘
            indexPersistence.saveIndex(projectKey, buildingIndex);
            log.info("💾 向量索引已持久化到磁盘: projectKey={}", projectKey);

            // 7. 更新 MD5 缓存
            if (!detectionResult.getAddedOrModifiedFiles().isEmpty() || !detectionResult.getDeletedFiles().isEmpty()) {
                String projectPath = projectConfigService.getProjectPath(projectKey);
                Map<String, String> currentMd5Map = refresher.scanJavaFiles(projectPath);
                refresher.updateMd5Cache(projectKey, currentMd5Map);
                log.info("✅ MD5 缓存已更新: projectKey={}, count={}", projectKey, currentMd5Map.size());
            }

            log.info("✅ 向量索引刷新完成: projectKey={}, 成功={}, 失败={}, 删除={}",
                    projectKey, successCount, failCount, deletedCount);

            return Map.of(
                    "success", true,
                    "projectKey", projectKey,
                    "message", "向量索引刷新完成",
                    "changedFiles", detectionResult.getTotalChanges(),
                    "addedOrModified", detectionResult.getAddedOrModifiedFiles().size(),
                    "deleted", detectionResult.getDeletedFiles().size(),
                    "successCount", successCount,
                    "failCount", failCount,
                    "deletedCount", deletedCount
            );

        } catch (Exception e) {
            log.error("刷新向量索引失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
            return Map.of(
                    "success", false,
                    "projectKey", projectKey,
                    "error", e.getMessage()
            );
        }
    }

    /**
     * 更新单个类的向量（类向量 + 方法向量）
     */
    /**
     * 更新单个类的向量（类向量 + 方法向量）
     */
    private Integer updateClassVectors(String projectKey, String filePath,
                                       VectorSearchService.JVectorIndexData buildingIndex) {
        int successCount = 0;

        try {
            // 🔥 生成类级别向量
            DocumentVector classDocVector = createClassDocumentVector(projectKey, filePath);

            if (classDocVector != null) {
                // 生成 embedding
                String classText = extractTextForClassVector(classDocVector, filePath);
                float[] classEmbedding = embeddingClient.embedText(classText);

                // 检查是否已存在
                int existingIndex = findDocumentIndex(buildingIndex, classDocVector.getId());

                if (existingIndex >= 0) {
                    // 更新
                    buildingIndex.getDocuments().set(existingIndex, classDocVector);
                    buildingIndex.getVectors().set(existingIndex, classEmbedding);
                    log.debug("更新类向量: {}", filePath);
                } else {
                    // 新增
                    buildingIndex.getDocuments().add(classDocVector);
                    buildingIndex.getVectors().add(classEmbedding);
                    log.debug("新增类向量: {}", filePath);
                }

                successCount++;

                // 🔥 为每个方法生成独立的向量
                if (classDocVector.getMetadata() != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> methods = (List<Map<String, Object>>) classDocVector.getMetadata().get("methods");
                    if (methods != null && !methods.isEmpty()) {
                        for (Map<String, Object> method : methods) {
                            DocumentVector methodDocVector = createMethodDocumentVector(
                                    projectKey,
                                    filePath,
                                    classDocVector.getClassName(),
                                    method
                            );

                            if (methodDocVector != null) {
                                // 检查方法向量是否已存在
                                int methodExistingIndex = findDocumentIndex(buildingIndex, methodDocVector.getId());

                                if (methodExistingIndex >= 0) {
                                    // 更新方法向量
                                    String methodText = extractTextForMethodVector(methodDocVector, filePath);
                                    float[] methodEmbedding = embeddingClient.embedText(methodText);
                                    buildingIndex.getDocuments().set(methodExistingIndex, methodDocVector);
                                    buildingIndex.getVectors().set(methodExistingIndex, methodEmbedding);
                                } else {
                                    // 新增方法向量
                                    String methodText = extractTextForMethodVector(methodDocVector, filePath);
                                    float[] methodEmbedding = embeddingClient.embedText(methodText);
                                    buildingIndex.getDocuments().add(methodDocVector);
                                    buildingIndex.getVectors().add(methodEmbedding);
                                }

                                successCount++;
                            }
                        }
                    }
                }
            }

            return successCount;

        } catch (Exception e) {
            log.error("❌ 更新类向量失败: file={}, error={}", filePath, e.getMessage(), e);
            return successCount;
        }
    }

    /**
     * 创建类级别的文档向量
     */
    private DocumentVector createClassDocumentVector(String projectKey, String filePath) {
        log.debug("📖 读取文件分析（类级别）: {}", filePath);

        try {
            // 获取项目路径
            String projectPath = projectConfigService.getProjectPath(projectKey);
            String fullPath = projectPath + "/" + filePath;

            // 使用 Spoon AST 分析
            String className = filePath.substring(filePath.lastIndexOf('/') + 1)
                    .replace(".java", "");
            ClassInfo classInfo = spoonAstService.getClassInfo(projectKey, className);

            if (classInfo == null || classInfo.getClassName() == null || classInfo.getClassName().isEmpty()) {
                log.warn("⚠️ 无法分析文件或类名为空: {}, classInfo={}", fullPath, classInfo);
                return null;
            }

            // 构建 DocumentVector
            DocumentVector doc = new DocumentVector();
            doc.setId(projectKey + "." + classInfo.getClassName());  // 类级别 ID
            doc.setClassName(classInfo.getClassName());
            doc.setRelativePath(filePath);
            doc.setLanguage("java");
            doc.setDocType("class");

            // 🔥 将类信息存入 metadata
            Map<String, Object> metadata = new HashMap<>();

            // 类注释
            if (classInfo.getClassComment() != null && !classInfo.getClassComment().isEmpty()) {
                metadata.put("classComment", classInfo.getClassComment());
            }

            // 类注解
            if (classInfo.getAnnotations() != null && !classInfo.getAnnotations().isEmpty()) {
                metadata.put("classAnnotations", classInfo.getAnnotations());
            }

            // 字段
            if (classInfo.getFields() != null && !classInfo.getFields().isEmpty()) {
                metadata.put("fields", classInfo.getFields());
            }

            // 方法信息（用于后续生成方法向量）
            if (classInfo.getMethods() != null && !classInfo.getMethods().isEmpty()) {
                List<Map<String, Object>> methodsData = new ArrayList<>();
                for (var method : classInfo.getMethods()) {
                    Map<String, Object> methodData = new HashMap<>();
                    methodData.put("name", method.getName());
                    methodData.put("signature", buildMethodSignature(method));

                    // 方法注释
                    if (method.getComment() != null && !method.getComment().isEmpty()) {
                        methodData.put("comment", method.getComment());
                    }

                    // 方法注解
                    if (method.getAnnotations() != null && !method.getAnnotations().isEmpty()) {
                        methodData.put("annotations", method.getAnnotations());
                    }

                    // 方法源码
                    if (method.getSourceCode() != null && !method.getSourceCode().isEmpty()) {
                        methodData.put("sourceCode", method.getSourceCode());
                    }

                    methodsData.add(methodData);
                }
                metadata.put("methods", methodsData);
            }

            doc.setMetadata(metadata);

            // 简短摘要（用于显示）
            StringBuilder summary = new StringBuilder();
            summary.append("Java 类: ").append(classInfo.getClassName());
            if (classInfo.getSuperClass() != null && !classInfo.getSuperClass().isEmpty()) {
                summary.append(", 继承: ").append(classInfo.getSuperClass());
            }
            if (classInfo.getFields() != null && !classInfo.getFields().isEmpty()) {
                summary.append(", 字段数: ").append(classInfo.getFields().size());
            }
            if (classInfo.getMethods() != null && !classInfo.getMethods().isEmpty()) {
                summary.append(", 方法数: ").append(classInfo.getMethods().size());
            }
            doc.setSummary(summary.toString());

            log.debug("✅ 类向量分析完成: {}, 方法数={}, hasClassComment={}",
                    classInfo.getClassName(),
                    classInfo.getMethods() != null ? classInfo.getMethods().size() : 0,
                    classInfo.getClassComment() != null && !classInfo.getClassComment().isEmpty());

            return doc;

        } catch (Exception e) {
            log.error("❌ 创建类文档向量失败: file={}, error={}", filePath, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 创建方法级别的文档向量
     */
    private DocumentVector createMethodDocumentVector(String projectKey, String filePath,
                                                       String className, Map<String, Object> methodData) {
        String methodName = (String) methodData.get("name");

        try {
            DocumentVector doc = new DocumentVector();
            // 🔥 方法级别 ID：className.methodName
            doc.setId(projectKey + "." + className + "." + methodName);
            doc.setClassName(className);
            doc.setRelativePath(filePath);
            doc.setLanguage("java");
            doc.setDocType("method");

            // 存储方法信息到 metadata
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("methodName", methodName);
            metadata.put("methodSignature", methodData.get("signature"));
            metadata.put("methodComment", methodData.get("comment"));
            metadata.put("methodAnnotations", methodData.get("annotations"));
            metadata.put("methodSourceCode", methodData.get("sourceCode"));
            doc.setMetadata(metadata);

            // 简短摘要
            String signature = (String) methodData.get("signature");
            String comment = (String) methodData.get("comment");
            StringBuilder summary = new StringBuilder();
            summary.append(className).append(".").append(methodName);
            if (signature != null) {
                summary.append(" - ").append(signature);
            }
            if (comment != null && !comment.isEmpty()) {
                summary.append(" - ").append(comment.substring(0, Math.min(50, comment.length())));
            }
            doc.setSummary(summary.toString());

            return doc;

        } catch (Exception e) {
            log.error("❌ 创建方法文档向量失败: {}.{}, error={}", className, methodName, e.getMessage());
            return null;
        }
    }

    /**
     * 构建方法签名字符串
     */
    private String buildMethodSignature(ai.smancode.sman.agent.models.SpoonModels.MethodInfo method) {
        StringBuilder sig = new StringBuilder();

        // 修饰符
        if (method.getModifiers() != null && !method.getModifiers().isEmpty()) {
            sig.append(String.join(" ", method.getModifiers())).append(" ");
        }

        // 返回类型
        sig.append(method.getReturnType()).append(" ");

        // 方法名
        sig.append(method.getName()).append("(");

        // 参数
        if (method.getParameters() != null && !method.getParameters().isEmpty()) {
            sig.append(String.join(", ", method.getParameters()));
        }

        sig.append(")");
        return sig.toString();
    }

    /**
     * 提取类级别文本用于 embedding
     *
     * 格式：类名 + 类型 + 类注释 + 类注解 + 字段
     */
    private String extractTextForClassVector(DocumentVector doc, String filePath) {
        StringBuilder text = new StringBuilder();

        // 1. 类名和类型
        text.append("类名: ").append(doc.getClassName()).append("\n");
        text.append("类型: ").append(doc.getDocType()).append("\n");

        // 2. 类注释
        String classComment = (String) doc.getMetadata().get("classComment");
        if (classComment != null && !classComment.isEmpty()) {
            text.append("类注释: ").append(classComment).append("\n");
        }

        // 3. 类注解
        @SuppressWarnings("unchecked")
        List<String> classAnnotations = (List<String>) doc.getMetadata().get("classAnnotations");
        if (classAnnotations != null && !classAnnotations.isEmpty()) {
            text.append("类注解: ").append(String.join(", ", classAnnotations)).append("\n");
        }

        // 4. 字段
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) doc.getMetadata().get("fields");
        if (fields != null && !fields.isEmpty()) {
            text.append("字段:\n");
            for (String field : fields) {
                text.append("  ").append(field).append("\n");
            }
        }

        // 5. 路径
        text.append("路径: ").append(filePath).append("\n");

        // 🔥 截取前 20000 个字符
        String result = text.toString();
        if (result.length() > 20000) {
            result = result.substring(0, 20000);
        }

        return result;
    }

    /**
     * 提取方法级别文本用于 embedding
     *
     * 格式：类名 + 方法名 + 方法签名 + 方法注释 + 方法注解 + 方法源码
     */
    private String extractTextForMethodVector(DocumentVector doc, String filePath) {
        StringBuilder text = new StringBuilder();

        // 1. 类名
        text.append("类名: ").append(doc.getClassName()).append("\n");

        // 2. 方法名
        String methodName = (String) doc.getMetadata().get("methodName");
        text.append("方法名: ").append(methodName).append("\n");

        // 3. 方法签名
        String methodSignature = (String) doc.getMetadata().get("methodSignature");
        if (methodSignature != null) {
            text.append("签名: ").append(methodSignature).append("\n");
        }

        // 4. 方法注释
        String methodComment = (String) doc.getMetadata().get("methodComment");
        if (methodComment != null && !methodComment.isEmpty()) {
            text.append("注释: ").append(methodComment).append("\n");
        }

        // 5. 方法注解
        @SuppressWarnings("unchecked")
        List<String> methodAnnotations = (List<String>) doc.getMetadata().get("methodAnnotations");
        if (methodAnnotations != null && !methodAnnotations.isEmpty()) {
            text.append("注解: ").append(String.join(", ", methodAnnotations)).append("\n");
        }

        // 6. 方法源码
        String methodSourceCode = (String) doc.getMetadata().get("methodSourceCode");
        if (methodSourceCode != null && !methodSourceCode.isEmpty()) {
            text.append("源码:\n").append(methodSourceCode).append("\n");
        }

        // 7. 路径
        text.append("路径: ").append(filePath).append("\n");

        // 🔥 截取前 20000 个字符
        String result = text.toString();
        if (result.length() > 20000) {
            result = result.substring(0, 20000);
        }

        return result;
    }

    /**
     * 查找文档索引
     */
    private int findDocumentIndex(VectorSearchService.JVectorIndexData indexData, String docId) {
        var documents = indexData.getDocuments();

        for (int i = 0; i < documents.size(); i++) {
            var doc = documents.get(i);

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

    /**
     * 从文件路径提取类名
     */
    private String extractClassName(String filePath) {
        // 从 "core/src/main/java/com/example/MyClass.java" 提取 "MyClass"
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

        // 统计删除数量
        int beforeCount = indexData.getDocuments().size();

        // 🔥 使用迭代器同步删除文档和向量
        var docIterator = indexData.getDocuments().listIterator();
        var vecIterator = indexData.getVectors().listIterator();

        int removedCount = 0;

        while (docIterator.hasNext()) {
            var doc = docIterator.next();
            vecIterator.next(); // 同步移动向量迭代器

            // 检查是否匹配类前缀
            if (doc.getId() != null && doc.getId().startsWith(classPrefix)) {
                docIterator.remove();
                vecIterator.remove();
                removedCount++;
            }
        }

        if (removedCount > 0) {
            log.info("🗑️ 删除类向量: projectKey={}, className={}, count={}",
                    projectKey, className, removedCount);
        }

        return removedCount;
    }

    /**
     * 刷新请求
     */
    public static class RefreshRequest {
        private String projectKey;
        private boolean force = false;

        public String getProjectKey() {
            return projectKey;
        }

        public void setProjectKey(String projectKey) {
            this.projectKey = projectKey;
        }

        public boolean isForce() {
            return force;
        }

        public void setForce(boolean force) {
            this.force = force;
        }
    }
}
