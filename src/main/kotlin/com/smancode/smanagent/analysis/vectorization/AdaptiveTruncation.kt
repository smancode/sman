package com.smancode.smanagent.analysis.vectorization

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 截断策略
 */
enum class TruncationStrategy {
    HEAD,       // 保留头部
    TAIL,       // 保留尾部
    MIDDLE,     // 保留头部和尾部
    SMART       // 智能截断（按段落/句子）
}

/**
 * 自适应截断器
 *
 * 特性：
 * - 检测 BGE 错误码，自动判断是否需要截断
 * - 逐步缩小文本（1000 字符/次）
 * - 支持多种截断策略
 * - 记录截断历史
 *
 * @param maxTokens 最大 token 数（默认 8192，BGE-M3 限制）
 * @param stepSize 每次截断的字符数（默认 1000）
 * @param maxRetries 最大截断重试次数（默认 10）
 * @param strategy 截断策略（默认 TAIL）
 */
class AdaptiveTruncation(
    private val maxTokens: Int = 8192,
    private val stepSize: Int = 1000,
    private val maxRetries: Int = 10,
    private val strategy: TruncationStrategy = TruncationStrategy.TAIL
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    init {
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(stepSize > 0) { "stepSize must be positive" }
        require(maxRetries > 0) { "maxRetries must be positive" }
    }

    /**
     * 截断历史记录
     */
    data class TruncationHistory(
        val originalLength: Int,
        val finalLength: Int,
        val steps: Int,
        val strategy: TruncationStrategy,
        val success: Boolean
    )

    private val history = ConcurrentHashMap<String, TruncationHistory>()

    /**
     * 判断异常是否为长度错误
     *
     * @param exception 异常对象
     * @return true 如果是长度错误
     */
    fun isLengthError(exception: Exception): Boolean {
        val message = exception.message?.lowercase() ?: return false

        return when {
            // HTTP 状态码
            message.contains("413") -> true  // Payload Too Large
            message.contains("400") && message.contains("too long") -> true  // Bad Request

            // 错误消息关键词
            message.contains("too long") -> true
            message.contains("exceeds limit") -> true
            message.contains("maximum length") -> true
            message.contains("token limit") -> true
            message.contains("max length") -> true
            message.contains("payload too large") -> true
            message.contains("request entity too large") -> true
            message.contains("input is too long") -> true
            message.contains("text too long") -> true
            message.contains("string too long") -> true

            // 特定服务错误
            message.contains("invalid payload size") -> true
            message.contains("size limit exceeded") -> true
            message.contains("length exceeds maximum") -> true
            message.contains("input length exceeds") -> true

            else -> false
        }
    }

    /**
     * 预处理文本（在调用 BGE 前截断）
     *
     * @param text 原始文本
     * @param identifier 文本标识符（用于日志）
     * @return 处理后的文本（可能被截断）
     */
    fun preprocessText(text: String, identifier: String = "unknown"): String {
        val originalLength = text.length
        var currentText = text
        var steps = 0

        // 预检查：如果明显超限，先截断
        val estimatedTokens = TokenEstimator.estimateTokens(text)
        if (estimatedTokens > maxTokens) {
            logger.warn("文本预检查超限: id={}, 长度={}, 估算token={}, maxToken={}",
                identifier, originalLength, estimatedTokens, maxTokens)

            // 计算目标长度
            val targetLength = TokenEstimator.estimateTruncatedLength(text, maxTokens)
            currentText = truncate(text, targetLength, strategy)
            steps++

            logger.info("文本预截断: id={}, 原长度={}, 新长度={}",
                identifier, originalLength, currentText.length)
        }

        // 记录历史
        if (steps > 0) {
            history[identifier] = TruncationHistory(
                originalLength = originalLength,
                finalLength = currentText.length,
                steps = steps,
                strategy = strategy,
                success = false  // 预处理不算成功，需要 BGE 调用成功
            )
        }

        return currentText
    }

    /**
     * 自适应处理（根据 BGE 错误逐步截断）
     *
     * @param text 原始文本
     * @param bgeFunction BGE 调用函数
     * @param identifier 文本标识符
     * @return BGE 返回结果
     */
    suspend fun <T> processAdaptive(
        text: String,
        bgeFunction: suspend (String) -> T,
        identifier: String = "unknown"
    ): T = withContext(Dispatchers.IO) {
        val originalLength = text.length
        var currentText = preprocessText(text, identifier)
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                val result = bgeFunction(currentText)

                // 记录成功历史
                history[identifier] = TruncationHistory(
                    originalLength = originalLength,
                    finalLength = currentText.length,
                    steps = attempt + 1,
                    strategy = strategy,
                    success = true
                )

                if (attempt > 0) {
                    logger.info("BGE 调用成功: id={}, 尝试次数={}, 原长度={}, 最终长度={}",
                        identifier, attempt + 1, originalLength, currentText.length)
                }

                return@withContext result
            } catch (e: Exception) {
                lastException = e
                logger.warn("BGE 调用失败 (尝试 {}/{}): id={}, 错误={}",
                    attempt + 1, maxRetries, identifier, e.message)

                // 检查是否为长度错误
                if (!isLengthError(e)) {
                    logger.error("非长度错误，抛出异常: {}", e.message)
                    throw e
                }

                // 如果文本已经很短了，放弃重试
                if (currentText.length <= stepSize) {
                    logger.error("文本已截断至最小长度 ({} 字符)，放弃重试", currentText.length)
                    throw e
                }

                // 截断文本
                val newLength = maxOf(currentText.length - stepSize, stepSize)
                currentText = truncate(currentText, newLength, strategy)

                logger.info("截断文本: id={}, 尝试={}, 原长度={}, 新长度={}",
                    identifier, attempt + 1, originalLength, currentText.length)
            }
        }

        // 记录失败历史
        history[identifier] = TruncationHistory(
            originalLength = originalLength,
            finalLength = currentText.length,
            steps = maxRetries,
            strategy = strategy,
            success = false
        )

        throw lastException ?: IllegalStateException("自适应处理失败")
    }

    /**
     * 截断文本
     *
     * @param text 原始文本
     * @param maxLength 最大长度
     * @param strategy 截断策略
     * @return 截断后的文本
     */
    private fun truncate(text: String, maxLength: Int, strategy: TruncationStrategy): String {
        if (text.length <= maxLength) return text

        return when (strategy) {
            TruncationStrategy.HEAD -> {
                text.take(maxLength)
            }

            TruncationStrategy.TAIL -> {
                text.takeLast(maxLength)
            }

            TruncationStrategy.MIDDLE -> {
                val half = maxLength / 2
                val head = text.take(half)
                val tail = text.takeLast(half)
                val omitted = text.length - maxLength
                "$head\n...[省略 $omitted 字符]...\n$tail"
            }

            TruncationStrategy.SMART -> {
                smartTruncate(text, maxLength)
            }
        }
    }

    /**
     * 智能截断（按段落/句子）
     */
    private fun smartTruncate(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text

        // 尝试在段落边界截断
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
        val result = StringBuilder()

        for (para in paragraphs) {
            if (result.length + para.length <= maxLength) {
                if (result.isNotEmpty()) result.append("\n\n")
                result.append(para)
            } else {
                // 段落太长，尝试在句子边界截断
                val remaining = maxLength - result.length
                if (remaining > 100) {  // 至少保留 100 字符
                    val sentences = para.split(Regex("[。.！!？?\\n]"))
                    var tempLength = result.length
                    for (sentence in sentences) {
                        if (tempLength + sentence.length <= maxLength) {
                            if (sentence.isNotEmpty()) {
                                result.append(sentence)
                                result.append("。")
                                tempLength += sentence.length + 1
                            }
                        } else {
                            break
                        }
                    }
                }
                break
            }
        }

        // 如果仍然超长，强制截断
        return if (result.length > maxLength) {
            result.substring(0, maxLength) + "..."
        } else {
            result.toString()
        }
    }

    /**
     * 获取截断历史
     */
    fun getHistory(identifier: String): TruncationHistory? = history[identifier]

    /**
     * 获取所有历史
     */
    fun getAllHistory(): Map<String, TruncationHistory> = history.toMap()

    /**
     * 清除历史
     */
    fun clearHistory() {
        history.clear()
    }

    /**
     * 获取截断统计
     */
    fun getStatistics(): TruncationStatistics {
        val allHistory = history.values
        val successful = allHistory.count { it.success }
        val failed = allHistory.size - successful
        val avgSteps = if (allHistory.isNotEmpty()) {
            allHistory.map { it.steps }.average()
        } else 0.0

        return TruncationStatistics(
            totalOperations = allHistory.size,
            successful = successful,
            failed = failed,
            averageSteps = avgSteps,
            strategy = strategy
        )
    }

    /**
     * 截断统计信息
     */
    data class TruncationStatistics(
        val totalOperations: Int,
        val successful: Int,
        val failed: Int,
        val averageSteps: Double,
        val strategy: TruncationStrategy
    ) {
        val successRate: Double
            get() = if (totalOperations > 0) {
                successful.toDouble() / totalOperations.toDouble()
            } else 0.0

        override fun toString(): String {
            return """
                |截断统计:
                |  总操作数: $totalOperations
                |  成功: $successful
                |  失败: $failed
                |  成功率: ${"%.2f%%".format(successRate * 100)}
                |  平均步骤: ${"%.2f".format(averageSteps)}
                |  策略: $strategy
            """.trimMargin()
        }
    }
}
