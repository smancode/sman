package com.smancode.smanagent.smancode.llm.config

/**
 * LLM 重试策略配置
 */
class LlmRetryPolicy {

    /**
     * 最大重试次数
     */
    var maxRetries: Int = 3

    /**
     * 基础延迟时间（毫秒）
     */
    var baseDelay: Long = 10000 // 10秒

    /**
     * 计算指数退避延迟时间
     *
     * @param retryAttempt 重试次数（从 0 开始）
     * @return 延迟时间（毫秒）
     */
    fun calculateDelay(retryAttempt: Int): Long {
        // 指数退避：10s, 20s, 30s
        return baseDelay * (retryAttempt + 1)
    }
}
