package com.smancode.sman.architect.model

import kotlinx.serialization.Serializable

/**
 * 架构师阶段枚举
 *
 * 标识架构师 Agent 当前所处的执行阶段
 */
@Serializable
enum class ArchitectPhase {
    /** 空闲状态 */
    IDLE,

    /** 选择目标 */
    SELECTING_GOAL,

    /** 检查增量更新 */
    CHECKING_INCREMENTAL,

    /** 执行分析（调用 SmanLoop） */
    EXECUTING,

    /** 评估结果 */
    EVALUATING,

    /** 持久化（写入 MD） */
    PERSISTING,

    /** 等待中（配额/退避） */
    WAITING
}

/**
 * 架构师停止原因枚举
 *
 * 标识架构师 Agent 停止的具体原因
 */
@Serializable
enum class ArchitectStopReason {
    /** 用户手动停止 */
    USER_STOPPED,

    /** API Key 未配置 */
    API_KEY_NOT_CONFIGURED,

    /** 所有目标已完成 */
    ALL_GOALS_COMPLETED,

    /** 达到每日配额 */
    DAILY_QUOTA_EXHAUSTED,

    /** 连续失败 */
    CONSECUTIVE_FAILURES,

    /** 项目未变化 */
    PROJECT_UNCHANGED,

    /** 配置禁用 */
    DISABLED_BY_CONFIG,

    /** 未知错误 */
    UNKNOWN_ERROR
}
