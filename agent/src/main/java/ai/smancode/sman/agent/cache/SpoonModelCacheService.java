package ai.smancode.sman.agent.cache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import ai.smancode.sman.agent.utils.PathUtils;
import spoon.reflect.CtModel;

/**
 * Spoon模型缓存服务
 * 
 * 功能：
 * 1. 将Spoon模型序列化到磁盘
 * 2. 从磁盘加载缓存的模型
 * 3. 管理缓存文件的生命周期
 * 
 * 缓存策略：
 * - 使用项目路径的hash作为缓存文件名
 * - 使用GZIP压缩减少文件大小
 * - 缓存文件存储在配置的缓存目录中
 * 
 * @businessDomain code.analysis.cache
 * @businessFunction spoon.model.cache
 * @codeType service
 * @riskLevel low
 * @performanceImpact high
 * @since 3.7.0
 */
@Service
public class SpoonModelCacheService {
    
    private static final Logger logger = LoggerFactory.getLogger(SpoonModelCacheService.class);
    
    @Value("${bank.analysis.static.spoon-cache-enabled:true}")
    private boolean cacheEnabled;
    
    @Value("${bank.analysis.static.spoon-snapshot-dir:./data/spoon-snapshots}")
    private String snapshotDir;
    
    /**
     * 保存模型到缓存文件
     * 
     * @param projectPath 项目路径（作为缓存key）
     * @param projectKey 项目标识（用于构建存储路径）
     * @param model Spoon模型
     * @param metadata 元数据（构建时间、类型数等）
     * @return 是否保存成功
     */
    public boolean saveModel(String projectPath, String projectKey, CtModel model, Map<String, Object> metadata) {
        if (!cacheEnabled) {
            logger.debug("Spoon模型缓存已禁用，跳过保存");
            return false;
        }
        
        if (projectPath == null || projectPath.isEmpty()) {
            logger.warn("⚠️ 项目路径为空，无法保存缓存");
            return false;
        }
        
        if (model == null) {
            logger.warn("⚠️ Spoon模型为空，无法保存缓存");
            return false;
        }
        
        try {
            // 🔥 统一规范化项目路径，确保保存和加载时使用相同的路径格式
            String normalizedProjectPath = PathUtils.normalizePath(projectPath);
            
            // 🔥 统一存储路径：data/spoon-snapshots/{projectKey}/spoon-models/
            String effectiveProjectKey = (projectKey != null && !projectKey.trim().isEmpty()) ? projectKey.trim() : "default";
            Path baseCacheDir = Paths.get(snapshotDir, effectiveProjectKey, "spoon-models");
            
            // 确保缓存目录存在
            ensureCacheDirectoryExists(baseCacheDir);
            
            // 生成缓存文件路径（使用规范化后的路径）
            String cacheFileName = generateCacheFileName(normalizedProjectPath);
            Path cacheFilePath = baseCacheDir.resolve(cacheFileName);
            
            logger.info("💾 开始保存Spoon模型缓存: {} (projectKey={})", cacheFilePath, effectiveProjectKey);
            long startTime = System.currentTimeMillis();
            
            // 🔥 使用Spoon的prettyprint将模型的所有Java文件保存到目录
            // 这样下次启动时可以直接从这些文件加载，而不需要重新解析源码
            Path modelOutputDir = baseCacheDir.resolve("model-" + generateCacheKey(normalizedProjectPath));
            if (Files.exists(modelOutputDir)) {
                // 清理旧缓存目录
                deleteDirectory(modelOutputDir);
            }
            Files.createDirectories(modelOutputDir);
            
            logger.info("💾 开始复制源文件到缓存目录: {}", modelOutputDir);
            
            // 🔥 方案2：直接复制原始Java源文件到缓存目录（不使用prettyprint，避免Spoon内部bug）
            // 这种方式更可靠，不会丢失任何代码，也不会遇到prettyprint的NullPointerException
            int copiedCount = copySourceFilesToCache(normalizedProjectPath, modelOutputDir);
            
            if (copiedCount == 0) {
                logger.warn("⚠️ 没有复制任何源文件，放弃保存缓存");
                return false;
            }
            
            logger.info("✅ 源文件复制完成: {} 个Java文件", copiedCount);
            
            // 统计保存的文件数
            int fileCount = countJavaFiles(modelOutputDir);
            logger.info("✅ Spoon模型文件保存完成: {} 个Java文件", fileCount);
            
            // 创建缓存数据对象（保存元数据和输出目录路径）
            CachedModelData cacheData = new CachedModelData();
            cacheData.projectPath = normalizedProjectPath; // 🔥 保存规范化后的路径，确保加载时能匹配
            cacheData.modelOutputDir = modelOutputDir.toString();
            cacheData.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
            cacheData.timestamp = System.currentTimeMillis();
            cacheData.fileCount = fileCount;
            // model字段使用transient，不序列化
            
            // 序列化元数据和目录路径
            try (FileOutputStream fos = new FileOutputStream(cacheFilePath.toFile());
                 GZIPOutputStream gzos = new GZIPOutputStream(fos);
                 ObjectOutputStream oos = new ObjectOutputStream(gzos)) {
                
                oos.writeObject(cacheData);
                oos.flush();
            }
            
            long fileSize = Files.size(cacheFilePath);
            long duration = System.currentTimeMillis() - startTime;
            logger.info("✅ Spoon模型缓存保存成功: {} (大小: {} MB, 耗时: {} ms)", 
                cacheFilePath, String.format("%.2f", fileSize / 1024.0 / 1024.0), duration);
            
            return true;
            
        } catch (Exception e) {
            logger.error("❌ 保存Spoon模型缓存失败: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 从缓存文件加载模型
     * 
     * @param projectPath 项目路径（作为缓存key）
     * @param projectKey 项目标识（用于构建存储路径）
     * @return 缓存的模型数据，如果不存在或加载失败则返回null
     */
    public CachedModelData loadModel(String projectPath, String projectKey) {
        if (!cacheEnabled) {
            logger.debug("Spoon模型缓存已禁用，跳过加载");
            return null;
        }
        
        if (projectPath == null || projectPath.isEmpty()) {
            logger.warn("⚠️ 项目路径为空，无法加载缓存");
            return null;
        }
        
        try {
            // 🔥 统一规范化项目路径，确保保存和加载时使用相同的路径格式
            String normalizedProjectPath = PathUtils.normalizePath(projectPath);
            
            // 🔥 统一存储路径：data/spoon-snapshots/{projectKey}/spoon-models/
            String effectiveProjectKey = (projectKey != null && !projectKey.trim().isEmpty()) ? projectKey.trim() : "default";
            Path baseCacheDir = Paths.get(snapshotDir, effectiveProjectKey, "spoon-models");
            
            // 生成缓存文件路径（使用规范化后的路径）
            String cacheFileName = generateCacheFileName(normalizedProjectPath);
            Path cacheFilePath = baseCacheDir.resolve(cacheFileName);
            
            if (!Files.exists(cacheFilePath)) {
                logger.debug("📭 Spoon模型缓存文件不存在: {} (projectKey={})", cacheFilePath, effectiveProjectKey);
                return null;
            }
            
            logger.info("📂 开始加载Spoon模型缓存: {}", cacheFilePath);
            long startTime = System.currentTimeMillis();
            
            // 解压并反序列化元数据
            CachedModelData cacheData;
            try (FileInputStream fis = new FileInputStream(cacheFilePath.toFile());
                 GZIPInputStream gzis = new GZIPInputStream(fis);
                 ObjectInputStream ois = new ObjectInputStream(gzis)) {
                
                cacheData = (CachedModelData) ois.readObject();
            }
            
            // 验证项目路径是否匹配（使用规范化后的路径进行比较）
            if (!normalizedProjectPath.equals(cacheData.projectPath)) {
                logger.warn("⚠️ 缓存文件的项目路径不匹配: 期望={}, 实际={}", 
                    normalizedProjectPath, cacheData.projectPath);
                return null;
            }
            
            // 🔥 不再检查缓存版本号，直接加载缓存
            // 缓存有效性由后台刷新服务（SpoonModelRefreshService）通过FileChangeDetector自动检测文件变化来决定
            // 大多数情况下缓存是有效的，先加载使用，后台会自动比对并更新
            
            // 🔥 从保存的模型输出目录重新构建模型
            if (cacheData.modelOutputDir != null && Files.exists(Paths.get(cacheData.modelOutputDir))) {
                try {
                    // 检查缓存目录中是否有Java文件
                    int cachedFileCount = countJavaFiles(Paths.get(cacheData.modelOutputDir));
                    if (cachedFileCount == 0) {
                        logger.warn("⚠️ 缓存目录为空（0个Java文件），缓存可能保存失败，将重新构建: {}", cacheData.modelOutputDir);
                        return null; // 返回null，触发重新构建
                    }
                    
                    logger.info("📂 从缓存目录重新构建Spoon模型: {} (包含{}个Java文件)", 
                        cacheData.modelOutputDir, cachedFileCount);
                    long rebuildStartTime = System.currentTimeMillis();
                    
                    spoon.Launcher launcher = new spoon.Launcher();
                    launcher.addInputResource(cacheData.modelOutputDir);
                    // 🔥 环境配置与buildModelInternal保持一致，确保等价性
                    launcher.getEnvironment().setNoClasspath(true);
                    launcher.getEnvironment().setComplianceLevel(21);
                    launcher.getEnvironment().setCommentEnabled(true);  // 保留注释
                    launcher.getEnvironment().setAutoImports(true);
                    launcher.getEnvironment().setShouldCompile(false);
                    launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
                    launcher.getEnvironment().setIgnoreSyntaxErrors(true);
                    launcher.getEnvironment().setLevel("OFF");
                    
                    // 设置UTF-8编码
                    try {
                        launcher.getEnvironment().setEncoding(java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        logger.debug("设置UTF-8编码失败: {}", e.getMessage());
                    }
                    
                    cacheData.model = launcher.buildModel();
                    
                    long rebuildDuration = System.currentTimeMillis() - rebuildStartTime;
                    int typeCount = cacheData.model != null ? cacheData.model.getAllTypes().size() : 0;
                    
                    if (typeCount == 0) {
                        logger.warn("⚠️ 从缓存目录构建的模型为空（0个类型），缓存可能损坏，将重新构建");
                        return null; // 返回null，触发重新构建
                    }
                    
                    logger.info("✅ 从缓存目录重新构建Spoon模型成功: {} 个类型, 耗时{}ms", typeCount, rebuildDuration);
                } catch (Exception e) {
                    logger.warn("⚠️ 从缓存目录重新构建模型失败，将使用原始项目路径: {}", e.getMessage(), e);
                    cacheData.model = null; // 标记需要重新构建
                    return null; // 返回null，触发重新构建
                }
            } else {
                logger.warn("⚠️ 模型输出目录不存在: {}", cacheData.modelOutputDir);
                cacheData.model = null; // 标记需要重新构建
                return null; // 返回null，触发重新构建
            }
            
            long fileSize = Files.size(cacheFilePath);
            long duration = System.currentTimeMillis() - startTime;
            logger.info("✅ Spoon模型缓存加载成功: {} (大小: {} MB, 耗时: {} ms)", 
                cacheFilePath, String.format("%.2f", fileSize / 1024.0 / 1024.0), duration);
            
            return cacheData;
            
        } catch (Exception e) {
            logger.error("❌ 加载Spoon模型缓存失败: {}", e.getMessage(), e);
            // 如果加载失败，删除损坏的缓存文件
            try {
                String normalizedProjectPath = PathUtils.normalizePath(projectPath);
                String effectiveProjectKey = (projectKey != null && !projectKey.trim().isEmpty()) ? projectKey.trim() : "default";
                Path baseCacheDir = Paths.get(snapshotDir, effectiveProjectKey, "spoon-models");
                String cacheFileName = generateCacheFileName(normalizedProjectPath);
                Path cacheFilePath = baseCacheDir.resolve(cacheFileName);
                if (Files.exists(cacheFilePath)) {
                    Files.delete(cacheFilePath);
                    logger.info("🗑️ 已删除损坏的缓存文件: {}", cacheFilePath);
                }
            } catch (IOException deleteEx) {
                logger.warn("⚠️ 删除损坏的缓存文件失败: {}", deleteEx.getMessage());
            }
            return null;
        }
    }
    
    /**
     * 删除指定项目的缓存文件
     * 
     * @param projectPath 项目路径
     * @param projectKey 项目标识（用于构建存储路径）
     * @return 是否删除成功
     */
    public boolean deleteCache(String projectPath, String projectKey) {
        if (projectPath == null || projectPath.isEmpty()) {
            return false;
        }
        
        try {
            // 🔥 统一规范化项目路径
            String normalizedProjectPath = PathUtils.normalizePath(projectPath);
            String effectiveProjectKey = (projectKey != null && !projectKey.trim().isEmpty()) ? projectKey.trim() : "default";
            Path baseCacheDir = Paths.get(snapshotDir, effectiveProjectKey, "spoon-models");
            String cacheFileName = generateCacheFileName(normalizedProjectPath);
            Path cacheFilePath = baseCacheDir.resolve(cacheFileName);
            
            if (Files.exists(cacheFilePath)) {
                Files.delete(cacheFilePath);
                logger.info("🗑️ 已删除Spoon模型缓存: {}", cacheFilePath);
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            logger.error("❌ 删除Spoon模型缓存失败: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 清除所有缓存文件（遍历所有projectKey）
     */
    public void clearAllCache() {
        try {
            Path snapshotBaseDir = Paths.get(snapshotDir);
            if (!Files.exists(snapshotBaseDir)) {
                return;
            }
            
            // 遍历所有projectKey目录下的spoon-models目录
            File[] projectDirs = snapshotBaseDir.toFile().listFiles(File::isDirectory);
            if (projectDirs == null) {
                return;
            }
            
            int totalCount = 0;
            for (File projectDir : projectDirs) {
                Path spoonModelsDir = projectDir.toPath().resolve("spoon-models");
                if (!Files.exists(spoonModelsDir)) {
                    continue;
                }
                
                File[] cacheFiles = spoonModelsDir.toFile().listFiles((dir, name) -> 
                    name.startsWith("spoon-model-") && name.endsWith(".cache.gz"));
                
                if (cacheFiles != null) {
                    int count = 0;
                    for (File file : cacheFiles) {
                        try {
                            Files.delete(file.toPath());
                            count++;
                        } catch (IOException e) {
                            logger.warn("⚠️ 删除缓存文件失败: {}", file.getName());
                        }
                    }
                    totalCount += count;
                }
            }
            logger.info("🗑️ 已清除所有Spoon模型缓存: {} 个文件", totalCount);
            
        } catch (Exception e) {
            logger.error("❌ 清除所有缓存失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 获取缓存文件信息
     */
    public Map<String, Object> getCacheInfo(String projectPath, String projectKey) {
        Map<String, Object> info = new HashMap<>();
        
        try {
            // 🔥 统一规范化项目路径
            String normalizedProjectPath = PathUtils.normalizePath(projectPath);
            String effectiveProjectKey = (projectKey != null && !projectKey.trim().isEmpty()) ? projectKey.trim() : "default";
            Path baseCacheDir = Paths.get(snapshotDir, effectiveProjectKey, "spoon-models");
            String cacheFileName = generateCacheFileName(normalizedProjectPath);
            Path cacheFilePath = baseCacheDir.resolve(cacheFileName);
            
            if (Files.exists(cacheFilePath)) {
                info.put("exists", true);
                info.put("filePath", cacheFilePath.toString());
                info.put("fileSize", Files.size(cacheFilePath));
                info.put("lastModified", Files.getLastModifiedTime(cacheFilePath).toMillis());
                
                // 尝试加载元数据
                CachedModelData cacheData = loadModel(projectPath, projectKey);
                if (cacheData != null && cacheData.metadata != null) {
                    info.put("metadata", cacheData.metadata);
                    info.put("cacheTimestamp", cacheData.timestamp);
                }
            } else {
                info.put("exists", false);
            }
            
        } catch (Exception e) {
            logger.error("❌ 获取缓存信息失败: {}", e.getMessage(), e);
            info.put("error", e.getMessage());
        }
        
        return info;
    }
    
    /**
     * 确保缓存目录存在
     */
    private void ensureCacheDirectoryExists(Path cacheDirPath) throws IOException {
        if (!Files.exists(cacheDirPath)) {
            Files.createDirectories(cacheDirPath);
            logger.debug("📁 创建缓存目录: {}", cacheDirPath);
        }
    }
    
    /**
     * 生成缓存key（用于文件名和目录名）
     */
    private String generateCacheKey(String projectPath) {
        // 使用项目路径的hash值
        int hash = projectPath.hashCode();
        // 处理负数
        return hash >= 0 ? String.valueOf(hash) : "n" + String.valueOf(-hash);
    }
    
    /**
     * 生成缓存文件名
     * 使用项目路径的hash值作为文件名，避免路径中的特殊字符问题
     */
    private String generateCacheFileName(String projectPath) {
        return "spoon-model-" + generateCacheKey(projectPath) + ".cache.gz";
    }
    
    /**
     * 统计目录中的Java文件数
     */
    private int countJavaFiles(Path directory) {
        try {
            return (int) Files.walk(directory)
                .filter(path -> path.toString().endsWith(".java"))
                .count();
        } catch (IOException e) {
            logger.warn("⚠️ 统计Java文件数失败: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * 递归删除目录
     */
    private void deleteDirectory(Path directory) {
        try {
            if (Files.exists(directory)) {
                Files.walk(directory)
                    .sorted((a, b) -> b.compareTo(a)) // 先删除文件，再删除目录
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            logger.warn("⚠️ 删除文件失败: {}", path);
                        }
                    });
            }
        } catch (IOException e) {
            logger.warn("⚠️ 删除目录失败: {}", e.getMessage());
        }
    }
    
    /**
     * 复制源文件到缓存目录（不使用prettyprint，直接复制原始文件）
     * 
     * 🔥 这种方式比prettyprint更可靠：
     * - 不会遇到Spoon内部的NullPointerException
     * - 保留原始代码格式和注释
     * - 不丢失任何文件
     * 
     * @param projectPath 项目根路径
     * @param targetDir 目标缓存目录
     * @return 复制的文件数量
     */
    private int copySourceFilesToCache(String projectPath, Path targetDir) {
        logger.info("📂 开始复制源文件: {} -> {}", projectPath, targetDir);
        
        java.util.concurrent.atomic.AtomicInteger copiedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger skipCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger errorCount = new java.util.concurrent.atomic.AtomicInteger(0);
        
        java.io.File projectDir = new java.io.File(projectPath);
        if (!projectDir.exists()) {
            logger.error("❌ 项目目录不存在: {}", projectPath);
            return 0;
        }
        
        // 递归复制源文件
        copySourceFilesRecursively(projectDir, projectPath, targetDir, copiedCount, skipCount, errorCount);
        
        logger.info("✅ 源文件复制完成: 复制 {} 个, 跳过 {} 个, 失败 {} 个", 
            copiedCount.get(), skipCount.get(), errorCount.get());
        
        return copiedCount.get();
    }
    
    /**
     * 递归复制源文件（保持目录结构）
     */
    private void copySourceFilesRecursively(java.io.File directory, String projectPath, Path targetDir,
                                            java.util.concurrent.atomic.AtomicInteger copiedCount,
                                            java.util.concurrent.atomic.AtomicInteger skipCount,
                                            java.util.concurrent.atomic.AtomicInteger errorCount) {
        java.io.File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        
        for (java.io.File file : files) {
            String relativePath = file.getAbsolutePath().replace(projectPath, "");
            // 确保relativePath以/开头
            if (!relativePath.startsWith("/") && !relativePath.startsWith("\\")) {
                relativePath = "/" + relativePath;
            }
            
            // 跳过测试相关目录和文件
            if (shouldSkipPath(relativePath, file.getName())) {
                skipCount.incrementAndGet();
                continue;
            }
            
            if (file.isDirectory()) {
                copySourceFilesRecursively(file, projectPath, targetDir, copiedCount, skipCount, errorCount);
            } else if (file.getName().endsWith(".java")) {
                try {
                    // 计算目标文件路径（保持相对目录结构）
                    Path targetFile = targetDir.resolve(relativePath.substring(1)); // 去掉开头的/
                    
                    // 确保父目录存在
                    Files.createDirectories(targetFile.getParent());
                    
                    // 复制文件
                    Files.copy(file.toPath(), targetFile, 
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    
                    copiedCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    logger.warn("⚠️ 复制文件失败: {} - {}", relativePath, e.getMessage());
                }
            }
        }
    }
    
    /**
     * 判断是否应该跳过某个路径（测试相关，与SpoonSearchEngine逻辑一致）
     * 
     * @param relativePath 相对路径
     * @param fileName 文件名
     * @return 是否跳过
     */
    private boolean shouldSkipPath(String relativePath, String fileName) {
        String lowerPath = relativePath.toLowerCase();
        String lowerName = fileName.toLowerCase();
        
        // 跳过测试目录
        if (lowerPath.contains("/src/test/") || lowerPath.contains("\\src\\test\\") ||
            lowerPath.contains("/test/java/") || lowerPath.contains("\\test\\java\\")) {
            return true;
        }
        
        // 跳过测试文件
        if (lowerName.endsWith("test.java") || 
            lowerName.endsWith("tests.java") ||
            lowerName.contains("mock") ||
            lowerName.contains("stub")) {
            return true;
        }
        
        // 跳过生成的代码目录（重要：避免扫描target、build等目录）
        if (lowerPath.contains("/generated/") || lowerPath.contains("\\generated\\") ||
            lowerPath.contains("/target/") || lowerPath.contains("\\target\\") ||
            lowerPath.contains("/build/") || lowerPath.contains("\\build\\")) {
            return true;
        }
        
        // 跳过隐藏目录
        if (lowerPath.contains("/.") || lowerPath.contains("\\.")) {
            return true;
        }
        
        return false;
    }
    
    
    /**
     * 查看缓存内容
     * 
     * @param projectPath 项目路径
     * @param projectKey 项目标识（用于构建存储路径）
     * @return 缓存信息
     */
    public Map<String, Object> inspectCache(String projectPath, String projectKey) {
        Map<String, Object> info = new HashMap<>();
        
        try {
            // 🔥 统一规范化项目路径
            String normalizedProjectPath = PathUtils.normalizePath(projectPath);
            String effectiveProjectKey = (projectKey != null && !projectKey.trim().isEmpty()) ? projectKey.trim() : "default";
            Path baseCacheDir = Paths.get(snapshotDir, effectiveProjectKey, "spoon-models");
            String cacheFileName = generateCacheFileName(normalizedProjectPath);
            Path cacheFilePath = baseCacheDir.resolve(cacheFileName);
            String cacheKey = generateCacheKey(normalizedProjectPath);
            Path modelOutputDir = baseCacheDir.resolve("model-" + cacheKey);
            
            // 检查元数据文件
            if (Files.exists(cacheFilePath)) {
                info.put("metadataFileExists", true);
                info.put("metadataFilePath", cacheFilePath.toString());
                info.put("metadataFileSize", Files.size(cacheFilePath));
                info.put("metadataLastModified", Files.getLastModifiedTime(cacheFilePath).toMillis());
                
                // 加载元数据
                CachedModelData cacheData = loadModel(projectPath, projectKey);
                if (cacheData != null) {
                    info.put("projectPath", cacheData.projectPath);
                    info.put("timestamp", cacheData.timestamp);
                    info.put("metadata", cacheData.metadata);
                    info.put("fileCount", cacheData.fileCount);
                }
            } else {
                info.put("metadataFileExists", false);
            }
            
            // 检查模型文件目录
            if (Files.exists(modelOutputDir)) {
                info.put("modelDirExists", true);
                info.put("modelDirPath", modelOutputDir.toString());
                
                // 统计文件
                int javaFileCount = countJavaFiles(modelOutputDir);
                info.put("javaFileCount", javaFileCount);
                
                // 计算目录大小
                long dirSize = calculateDirectorySize(modelOutputDir);
                info.put("modelDirSize", dirSize);
                info.put("modelDirSizeMB", String.format("%.2f", dirSize / 1024.0 / 1024.0));
            } else {
                info.put("modelDirExists", false);
            }
            
        } catch (Exception e) {
            logger.error("❌ 查看缓存信息失败: {}", e.getMessage(), e);
            info.put("error", e.getMessage());
        }
        
        return info;
    }
    
    /**
     * 计算目录大小
     */
    private long calculateDirectorySize(Path directory) {
        try {
            return Files.walk(directory)
                .filter(Files::isRegularFile)
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .sum();
        } catch (IOException e) {
            logger.warn("⚠️ 计算目录大小失败: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * 缓存的模型数据
     * 包含模型和元数据
     * 
     * 🔥 注意：CtModel使用transient标记，不序列化
     * 模型通过prettyprint保存到目录，加载时从目录重新构建
     * 
     * 🔥 缓存有效性策略：
     * - 不再使用版本号检查，每次都先尝试加载缓存（大多数时候足够了）
     * - 后台刷新服务（SpoonModelRefreshService）通过FileChangeDetector自动检测文件变化
     * - 当检测到文件变化时，后台会自动刷新缓存，确保缓存与源码同步
     * - 这种策略更可靠，避免了版本号维护的复杂性
     */
    public static class CachedModelData implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        
        public String projectPath;
        public transient CtModel model; // 使用transient，不序列化，加载时从目录重建
        public String modelOutputDir; // 模型输出目录路径
        public Map<String, Object> metadata;
        public long timestamp;
        public int fileCount; // 保存的Java文件数
    }
}

