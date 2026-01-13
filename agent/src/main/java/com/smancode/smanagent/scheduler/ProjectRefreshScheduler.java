package com.smancode.smanagent.scheduler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.smancode.smanagent.cache.ProjectCacheService;
import com.smancode.smanagent.model.cache.ChangeDetectionResult;

/**
 * 项目定时刷新调度器
 *
 * 功能：
 * - 定时检测项目文件变化
 * - 增量刷新缓存
 * - 统计刷新信息
 *
 * 配置：
 * - project.refresh.enabled: 是否启用定时刷新（默认true）
 * - project.refresh.interval-minutes: 刷新间隔（默认5分钟）
 * - project.refresh.initial-delay-minutes: 首次延迟（默认1分钟）
 *
 * @since 1.0.0
 */
@Service
@EnableScheduling
@ConditionalOnProperty(
    prefix = "project.refresh",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true  // 默认启用
)
public class ProjectRefreshScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ProjectRefreshScheduler.class);

    @Autowired
    private ProjectCacheService projectCacheService;

    /**
     * 刷新间隔（分钟）
     */
    @Value("${project.refresh.interval-minutes:5}")
    private int refreshIntervalMinutes;

    /**
     * 首次延迟（分钟）
     */
    @Value("${project.refresh.initial-delay-minutes:1}")
    private int initialDelayMinutes;

    /**
     * 项目路径
     */
    @Value("${project.path:}")
    private String projectPath;

    /**
     * 项目Key
     */
    @Value("${project.key:default}")
    private String projectKey;

    // 刷新统计
    private final AtomicInteger checkCount = new AtomicInteger(0);
    private final AtomicInteger skipCount = new AtomicInteger(0);
    private final AtomicInteger refreshCount = new AtomicInteger(0);
    private final AtomicLong lastCheckTime = new AtomicLong(0);
    private final AtomicLong lastRefreshTime = new AtomicLong(0);
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);
    private volatile ChangeDetectionResult lastDetectionResult = null;

    /**
     * 定时检测并刷新
     */
    @Scheduled(
        initialDelayString = "#{${project.refresh.initial-delay-minutes:1} * 60 * 1000}",
        fixedDelayString = "#{${project.refresh.interval-minutes:5} * 60 * 1000}"
    )
    public void scheduledRefresh() {
        if (projectPath == null || projectPath.isEmpty()) {
            logger.debug("⚠️ 未配置项目路径，跳过定时检测");
            return;
        }

        if (!isRefreshing.compareAndSet(false, true)) {
            logger.warn("⚠️ 上次检测尚未完成，跳过本次定时检测");
            return;
        }

        try {
            checkCount.incrementAndGet();
            lastCheckTime.set(System.currentTimeMillis());

            logger.info("⏰ 开始定时检测文件变化（间隔={}分钟, 累计检测{}次）",
                refreshIntervalMinutes, checkCount.get());

            // 检测并刷新
            ChangeDetectionResult result = projectCacheService.detectAndRefresh(projectPath);
            lastDetectionResult = result;

            if (result.isHasChanges()) {
                refreshCount.incrementAndGet();
                lastRefreshTime.set(System.currentTimeMillis());
                logger.info("🔄 检测到变化并已刷新（累计刷新{}次）: {}", refreshCount.get(), result.getSummary());
            } else {
                skipCount.incrementAndGet();
                logger.info("⏭️ 无文件变化，跳过刷新（累计跳过{}次）", skipCount.get());
            }

        } catch (Exception e) {
            logger.error("❌ 定时检测/刷新异常", e);
        } finally {
            isRefreshing.set(false);
        }
    }

    /**
     * 手动触发刷新
     */
    public ChangeDetectionResult manualRefresh() {
        logger.info("🔄 手动触发刷新: projectPath={}", projectPath);

        if (!isRefreshing.compareAndSet(false, true)) {
            logger.warn("⚠️ 正在刷新中，请稍后再试");
            ChangeDetectionResult result = new ChangeDetectionResult();
            result.setHasChanges(false);
            result.setSummary("正在刷新中，请稍后再试");
            result.buildSummary();
            return result;
        }

        try {
            checkCount.incrementAndGet();
            lastCheckTime.set(System.currentTimeMillis());

            ChangeDetectionResult result = projectCacheService.detectAndRefresh(projectPath);
            lastDetectionResult = result;

            if (result.isHasChanges()) {
                refreshCount.incrementAndGet();
                lastRefreshTime.set(System.currentTimeMillis());
            }

            return result;

        } catch (Exception e) {
            logger.error("❌ 手动刷新失败", e);
            ChangeDetectionResult errorResult = new ChangeDetectionResult();
            errorResult.setHasChanges(false);
            errorResult.setSummary("刷新失败: " + e.getMessage());
            errorResult.buildSummary();
            return errorResult;
        } finally {
            isRefreshing.set(false);
        }
    }

    /**
     * 强制刷新（忽略修改时间缓存）
     */
    public ChangeDetectionResult forceRefresh() {
        logger.info("🔄 强制刷新: projectPath={}", projectPath);

        if (!isRefreshing.compareAndSet(false, true)) {
            logger.warn("⚠️ 正在刷新中，请稍后再试");
            ChangeDetectionResult result = new ChangeDetectionResult();
            result.setHasChanges(false);
            result.setSummary("正在刷新中，请稍后再试");
            result.buildSummary();
            return result;
        }

        try {
            checkCount.incrementAndGet();
            lastCheckTime.set(System.currentTimeMillis());

            ChangeDetectionResult result = projectCacheService.forceRefresh(projectPath);
            lastDetectionResult = result;

            refreshCount.incrementAndGet();
            lastRefreshTime.set(System.currentTimeMillis());

            return result;

        } catch (Exception e) {
            logger.error("❌ 强制刷新失败", e);
            ChangeDetectionResult errorResult = new ChangeDetectionResult();
            errorResult.setHasChanges(false);
            errorResult.setSummary("强制刷新失败: " + e.getMessage());
            errorResult.buildSummary();
            return errorResult;
        } finally {
            isRefreshing.set(false);
        }
    }

    /**
     * 获取刷新统计信息
     */
    public Map<String, Object> getRefreshStatistics() {
        Map<String, Object> stats = new HashMap<>();

        stats.put("refreshIntervalMinutes", refreshIntervalMinutes);
        stats.put("initialDelayMinutes", initialDelayMinutes);
        stats.put("projectPath", projectPath);
        stats.put("projectKey", projectKey);
        stats.put("totalCheckCount", checkCount.get());
        stats.put("skippedCount", skipCount.get());
        stats.put("refreshCount", refreshCount.get());
        stats.put("lastCheckTime", lastCheckTime.get());
        stats.put("lastRefreshTime", lastRefreshTime.get());
        stats.put("isRefreshing", isRefreshing.get());
        stats.put("enabled", true);

        if (lastDetectionResult != null) {
            Map<String, Object> detectionInfo = new HashMap<>();
            detectionInfo.put("hasChanges", lastDetectionResult.isHasChanges());
            detectionInfo.put("summary", lastDetectionResult.getSummary());
            detectionInfo.put("addedFilesCount", lastDetectionResult.getAddedFiles().size());
            detectionInfo.put("deletedFilesCount", lastDetectionResult.getDeletedFiles().size());
            detectionInfo.put("md5ChangedFilesCount", lastDetectionResult.getMd5ChangedFiles().size());
            detectionInfo.put("detectionDuration", lastDetectionResult.getDetectionDuration());
            stats.put("lastDetection", detectionInfo);
        }

        return stats;
    }

    /**
     * 获取是否正在刷新
     */
    public boolean isRefreshing() {
        return isRefreshing.get();
    }
}
