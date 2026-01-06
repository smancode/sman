package ai.smancode.sman.agent.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import ai.smancode.sman.agent.ast.SpoonAstService;
import ai.smancode.sman.agent.cache.FileChangeDetector;
import ai.smancode.sman.agent.callchain.CallChainService;
import ai.smancode.sman.agent.models.ChangeDetectionResult;
import ai.smancode.sman.agent.utils.PathUtils;
import ai.smancode.sman.agent.vector.VectorSearchService;

/**
 * 数据同步协调器
 *
 * 职责：
 * 1. 协调 Spoon、Vector、CallChain 三层索引的刷新
 * 2. 确保双缓存刷新（后台刷新，前台继续使用）
 * 3. 定时检测变化并触发增量刷新
 */
@Service
public class DataSyncCoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(DataSyncCoordinatorService.class);

    @Autowired
    private SpoonAstService spoonAstService;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private CallChainService callChainService;

    @Autowired
    private FileChangeDetector fileChangeDetector;

    /**
     * 定时检测并刷新所有索引
     */
    public void scheduledRefresh() {
        log.info("⏰ 开始定时刷新所有索引");
        // TODO: 实现多项目支持
        // 暂时跳过，等待用户确认需求
    }

    /**
     * 手动刷新指定项目的所有索引
     */
    public boolean manualRefresh(String projectKey, String projectPath) {
        log.info("🔄 手动刷新索引: projectKey={}", projectKey);

        try {
            String normalizedPath = PathUtils.normalizePath(projectPath);

            // 1. 检测文件变化
            ChangeDetectionResult detectionResult = fileChangeDetector.detectChanges(normalizedPath);

            if (!detectionResult.isHasChanges()) {
                log.info("⏭️ 无文件变化，跳过刷新");
                return false;
            }

            // 2. 刷新 Spoon AST（优先级最高）
            log.info("🔄 刷新 Spoon AST");
            // TODO: 实现 SpoonAstService.refreshModel()

            // 3. 刷新 CallChain 索引
            log.info("🔄 刷新 CallChain 索引");
            // TODO: 实现 CallChainService.refreshIndex()

            // 4. 刷新 Vector 索引（成本最高，最后执行）
            if (!detectionResult.getMd5ChangedFiles().isEmpty()) {
                log.info("🔄 刷新 Vector 索引");
                vectorSearchService.clearIndex(projectKey);
                // TODO: 实现 VectorSearchService.incrementalIndex()
            }

            // 5. 提交快照
            fileChangeDetector.commitSnapshot(normalizedPath);

            log.info("✅ 索引刷新完成");
            return true;

        } catch (Exception e) {
            log.error("❌ 刷新失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
