package com.smancode.sman.domain.scheduler

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 用户活动监视器
 *
 * 检测用户活跃状态，活跃时暂停后台任务。
 * 使用最后活动时间 + 阈值判断用户是否活跃。
 *
 * 线程安全：所有状态均使用原子类保证线程安全。
 */
class ActivityMonitor(thresholdMs: Long = DEFAULT_THRESHOLD_MS) {

    private val lastActivityTime = AtomicReference<Instant?>(null)
    private val threshold = AtomicLong(thresholdMs)

    /**
     * 检查用户是否活跃
     *
     * @return true 如果用户在阈值时间内有活动
     */
    fun isUserActive(): Boolean {
        val lastTime = lastActivityTime.get() ?: return false
        val elapsed = Duration.between(lastTime, Instant.now()).toMillis()
        return elapsed < threshold.get()
    }

    /**
     * 获取用户空闲时间
     *
     * @return 空闲时间；若从未有活动记录，返回非常大的值表示"无限"
     */
    fun getIdleTime(): Duration {
        val lastTime = lastActivityTime.get() ?: return Duration.ofMillis(Long.MAX_VALUE)
        return Duration.between(lastTime, Instant.now())
    }

    /**
     * 记录用户活动
     */
    fun recordActivity() {
        lastActivityTime.set(Instant.now())
    }

    /**
     * 获取最后活动时间
     */
    fun getLastActivityTime(): Instant? = lastActivityTime.get()

    /**
     * 获取阈值（毫秒）
     */
    fun getThresholdMs(): Long = threshold.get()

    /**
     * 设置阈值（毫秒）
     */
    fun setThreshold(ms: Long) {
        threshold.set(ms)
    }

    companion object {
        /** 默认阈值：1 分钟 */
        const val DEFAULT_THRESHOLD_MS = 60_000L
    }
}
