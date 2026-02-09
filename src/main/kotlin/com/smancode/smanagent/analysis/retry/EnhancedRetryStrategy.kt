package com.smancode.smanagent.analysis.retry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ThreadLocalRandom

/**
 * 增强版重试策略（支持并发场景）
 *
 * 特性：
 * - 指数退避 + 抖动（避免雷击效应）
 * - 可配置最大重试次数
 * - 可配置基础延迟
 * - 支持自定义重试条件
 * - 支持自定义退避算法
 *
 * @param maxRetries 最大重试次数
 * @param baseDelayMs 基础延迟（毫秒）
 * @param maxDelayMs 最大延迟（毫秒）
 * @param jitterFactor 抖动因子（0.0-1.0，默认 0.1 表示 ±10%）
 * @param enableExponentialBackoff 是否启用指数退避
 * @param retryCondition 重试条件判断函数
 */
data class EnhancedRetryPolicy(
    val maxRetries: Int = 3,
    val baseDelayMs: Long = 1000,
    val maxDelayMs: Long = 30000,
    val jitterFactor: Double = 0.1,
    val enableExponentialBackoff: Boolean = true,
    val retryCondition: (Exception) -> Boolean = { true }
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be non-negative" }
        require(baseDelayMs > 0) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be >= baseDelayMs" }
        require(jitterFactor in 0.0..1.0) { "jitterFactor must be in [0.0, 1.0]" }
    }

    /**
     * 计算下次重试延迟（带抖动的指数退避）
     *
     * 公式：delay = baseDelay * (2 ^ attempt) ± jitter
     *
     * @param attempt 尝试次数（从 0 开始）
     * @return 延迟毫秒数
     */
    fun calculateDelay(attempt: Int): Long {
        require(attempt >= 0) { "尝试次数必须 >= 0" }

        val exponentialDelay = if (enableExponentialBackoff) {
            (baseDelayMs * Math.pow(2.0, attempt.toDouble())).toLong()
        } else {
            baseDelayMs
        }

        // 限制最大延迟
        val cappedDelay = minOf(exponentialDelay, maxDelayMs)

        // 添加抖动避免雷击效应
        val jitterRange = (cappedDelay * jitterFactor).toLong()
        val jitter = ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1)

        return maxOf(cappedDelay + jitter, 0)  // 确保非负
    }

    /**
     * 判断是否应该重试
     *
     * @param exception 异常对象
     * @param attempt 当前尝试次数
     * @return true 如果应该重试
     */
    fun shouldRetry(exception: Exception, attempt: Int): Boolean {
        if (attempt >= maxRetries) return false
        return retryCondition(exception)
    }

    companion object {
        /**
         * 默认策略：指数退避，3次重试
         */
        val DEFAULT = EnhancedRetryPolicy()

        /**
         * 激进策略：快速失败，1次重试
         */
        val AGGRESSIVE = EnhancedRetryPolicy(
            maxRetries = 1,
            baseDelayMs = 500
        )

        /**
         * 保守策略：5次重试，更长延迟
         */
        val CONSERVATIVE = EnhancedRetryPolicy(
            maxRetries = 5,
            baseDelayMs = 2000,
            maxDelayMs = 60000
        )

        /**
         * 限流专用策略：对 429 使用更强的指数退避
         */
        val RATE_LIMIT = EnhancedRetryPolicy(
            maxRetries = 5,
            baseDelayMs = 5000,
            maxDelayMs = 120000,  // 2分钟
            enableExponentialBackoff = true,
            retryCondition = { e ->
                e.message?.contains("429") == true ||
                e.message?.contains("Too Many Requests", ignoreCase = true) == true
            }
        )

        /**
         * 网络错误策略：针对网络超时和连接错误
         */
        val NETWORK_ERROR = EnhancedRetryPolicy(
            maxRetries = 3,
            baseDelayMs = 2000,
            enableExponentialBackoff = true,
            retryCondition = { e ->
                val message = e.message?.lowercase() ?: return@EnhancedRetryPolicy false
                message.contains("timeout") ||
                message.contains("timed out") ||
                message.contains("connect") ||
                message.contains("connection refused")
            }
        )
    }
}

/**
 * 增强版重试执行器
 *
 * @param policy 重试策略
 * @param metricsCollector 指标收集器（可选）
 */
class EnhancedRetryExecutor(
    private val policy: EnhancedRetryPolicy = EnhancedRetryPolicy.DEFAULT,
    private val metricsCollector: RetryMetricsCollector? = null
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * 执行带重试的操作
     *
     * @param operationName 操作名称（用于日志和指标）
     * @param operation 要执行的操作
     * @return 操作结果
     * @throws Exception 重试失败后抛出最后一次异常
     */
    suspend fun <T> executeWithRetry(
        operationName: String,
        operation: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        repeat(policy.maxRetries + 1) { attempt ->
            try {
                val result = operation()

                // 记录成功指标
                metricsCollector?.recordSuccess(operationName, attempt)

                if (attempt > 0) {
                    logger.info("{} 第 {} 次重试成功", operationName, attempt)
                }

                return@withContext result
            } catch (e: Exception) {
                lastException = e
                logger.warn("{} 第 {} 次尝试失败: {}", operationName, attempt, e.message)

                // 记录失败指标
                metricsCollector?.recordFailure(operationName, attempt, e)

                // 判断是否继续重试
                if (!policy.shouldRetry(e, attempt)) {
                    logger.error("{} 达到最大重试次数或不可重试，放弃", operationName)
                    throw e
                }

                // 计算延迟并等待
                val delay = policy.calculateDelay(attempt)
                logger.info("{} 等待 {} ms 后重试...", operationName, delay)
                delay(delay)
            }
        }

        throw lastException ?: IllegalStateException("重试逻辑异常")
    }

    /**
     * 执行带重试的操作（同步版本）
     *
     * @param operationName 操作名称
     * @param operation 要执行的操作
     * @return 操作结果
     */
    fun <T> executeWithRetryBlocking(
        operationName: String,
        operation: () -> T
    ): T {
        var lastException: Exception? = null

        repeat(policy.maxRetries + 1) { attempt ->
            try {
                val result = operation()

                // 记录成功指标
                metricsCollector?.recordSuccess(operationName, attempt)

                if (attempt > 0) {
                    logger.info("{} 第 {} 次重试成功", operationName, attempt)
                }

                return result
            } catch (e: Exception) {
                lastException = e
                logger.warn("{} 第 {} 次尝试失败: {}", operationName, attempt, e.message)

                // 记录失败指标
                metricsCollector?.recordFailure(operationName, attempt, e)

                // 判断是否继续重试
                if (!policy.shouldRetry(e, attempt)) {
                    logger.error("{} 达到最大重试次数或不可重试，放弃", operationName)
                    throw e
                }

                // 计算延迟并等待
                val delay = policy.calculateDelay(attempt)
                logger.info("{} 等待 {} ms 后重试...", operationName, delay)
                Thread.sleep(delay)
            }
        }

        throw lastException ?: IllegalStateException("重试逻辑异常")
    }

    /**
     * 获取当前策略
     */
    fun getPolicy(): EnhancedRetryPolicy = policy
}
