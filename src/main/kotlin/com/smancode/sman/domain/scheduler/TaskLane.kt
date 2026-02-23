package com.smancode.sman.domain.scheduler

/**
 * 任务通道
 *
 * 不同类型任务隔离执行，避免后台阻塞前台
 */
enum class TaskLane {
    /** 主通道：用户交互响应 */
    MAIN,

    /** 后台通道：自动分析任务 */
    BACKGROUND,

    /** 低优先级通道：知识整理、清理等 */
    LOW_PRIORITY
}
