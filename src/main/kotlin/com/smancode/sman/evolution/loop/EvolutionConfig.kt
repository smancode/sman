package com.smancode.sman.evolution.loop

import kotlinx.serialization.Serializable

/**
 * 进化配置 - 自进化循环的配置参数
 *
 * 用于控制后台自问自答学习流程的行为，包括迭代间隔、问题生成、探索步数、
 * 每日配额、退避策略和 Token 预算等。
 */
@Serializable
data class EvolutionConfig(
    // ==================== 基础配置 ====================

    /** 是否启用自进化功能 */
    val enabled: Boolean = true,

    /** 每次迭代间隔 (毫秒)，默认 60000ms (1 分钟) */
    val intervalMs: Long = 60000L,

    // ==================== 问题生成配置 ====================

    /** 每次迭代生成的问题数量 */
    val questionsPerIteration: Int = 3,

    /** 问题生成最大重试次数 */
    val maxQuestionRetries: Int = 3,

    // ==================== 探索配置 ====================

    /** 每次探索最大步数 */
    val maxExplorationSteps: Int = 10,

    /** 探索超时时间 (毫秒)，默认 120000ms (2 分钟) */
    val explorationTimeoutMs: Long = 120000L,

    // ==================== 每日配额 ====================

    /** 每天最多生成的问题数量 */
    val maxDailyQuestions: Int = 50,

    // ==================== 退避配置 ====================

    /** 基础退避时间 (毫秒)，默认 1000ms (1 秒) */
    val baseBackoffMs: Long = 1000L,

    /** 最大退避时间 (毫秒)，默认 3600000ms (1 小时) */
    val maxBackoffMs: Long = 3600000L,

    /** 最大连续错误次数，超过后触发退避 */
    val maxConsecutiveErrors: Int = 5,

    // ==================== Token 预算 ====================

    /** 每次迭代最大 Token 消耗 */
    val maxTokensPerIteration: Int = 8000
) {
    companion object {
        /** 默认配置实例 */
        val DEFAULT = EvolutionConfig()

        /** 禁用自进化的配置实例 */
        val DISABLED = EvolutionConfig(enabled = false)

        /** 快速测试配置 (短间隔、少问题、小配额) */
        val FAST_TEST = EvolutionConfig(
            enabled = true,
            intervalMs = 1000L,
            questionsPerIteration = 1,
            maxExplorationSteps = 3,
            maxDailyQuestions = 5,
            maxTokensPerIteration = 2000
        )
    }
}
