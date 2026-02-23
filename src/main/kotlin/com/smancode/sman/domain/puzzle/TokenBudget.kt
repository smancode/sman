package com.smancode.sman.domain.puzzle

/**
 * Token 预算
 *
 * 表示当前可用的 Token 预算限制
 *
 * @property maxTokensPerTask 单个任务最大 Token 数
 * @property maxTasksPerSession 单次会话最大任务数
 */
data class TokenBudget(
    val maxTokensPerTask: Int,
    val maxTasksPerSession: Int
) {
    init {
        require(maxTokensPerTask >= 0) { "maxTokensPerTask 不能为负数" }
        require(maxTasksPerSession >= 0) { "maxTasksPerSession 不能为负数" }
    }

    /**
     * 检查预算是否可用
     */
    fun isAvailable(): Boolean = maxTokensPerTask > 0 && maxTasksPerSession > 0
}
