package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * 触发类型枚举
 */
enum class TriggerType {
    /** 文件变更 */
    FILE_CHANGE,
    /** 用户查询 */
    USER_QUERY,
    /** 定时触发 */
    SCHEDULED,
    /** 手动触发 */
    MANUAL
}

/**
 * 协调器状态
 */
data class CoordinatorStatus(
    val isRunning: Boolean,
    val pendingTasks: Int,
    val runningTask: AnalysisTask?,
    val lastUpdated: Instant
)

/**
 * Puzzle 协调器
 *
 * 负责协调自迭代系统的各个组件：
 * - 启动时恢复中断任务
 * - 检测知识空白
 * - 调度任务优先级
 * - 执行分析任务
 */
class PuzzleCoordinator(
    private val puzzleStore: PuzzleStore,
    private val taskQueueStore: TaskQueueStore,
    private val gapDetector: GapDetector,
    private val taskScheduler: TaskScheduler,
    private val taskExecutor: TaskExecutor,
    private val recoveryService: RecoveryService,
    private val doomLoopGuard: DoomLoopGuard
) {
    private val logger = LoggerFactory.getLogger(PuzzleCoordinator::class.java)

    @Volatile
    private var isStarted = false

    /**
     * 启动协调器
     */
    suspend fun start() {
        logger.info("启动 PuzzleCoordinator")

        // 1. 恢复中断的任务
        if (recoveryService.needsRecovery()) {
            val recovered = recoveryService.recover()
            logger.info("恢复了 {} 个中断任务", recovered)
        }

        // 2. 初始空白检测
        detectAndEnqueueGaps()

        isStarted = true
        logger.info("PuzzleCoordinator 启动完成")
    }

    /**
     * 触发一次分析
     */
    suspend fun trigger(
        type: TriggerType,
        changedFiles: List<String> = emptyList(),
        query: String = ""
    ) {
        logger.debug("触发分析: type={}", type)

        when (type) {
            TriggerType.FILE_CHANGE -> {
                if (changedFiles.isNotEmpty()) {
                    detectGapsByFileChange(changedFiles)
                }
            }
            TriggerType.USER_QUERY -> {
                if (query.isNotBlank()) {
                    detectGapsByUserQuery(query)
                }
            }
            TriggerType.SCHEDULED, TriggerType.MANUAL -> {
                // 定时/手动触发：执行下一个任务
                executeNextTask()
            }
        }
    }

    /**
     * 获取当前状态
     */
    fun getStatus(): CoordinatorStatus {
        val runningTasks = taskQueueStore.findByStatus(TaskStatus.RUNNING)
        val pendingTasks = taskQueueStore.findByStatus(TaskStatus.PENDING)

        return CoordinatorStatus(
            isRunning = runningTasks.isNotEmpty(),
            pendingTasks = pendingTasks.size,
            runningTask = runningTasks.firstOrNull(),
            lastUpdated = Instant.now()
        )
    }

    /**
     * 停止协调器
     */
    fun stop() {
        logger.info("停止 PuzzleCoordinator")
        isStarted = false
    }

    // 私有方法

    private suspend fun detectAndEnqueueGaps() {
        val puzzles = puzzleStore.loadAll().getOrNull() ?: emptyList()
        if (puzzles.isEmpty()) {
            logger.debug("无 Puzzle，跳过空白检测")
            return
        }

        val gaps = gapDetector.detect(puzzles)
        if (gaps.isEmpty()) {
            logger.debug("未检测到空白")
            return
        }

        // 过滤掉可能导致死循环的任务
        val tasks = gaps.map { gapToTask(it) }
        val filteredTasks = doomLoopGuard.filter(tasks)

        // 按优先级排序
        val prioritizedTasks = taskScheduler.prioritize(
            filteredTasks.map { gapFromTask(it) }
        ).map { gapToTask(it) }

        // 入队
        prioritizedTasks.forEach { task ->
            taskQueueStore.enqueue(task)
        }

        logger.info("检测到 {} 个空白，入队 {} 个任务", gaps.size, prioritizedTasks.size)
    }

    private suspend fun detectGapsByFileChange(changedFiles: List<String>) {
        val puzzles = puzzleStore.loadAll().getOrNull() ?: emptyList()

        val gaps = gapDetector.detectByFileChange(puzzles, changedFiles)
        if (gaps.isEmpty()) return

        val tasks = gaps.map { gapToTask(it) }
            .filter { doomLoopGuard.canExecute(it) }

        tasks.forEach { taskQueueStore.enqueue(it) }

        logger.info("文件变更检测到 {} 个空白", gaps.size)
    }

    private suspend fun detectGapsByUserQuery(query: String) {
        val puzzles = puzzleStore.loadAll().getOrNull() ?: emptyList()

        val gaps = gapDetector.detectByUserQuery(puzzles, query)
        if (gaps.isEmpty()) return

        val tasks = gaps.map { gapToTask(it) }
            .filter { doomLoopGuard.canExecute(it) }

        tasks.forEach { taskQueueStore.enqueue(it) }

        logger.info("用户查询检测到 {} 个空白", gaps.size)
    }

    private suspend fun executeNextTask() {
        val budget = TokenBudget(
            maxTokensPerTask = 4000,
            maxTasksPerSession = 5
        )

        taskExecutor.executeNext(budget)
    }

    private fun gapToTask(gap: Gap): AnalysisTask {
        return AnalysisTask.create(
            id = UUID.randomUUID().toString(),
            type = mapGapTypeToTaskType(gap.type),
            target = gap.relatedFiles.firstOrNull() ?: "unknown",
            puzzleId = "${gap.puzzleType.name.lowercase()}-${gap.relatedFiles.firstOrNull()?.hashCode() ?: 0}",
            priority = gap.priority,
            relatedFiles = gap.relatedFiles
        )
    }

    private fun gapFromTask(task: AnalysisTask): Gap {
        return Gap(
            type = GapType.INCOMPLETE,
            puzzleType = mapTaskTypeToPuzzleType(task.type),
            description = task.target,
            priority = task.priority,
            relatedFiles = task.relatedFiles,
            detectedAt = Instant.now()
        )
    }

    private fun mapGapTypeToTaskType(gapType: GapType): TaskType {
        return when (gapType) {
            GapType.LOW_COMPLETENESS, GapType.INCOMPLETE -> TaskType.UPDATE_PUZZLE
            GapType.FILE_CHANGE_TRIGGERED -> TaskType.UPDATE_PUZZLE
            GapType.USER_QUERY_TRIGGERED -> TaskType.UPDATE_PUZZLE
            else -> TaskType.ANALYZE_API
        }
    }

    private fun mapTaskTypeToPuzzleType(taskType: TaskType): com.smancode.sman.shared.model.PuzzleType {
        return when (taskType) {
            TaskType.ANALYZE_STRUCTURE -> com.smancode.sman.shared.model.PuzzleType.STRUCTURE
            TaskType.ANALYZE_API -> com.smancode.sman.shared.model.PuzzleType.API
            TaskType.ANALYZE_DATA -> com.smancode.sman.shared.model.PuzzleType.DATA
            TaskType.ANALYZE_FLOW -> com.smancode.sman.shared.model.PuzzleType.FLOW
            TaskType.ANALYZE_RULE -> com.smancode.sman.shared.model.PuzzleType.RULE
            TaskType.UPDATE_PUZZLE -> com.smancode.sman.shared.model.PuzzleType.API
        }
    }
}
