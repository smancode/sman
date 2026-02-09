package com.smancode.smanagent.analysis.retry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 熔断器状态
 */
enum class CircuitBreakerState {
    CLOSED,    // 关闭：正常工作
    OPEN,      // 打开：熔断中，直接拒绝请求
    HALF_OPEN  // 半开：尝试恢复
}

/**
 * 熔断器（Circuit Breaker Pattern）
 *
 * 用途：
 * - 当服务连续失败达到阈值后，暂停请求
 * - 避免持续调用不可用的服务
 * - 支持自动恢复
 *
 * @param failureThreshold 失败阈值（连续失败多少次后熔断）
 * @param successThreshold 成功阈值（半开状态下连续成功多少次后恢复）
 * @param timeoutMs 熔断超时时间（毫秒，多久后尝试恢复）
 * @param name 熔断器名称（用于日志）
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val successThreshold: Int = 2,
    private val timeoutMs: Long = 60000,
    private val name: String = "Breaker"
) {
    @Volatile
    private var state: CircuitBreakerState = CircuitBreakerState.CLOSED

    @Volatile
    private var failureCount: Int = 0

    @Volatile
    private var successCount: Int = 0

    @Volatile
    private var lastFailureTime: Long = 0

    private val logger = LoggerFactory.getLogger(this::class.java)
    private val lock = ReentrantLock()

    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(successThreshold > 0) { "successThreshold must be positive" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        logger.info("初始化熔断器: name={}, failureThreshold={}, successThreshold={}, timeoutMs={}",
            name, failureThreshold, successThreshold, timeoutMs)
    }

    /**
     * 执行带熔断保护的操作
     *
     * @param operation 要执行的操作
     * @return 操作结果
     * @throws CircuitBreakerOpenException 当熔断器打开时抛出
     */
    suspend fun <T> execute(operation: suspend () -> T): T = withContext(Dispatchers.IO) {
        checkState()

        try {
            val result = operation()
            recordSuccess()
            result
        } catch (e: Exception) {
            recordFailure()
            throw e
        }
    }

    /**
     * 执行带熔断保护的操作（同步版本）
     *
     * @param operation 要执行的操作
     * @return 操作结果
     * @throws CircuitBreakerOpenException 当熔断器打开时抛出
     */
    fun <T> executeBlocking(operation: () -> T): T {
        checkState()

        try {
            val result = operation()
            recordSuccess()
            return result
        } catch (e: Exception) {
            recordFailure()
            throw e
        }
    }

    /**
     * 检查状态，决定是否允许执行
     */
    private fun checkState() {
        when (state) {
            CircuitBreakerState.OPEN -> {
                // 检查是否超时
                if (System.currentTimeMillis() - lastFailureTime > timeoutMs) {
                    lock.withLock {
                        logger.info("{} 熔断超时，进入半开状态", name)
                        state = CircuitBreakerState.HALF_OPEN
                        successCount = 0
                    }
                } else {
                    throw CircuitBreakerOpenException("$name 熔断器已打开")
                }
            }
            CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED -> {
                // 继续执行
            }
        }
    }

    private fun recordSuccess() {
        lock.withLock {
            when (state) {
                CircuitBreakerState.HALF_OPEN -> {
                    successCount++
                    logger.info("{} 半开状态成功计数: {}/{}", name, successCount, successThreshold)

                    if (successCount >= successThreshold) {
                        logger.info("{} 恢复正常，关闭熔断器", name)
                        state = CircuitBreakerState.CLOSED
                        failureCount = 0
                    }
                }
                CircuitBreakerState.CLOSED -> {
                    // 重置失败计数
                    failureCount = 0
                }
                CircuitBreakerState.OPEN -> {
                    // 不应该发生
                }
            }
        }
    }

    private fun recordFailure() {
        lock.withLock {
            failureCount++
            lastFailureTime = System.currentTimeMillis()

            logger.warn("{} 失败计数: {}/{}", name, failureCount, failureThreshold)

            when (state) {
                CircuitBreakerState.CLOSED -> {
                    if (failureCount >= failureThreshold) {
                        logger.error("{} 达到失败阈值，打开熔断器", name)
                        state = CircuitBreakerState.OPEN
                    }
                }
                CircuitBreakerState.HALF_OPEN -> {
                    logger.error("{} 半开状态失败，重新打开熔断器", name)
                    state = CircuitBreakerState.OPEN
                }
                CircuitBreakerState.OPEN -> {
                    // 不应该发生
                }
            }
        }
    }

    /**
     * 获取当前状态
     */
    fun getState(): CircuitBreakerState = state

    /**
     * 重置熔断器
     */
    fun reset() {
        lock.withLock {
            logger.info("{} 重置熔断器", name)
            state = CircuitBreakerState.CLOSED
            failureCount = 0
            successCount = 0
        }
    }

    /**
     * 获取统计信息
     */
    fun getStatistics(): CircuitBreakerStatistics {
        return CircuitBreakerStatistics(
            name = name,
            state = state,
            failureCount = failureCount,
            successCount = successCount,
            failureThreshold = failureThreshold,
            successThreshold = successThreshold,
            lastFailureTime = lastFailureTime,
            timeSinceLastFailure = if (lastFailureTime > 0) {
                System.currentTimeMillis() - lastFailureTime
            } else 0
        )
    }

    companion object {
        /**
         * 创建 BGE 专用熔断器
         */
        fun forBge(
            failureThreshold: Int = 5,
            successThreshold: Int = 2
        ) = CircuitBreaker(
            failureThreshold = failureThreshold,
            successThreshold = successThreshold,
            name = "BGE-Breaker"
        )

        /**
         * 创建 LLM 专用熔断器
         */
        fun forLlm(
            failureThreshold: Int = 5,
            successThreshold: Int = 2
        ) = CircuitBreaker(
            failureThreshold = failureThreshold,
            successThreshold = successThreshold,
            name = "LLM-Breaker"
        )

        /**
         * 创建 Reranker 专用熔断器
         */
        fun forReranker(
            failureThreshold: Int = 3,
            successThreshold: Int = 2
        ) = CircuitBreaker(
            failureThreshold = failureThreshold,
            successThreshold = successThreshold,
            name = "Reranker-Breaker"
        )
    }

    /**
     * 熔断器统计信息
     */
    data class CircuitBreakerStatistics(
        val name: String,
        val state: CircuitBreakerState,
        val failureCount: Int,
        val successCount: Int,
        val failureThreshold: Int,
        val successThreshold: Int,
        val lastFailureTime: Long,
        val timeSinceLastFailure: Long
    ) {
        override fun toString(): String {
            return """
                |熔断器统计 [$name]:
                |  状态: $state
                |  失败计数: $failureCount/$failureThreshold
                |  成功计数: $successCount/$successThreshold
                |  上次失败: ${lastFailureTime}ms ago
                |  距离上次失败: ${timeSinceLastFailure}ms
            """.trimMargin()
        }
    }
}

/**
 * 熔断器打开异常
 */
class CircuitBreakerOpenException(message: String) : Exception(message)
