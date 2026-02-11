package com.smancode.sman.analysis.vectorization

import org.slf4j.LoggerFactory
import kotlin.math.max

/**
 * Token 估算器
 *
 * 用于估算文本的 token 数量，避免实际调用 BGE 前就知道会超限。
 *
 * 说明：
 * - BGE-M3 使用与 GPT 类似的分词器
 * - 中文：约 0.5-0.7 token/字符
 * - 英文：约 0.25-0.3 token/字符
 * - 代码：约 0.3-0.4 token/字符
 *
 * 为安全起见，使用保守估算（高估 token 数）
 */
object TokenEstimator {

    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * 估算 token 数量
     *
     * @param text 输入文本
     * @return 估算的 token 数（保守估计，可能偏高）
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0

        // 统计不同类型的字符
        var chineseChars = 0
        var englishChars = 0
        var digits = 0
        var whitespace = 0
        var other = 0

        for (char in text) {
            when {
                char.code in 0x4E00..0x9FFF -> chineseChars++  // 中文
                char.code in 0x0041..0x005A || char.code in 0x0061..0x007A -> englishChars++  // 英文
                char.code in 0x0030..0x0039 -> digits++  // 数字
                char.isWhitespace() -> whitespace++  // 空白
                else -> other++  // 其他（符号、标点等）
            }
        }

        // 保守估算（高估 token 数）
        val chineseTokens = (chineseChars * 0.8).toInt()  // 中文：0.8 token/字符（高估）
        val englishTokens = ((englishChars + digits) / 4.0).toInt()  // 英文+数字：0.25 token/字符
        val whitespaceTokens = whitespace / 2  // 空白：0.5 token/字符
        val otherTokens = other  // 其他：1 token/字符

        return chineseTokens + englishTokens + whitespaceTokens + otherTokens
    }

    /**
     * 估算字符数限制（基于 token 限制）
     *
     * @param maxTokens 最大 token 数
     * @param textSample 文本样本（用于估算文本类型）
     * @return 建议的字符数限制
     */
    fun estimateCharLimit(maxTokens: Int, textSample: String? = null): Int {
        // 保守估算：假设平均 0.5 token/字符
        // 纯中文：~0.7 token/字符 → 1 token ≈ 1.4 字符
        // 纯英文：~0.3 token/字符 → 1 token ≈ 3.3 字符
        // 代码：~0.4 token/字符 → 1 token ≈ 2.5 字符
        // 混合：~0.5 token/字符 → 1 token ≈ 2 字符

        return if (textSample != null && textSample.isNotEmpty()) {
            val estimatedTokens = estimateTokens(textSample)
            if (estimatedTokens > 0) {
                // 根据样本计算
                val ratio = textSample.length.toDouble() / estimatedTokens.toDouble()
                ((maxTokens * ratio) * 0.9).toInt()  // 90% 安全余量
            } else {
                maxTokens * 2  // 默认 1 token = 2 字符
            }
        } else {
            maxTokens * 2  // 默认 1 token = 2 字符
        }
    }

    /**
     * 检查文本是否超过 token 限制
     *
     * @param text 输入文本
     * @param maxTokens 最大 token 数
     * @return true 如果超过限制
     */
    fun exceedsLimit(text: String, maxTokens: Int): Boolean {
        return estimateTokens(text) > maxTokens
    }

    /**
     * 超过的 token 数量
     *
     * @param text 输入文本
     * @param maxTokens 最大 token 数
     * @return 超过的 token 数（负数表示未超过）
     */
    fun excessTokens(text: String, maxTokens: Int): Int {
        return estimateTokens(text) - maxTokens
    }

    /**
     * 估算截断后的文本长度
     *
     * @param text 原始文本
     * @param maxTokens 最大 token 数
     * @return 建议截断到的字符数
     */
    fun estimateTruncatedLength(text: String, maxTokens: Int): Int {
        if (text.isEmpty()) return 0

        val estimatedTokens = estimateTokens(text)
        if (estimatedTokens <= maxTokens) {
            return text.length
        }

        val ratio = maxTokens.toDouble() / estimatedTokens.toDouble()
        return (text.length * ratio * 0.95).toInt()  // 95% 安全余量
    }

    /**
     * 获取文本统计信息
     *
     * @param text 输入文本
     * @return 文本统计信息
     */
    fun getTextStats(text: String): TextStats {
        var chineseChars = 0
        var englishChars = 0
        var digits = 0
        var whitespace = 0
        var other = 0
        var lines = 1

        for (char in text) {
            when {
                char.code in 0x4E00..0x9FFF -> chineseChars++
                char.code in 0x0041..0x005A || char.code in 0x0061..0x007A -> englishChars++
                char.code in 0x0030..0x0039 -> digits++
                char.isWhitespace() -> whitespace++
                char == '\n' -> lines++
                else -> other++
            }
        }

        return TextStats(
            totalLength = text.length,
            chineseChars = chineseChars,
            englishChars = englishChars,
            digits = digits,
            whitespace = whitespace,
            other = other,
            lines = lines,
            estimatedTokens = estimateTokens(text)
        )
    }

    /**
     * 文本统计信息
     */
    data class TextStats(
        val totalLength: Int,
        val chineseChars: Int,
        val englishChars: Int,
        val digits: Int,
        val whitespace: Int,
        val other: Int,
        val lines: Int,
        val estimatedTokens: Int
    ) {
        override fun toString(): String {
            return """
                |文本统计:
                |  总长度: $totalLength 字符
                |  中文: $chineseChars 字符
                |  英文: $englishChars 字符
                |  数字: $digits 字符
                |  空白: $whitespace 字符
                |  其他: $other 字符
                |  行数: $lines
                |  估算 Token: $estimatedTokens
            """.trimMargin()
        }
    }
}
