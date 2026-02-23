package com.smancode.sman.domain.scheduler

/**
 * 调度器配置
 */
data class SchedulerConfig(
    /** 是否启用 */
    val enabled: Boolean = true,

    /** 心跳间隔（毫秒） */
    val intervalMs: Long = DEFAULT_INTERVAL_MS,

    /** 单次执行最大 Token 数 */
    val maxTokensPerTick: Int = 4000,

    /** 用户活跃时是否暂停 */
    val pauseOnUserActive: Boolean = true,

    /** 用户活跃判定阈值（毫秒） */
    val activityThresholdMs: Long = DEFAULT_ACTIVITY_THRESHOLD_MS,

    /** 是否跳过校验（用于测试） */
    val skipValidation: Boolean = false
) {
    companion object {
        /** 默认心跳间隔：5 分钟 */
        const val DEFAULT_INTERVAL_MS = 5 * 60 * 1000L

        /** 最小心跳间隔：30 秒 */
        const val MIN_INTERVAL_MS = 30 * 1000L

        /** 最大定时器延迟：60 秒（防止系统休眠后时间漂移） */
        const val MAX_TIMER_DELAY_MS = 60 * 1000L

        /** 请求合并窗口：250 毫秒 */
        const val COALESCE_WINDOW_MS = 250L

        /** 默认用户活跃阈值：1 分钟 */
        const val DEFAULT_ACTIVITY_THRESHOLD_MS = 60 * 1000L
    }

    init {
        if (!skipValidation) {
            require(intervalMs >= MIN_INTERVAL_MS) {
                "intervalMs 必须 >= $MIN_INTERVAL_MS"
            }
        }
    }
}
