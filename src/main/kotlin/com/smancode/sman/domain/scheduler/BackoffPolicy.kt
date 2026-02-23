package com.smancode.sman.domain.scheduler

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 退避策略
 *
 * 连续失败后逐步退避，避免疯狂重试：
 * - 第 1 次失败 → 30 秒
 * - 第 2 次失败 → 1 分钟
 * - 第 3 次失败 → 5 分钟
 * - 第 4 次失败 → 15 分钟
 * - 第 5+ 次失败 → 1 小时
 *
 * 线程安全：所有状态均使用原子类保证线程安全。
 *
 * @see BACKOFF_SCHEDULE_MS 退避时间表
 */
class BackoffPolicy {

    private val consecutiveErrors = AtomicInteger(0)
    private val lastErrorTime = AtomicReference<Instant?>(null)

    /** 记录错误 */
    fun recordError() {
        consecutiveErrors.incrementAndGet()
        lastErrorTime.set(Instant.now())
    }

    /** 记录成功，重置退避状态 */
    fun recordSuccess() {
        consecutiveErrors.set(0)
        lastErrorTime.set(null)
    }

    /**
     * 获取下次执行前的等待时间
     *
     * @return 等待毫秒数，无错误时返回 0
     */
    fun getNextDelayMs(): Long {
        val errors = consecutiveErrors.get()
        if (errors == 0) return 0L
        val index = (errors - 1).coerceAtMost(BACKOFF_SCHEDULE_MS.lastIndex)
        return BACKOFF_SCHEDULE_MS[index]
    }

    /** 是否处于退避状态 */
    fun isInBackoff(): Boolean = consecutiveErrors.get() > 0

    /** 获取连续错误次数 */
    fun getConsecutiveErrors(): Int = consecutiveErrors.get()

    /** 获取最后错误时间 */
    fun getLastErrorTime(): Instant? = lastErrorTime.get()

    companion object {
        /** 退避时间表（毫秒） */
        val BACKOFF_SCHEDULE_MS: List<Long> = listOf(
            30_000L,        // 第 1 次失败 → 30 秒
            60_000L,        // 第 2 次失败 → 1 分钟
            5 * 60_000L,    // 第 3 次失败 → 5 分钟
            15 * 60_000L,   // 第 4 次失败 → 15 分钟
            60 * 60_000L    // 第 5+ 次失败 → 1 小时
        )
    }
}
