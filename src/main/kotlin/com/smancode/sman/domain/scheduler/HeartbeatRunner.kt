package com.smancode.sman.domain.scheduler

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 心跳运行器
 *
 * 负责定期触发后台任务：
 * - 定期唤醒（默认 5 分钟）
 * - 请求合并窗口（250ms）
 * - Timer Clamp（最大延迟 60 秒）
 * - Generation Counter（安全重启）
 */
class HeartbeatRunner(
    private var config: SchedulerConfig,
    private val onTick: suspend () -> Unit
) {
    private val logger = LoggerFactory.getLogger(HeartbeatRunner::class.java)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SmanCode-Heartbeat").apply { isDaemon = true }
    }

    private val generation = AtomicInteger(0)
    private val isRunningFlag = AtomicBoolean(false)
    private val nextWakeTime = AtomicReference<Instant?>(null)
    private val pendingWakeReason = AtomicReference<WakeReason?>(null)

    private var scheduledFuture: ScheduledFuture<*>? = null
    @Volatile
    private var currentGeneration: Int = 0
    private val isShutdown = AtomicBoolean(false)

    /**
     * 是否正在运行
     */
    fun isRunning(): Boolean = isRunningFlag.get()

    /**
     * 获取当前 Generation
     */
    fun getCurrentGeneration(): Int = currentGeneration

    /**
     * 获取下次唤醒时间
     */
    fun getNextWakeTime(): Instant? = nextWakeTime.get()

    /**
     * 获取当前配置
     */
    fun getConfig(): SchedulerConfig = config

    /**
     * 启动心跳
     */
    fun start() {
        if (isRunningFlag.getAndSet(true)) {
            logger.debug("HeartbeatRunner 已经在运行")
            return
        }

        currentGeneration = generation.incrementAndGet()
        logger.info("启动 HeartbeatRunner: generation={}, intervalMs={}", currentGeneration, config.intervalMs)

        scheduleNext(config.intervalMs)
    }

    /**
     * 停止心跳
     */
    fun stop() {
        if (!isRunningFlag.getAndSet(false)) {
            return
        }

        logger.info("停止 HeartbeatRunner: generation={}", currentGeneration)

        scheduledFuture?.cancel(false)
        scheduledFuture = null
        nextWakeTime.set(null)
        pendingWakeReason.set(null)
    }

    /**
     * 完全关闭，释放资源
     */
    fun shutdown() {
        stop()
        if (isShutdown.compareAndSet(false, true)) {
            logger.info("关闭 HeartbeatRunner executor")
            scheduler.shutdown()
        }
    }

    /**
     * 请求立即唤醒
     *
     * @param reason 唤醒原因
     */
    fun requestWakeNow(reason: WakeReason = WakeReason.MANUAL) {
        if (!isRunningFlag.get()) {
            return
        }

        pendingWakeReason.set(reason)
        scheduleNext(SchedulerConfig.COALESCE_WINDOW_MS)

        logger.debug("请求立即唤醒: reason={}, coalesceMs={}", reason, SchedulerConfig.COALESCE_WINDOW_MS)
    }

    /**
     * 更新配置
     */
    fun updateConfig(newConfig: SchedulerConfig) {
        val wasEnabled = config.enabled
        config = newConfig

        logger.info("更新配置: enabled={}, intervalMs={}", newConfig.enabled, newConfig.intervalMs)

        if (isRunningFlag.get()) {
            if (!newConfig.enabled) {
                stop()
            } else if (!wasEnabled) {
                start()
            } else {
                // 重新调度以应用新间隔
                scheduledFuture?.cancel(false)
                scheduleNext(config.intervalMs)
            }
        }
    }

    // 私有方法

    private fun scheduleNext(delayMs: Long) {
        val actualDelay = delayMs.coerceAtMost(SchedulerConfig.MAX_TIMER_DELAY_MS)
        nextWakeTime.set(Instant.now().plusMillis(actualDelay))

        scheduledFuture?.cancel(false)
        scheduledFuture = scheduler.schedule({
            executeTick()
        }, actualDelay, TimeUnit.MILLISECONDS)
    }

    private fun executeTick() {
        if (!isRunningFlag.get()) {
            return
        }

        val tickGeneration = currentGeneration
        val reason = pendingWakeReason.getAndSet(null) ?: WakeReason.INTERVAL

        logger.debug("执行心跳: generation={}, reason={}", tickGeneration, reason)

        try {
            // 使用 kotlinx.coroutines 运行 suspend 函数
            kotlinx.coroutines.runBlocking {
                onTick()
            }
        } catch (e: Exception) {
            logger.error("心跳执行失败: generation={}, error={}", tickGeneration, e.message)
        }

        // 检查是否被停止或重启
        if (isRunningFlag.get() && currentGeneration == tickGeneration) {
            scheduleNext(config.intervalMs)
        }
    }
}
