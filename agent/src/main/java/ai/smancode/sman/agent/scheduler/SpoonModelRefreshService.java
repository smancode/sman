package ai.smancode.sman.agent.scheduler;

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

import ai.smancode.sman.agent.cache.FileChangeDetector;
import ai.smancode.sman.agent.models.ChangeDetectionResult;
import ai.smancode.sman.agent.utils.PathUtils;

/**
 * Spoon模型智能刷新服务（已禁用）
 *
 * 禁用原因：
 * 1. VectorCacheManager 已改用轻量级正则表达式解析器（LightWeightJavaParser）
 * 2. Spoon AST 每次全量解析项目，性能较差
 * 3. 不再需要 Spoon 模型缓存
 *
 * 如需启用：在 application.yml 中设置 spoon.refresh.enabled=true
 */
@Service
@EnableScheduling
@ConditionalOnProperty(
    prefix = "spoon.refresh",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false  // 默认禁用
)
public class SpoonModelRefreshService {

    private static final Logger logger = LoggerFactory.getLogger(SpoonModelRefreshService.class);

    @Autowired
    private FileChangeDetector fileChangeDetector;

    @Value("${spoon.refresh.interval-minutes:15}")
    private int refreshIntervalMinutes;

    @Value("${agent.projects.autoloop.project-path:}")
    private String projectRootPath;

    // 刷新统计
    private final AtomicInteger checkCount = new AtomicInteger(0);
    private final AtomicInteger skipCount = new AtomicInteger(0);
    private final AtomicLong lastCheckTime = new AtomicLong(0);
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);
    private volatile ChangeDetectionResult lastDetectionResult = null;

    /**
     * 定时检测文件变化
     */
    @Scheduled(
        initialDelayString = "#{${spoon.refresh.initial-delay-minutes:5} * 60 * 1000}",
        fixedDelayString = "#{${spoon.refresh.interval-minutes:5} * 60 * 1000}"
    )
    public void scheduledRefresh() {
        if (projectRootPath == null || projectRootPath.isEmpty()) {
            logger.debug("⚠️ 未配置项目根路径，跳过定时检测");
            return;
        }

        if (!isRefreshing.compareAndSet(false, true)) {
            logger.warn("⚠️ 上次检测尚未完成，跳过本次定时检测");
            return;
        }

        String normalizedPath = null;
        try {
            checkCount.incrementAndGet();
            lastCheckTime.set(System.currentTimeMillis());

            logger.info("⏰ 开始定时检测文件变化（间隔={}分钟, 累计检测{}次）",
                refreshIntervalMinutes, checkCount.get());

            normalizedPath = PathUtils.normalizePath(projectRootPath);

            // 智能检测文件变化
            ChangeDetectionResult detectionResult = fileChangeDetector.detectChanges(normalizedPath);
            lastDetectionResult = detectionResult;

            logger.info("📊 变化检测结果: {}", detectionResult.getSummary());

            if (!detectionResult.isHasChanges()) {
                skipCount.incrementAndGet();
                logger.info("⏭️ 无文件变化，跳过刷新（累计跳过{}次）", skipCount.get());
                return;
            }

            // 有变化，触发刷新（TODO: 调用 SpoonAstService 刷新）
            logger.info("🔄 检测到变化，开始刷新Spoon模型（TODO: 实现刷新逻辑）");
            // TODO: spoonAstService.refreshModel(normalizedPath);

            // 提交快照
            fileChangeDetector.commitSnapshot(normalizedPath);

        } catch (Exception e) {
            logger.error("❌ 定时检测/刷新异常", e);

            // 回滚快照
            if (normalizedPath != null) {
                try {
                    fileChangeDetector.rollbackSnapshot(normalizedPath);
                } catch (Exception ex) {
                    logger.warn("⚠️ 回滚MD5快照失败: {}", ex.getMessage());
                }
            }
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
        stats.put("totalCheckCount", checkCount.get());
        stats.put("skippedCount", skipCount.get());
        stats.put("lastCheckTime", lastCheckTime.get());
        stats.put("isRefreshing", isRefreshing.get());

        if (lastDetectionResult != null) {
            Map<String, Object> detectionInfo = new HashMap<>();
            detectionInfo.put("hasChanges", lastDetectionResult.isHasChanges());
            detectionInfo.put("summary", lastDetectionResult.getSummary());
            detectionInfo.put("addedFilesCount", lastDetectionResult.getAddedFiles().size());
            detectionInfo.put("deletedFilesCount", lastDetectionResult.getDeletedFiles().size());
            detectionInfo.put("md5ChangedFilesCount", lastDetectionResult.getMd5ChangedFiles().size());
            stats.put("lastDetection", detectionInfo);
        }

        return stats;
    }
}
