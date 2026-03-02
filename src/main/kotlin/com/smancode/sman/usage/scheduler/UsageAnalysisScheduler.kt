package com.smancode.sman.usage.scheduler

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.usage.analyzer.UsageAnalyzer
import com.smancode.sman.usage.converter.PuzzleToSkillConverter
import com.smancode.sman.usage.model.*
import com.smancode.sman.usage.store.AnalysisJobStateStore
import com.smancode.sman.usage.store.SkillUsageStore
import com.smancode.sman.usage.updater.PuzzleQualityUpdater
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant

/**
 * 使用分析调度器
 *
 * 定时执行使用记录分析，支持：
 * - 并发控制（检测 RUNNING 状态，跳过）
 * - 断点续传（从 lastProcessedIndex 继续）
 * - 异常恢复（FAILED 状态自动恢复）
 * - 状态外化（analysis_job.json）
 *
 * 工作流程：
 * 1. 检查当前任务状态
 * 2. 如果 RUNNING → 跳过
 * 3. 如果 FAILED → 断点续传
 * 4. 否则 → 创建新任务
 * 5. 分批处理记录，每批保存进度
 * 6. 分析完成 → 更新 Puzzle 质量 → 重新生成 Skill
 */
class UsageAnalysisScheduler(
    projectPath: Path
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 存储组件
    private val usageStore: SkillUsageStore
    private val jobStateStore: AnalysisJobStateStore

    // 分析组件
    private val analyzer: UsageAnalyzer
    private val qualityUpdater: PuzzleQualityUpdater
    private val skillConverter: PuzzleToSkillConverter
    private val puzzleStore: PuzzleStore

    // 配置
    private val batchSize: Int = DEFAULT_BATCH_SIZE

    init {
        val usageDir = projectPath.resolve(".sman/usage")
        val historyDir = usageDir.resolve("history")

        usageStore = SkillUsageStore(usageDir.resolve("records.json"))
        jobStateStore = AnalysisJobStateStore(
            usageDir.resolve("analysis_job.json"),
            historyDir
        )

        puzzleStore = PuzzleStore(projectPath.toString())
        analyzer = UsageAnalyzer(usageStore)
        skillConverter = PuzzleToSkillConverter(projectPath.toString())
        qualityUpdater = PuzzleQualityUpdater(puzzleStore, skillConverter)
    }

    /**
     * 执行分析任务
     *
     * @return 调度结果
     */
    fun execute(): ScheduleResult {
        log.info("开始执行使用分析任务")

        // 1. 检查当前状态
        val currentState = jobStateStore.loadCurrent()

        // 2. 如果正在运行，跳过
        if (currentState != null && currentState.isRunning()) {
            log.warn("任务已在运行中: ${currentState.jobId}, 跳过本次执行")
            return ScheduleResult.SkippedRunning
        }

        // 3. 如果需要断点续传
        if (currentState != null && currentState.canResume()) {
            log.info("检测到失败任务，尝试断点续传: ${currentState.jobId}")
            return resumeFromCheckpoint(currentState)
        }

        // 4. 创建新任务
        val allRecords = usageStore.loadAll()
        if (allRecords.isEmpty()) {
            log.info("没有使用记录需要分析")
            return ScheduleResult.NoData
        }

        val newState = jobStateStore.createNew(allRecords.size)
        log.info("创建新分析任务: ${newState.jobId}, 总记录数: ${allRecords.size}")

        // 5. 执行分析（分批处理）
        return executeWithCheckpoint(newState, allRecords)
    }

    /**
     * 从断点恢复执行
     */
    private fun resumeFromCheckpoint(state: AnalysisJobState): ScheduleResult {
        val allRecords = usageStore.loadAll()
        val startIndex = state.lastProcessedIndex + 1

        if (startIndex >= allRecords.size) {
            // 已经处理完了，清理状态
            jobStateStore.clear()
            return ScheduleResult.NoData
        }

        log.info("从索引 $startIndex 继续，剩余 ${allRecords.size - startIndex} 条记录")

        val resumedState = state.copy(
            status = JobStatus.RUNNING,
            errorMessage = null
        )
        jobStateStore.save(resumedState)

        val result = executeWithCheckpoint(resumedState, allRecords)

        return if (result is ScheduleResult.Success) {
            ScheduleResult.Resumed(result.result, startIndex)
        } else {
            result
        }
    }

    /**
     * 分批执行分析（带断点保存）
     */
    private fun executeWithCheckpoint(
        state: AnalysisJobState,
        allRecords: List<SkillUsageRecord>
    ): ScheduleResult {
        try {
            val totalRecords = allRecords.size
            var currentIndex = state.lastProcessedIndex + 1
            val processedRecords = mutableListOf<SkillUsageRecord>()

            // 分批处理
            while (currentIndex < totalRecords) {
                val endIndex = minOf(currentIndex + batchSize, totalRecords)
                val batch = allRecords.subList(currentIndex, endIndex)

                processedRecords.addAll(batch)

                // 更新进度
                val newProgress = endIndex - 1
                jobStateStore.updateProgress(state.jobId, newProgress)
                log.debug("已处理 $endIndex/$totalRecords 条记录")

                currentIndex = endIndex
            }

            // 执行分析
            log.info("开始分析 ${processedRecords.size} 条记录")
            val analysisResult = analyzer.analyze(processedRecords, state.jobId)

            // 应用质量更新
            if (analysisResult.puzzleUpdates.isNotEmpty()) {
                log.info("应用 ${analysisResult.puzzleUpdates.size} 个 Puzzle 质量更新")
                val updateCount = qualityUpdater.updateAll(analysisResult.puzzleUpdates)
                log.info("成功更新 $updateCount 个 Puzzle")
            }

            // 标记完成
            jobStateStore.markCompleted(state.jobId, analysisResult)
            log.info("分析任务完成: ${state.jobId}")

            return ScheduleResult.Success(analysisResult)

        } catch (e: Exception) {
            log.error("分析任务失败: ${state.jobId}", e)
            jobStateStore.markFailed(state.jobId, e.message ?: "Unknown error")
            return ScheduleResult.Failed(e.message ?: "Unknown error")
        }
    }

    /**
     * 强制取消当前任务
     */
    fun cancel(): Boolean {
        val current = jobStateStore.loadCurrent()
        if (current == null || !current.isRunning()) {
            return false
        }

        log.warn("取消任务: ${current.jobId}")
        jobStateStore.clear()
        return true
    }

    /**
     * 获取当前任务状态
     */
    fun getCurrentStatus(): AnalysisJobState? {
        return jobStateStore.loadCurrent()
    }

    /**
     * 获取历史任务列表
     */
    fun getHistory(limit: Int = 10): List<AnalysisJobState> {
        return jobStateStore.getRecentHistory(limit)
    }

    /**
     * 手动触发 Puzzle → Skill 转换
     *
     * 用于首次启动或手动刷新
     */
    fun convertPuzzlesToSkills(): Int {
        val puzzles = puzzleStore.loadAll().getOrNull() ?: return 0
        val completedPuzzles = puzzles.filter { it.status == com.smancode.sman.shared.model.PuzzleStatus.COMPLETED }

        val count = completedPuzzles.count { skillConverter.convertAndSave(it) != null }
        log.info("转换了 $count 个 Puzzle 到 Skill")
        return count
    }

    companion object {
        /**
         * 默认批处理大小
         */
        const val DEFAULT_BATCH_SIZE = 100
    }
}
