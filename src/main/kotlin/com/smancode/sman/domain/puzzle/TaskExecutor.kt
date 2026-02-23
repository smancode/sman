package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/**
 * 执行结果密封类
 */
sealed class ExecutionResult {
    /** 成功 */
    object Success : ExecutionResult()
    /** 跳过 */
    data class Skipped(val reason: String) : ExecutionResult()
    /** 失败 */
    data class Failed(val error: Throwable) : ExecutionResult()
}

/**
 * 任务执行器
 *
 * 负责执行分析任务：
 * - 调用 LLM 分析代码（当前简化实现）
 * - 生成/更新 Puzzle
 * - 支持幂等执行
 */
class TaskExecutor(
    private val taskQueueStore: TaskQueueStore,
    private val puzzleStore: PuzzleStore,
    private val checksumCalculator: ChecksumCalculator
) {
    private val logger = LoggerFactory.getLogger(TaskExecutor::class.java)

    companion object {
        /** 完成度阈值：低于此值认为 Puzzle 需要更新 */
        private const val COMPLETENESS_THRESHOLD = 0.9
    }

    /**
     * 执行下一个任务
     *
     * @param budget Token 预算
     * @return 执行结果，如果无任务则返回 null
     */
    suspend fun executeNext(budget: TokenBudget): ExecutionResult? {
        if (!budget.isAvailable()) {
            logger.debug("预算不足，跳过执行")
            return null
        }

        val tasks = taskQueueStore.findRunnable(DoomLoopGuard.MAX_RETRY)
        if (tasks.isEmpty()) {
            logger.debug("无可执行任务")
            return null
        }

        // 选择最高优先级任务
        val task = tasks.maxByOrNull { it.priority } ?: return null

        return execute(task)
    }

    /**
     * 执行指定任务
     *
     * @param task 要执行的任务
     * @return 执行结果
     */
    suspend fun execute(task: AnalysisTask): ExecutionResult {
        logger.info("开始执行任务: id={}, type={}", task.id, task.type)

        // 检查任务状态
        if (task.status == TaskStatus.RUNNING) {
            return ExecutionResult.Skipped("Task is already running")
        }

        if (task.status == TaskStatus.COMPLETED) {
            return ExecutionResult.Skipped("Task is already completed")
        }

        try {
            // 幂等性检查：checksum 未变更且 Puzzle 已存在
            val targetFile = File(task.target)
            if (!checksumCalculator.hasChanged(targetFile, task.checksum)) {
                val existingPuzzle = puzzleStore.load(task.puzzleId).getOrNull()
                if (existingPuzzle != null && existingPuzzle.completeness >= COMPLETENESS_THRESHOLD) {
                    logger.info("文件未变更且 Puzzle 已完成，跳过: id={}", task.id)
                    return ExecutionResult.Skipped("File unchanged and puzzle complete")
                }
            }

            // 标记任务为 RUNNING
            val runningTask = task.start()
            taskQueueStore.update(runningTask)

            // 执行分析
            val result = performAnalysis(task)

            // 更新 Puzzle
            puzzleStore.save(result)

            // 标记任务为 COMPLETED
            val completedTask = runningTask.complete()
            taskQueueStore.update(completedTask)

            logger.info("任务执行完成: id={}", task.id)
            return ExecutionResult.Success

        } catch (e: Exception) {
            logger.error("任务执行失败: id={}, error={}", task.id, e.message)

            // 标记任务为 FAILED
            val failedTask = task.copy(
                status = TaskStatus.FAILED,
                completedAt = Instant.now(),
                errorMessage = e.message
            )
            taskQueueStore.update(failedTask)

            return ExecutionResult.Failed(e)
        }
    }

    // 私有方法

    private fun performAnalysis(task: AnalysisTask): Puzzle {
        // 简化实现：生成基础 Puzzle 内容
        // TODO: 集成 LLM 进行深度分析

        val content = when (task.type) {
            TaskType.ANALYZE_API -> """
                # API Analysis: ${task.target}

                ## Endpoints
                Analysis pending - LLM integration required.

                ## Related Files
                ${task.relatedFiles.joinToString("\n") { "- $it" }}
            """.trimIndent()

            TaskType.ANALYZE_STRUCTURE -> """
                # Structure Analysis: ${task.target}

                ## Modules
                Analysis pending - LLM integration required.
            """.trimIndent()

            TaskType.ANALYZE_DATA -> """
                # Data Analysis: ${task.target}

                ## Entities
                Analysis pending - LLM integration required.
            """.trimIndent()

            TaskType.ANALYZE_FLOW -> """
                # Flow Analysis: ${task.target}

                ## Steps
                Analysis pending - LLM integration required.
            """.trimIndent()

            TaskType.ANALYZE_RULE -> """
                # Rule Analysis: ${task.target}

                ## Business Rules
                Analysis pending - LLM integration required.
            """.trimIndent()

            TaskType.UPDATE_PUZZLE -> {
                // 更新模式：加载现有 Puzzle 并更新
                val existing = puzzleStore.load(task.puzzleId).getOrNull()
                existing?.content ?: "# Updated Puzzle\n\nContent pending."
            }
        }

        return Puzzle(
            id = task.puzzleId,
            type = mapTaskTypeToPuzzleType(task.type),
            status = PuzzleStatus.IN_PROGRESS,
            content = content,
            completeness = 0.5,  // 简化实现，默认 50%
            confidence = 0.7,
            lastUpdated = Instant.now(),
            filePath = ".sman/puzzles/${task.puzzleId}.md"
        )
    }

    private fun mapTaskTypeToPuzzleType(taskType: TaskType): PuzzleType {
        return when (taskType) {
            TaskType.ANALYZE_STRUCTURE -> PuzzleType.STRUCTURE
            TaskType.ANALYZE_API -> PuzzleType.API
            TaskType.ANALYZE_DATA -> PuzzleType.DATA
            TaskType.ANALYZE_FLOW -> PuzzleType.FLOW
            TaskType.ANALYZE_RULE -> PuzzleType.RULE
            TaskType.UPDATE_PUZZLE -> PuzzleType.API  // 默认
        }
    }
}
