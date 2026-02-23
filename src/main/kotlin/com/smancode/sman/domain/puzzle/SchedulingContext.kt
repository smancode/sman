package com.smancode.sman.domain.puzzle

/**
 * 调度上下文
 *
 * 提供任务调度所需的上下文信息
 *
 * @property recentQueries 最近的查询列表
 * @property recentFileChanges 最近变更的文件列表
 * @property availableBudget 可用的 Token 预算
 */
data class SchedulingContext(
    val recentQueries: List<String>,
    val recentFileChanges: List<String>,
    val availableBudget: TokenBudget
)
