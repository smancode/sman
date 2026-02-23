package com.smancode.sman.domain.scheduler

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 调度核心
 *
 * 协调各个组件，执行实际的调度逻辑：
 * - 检查用户活跃状态
 * - 管理退避策略
 * - 执行 onTick 回调
 */
class SchedulerCore(
    private val onTick: suspend () -> Unit,
    private val activityMonitor: ActivityMonitor,
    private val backoffPolicy: BackoffPolicy,
    private val config: SchedulerConfig
) {
    private val logger = LoggerFactory.getLogger(SchedulerCore::class.java)

    private val state = AtomicReference(SchedulerState.IDLE)
    private val generation = AtomicInteger(0)
    private val totalTicks = AtomicLong(0)
    private val totalErrors = AtomicLong(0)
    private val pausedByUser = AtomicReference(false)

    /**
     * 启动调度
     *
     * 从 IDLE、PAUSED 或 BACKOFF 状态转换到 RUNNING 状态
     */
    fun start() {
        if (tryTransitionToRunning()) {
            generation.incrementAndGet()
            pausedByUser.set(false)
            logger.info("启动 SchedulerCore: generation={}", generation.get())
        }
    }

    /**
     * 尝试从任意非运行状态转换到 RUNNING
     */
    private fun tryTransitionToRunning(): Boolean {
        val currentState = state.get()
        if (currentState == SchedulerState.RUNNING) {
            return false
        }
        return state.compareAndSet(currentState, SchedulerState.RUNNING)
    }

    /**
     * 停止调度
     */
    fun stop() {
        val previousState = state.getAndSet(SchedulerState.IDLE)
        if (previousState != SchedulerState.IDLE) {
            logger.info("停止 SchedulerCore: generation={}", generation.get())
        }
    }

    /**
     * 暂停调度
     */
    fun pause() {
        pausedByUser.set(true)
        state.set(SchedulerState.PAUSED)
        logger.debug("暂停 SchedulerCore")
    }

    /**
     * 恢复调度
     */
    fun resume() {
        pausedByUser.set(false)
        if (backoffPolicy.isInBackoff()) {
            state.set(SchedulerState.BACKOFF)
        } else {
            state.set(SchedulerState.RUNNING)
        }
        logger.debug("恢复 SchedulerCore: state={}", state.get())
    }

    /**
     * 执行一次调度
     *
     * @return 执行结果：Success、Skipped 或 Error
     */
    suspend fun tick(): TickResult {
        // 检查状态是否可执行
        if (!ensureExecutableState()) {
            return TickResult.Skipped
        }

        // 检查用户活跃
        if (config.pauseOnUserActive && activityMonitor.isUserActive()) {
            state.set(SchedulerState.PAUSED)
            logger.debug("用户活跃，暂停调度")
            return TickResult.Skipped
        }

        // 执行 tick
        return executeTick()
    }

    /**
     * 检查并确保处于可执行状态
     *
     * @return true 表示可以执行，false 表示应跳过
     */
    private fun ensureExecutableState(): Boolean {
        return when (state.get()) {
            SchedulerState.IDLE -> false
            SchedulerState.PAUSED -> false
            SchedulerState.BACKOFF -> {
                if (backoffPolicy.isInBackoff()) {
                    false
                } else {
                    state.set(SchedulerState.RUNNING)
                    true
                }
            }
            SchedulerState.RUNNING -> true
        }
    }

    /**
     * 执行实际的 tick 逻辑
     */
    private suspend fun executeTick(): TickResult {
        return try {
            totalTicks.incrementAndGet()
            onTick()
            backoffPolicy.recordSuccess()

            // 确保状态为 RUNNING（除非被用户暂停）
            if (state.get() != SchedulerState.PAUSED) {
                state.set(SchedulerState.RUNNING)
            }

            TickResult.Success
        } catch (e: Exception) {
            handleTickError(e)
        }
    }

    /**
     * 处理 tick 执行错误
     */
    private fun handleTickError(e: Exception): TickResult {
        totalErrors.incrementAndGet()
        backoffPolicy.recordError()
        logger.error("Tick 执行失败: error={}", e.message)

        if (backoffPolicy.getConsecutiveErrors() >= BACKOFF_THRESHOLD) {
            state.set(SchedulerState.BACKOFF)
            logger.warn("进入退避状态: consecutiveErrors={}", backoffPolicy.getConsecutiveErrors())
        }

        return TickResult.Error(e)
    }

    /**
     * 获取当前状态
     */
    fun getState(): SchedulerState = state.get()

    /**
     * 获取总执行次数
     */
    fun getTotalTicks(): Long = totalTicks.get()

    /**
     * 获取总错误次数
     */
    fun getTotalErrors(): Long = totalErrors.get()

    /**
     * 获取当前 Generation
     */
    fun getGeneration(): Int = generation.get()

    companion object {
        /** 触发退避的连续错误阈值 */
        private const val BACKOFF_THRESHOLD = 3
    }
}
