package com.smancode.sman.domain.puzzle

import org.slf4j.LoggerFactory

/**
 * 中断恢复服务
 *
 * 负责处理任务执行中断后的恢复：
 * - 启动时检查 RUNNING 状态的超时任务
 * - 判断是否需要重试或标记失败
 * - 支持 checkpoint 保存和恢复
 */
class RecoveryService(
    private val taskQueueStore: TaskQueueStore
) {
    private val logger = LoggerFactory.getLogger(RecoveryService::class.java)

    companion object {
        /** 默认超时时间（秒） */
        const val DEFAULT_TIMEOUT_SECONDS = 60L
    }

    /**
     * 检查是否需要恢复
     *
     * @return true 如果有需要恢复的任务
     */
    fun needsRecovery(): Boolean {
        val staleTasks = taskQueueStore.findStaleRunning(DEFAULT_TIMEOUT_SECONDS)
        return staleTasks.isNotEmpty()
    }

    /**
     * 执行恢复
     *
     * @return 恢复的任务数量
     */
    fun recover(): Int {
        val staleTasks = taskQueueStore.findStaleRunning(DEFAULT_TIMEOUT_SECONDS)

        if (staleTasks.isEmpty()) {
            logger.debug("无需恢复的任务")
            return 0
        }

        logger.info("发现 {} 个需要恢复的任务", staleTasks.size)

        var recovered = 0
        staleTasks.forEach { task ->
            try {
                if (task.retryCount >= DoomLoopGuard.MAX_RETRY) {
                    // 重试次数已达上限，标记失败
                    taskQueueStore.markFailed(task.id, "Execution timeout after ${task.retryCount} retries")
                    logger.warn("任务已达最大重试次数，标记失败: id={}", task.id)
                } else {
                    // 重置为 PENDING 状态，等待重试
                    taskQueueStore.resetTask(task.id)
                    logger.info("任务已重置，等待重试: id={}, retryCount={}", task.id, task.retryCount + 1)
                }
                recovered++
            } catch (e: Exception) {
                logger.error("恢复任务失败: id={}, error={}", task.id, e.message)
            }
        }

        return recovered
    }

    /**
     * 保存 checkpoint
     *
     * @param task 当前任务
     * @param progress 进度描述
     */
    fun saveCheckpoint(task: AnalysisTask, progress: String) {
        logger.debug("保存 checkpoint: id={}, progress={}", task.id, progress)
        // checkpoint 通过更新任务状态实现
        taskQueueStore.update(task)
    }

    /**
     * 获取 checkpoint
     *
     * @param taskId 任务 ID
     * @return 任务状态，或 null 如果不存在
     */
    fun getCheckpoint(taskId: String): AnalysisTask? {
        return taskQueueStore.findById(taskId)
    }

    /**
     * 处理失败
     *
     * @param taskId 任务 ID
     * @param error 错误信息
     */
    fun handleFailure(taskId: String, error: Throwable) {
        val task = taskQueueStore.findById(taskId)
            ?: throw IllegalArgumentException("任务不存在: $taskId")

        logger.error("任务执行失败: id={}, error={}", taskId, error.message)

        if (task.retryCount >= DoomLoopGuard.MAX_RETRY - 1) {
            // 即将达到重试上限，直接标记失败
            taskQueueStore.markFailed(taskId, error.message ?: "Unknown error")
        } else {
            // 还有重试机会，重置任务
            taskQueueStore.resetTask(taskId)
            logger.info("任务已重置，将重试: id={}", taskId)
        }
    }
}
