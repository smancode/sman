package com.smancode.smanagent.cache;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.smancode.smanagent.model.cache.ChangeDetectionResult;

/**
 * 项目缓存服务
 *
 * 功能：
 * - 统一管理项目缓存的生命周期
 * - 启动时加载缓存
 * - 运行时增量刷新
 * - 停机时持久化缓存
 *
 * @since 1.0.0
 */
@Service
public class ProjectCacheService {

    private static final Logger logger = LoggerFactory.getLogger(ProjectCacheService.class);

    /**
     * 缓存基础目录
     */
    @Value("${project.cache.base-dir:./data}")
    private String cacheBaseDir;

    /**
     * MD5缓存目录
     */
    @Value("${project.cache.md5-dir:./data/file-md5-cache}")
    private String md5CacheDir;

    /**
     * Spoon快照目录
     */
    @Value("${project.cache.spoon-dir:./data/spoon-snapshots}")
    private String spoonCacheDir;

    /**
     * 异步刷新线程池
     */
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "project-cache-refresh");
        t.setDaemon(true);
        return t;
    });

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FileChangeDetector fileChangeDetector;

    /**
     * 初始化（启动时加载缓存）
     */
    public void initialize(String projectPath, String projectKey) {
        logger.info("🚀 初始化项目缓存: projectPath={}, projectKey={}", projectPath, projectKey);

        try {
            // 确保缓存目录存在
            ensureCacheDirectories();

            // 加载MD5缓存（由FileChangeDetector在首次检测时自动加载）
            if (fileChangeDetector != null) {
                logger.info("📖 MD5缓存将在首次检测时自动加载");
            }

            logger.info("✅ 项目缓存初始化完成");

        } catch (Exception e) {
            logger.error("❌ 初始化项目缓存失败", e);
        }
    }

    /**
     * 检测变化并增量刷新
     *
     * @param projectPath 项目路径
     * @return 刷新结果
     */
    public ChangeDetectionResult detectAndRefresh(String projectPath) {
        logger.info("🔍 检测项目变化: projectPath={}", projectPath);

        if (fileChangeDetector == null) {
            logger.warn("⚠️ FileChangeDetector 未注入，无法检测变化");
            return createNoChangeResult();
        }

        try {
            // 检测文件变化
            ChangeDetectionResult result = fileChangeDetector.detectChanges(projectPath);

            if (result.isHasChanges()) {
                logger.info("✅ 检测到变化: {}", result.getSummary());

                // 提交快照
                fileChangeDetector.commitSnapshot(projectPath);

            } else {
                logger.info("⏭️ 无变化，跳过刷新");
            }

            return result;

        } catch (Exception e) {
            logger.error("❌ 检测变化失败", e);

            // 回滚快照
            fileChangeDetector.rollbackSnapshot(projectPath);

            return createErrorResult(e.getMessage());
        }
    }

    /**
     * 异步检测并刷新
     */
    public CompletableFuture<ChangeDetectionResult> detectAndRefreshAsync(String projectPath) {
        return CompletableFuture.supplyAsync(() -> detectAndRefresh(projectPath), refreshExecutor);
    }

    /**
     * 强制刷新（忽略修改时间，强制检测所有文件的MD5）
     */
    public ChangeDetectionResult forceRefresh(String projectPath) {
        logger.info("🔄 强制刷新项目: projectPath={}", projectPath);

        if (fileChangeDetector == null) {
            logger.warn("⚠️ FileChangeDetector 未注入，无法强制刷新");
            return createNoChangeResult();
        }

        try {
            // 强制检测MD5
            ChangeDetectionResult result = fileChangeDetector.detectChanges(projectPath, true);

            // 提交快照
            fileChangeDetector.commitSnapshot(projectPath);

            logger.info("✅ 强制刷新完成: {}", result.getSummary());

            return result;

        } catch (Exception e) {
            logger.error("❌ 强制刷新失败", e);
            fileChangeDetector.rollbackSnapshot(projectPath);
            return createErrorResult(e.getMessage());
        }
    }

    /**
     * 持久化缓存（停机时调用）
     */
    public void persistCache(String projectPath) {
        logger.info("💾 持久化项目缓存: projectPath={}", projectPath);

        try {
            // MD5缓存由FileChangeDetector在每次检测后自动保存
            // 这里只需要确保目录结构完整
            ensureCacheDirectories();

            logger.info("✅ 项目缓存持久化完成");

        } catch (Exception e) {
            logger.error("❌ 持久化项目缓存失败", e);
        }
    }

    /**
     * 清除项目缓存
     */
    public void clearCache(String projectPath) {
        logger.info("🗑️ 清除项目缓存: projectPath={}", projectPath);

        try {
            if (fileChangeDetector != null) {
                fileChangeDetector.clearSnapshot(projectPath);
            }

            // 删除MD5缓存文件
            String projectKey = projectPath.replaceAll("[^a-zA-Z0-9]", "_");
            Path md5CacheFile = Paths.get(md5CacheDir, projectKey + "_md5_cache.json");
            if (Files.exists(md5CacheFile)) {
                Files.delete(md5CacheFile);
                logger.info("🗑️ 已删除MD5缓存文件: {}", md5CacheFile);
            }

            logger.info("✅ 项目缓存已清除");

        } catch (Exception e) {
            logger.error("❌ 清除项目缓存失败", e);
        }
    }

    /**
     * 获取缓存统计信息
     */
    public Map<String, Object> getCacheStatistics(String projectPath) {
        Map<String, Object> stats = new HashMap<>();

        try {
            stats.put("projectPath", projectPath);
            stats.put("cacheBaseDir", cacheBaseDir);
            stats.put("md5CacheDir", md5CacheDir);
            stats.put("spoonCacheDir", spoonCacheDir);

            // 快照统计
            if (fileChangeDetector != null) {
                Map<String, Object> snapshotStats = fileChangeDetector.getSnapshotStatistics();
                stats.put("snapshots", snapshotStats);
            }

            // 缓存文件大小
            String projectKey = projectPath.replaceAll("[^a-zA-Z0-9]", "_");
            Path md5CacheFile = Paths.get(md5CacheDir, projectKey + "_md5_cache.json");
            if (Files.exists(md5CacheFile)) {
                stats.put("md5CacheSize", Files.size(md5CacheFile));
                stats.put("md5CacheExists", true);
            } else {
                stats.put("md5CacheExists", false);
            }

        } catch (Exception e) {
            logger.error("❌ 获取缓存统计失败", e);
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    /**
     * 确保缓存目录存在
     */
    private void ensureCacheDirectories() throws Exception {
        Files.createDirectories(Paths.get(cacheBaseDir));
        Files.createDirectories(Paths.get(md5CacheDir));
        Files.createDirectories(Paths.get(spoonCacheDir));
        logger.debug("📁 缓存目录已创建/确认: {}", cacheBaseDir);
    }

    /**
     * 创建无变化结果
     */
    private ChangeDetectionResult createNoChangeResult() {
        ChangeDetectionResult result = new ChangeDetectionResult();
        result.setHasChanges(false);
        result.setSummary("无法检测变化（FileChangeDetector未注入）");
        result.buildSummary();
        return result;
    }

    /**
     * 创建错误结果
     */
    private ChangeDetectionResult createErrorResult(String errorMessage) {
        ChangeDetectionResult result = new ChangeDetectionResult();
        result.setHasChanges(true); // 保守策略：出错时认为有变化
        result.setSummary("检测失败: " + errorMessage);
        result.buildSummary();
        return result;
    }

    /**
     * 关闭线程池
     */
    public void shutdown() {
        logger.info("🛑 关闭项目缓存服务");
        refreshExecutor.shutdown();
    }
}
