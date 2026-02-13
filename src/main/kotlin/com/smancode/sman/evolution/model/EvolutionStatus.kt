package com.smancode.sman.evolution.model

import kotlinx.serialization.Serializable

/**
 * 进化状态 - 记录项目自进化的当前状态
 *
 * 用于跟踪后台自问自答学习流程的状态，包括错误计数、退避机制、每日问题生成限制等。
 */
@Serializable
data class EvolutionStatus(
    /** 是否启用自进化 */
    val isEnabled: Boolean = true,

    /** 当前阶段 */
    val currentPhase: EvolutionPhase = EvolutionPhase.IDLE,

    /** 连续错误次数 (用于退避计算) */
    val consecutiveErrors: Int = 0,

    /** 最后一次错误信息 */
    val lastError: String? = null,

    /** 退避截止时间 (时间戳，毫秒) */
    val backoffUntil: Long? = null,

    /** 今日已生成的问题数量 */
    val questionsGeneratedToday: Int = 0,

    /** 上次重置日期 (格式: yyyy-MM-dd) */
    val lastResetDate: String = "",

    /** 总共探索的问题数量 */
    val totalQuestionsExplored: Int = 0
)

/**
 * 进化阶段枚举
 */
enum class EvolutionPhase {
    /** 空闲 - 等待下一轮学习 */
    IDLE,

    /** 生成问题中 - LLM 正在生成探索问题 */
    GENERATING_QUESTION,

    /** 探索中 - 正在使用工具探索代码 */
    EXPLORING,

    /** 总结中 - 正在生成学习记录 */
    SUMMARIZING,

    /** 退避中 - 因连续错误而暂停 */
    BACKING_OFF
}
