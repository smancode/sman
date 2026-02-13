package com.smancode.sman.evolution.guard

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * 指数退避策略
 *
 * 用于在连续错误发生时，按照指数级增加等待时间，避免频繁重试导致系统压力过大。
 *
 * @param baseMs 基础退避时间（毫秒），默认 1000ms（1 秒）
 * @param maxMs 最大退避时间（毫秒），默认 3600000ms（1 小时）
 * @param multiplier 乘数，默认 2.0
 */
class ExponentialBackoff(
    private val baseMs: Long = 1000,
    private val maxMs: Long = 3600000,
    private val multiplier: Double = 2.0
) {
    /**
     * 计算退避时间
     *
     * @param consecutiveErrors 连续错误次数
     * @return 退避时间（毫秒）
     */
    fun calculateBackoff(consecutiveErrors: Int): Long {
        if (consecutiveErrors <= 0) return 0

        val backoff = (baseMs * multiplier.pow(consecutiveErrors - 1)).toLong()
        return minOf(backoff, maxMs)
    }

    /**
     * 计算带抖动的退避时间
     *
     * 添加 0-20% 的随机抖动，避免多个客户端同时重试导致的"惊群效应"
     *
     * @param consecutiveErrors 连续错误次数
     * @return 带抖动的退避时间（毫秒）
     */
    fun calculateBackoffWithJitter(consecutiveErrors: Int): Long {
        val baseBackoff = calculateBackoff(consecutiveErrors)
        // 添加 0-20% 的随机抖动
        val jitter = baseBackoff * (Math.random() * 0.2)
        return baseBackoff + jitter.toLong()
    }
}

/**
 * 退避状态
 *
 * @param consecutiveErrors 连续错误次数
 * @param lastErrorTime 最后一次错误时间戳
 * @param backoffUntil 退避结束时间戳
 */
data class BackoffState(
    val consecutiveErrors: Int = 0,
    val lastErrorTime: Long? = null,
    val backoffUntil: Long? = null
)

/**
 * 退避状态管理器
 *
 * 管理各项目的退避状态，提供错误记录、成功记录、退避检查等功能。
 *
 * @param backoff 指数退避策略实例
 */
class BackoffStateManager(
    private val backoff: ExponentialBackoff = ExponentialBackoff()
) {
    // 项目 -> 退避状态
    private val states = ConcurrentHashMap<String, BackoffState>()

    /**
     * 记录错误
     *
     * 增加连续错误计数，并计算新的退避结束时间。
     *
     * @param projectKey 项目标识
     */
    fun recordError(projectKey: String) {
        states.compute(projectKey) { _, state ->
            val current = state ?: BackoffState()
            val newConsecutiveErrors = current.consecutiveErrors + 1
            current.copy(
                consecutiveErrors = newConsecutiveErrors,
                lastErrorTime = System.currentTimeMillis(),
                backoffUntil = System.currentTimeMillis() + backoff.calculateBackoffWithJitter(newConsecutiveErrors)
            )
        }
    }

    /**
     * 记录成功
     *
     * 重置连续错误计数，清除退避状态。
     *
     * @param projectKey 项目标识
     */
    fun recordSuccess(projectKey: String) {
        states.compute(projectKey) { _, state ->
            state?.copy(
                consecutiveErrors = 0,
                backoffUntil = null
            ) ?: BackoffState()
        }
    }

    /**
     * 检查是否在退避期
     *
     * @param projectKey 项目标识
     * @return 是否在退避期
     */
    fun isInBackoff(projectKey: String): Boolean {
        val state = states[projectKey] ?: return false
        val backoffUntil = state.backoffUntil ?: return false
        return System.currentTimeMillis() < backoffUntil
    }

    /**
     * 获取剩余退避时间
     *
     * @param projectKey 项目标识
     * @return 剩余退避时间（毫秒），如果不在退避期则返回 0
     */
    fun getRemainingBackoff(projectKey: String): Long {
        val state = states[projectKey] ?: return 0
        val backoffUntil = state.backoffUntil ?: return 0
        return maxOf(0, backoffUntil - System.currentTimeMillis())
    }

    /**
     * 获取当前状态
     *
     * @param projectKey 项目标识
     * @return 当前退避状态，如果不存在则返回默认状态
     */
    fun getState(projectKey: String): BackoffState {
        return states[projectKey] ?: BackoffState()
    }

    /**
     * 清除指定项目的退避状态
     *
     * @param projectKey 项目标识
     */
    fun clearState(projectKey: String) {
        states.remove(projectKey)
    }

    /**
     * 清除所有退避状态
     */
    fun clearAllStates() {
        states.clear()
    }
}
