package com.smancode.sman.domain.puzzle

import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * 死循环防护器
 *
 * 防止自迭代系统进入死循环：
 * - 任务去重：同一 target + type 24 小时内不重复执行
 * - 重试限制：最多重试 MAX_RETRY 次
 * - 超时检测：执行超过 EXECUTION_TIMEOUT_SECONDS 视为失败
 */
class DoomLoopGuard(
    private val taskQueueStore: TaskQueueStore
) {
    private val logger = LoggerFactory.getLogger(DoomLoopGuard::class.java)

    companion object {
        /** 最大重试次数 */
        const val MAX_RETRY = 3

        /** 去重时间窗口（小时） */
        const val DEDUP_HOURS = 24

        /** 执行超时时间（秒） */
        const val EXECUTION_TIMEOUT_SECONDS = 60
    }

    /**
     * 检查任务是否可以执行
     *
     * @param task 要检查的任务
     * @return true 如果可以执行
     */
    fun canExecute(task: AnalysisTask): Boolean {
        // 检查重试次数
        if (task.retryCount >= MAX_RETRY) {
            logger.debug("任务重试次数超限: id={}, retryCount={}", task.id, task.retryCount)
            return false
        }

        // 检查是否在去重时间窗口内
        if (isInDedupWindow(task)) {
            logger.debug("任务在去重时间窗口内: id={}, target={}", task.id, task.target)
            return false
        }

        return true
    }

    /**
     * 检查任务是否超时
     *
     * @param task 要检查的任务
     * @return true 如果任务已超时
     */
    fun isTimedOut(task: AnalysisTask): Boolean {
        if (task.status != TaskStatus.RUNNING) return false
        if (task.startedAt == null) return false

        val now = Instant.now()
        val elapsedSeconds = now.epochSecond - task.startedAt.epochSecond

        return elapsedSeconds > EXECUTION_TIMEOUT_SECONDS
    }

    /**
     * 过滤掉不可执行的任务
     *
     * @param tasks 任务列表
     * @return 可执行的任务列表
     */
    fun filter(tasks: List<AnalysisTask>): List<AnalysisTask> {
        return tasks.filter { canExecute(it) }
    }

    /**
     * 记录任务执行（用于去重）
     *
     * 注意：实际的去重记录存储在 TaskQueueStore 的已完成任务中
     *
     * @param task 已执行的任务
     */
    fun recordExecution(task: AnalysisTask) {
        logger.debug("记录任务执行: id={}, target={}", task.id, task.target)
        // 去重信息通过 TaskQueueStore 的 COMPLETED 任务自动维护
    }

    // 私有方法

    private fun isInDedupWindow(task: AnalysisTask): Boolean {
        val cutoff = Instant.now().minusSeconds(DEDUP_HOURS * 3600L)

        // 检查最近完成的相同 target + type 的任务
        val recentCompleted = taskQueueStore.findByStatus(TaskStatus.COMPLETED)

        return recentCompleted.any { completed ->
            completed.target == task.target &&
            completed.type == task.type &&
            completed.completedAt != null &&
            completed.completedAt.isAfter(cutoff)
        }
    }
}
