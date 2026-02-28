package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * 调度器状态
 */
data class SchedulerStatus(
    val isRunning: Boolean,
    val currentVersion: Int,
    val lastChecksum: String,
    val lastExecutionTime: Instant?
)

/**
 * 调度器统计信息
 */
data class SchedulerStatistics(
    val totalIterations: Int,
    val puzzlesCreated: Int,
    val averageQuality: Double
)

/**
 * 知识进化调度器
 *
 * 负责：
 * - 定时执行知识进化循环
 * - 管理版本快照
 * - 支持增量分析
 */
class PuzzleScheduler(
    private val projectPath: String,
    private val puzzleStore: PuzzleStore,
    private val versionStore: KnowledgeBaseVersionStore,
    private val evolutionLoop: KnowledgeEvolutionLoop,
    private val intervalMs: Long = 300_000  // 5 分钟
) {
    private val logger = LoggerFactory.getLogger(PuzzleScheduler::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    @Volatile
    private var isRunning = false

    // 统计信息
    private var totalIterations = 0
    private var puzzlesCreated = 0
    private var totalQuality = 0.0
    private var lastExecutionTime: Instant? = null

    /**
     * 启动调度器
     */
    fun start() {
        if (isRunning) {
            logger.debug("调度器已在运行")
            return
        }

        logger.info("启动知识进化调度器: projectPath={}, intervalMs={}", projectPath, intervalMs)
        isRunning = true

        currentJob = scope.launch {
            while (isRunning) {
                try {
                    executeEvolutionCycle()
                    delay(intervalMs)
                } catch (e: Exception) {
                    logger.error("知识进化循环出错", e)
                    delay(60000) // 出错后等待 1 分钟再重试
                }
            }
        }

        logger.info("知识进化调度器已启动")
    }

    /**
     * 停止调度器
     */
    fun stop() {
        logger.info("停止知识进化调度器")
        isRunning = false
        currentJob?.cancel()
        currentJob = null
    }

    /**
     * 检查是否运行中
     */
    fun isRunning(): Boolean = isRunning

    /**
     * 立即触发一次分析
     */
    fun triggerImmediate() {
        logger.info("触发即时知识进化分析")

        scope.launch {
            try {
                executeEvolutionCycle()
            } catch (e: Exception) {
                logger.error("立即触发分析失败", e)
            }
        }
    }

    /**
     * 强制全量分析
     */
    fun forceFullAnalysis() {
        logger.info("强制全量分析")

        scope.launch {
            try {
                // 强制全量分析：忽略 checksum 检查
                executeEvolutionCycle(forceFull = true)
            } catch (e: Exception) {
                logger.error("强制全量分析失败", e)
            }
        }
    }

    /**
     * 获取调度器状态
     */
    fun getStatus(): SchedulerStatus {
        return SchedulerStatus(
            isRunning = isRunning,
            currentVersion = versionStore.getCurrentVersion(),
            lastChecksum = versionStore.getLatestChecksum(),
            lastExecutionTime = lastExecutionTime
        )
    }

    /**
     * 获取统计信息
     */
    fun getStatistics(): SchedulerStatistics {
        val avgQuality = if (totalIterations > 0) totalQuality / totalIterations else 0.0
        return SchedulerStatistics(
            totalIterations = totalIterations,
            puzzlesCreated = puzzlesCreated,
            averageQuality = avgQuality
        )
    }

    // ========== 私有方法 ==========

    private suspend fun executeEvolutionCycle(forceFull: Boolean = false) {
        logger.debug("开始知识进化循环: forceFull={}", forceFull)

        lastExecutionTime = Instant.now()

        // 1. 加载当前拼图
        val puzzles = puzzleStore.loadAll().getOrElse { emptyList() }
        val lastChecksum = versionStore.getLatestChecksum()

        // 2. 检测是否有变更（非强制模式）
        if (!forceFull && !versionStore.hasChangesSince(lastChecksum, puzzles)) {
            logger.debug("无变更，跳过本轮分析")
            return
        }

        // 3. 执行知识进化
        val trigger = if (forceFull) {
            Trigger.Manual("强制全量分析")
        } else {
            Trigger.Scheduled("定时调度")
        }

        val result = evolutionLoop.evolve(trigger)

        // 4. 更新统计信息
        totalIterations++
        if (result.status == IterationStatus.COMPLETED) {
            result.evaluation?.let { evaluation ->
                totalQuality += evaluation.qualityScore
            }
            puzzlesCreated += result.puzzlesCreated
        }

        // 5. 重新加载拼图并创建版本
        val updatedPuzzles = puzzleStore.loadAll().getOrElse { emptyList() }
        if (updatedPuzzles.isNotEmpty()) {
            versionStore.createVersion(
                puzzles = updatedPuzzles,
                trigger = if (forceFull) VersionTrigger.MANUAL else VersionTrigger.AUTO,
                description = "知识进化: ${result.iterationId}"
            )
        }

        logger.info("知识进化循环完成: status={}, puzzlesCreated={}", result.status, result.puzzlesCreated)
    }
}
