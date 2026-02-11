package com.smancode.sman.analysis.retry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * 并发限流器（基于 Semaphore）
 *
 * 用途：
 * - 限制并发请求数量，避免触发 API 速率限制
 * - 支持 BGE、LLM 等外部调用
 * - 支持动态调整并发数
 *
 * @param maxConcurrency 最大并发数
 * @param name 限流器名称（用于日志）
 */
class ConcurrencyLimiter(
    private val maxConcurrency: Int,
    private val name: String = "Limiter"
) {
    private val semaphore = Semaphore(maxConcurrency)
    private val logger = LoggerFactory.getLogger(this::class.java)

    // 监控指标
    @Volatile
    private var activeCount = 0
    private val maxActiveCount = AtomicInteger(0)

    init {
        require(maxConcurrency > 0) { "并发数必须大于 0" }
        logger.info("初始化并发限流器: name={}, maxConcurrency={}", name, maxConcurrency)
    }

    /**
     * 执行带限流的操作
     *
     * @param operation 要执行的操作
     * @return 操作结果
     */
    suspend fun <T> execute(operation: suspend () -> T): T = withContext(Dispatchers.IO) {
        semaphore.acquire()
        try {
            val current = ++activeCount
            synchronized(maxActiveCount) {
                if (current > maxActiveCount.get()) {
                    maxActiveCount.set(current)
                }
            }
            logger.debug("{} 活跃线程: {}/{}, 峰值: {}", name, current, maxConcurrency, maxActiveCount.get())

            operation()
        } finally {
            --activeCount
            semaphore.release()
        }
    }

    /**
     * 执行带限流的操作（同步版本）
     *
     * @param operation 要执行的操作
     * @return 操作结果
     */
    fun <T> executeBlocking(operation: () -> T): T {
        semaphore.acquire()
        try {
            val current = ++activeCount
            synchronized(maxActiveCount) {
                if (current > maxActiveCount.get()) {
                    maxActiveCount.set(current)
                }
            }
            logger.debug("{} 活跃线程: {}/{}, 峰值: {}", name, current, maxConcurrency, maxActiveCount.get())

            return operation()
        } finally {
            --activeCount
            semaphore.release()
        }
    }

    /**
     * 获取当前活跃数
     */
    fun getActiveCount(): Int = activeCount

    /**
     * 获取峰值活跃数
     */
    fun getMaxActiveCount(): Int = maxActiveCount.get()

    /**
     * 获取可用许可数
     */
    fun getAvailablePermits(): Int = semaphore.availablePermits()

    /**
     * 重置峰值计数
     */
    fun resetPeakCount() {
        maxActiveCount.set(activeCount)
    }

    /**
     * 获取统计信息
     */
    fun getStatistics(): ConcurrencyStatistics {
        return ConcurrencyStatistics(
            name = name,
            maxConcurrency = maxConcurrency,
            activeCount = activeCount,
            peakCount = maxActiveCount.get(),
            availablePermits = getAvailablePermits()
        )
    }

    companion object {
        /**
         * 创建 BGE 专用限流器
         */
        fun forBge(maxConcurrency: Int) = ConcurrencyLimiter(
            maxConcurrency = maxConcurrency,
            name = "BGE-Limiter"
        )

        /**
         * 创建 LLM 专用限流器
         */
        fun forLlm(maxConcurrency: Int) = ConcurrencyLimiter(
            maxConcurrency = maxConcurrency,
            name = "LLM-Limiter"
        )

        /**
         * 创建向量化专用限流器
         */
        fun forVectorization(maxConcurrency: Int) = ConcurrencyLimiter(
            maxConcurrency = maxConcurrency,
            name = "Vectorization-Limiter"
        )

        /**
         * 创建分析步骤专用限流器
         */
        fun forAnalysis(maxConcurrency: Int) = ConcurrencyLimiter(
            maxConcurrency = maxConcurrency,
            name = "Analysis-Limiter"
        )
    }

    /**
     * 并发统计信息
     */
    data class ConcurrencyStatistics(
        val name: String,
        val maxConcurrency: Int,
        val activeCount: Int,
        val peakCount: Int,
        val availablePermits: Int
    ) {
        val utilization: Double
            get() = if (maxConcurrency > 0) {
                activeCount.toDouble() / maxConcurrency.toDouble()
            } else 0.0

        override fun toString(): String {
            return """
                |并发统计 [$name]:
                |  最大并发: $maxConcurrency
                |  当前活跃: $activeCount
                |  峰值活跃: $peakCount
                |  可用许可: $availablePermits
                |  利用率: ${"%.2f%%".format(utilization * 100)}
            """.trimMargin()
        }
    }
}
