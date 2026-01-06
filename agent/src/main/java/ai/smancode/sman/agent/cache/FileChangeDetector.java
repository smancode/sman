package ai.smancode.sman.agent.cache;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.smancode.sman.agent.models.ChangeDetectionResult;
import ai.smancode.sman.agent.models.ChangeDetectionResult.DetectionLevel;
import ai.smancode.sman.agent.models.FileSnapshot;
import ai.smancode.sman.agent.models.FileSnapshot.FileMetadata;

/**
 * 文件变化检测服务
 * 
 * 实现智能的四级检测逻辑：
 * 1. 扫描当前所有.java文件
 * 2. 检测文件列表变化（增删）
 * 3. 检测修改时间变化
 * 4. 检测MD5变化
 * 
 * @businessDomain code.analysis.cache
 * @businessFunction file.change.detection
 * @codeType service
 * @riskLevel low
 * @since 3.7.0
 */
@Service
public class FileChangeDetector {
    
    private static final Logger logger = LoggerFactory.getLogger(FileChangeDetector.class);
    
    // MD5持久化基础目录
    private static final String MD5_PERSIST_DIR = "./data/file-md5-cache";
    
    // 快照缓存（按项目路径）
    private final Map<String, FileSnapshot> snapshotCache = new ConcurrentHashMap<>();
    
    // 🔥 待提交的快照（延迟更新，在整个刷新周期结束后提交）
    private final Map<String, FileSnapshot> pendingSnapshot = new ConcurrentHashMap<>();
    
    /**
     * 检测文件变化（四级检测）
     * 
     * @param projectPath 项目根路径
     * @return 变化检测结果
     */
    public ChangeDetectionResult detectChanges(String projectPath) {
        return detectChanges(projectPath, false);
    }

    /**
     * 检测文件变化（四级检测）
     * 
     * @param projectPath 项目根路径
     * @param forceCheckMd5 即使修改时间一致，也强制检测MD5（用于手动刷新）
     * @return 变化检测结果
     */
    public ChangeDetectionResult detectChanges(String projectPath, boolean forceCheckMd5) {
        long startTime = System.currentTimeMillis();
        ChangeDetectionResult result = new ChangeDetectionResult();
        
        try {
            // 获取旧快照
            FileSnapshot oldSnapshot = snapshotCache.get(projectPath);
            if (oldSnapshot == null) {
                logger.info("📷 首次检测，创建初始快照: projectPath={}", projectPath);
                
                // 🔥 加载持久化的MD5缓存
                Map<String, String> persistedMd5Cache = loadMd5Cache(projectPath);
                if (!persistedMd5Cache.isEmpty()) {
                    logger.info("📖 加载持久化MD5缓存: projectPath={}, files={}", projectPath, persistedMd5Cache.size());
                }
                
                FileSnapshot newSnapshot = createSnapshot(projectPath, persistedMd5Cache);
                snapshotCache.put(projectPath, newSnapshot);
                
                result.setFileCountAfter(newSnapshot.getFileCount());
                result.setDetectionLevel(DetectionLevel.NO_CHANGE);
                result.setHasChanges(false);
                result.setSummary("首次快照，无历史对比");
                result.setDetectionDuration(System.currentTimeMillis() - startTime);
                return result;
            }
            
            // Step 1: 扫描当前所有.java文件
            logger.debug("🔍 Step 1: 扫描当前Java文件");
            
            // 🔥 加载持久化的MD5缓存，用于优先使用缓存MD5
            Map<String, String> persistedMd5Cache = loadMd5Cache(projectPath);
            FileSnapshot newSnapshot = createSnapshot(projectPath, persistedMd5Cache);
            
            result.setFileCountBefore(oldSnapshot.getFileCount());
            result.setFileCountAfter(newSnapshot.getFileCount());
            
            // Step 2: 检测文件列表变化（增删）
            logger.debug("🔍 Step 2: 检测文件列表变化");
            detectFileListChanges(oldSnapshot, newSnapshot, result);
            
            if (result.isHasChanges()) {
                logger.info("✅ 检测到文件列表变化: {}", result.getSummary());
                // 🔥 延迟提交快照
                pendingSnapshot.put(projectPath, newSnapshot);
                return result;
            }
            
            // Step 3: 检测修改时间变化
            logger.debug("🔍 Step 3: 检测修改时间变化");
            detectModifyTimeChanges(oldSnapshot, newSnapshot, result);
            
            // Step 4: 检测MD5变化
            // 策略：
            // 1. 如果forceCheckMd5=true，即使修改时间一致，也检测所有文件的MD5
            // 2. 如果修改时间一致，检查是否有文件缺少MD5（首次检测或MD5丢失）
            List<String> filesToCheckMd5 = new ArrayList<>(result.getModifiedTimeFiles());
            if (filesToCheckMd5.isEmpty()) {
                if (forceCheckMd5) {
                    // 强制检测：即使修改时间一致，也检测所有文件的MD5
                    logger.info("🔍 Step 4: 强制检测模式，检测所有文件的MD5");
                    filesToCheckMd5.addAll(newSnapshot.getFileMetadataMap().keySet());
                    result.setModifiedTimeFiles(filesToCheckMd5);
                } else {
                    // 修改时间一致，但检查是否有文件缺少MD5（需要首次计算）
                    logger.debug("🔍 Step 4: 修改时间一致，检查是否有文件缺少MD5");
                    for (String relativePath : newSnapshot.getFileMetadataMap().keySet()) {
                        FileMetadata oldMeta = oldSnapshot.getFile(relativePath);
                        FileMetadata newMeta = newSnapshot.getFile(relativePath);
                        if (oldMeta != null && newMeta != null && oldMeta.getMd5() == null) {
                            filesToCheckMd5.add(relativePath);
                            logger.debug("📋 文件缺少MD5，需要检测: {}", relativePath);
                        }
                    }
                    if (filesToCheckMd5.isEmpty()) {
                        // 所有文件修改时间一致且都有MD5 → 跳过刷新
                        logger.info("⏭️ 所有文件修改时间一致且MD5已缓存，跳过刷新");
                        result.setDetectionLevel(DetectionLevel.NO_CHANGE);
                        result.setHasChanges(false);
                        return result;
                    } else {
                        logger.info("📋 发现{}个文件缺少MD5，将检测MD5", filesToCheckMd5.size());
                        result.setModifiedTimeFiles(filesToCheckMd5);
                    }
                }
            }
            
            // Step 4: 检测MD5变化（检测修改时间变化的文件 + 缺少MD5的文件 + 强制检测的所有文件）
            logger.debug("🔍 Step 4: 检测MD5变化（{}个文件）", filesToCheckMd5.size());
            detectMd5Changes(oldSnapshot, newSnapshot, result);
            
            if (result.getMd5ChangedFiles().isEmpty()) {
                // 修改时间变了，但MD5一致（如git checkout） → 跳过刷新
                logger.info("⏭️ 修改时间变化但MD5一致，跳过刷新");
                result.setDetectionLevel(DetectionLevel.LEVEL2_MODIFY_TIME);
                result.setHasChanges(false);
            } else {
                // MD5变化 → 需要刷新
                logger.info("✅ 检测到MD5变化: {}", result.getSummary());
                result.setDetectionLevel(DetectionLevel.LEVEL3_MD5);
                result.setHasChanges(true);
            }
            
            // 🔥 核心修复：延迟更新快照
            // 保存待提交的快照，而不是立即更新
            // 在整个刷新周期结束后，调用 commitSnapshot 来更新
            pendingSnapshot.put(projectPath, newSnapshot);
            logger.debug("📌 快照已准备，等待提交: projectPath={}", projectPath);
            
        } catch (Exception e) {
            logger.error("❌ 文件变化检测失败", e);
            result.setHasChanges(true); // 出错时，保守策略：触发刷新
            result.setSummary("检测失败: " + e.getMessage());
            result.setDetectionLevel(DetectionLevel.LEVEL1_FILE_LIST);
            
        } finally {
            result.setDetectionDuration(System.currentTimeMillis() - startTime);
            result.buildSummary();
        }
        
        return result;
    }
    
    /**
     * 创建项目快照
     */
    private FileSnapshot createSnapshot(String projectPath) throws IOException {
        return createSnapshot(projectPath, null);
    }
    
    /**
     * 创建项目快照（带MD5缓存）
     */
    private FileSnapshot createSnapshot(String projectPath, Map<String, String> md5Cache) throws IOException {
        FileSnapshot snapshot = new FileSnapshot(projectPath);
        File projectDir = new File(projectPath);
        
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            throw new IOException("项目路径不存在或不是目录: " + projectPath);
        }
        
        // 递归扫描所有.java文件
        scanJavaFiles(projectDir, projectPath, snapshot, md5Cache);
        
        logger.debug("📷 快照创建完成: {} 个Java文件", snapshot.getFileCount());
        
        // 🔥 保存MD5缓存到文件
        if (md5Cache != null || !snapshot.getFileMetadataMap().isEmpty()) {
            saveMd5Cache(projectPath, snapshot);
        }
        
        return snapshot;
    }
    
    /**
     * 递归扫描Java文件
     */
    private void scanJavaFiles(File directory, String projectRoot, FileSnapshot snapshot) {
        scanJavaFiles(directory, projectRoot, snapshot, null);
    }
    
    /**
     * 递归扫描Java文件（带MD5缓存）
     */
    private void scanJavaFiles(File directory, String projectRoot, FileSnapshot snapshot, Map<String, String> md5Cache) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        
        for (File file : files) {
            // 跳过测试、生成代码、隐藏目录
            if (shouldSkip(file)) {
                continue;
            }
            
            if (file.isDirectory()) {
                scanJavaFiles(file, projectRoot, snapshot, md5Cache);
            } else if (file.getName().endsWith(".java") || file.getName().endsWith(".xml")) {
                // 🔥 修复：同时扫描 .java 和 .xml 文件，支持 XML 的增量刷新
                String absolutePath = file.getAbsolutePath();
                String relativePath = absolutePath.replace(projectRoot, "")
                    .replaceAll("^[/\\\\]+", ""); // 移除开头的斜杠
                
                FileMetadata metadata = new FileMetadata(
                    absolutePath,
                    relativePath,
                    file.length(),
                    file.lastModified()
                );
                
                // 🔥 如果有持久化MD5缓存，优先使用缓存的MD5
                if (md5Cache != null && md5Cache.containsKey(relativePath)) {
                    metadata.setMd5(md5Cache.get(relativePath));
                    // logger.debug("📋 使用缓存MD5: file={}, md5={}", relativePath, 
                    //     metadata.getMd5() != null ? metadata.getMd5().substring(0, 8) + "..." : "null");
                } else {
                    // 🔥 如果没有缓存MD5，主动计算MD5（用于持久化保存）
                    try {
                        String computedMd5 = calculateMd5(file);
                        metadata.setMd5(computedMd5);
                        // logger.debug("🔐 计算文件MD5: file={}, md5={}", relativePath, 
                        //     computedMd5.substring(0, Math.min(8, computedMd5.length())) + "...");
                    } catch (Exception e) {
                        logger.warn("⚠️ 计算MD5失败: file={}, error={}", relativePath, e.getMessage());
                    }
                }
                
                snapshot.addFile(relativePath, metadata);
            }
        }
    }
    
    /**
     * 判断是否跳过（测试、生成代码、隐藏目录）
     */
    private boolean shouldSkip(File file) {
        String path = file.getAbsolutePath().toLowerCase();
        String name = file.getName().toLowerCase();
        
        // 跳过测试目录
        if (path.contains("/src/test/") || path.contains("\\src\\test\\") ||
            path.contains("/test/java/") || path.contains("\\test\\java\\")) {
            return true;
        }
        
        // 跳过生成的代码目录
        if (path.contains("/generated/") || path.contains("\\generated\\") ||
            path.contains("/target/") || path.contains("\\target\\") ||
            path.contains("/build/") || path.contains("\\build\\")) {
            return true;
        }
        
        // 跳过隐藏目录
        if (file.isDirectory() && name.startsWith(".")) {
            return true;
        }
        
        // 跳过测试文件
        if (name.endsWith("test.java") || name.endsWith("tests.java") ||
            name.contains("mock") || name.contains("stub")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检测文件列表变化（增删）
     */
    private void detectFileListChanges(FileSnapshot oldSnapshot, FileSnapshot newSnapshot, 
                                       ChangeDetectionResult result) {
        
        Set<String> oldFiles = oldSnapshot.getFileMetadataMap().keySet();
        Set<String> newFiles = newSnapshot.getFileMetadataMap().keySet();
        
        // 找出新增的文件
        Set<String> added = new HashSet<>(newFiles);
        added.removeAll(oldFiles);
        result.setAddedFiles(new ArrayList<>(added));
        
        // 找出删除的文件
        Set<String> deleted = new HashSet<>(oldFiles);
        deleted.removeAll(newFiles);
        result.setDeletedFiles(new ArrayList<>(deleted));
        
        // 有增删 → 需要刷新
        if (!added.isEmpty() || !deleted.isEmpty()) {
            result.setHasChanges(true);
            result.setDetectionLevel(DetectionLevel.LEVEL1_FILE_LIST);
            logger.info("📝 文件列表变化: 新增{}个, 删除{}个", added.size(), deleted.size());
            
            if (!added.isEmpty() && logger.isDebugEnabled()) {
                logger.debug("新增文件: {}", added.stream().limit(5).collect(Collectors.toList()));
            }
            if (!deleted.isEmpty() && logger.isDebugEnabled()) {
                logger.debug("删除文件: {}", deleted.stream().limit(5).collect(Collectors.toList()));
            }
        }
    }
    
    /**
     * 检测修改时间变化
     */
    private void detectModifyTimeChanges(FileSnapshot oldSnapshot, FileSnapshot newSnapshot, 
                                         ChangeDetectionResult result) {
        
        List<String> modifiedTimeFiles = new ArrayList<>();
        
        // 只检查文件列表中都存在的文件
        for (String relativePath : newSnapshot.getFileMetadataMap().keySet()) {
            FileMetadata oldMeta = oldSnapshot.getFile(relativePath);
            FileMetadata newMeta = newSnapshot.getFile(relativePath);
            
            if (oldMeta != null && newMeta != null) {
                if (oldMeta.getLastModified() != newMeta.getLastModified()) {
                    modifiedTimeFiles.add(relativePath);
                }
            }
        }
        
        result.setModifiedTimeFiles(modifiedTimeFiles);
        
        if (!modifiedTimeFiles.isEmpty()) {
            logger.info("⏱️ 发现{}个文件修改时间变化", modifiedTimeFiles.size());
            if (logger.isDebugEnabled()) {
                logger.debug("修改时间变化的文件: {}", 
                    modifiedTimeFiles.stream().limit(5).collect(Collectors.toList()));
            }
        }
    }
    
    /**
     * 检测MD5变化（只检测修改时间变化的文件）
     * 
     * 优化策略：
     * 1. 如果旧快照有MD5，先与新计算的MD5对比
     * 2. 如果MD5一致，说明内容未变，将MD5保存到新快照（避免下次重复计算）
     * 3. 如果MD5不一致，说明内容变了，标记为需要刷新
     */
    private void detectMd5Changes(FileSnapshot oldSnapshot, FileSnapshot newSnapshot, ChangeDetectionResult result) {
        List<String> md5ChangedFiles = new ArrayList<>();
        int md5MatchedCount = 0; // MD5一致的文件数（内容未变）
        
        List<String> modifiedTimeFiles = result.getModifiedTimeFiles();
        
        for (String relativePath : modifiedTimeFiles) {
            FileMetadata oldMeta = oldSnapshot.getFile(relativePath);
            FileMetadata newMeta = newSnapshot.getFile(relativePath);
            
            if (newMeta == null) {
                continue;
            }
            
            try {
                // 🔥 核心修复：修改时间变化了，必须重新计算 MD5，不能使用缓存！
                // 缓存的 MD5 是旧的，如果文件被修改，缓存 MD5 就是错的
                String newMd5 = calculateMd5(new File(newMeta.getAbsolutePath()));
                // 更新快照中的 MD5
                newMeta.setMd5(newMd5);
                logger.debug("🔐 重新计算MD5: file={}, md5={}", relativePath, 
                    newMd5 != null ? newMd5.substring(0, 8) + "..." : "null");
                
                // 如果旧快照有MD5，进行对比
                if (oldMeta != null && oldMeta.getMd5() != null) {
                    String oldMd5 = oldMeta.getMd5();
                    
                    if (newMd5.equals(oldMd5)) {
                        // MD5一致，说明内容未变（虽然修改时间变了，可能是git操作）
                        md5MatchedCount++;
                        // logger.debug("📌 文件MD5一致（内容未变）: {}", relativePath);
                        // 不添加到md5ChangedFiles，表示内容未变
                    } else {
                        // MD5不一致，内容确实变了
                        md5ChangedFiles.add(relativePath);
                        logger.debug("🔐 文件MD5变化（内容已变）: {}", relativePath);
                    }
                } else {
                    // 第一次计算MD5（旧快照没有MD5）
                    // 保守策略：认为内容可能变化了
                    md5ChangedFiles.add(relativePath);
                    // logger.debug("🆕 首次计算MD5: {}", relativePath);
                }
                
            } catch (Exception e) {
                logger.warn("⚠️ 计算MD5失败: {} - {}", relativePath, e.getMessage());
                // 计算失败，保守策略：认为文件变化了
                md5ChangedFiles.add(relativePath);
            }
        }
        
        result.setMd5ChangedFiles(md5ChangedFiles);
        
        // 日志输出
        if (!md5ChangedFiles.isEmpty()) {
            logger.info("🔐 发现{}个文件MD5变化（内容已变）", md5ChangedFiles.size());
            if (logger.isDebugEnabled()) {
                logger.debug("MD5变化的文件: {}", 
                    md5ChangedFiles.stream().limit(5).collect(Collectors.toList()));
            }
        }
        
        if (md5MatchedCount > 0) {
            logger.info("📌 {}个文件MD5一致（修改时间变了但内容未变，已保存MD5避免下次重复计算）", md5MatchedCount);
        }
    }
    
    /**
     * 计算文件MD5
     */
    private String calculateMd5(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = fis.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
        }
        
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        
        return sb.toString();
    }
    
    /**
     * 获取快照（用于首次快照时的MD5对比）
     */
    public FileSnapshot getSnapshot(String projectPath) {
        return snapshotCache.get(projectPath);
    }
    
    /**
     * 清除快照缓存
     */
    public void clearSnapshot(String projectPath) {
        snapshotCache.remove(projectPath);
        logger.info("🗑️ 清除快照缓存: projectPath={}", projectPath);
    }
    
    /**
     * 🔥 提交待提交的快照（在整个刷新周期结束后调用）
     * 这确保了 Spoon 和 Vector 在同一个刷新周期内使用相同的变化检测结果
     */
    public void commitSnapshot(String projectPath) {
        FileSnapshot pending = pendingSnapshot.remove(projectPath);
        if (pending != null) {
            snapshotCache.put(projectPath, pending);
            logger.info("✅ 快照已提交: projectPath={}, fileCount={}", projectPath, pending.getFileCount());
            
            // 持久化 MD5 缓存
            try {
                saveMd5Cache(projectPath, pending);
            } catch (Exception e) {
                logger.warn("⚠️ 持久化MD5缓存失败: {}", e.getMessage());
            }
        } else {
            logger.debug("📌 无待提交的快照: projectPath={}", projectPath);
        }
    }
    
    /**
     * 🔥 回滚待提交的快照（刷新失败时调用）
     */
    public void rollbackSnapshot(String projectPath) {
        FileSnapshot removed = pendingSnapshot.remove(projectPath);
        if (removed != null) {
            logger.info("🔙 快照已回滚: projectPath={}", projectPath);
        }
    }

    /**
     * 获取MD5缓存文件路径
     */
    private Path getMd5CachePath(String projectPath) {
        String projectKey = projectPath.replaceAll("[^a-zA-Z0-9]", "_");
        return Paths.get(MD5_PERSIST_DIR, projectKey + "_md5_cache.json");
    }
    
    /**
     * 保存MD5缓存到文件
     */
    private void saveMd5Cache(String projectPath, FileSnapshot snapshot) {
        try {
            Path cachePath = getMd5CachePath(projectPath);
            Files.createDirectories(cachePath.getParent());
            
            Map<String, String> md5Cache = new HashMap<>();
            for (Map.Entry<String, FileMetadata> entry : snapshot.getFileMetadataMap().entrySet()) {
                String relativePath = entry.getKey();
                FileMetadata metadata = entry.getValue();
                if (metadata.getMd5() != null) {
                    md5Cache.put(relativePath, metadata.getMd5());
                }
            }
            
            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(cachePath.toFile(), md5Cache);
            
            logger.info("💾 MD5缓存已保存: projectPath={}, files={}, cachePath={}", 
                projectPath, md5Cache.size(), cachePath);
        } catch (Exception e) {
            logger.warn("⚠️ 保存MD5缓存失败: projectPath={}, error={}", projectPath, e.getMessage());
        }
    }
    
    /**
     * 从文件加载MD5缓存
     */
    private Map<String, String> loadMd5Cache(String projectPath) {
        try {
            Path cachePath = getMd5CachePath(projectPath);
            if (!Files.exists(cachePath)) {
                // logger.debug("📋 MD5缓存文件不存在: cachePath={}", cachePath);
                return new HashMap<>();
            }
            
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> md5Cache = mapper.readValue(cachePath.toFile(), 
                new TypeReference<Map<String, String>>() {});
            
            logger.info("📖 MD5缓存已加载: projectPath={}, files={}, cachePath={}", 
                projectPath, md5Cache.size(), cachePath);
            return md5Cache;
        } catch (Exception e) {
            logger.warn("⚠️ 加载MD5缓存失败: projectPath={}, error={}", projectPath, e.getMessage());
            return new HashMap<>();
        }
    }
    
    /**
     * 清除所有快照缓存
     */
    public void clearAllSnapshots() {
        int size = snapshotCache.size();
        snapshotCache.clear();
        logger.info("🗑️ 清除所有快照缓存: {} 个", size);
    }
    
    /**
     * 获取指定文件的MD5值
     * 优先从新快照中获取，如果没有则从持久化缓存中获取
     * 
     * @param projectPath 项目路径
     * @param filePath 文件绝对路径
     * @return MD5值，如果找不到返回null
     */
    public String getMd5ForFile(String projectPath, String filePath) {
        try {
            // 1. 优先从当前快照中获取MD5
            FileSnapshot snapshot = snapshotCache.get(projectPath);
            if (snapshot != null && snapshot.getFileMetadataMap() != null) {
                // 将绝对路径转换为相对路径进行查找
                File projectDir = new File(projectPath);
                String relativePath = getRelativePath(projectDir, new File(filePath));
                
                if (relativePath != null) {
                    FileMetadata metadata = snapshot.getFile(relativePath);
                    if (metadata != null && metadata.getMd5() != null) {
                        logger.debug("📋 从快照获取MD5: file={}, md5={}", filePath, 
                            metadata.getMd5().substring(0, Math.min(8, metadata.getMd5().length())) + "...");
                        return metadata.getMd5();
                    }
                }
            }
            
            // 2. 如果快照中没有，从持久化缓存中获取
            Map<String, String> md5Cache = loadMd5Cache(projectPath);
            if (!md5Cache.isEmpty()) {
                // 将绝对路径转换为相对路径进行查找
                File projectDir = new File(projectPath);
                String relativePath = getRelativePath(projectDir, new File(filePath));
                
                if (relativePath != null && md5Cache.containsKey(relativePath)) {
                    String cachedMd5 = md5Cache.get(relativePath);
                    logger.debug("📖 从缓存获取MD5: file={}, md5={}", filePath, 
                        cachedMd5.substring(0, Math.min(8, cachedMd5.length())) + "...");
                    return cachedMd5;
                }
            }
            
            // logger.debug("❓ 未找到MD5缓存: file={}", filePath);
            return null;
        } catch (Exception e) {
            logger.warn("⚠️ 获取文件MD5失败: file={}, error={}", filePath, e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取相对路径
     * 
     * @param projectDir 项目根目录
     * @param file 目标文件
     * @return 相对路径，如果获取失败返回null
     */
    private String getRelativePath(File projectDir, File file) {
        try {
            String projectPath = projectDir.getAbsolutePath();
            String filePath = file.getAbsolutePath();
            
            if (!filePath.startsWith(projectPath)) {
                logger.warn("⚠️ 文件不在项目目录中: project={}, file={}", projectPath, filePath);
                return null;
            }
            
            String relativePath = filePath.substring(projectPath.length())
                .replaceAll("^[/\\\\]+", ""); // 移除开头的斜杠
            
            return relativePath;
        } catch (Exception e) {
            logger.warn("⚠️ 获取相对路径失败: project={}, file={}, error={}", 
                projectDir.getAbsolutePath(), file.getAbsolutePath(), e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取快照统计
     */
    public Map<String, Object> getSnapshotStatistics() {
        return snapshotCache.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> {
                    FileSnapshot snapshot = entry.getValue();
                    return Map.of(
                        "fileCount", snapshot.getFileCount(),
                        "timestamp", snapshot.getTimestamp(),
                        "age", (System.currentTimeMillis() - snapshot.getTimestamp()) / 1000
                    );
                }
            ));
    }
}

