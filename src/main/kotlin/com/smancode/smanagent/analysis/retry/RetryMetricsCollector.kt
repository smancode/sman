package com.smancode.smanagent.analysis.retry

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 重试指标数据
 */
data class RetryMetrics(
    val operationName: String,
    val totalAttempts: Long = 0,
    val successCount: Long = 0,
    val failureCount: Long = 0,
    val totalDelayMs: Long = 0,
    val retryDistribution: Map<Int, Int> = emptyMap(),  // 第N次重试成功的次数
    val errorDistribution: Map<String, Int> = emptyMap()  // 错误类型分布
) {
    val successRate: Double
        get() = if (totalAttempts > 0) {
            successCount.toDouble() / totalAttempts.toDouble()
        } else 0.0

    val averageDelayMs: Long
        get() = if (successCount > 0) {
            totalDelayMs / successCount
        } else 0

    override fun toString(): String {
        return """
            |重试指标 [$operationName]:
            |  总尝试次数: $totalAttempts
            |  成功: $successCount
            |  失败: $failureCount
            |  成功率: ${"%.2f%%".format(successRate * 100)}
            |  平均延迟: ${averageDelayMs} ms
            |  重试分布: $retryDistribution
            |  错误分布: ${errorDistribution.entries.take(5)}
        """.trimMargin()
    }
}

/**
 * 重试指标收集器
 *
 * 用途：
 * - 收集重试操作的成功率、延迟等指标
 * - 支持并发写入
 * - 生成统计报告
 */
class RetryMetricsCollector {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val metrics = ConcurrentHashMap<String, RetryMetrics>()
    private val lock = ReentrantReadWriteLock()

    /**
     * 记录成功
     *
     * @param operationName 操作名称
     * @param attempt 尝试次数（0 表示首次成功）
     */
    fun recordSuccess(operationName: String, attempt: Int) {
        lock.write {
            val current = metrics[operationName] ?: RetryMetrics(operationName)
            val updated = current.copy(
                totalAttempts = current.totalAttempts + 1,
                successCount = current.successCount + 1,
                retryDistribution = current.retryDistribution + (attempt to
                    (current.retryDistribution[attempt] ?: 0) + 1)
            )
            metrics[operationName] = updated
        }
    }

    /**
     * 记录失败
     *
     * @param operationName 操作名称
     * @param attempt 尝试次数
     * @param exception 异常对象
     */
    fun recordFailure(operationName: String, attempt: Int, exception: Exception) {
        lock.write {
            val current = metrics[operationName] ?: RetryMetrics(operationName)
            val errorType = exception.javaClass.simpleName

            val updated = current.copy(
                totalAttempts = current.totalAttempts + 1,
                failureCount = current.failureCount + 1,
                errorDistribution = current.errorDistribution + (errorType to
                    (current.errorDistribution[errorType] ?: 0) + 1)
            )
            metrics[operationName] = updated
        }
    }

    /**
     * 记录延迟
     *
     * @param operationName 操作名称
     * @param delayMs 延迟毫秒数
     */
    fun recordDelay(operationName: String, delayMs: Long) {
        lock.write {
            val current = metrics[operationName] ?: RetryMetrics(operationName)
            val updated = current.copy(
                totalDelayMs = current.totalDelayMs + delayMs
            )
            metrics[operationName] = updated
        }
    }

    /**
     * 获取指标
     *
     * @param operationName 操作名称
     * @return 指标数据，如果不存在返回 null
     */
    fun getMetrics(operationName: String): RetryMetrics? = metrics[operationName]

    /**
     * 获取所有指标
     *
     * @return 所有指标的映射
     */
    fun getAllMetrics(): Map<String, RetryMetrics> = lock.read { metrics.toMap() }

    /**
     * 重置指标
     *
     * @param operationName 操作名称（如果为 null 则重置所有）
     */
    fun resetMetrics(operationName: String? = null) {
        lock.write {
            if (operationName != null) {
                metrics.remove(operationName)
                logger.info("重置指标: {}", operationName)
            } else {
                metrics.clear()
                logger.info("重置所有指标")
            }
        }
    }

    /**
     * 打印报告到控制台
     */
    fun printReport() {
        lock.read {
            val allMetrics = metrics.values.toList()
            if (allMetrics.isEmpty()) {
                logger.info("暂无重试指标数据")
                return
            }

            println("=== 重试指标报告 ===")
            println("生成时间: ${java.time.LocalDateTime.now()}")
            println()

            allMetrics
                .sortedByDescending { it.totalAttempts }
                .forEach { m ->
                    println(m.toString())
                    println()
                }

            // 汇总统计
            val totalAttempts = allMetrics.sumOf { it.totalAttempts }
            val totalSuccess = allMetrics.sumOf { it.successCount }
            val totalFailure = allMetrics.sumOf { it.failureCount }
            val overallSuccessRate = if (totalAttempts > 0) {
                totalSuccess.toDouble() / totalAttempts.toDouble()
            } else 0.0

            println("=== 汇总统计 ===")
            println("总尝试次数: $totalAttempts")
            println("总成功次数: $totalSuccess")
            println("总失败次数: $totalFailure")
            println("整体成功率: ${"%.2f%%".format(overallSuccessRate * 100)}")
        }
    }

    /**
     * 获取汇总统计
     *
     * @return 汇总统计数据
     */
    fun getSummaryMetrics(): SummaryMetrics = lock.read {
        val allMetrics = metrics.values.toList()
        if (allMetrics.isEmpty()) {
            return SummaryMetrics(0, 0, 0, 0.0, 0L)
        }

        SummaryMetrics(
            totalOperations = allMetrics.size,
            totalAttempts = allMetrics.sumOf { it.totalAttempts },
            totalSuccess = allMetrics.sumOf { it.successCount },
            overallSuccessRate = if (allMetrics.sumOf { it.totalAttempts } > 0) {
                allMetrics.sumOf { it.successCount }.toDouble() / allMetrics.sumOf { it.totalAttempts }.toDouble()
            } else 0.0,
            averageDelayMs = if (allMetrics.count { it.successCount > 0 } > 0) {
                allMetrics.filter { it.successCount > 0 }.map { it.averageDelayMs }.average().toLong()
            } else 0L
        )
    }

    /**
     * 导出为 JSON 格式字符串
     */
    fun exportToJson(): String {
        val allMetrics = getAllMetrics()
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"timestamp\": \"${java.time.Instant.now()}\",\n")
        sb.append("  \"metrics\": [\n")

        allMetrics.entries.forEachIndexed { index, (name, metric) ->
            sb.append("    {\n")
            sb.append("      \"operation\": \"$name\",\n")
            sb.append("      \"totalAttempts\": ${metric.totalAttempts},\n")
            sb.append("      \"successCount\": ${metric.successCount},\n")
            sb.append("      \"failureCount\": ${metric.failureCount},\n")
            sb.append("      \"successRate\": ${"%.4f".format(metric.successRate)},\n")
            sb.append("      \"averageDelayMs\": ${metric.averageDelayMs}\n")
            sb.append("    }")
            if (index < allMetrics.size - 1) sb.append(",")
            sb.append("\n")
        }

        sb.append("  ]\n")
        sb.append("}")
        return sb.toString()
    }

    companion object {
        /**
         * 全局单例（可选）
         */
        val GLOBAL = RetryMetricsCollector()
    }
}

/**
 * 汇总统计数据
 */
data class SummaryMetrics(
    val totalOperations: Int,
    val totalAttempts: Long,
    val totalSuccess: Long,
    val overallSuccessRate: Double,
    val averageDelayMs: Long
) {
    val totalFailure: Long
        get() = totalAttempts - totalSuccess

    override fun toString(): String {
        return """
            |汇总统计:
            |  操作类型数: $totalOperations
            |  总尝试次数: $totalAttempts
            |  总成功: $totalSuccess
            |  总失败: $totalFailure
            |  整体成功率: ${"%.2f%%".format(overallSuccessRate * 100)}
            |  平均延迟: ${averageDelayMs} ms
        """.trimMargin()
    }
}
