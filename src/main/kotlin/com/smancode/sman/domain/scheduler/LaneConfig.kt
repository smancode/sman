package com.smancode.sman.domain.scheduler

/**
 * Lane 配置
 */
data class LaneConfig(
    val maxConcurrent: Int,
    val queueCapacity: Int,
    val priority: Int
) {
    companion object {
        /** 主通道配置 */
        val MAIN = LaneConfig(
            maxConcurrent = 3,
            queueCapacity = 100,
            priority = 100
        )

        /** 后台通道配置 */
        val BACKGROUND = LaneConfig(
            maxConcurrent = 1,
            queueCapacity = 50,
            priority = 50
        )

        /** 低优先级通道配置 */
        val LOW_PRIORITY = LaneConfig(
            maxConcurrent = 1,
            queueCapacity = 20,
            priority = 10
        )

        /** 根据 Lane 获取配置 */
        fun forLane(lane: TaskLane): LaneConfig = when (lane) {
            TaskLane.MAIN -> MAIN
            TaskLane.BACKGROUND -> BACKGROUND
            TaskLane.LOW_PRIORITY -> LOW_PRIORITY
        }
    }
}
