package com.smancode.sman.domain.scheduler

/**
 * 唤醒原因枚举
 */
enum class WakeReason {
    /** 定时触发 */
    INTERVAL,

    /** 文件变更触发 */
    FILE_CHANGE,

    /** 用户查询触发 */
    USER_QUERY,

    /** 手动触发 */
    MANUAL,

    /** 错误恢复触发 */
    ERROR_RECOVERY
}
