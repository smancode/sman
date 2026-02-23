package com.smancode.sman.domain.scheduler

/**
 * Tick 执行结果
 */
sealed class TickResult {
    /** 成功 */
    object Success : TickResult()

    /** 跳过（暂停、退避等） */
    object Skipped : TickResult()

    /** 错误 */
    data class Error(val exception: Throwable) : TickResult()
}
