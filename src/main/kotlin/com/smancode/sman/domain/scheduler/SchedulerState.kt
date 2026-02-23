package com.smancode.sman.domain.scheduler

/**
 * 调度器状态
 */
enum class SchedulerState {
    /** 空闲 */
    IDLE,

    /** 运行中 */
    RUNNING,

    /** 暂停（用户活跃） */
    PAUSED,

    /** 退避（连续错误） */
    BACKOFF
}
