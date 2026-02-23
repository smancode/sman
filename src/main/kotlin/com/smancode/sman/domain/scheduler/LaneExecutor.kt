package com.smancode.sman.domain.scheduler

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lane 执行器
 *
 * 管理不同 Lane 的任务执行，通过线程池限制并发数
 */
class LaneExecutor {

    private val logger = LoggerFactory.getLogger(LaneExecutor::class.java)

    private val executors = ConcurrentHashMap<TaskLane, ExecutorService>()
    private val shutdownFlag = AtomicBoolean(false)

    init {
        TaskLane.entries.forEach { lane ->
            val config = LaneConfig.forLane(lane)
            executors[lane] = Executors.newFixedThreadPool(config.maxConcurrent) { r ->
                Thread(r, "SmanCode-${lane.name}").apply { isDaemon = true }
            }
        }
    }

    /**
     * 提交任务到指定 Lane
     *
     * @param lane 目标通道
     * @param task 要执行的任务
     * @throws IllegalArgumentException 当 lane 不存在时
     * @throws IllegalStateException 当执行器已关闭时
     */
    fun submit(lane: TaskLane, task: () -> Unit) {
        check(!shutdownFlag.get()) { "LaneExecutor 已关闭" }

        val executor = checkNotNull(executors[lane]) { "Unknown lane: $lane" }
        executor.submit {
            try {
                task()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.debug("任务被中断: lane={}", lane)
            }
        }
    }

    /**
     * 关闭执行器
     *
     * 等待最多 5 秒让任务完成，之后强制关闭
     */
    fun shutdown() {
        if (shutdownFlag.compareAndSet(false, true)) {
            logger.info("关闭 LaneExecutor")
            executors.values.forEach { executor ->
                executor.shutdown()
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.warn("Lane 执行器未能在 5 秒内完成，强制关闭")
                        executor.shutdownNow()
                    }
                } catch (e: InterruptedException) {
                    logger.warn("等待 Lane 执行器关闭时被中断")
                    executor.shutdownNow()
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    /**
     * 检查是否已关闭
     */
    fun isShutdown(): Boolean = shutdownFlag.get()
}
