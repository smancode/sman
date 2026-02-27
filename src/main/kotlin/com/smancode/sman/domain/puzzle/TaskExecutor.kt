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
 * - 调用 LlmAnalyzer 分析代码
 * - 生成/更新 Puzzle
 * - 支持幂等执行
 */
class TaskExecutor(
    private val taskQueueStore: TaskQueueStore,
    private val puzzleStore: PuzzleStore,
    private val checksumCalculator: ChecksumCalculator,
    private val llmAnalyzer: LlmAnalyzer,
    private val fileReader: FileReader
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

            // 准备上下文
            val context = fileReader.readWithContext(task.target)

            // 调用 LLM 分析
            val analysisResult = llmAnalyzer.analyze(task.target, context)

            // 构建 Puzzle
            val puzzle = buildPuzzle(task.puzzleId, analysisResult)

            // 保存 Puzzle
            puzzleStore.save(puzzle)

            // 标记任务为 COMPLETED
            val completedTask = runningTask.complete()
            taskQueueStore.update(completedTask)

            logger.info("任务执行完成: id={}", task.id)
            return ExecutionResult.Success

        } catch (e: AnalysisException) {
            logger.error("LLM 分析失败: id={}, error={}", task.id, e.message)

            // 标记任务为 FAILED
            val failedTask = task.copy(
                status = TaskStatus.FAILED,
                completedAt = Instant.now(),
                errorMessage = e.message
            )
            taskQueueStore.update(failedTask)

            return ExecutionResult.Failed(e)

        } catch (e: Exception) {
            logger.error("任务执行失败: id={}, error={}", task.id, e.message, e)

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

    /**
     * 从分析结果构建 Puzzle
     */
    private fun buildPuzzle(puzzleId: String, result: AnalysisResult): Puzzle {
        return Puzzle(
            id = puzzleId,
            type = inferPuzzleType(result.tags),
            status = PuzzleStatus.COMPLETED,
            content = "# ${result.title}\n\n${result.content}",
            completeness = result.confidence,
            confidence = result.confidence,
            lastUpdated = Instant.now(),
            filePath = ".sman/puzzles/$puzzleId.md"
        )
    }

    /**
     * 从标签推断 Puzzle 类型
     *
     * 不再硬编码类型，而是从 LLM 生成的标签中推断
     */
    private fun inferPuzzleType(tags: List<String>): PuzzleType {
        val lowerTags = tags.map { it.lowercase() }

        return when {
            lowerTags.any { it in listOf("api", "rest", "controller", "endpoint") } -> PuzzleType.API
            lowerTags.any { it in listOf("entity", "data", "model", "table", "schema") } -> PuzzleType.DATA
            lowerTags.any { it in listOf("flow", "process", "workflow", "pipeline") } -> PuzzleType.FLOW
            lowerTags.any { it in listOf("rule", "validation", "constraint", "policy") } -> PuzzleType.RULE
            lowerTags.any { it in listOf("structure", "architecture", "module", "package") } -> PuzzleType.STRUCTURE
            lowerTags.any { it in listOf("tech", "technology", "framework", "library", "dependency") } -> PuzzleType.TECH_STACK
            else -> PuzzleType.STRUCTURE // 默认类型
        }
    }
}
