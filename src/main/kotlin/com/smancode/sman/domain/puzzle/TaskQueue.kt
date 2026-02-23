package com.smancode.sman.domain.puzzle

import java.time.Instant

/**
 * 任务队列数据类
 *
 * @property version 队列格式版本
 * @property lastUpdated 最后更新时间
 * @property tasks 任务列表
 */
data class TaskQueue(
    val version: Int = 1,
    val lastUpdated: Instant,
    val tasks: List<AnalysisTask>
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
