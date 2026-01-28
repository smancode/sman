package com.smancode.smanagent.smancode.core

import com.smancode.smanagent.model.part.Part
import com.smancode.smanagent.model.part.TextPart
import com.smancode.smanagent.model.part.ToolPart

/**
 * Token 估算器
 *
 * 粗略估算 Part 的 Token 数量
 *
 * 参考 OpenCode: 4 字符 ≈ 1 token
 */
object TokenEstimator {
    private const val CHARS_PER_TOKEN = 4

    /**
     * 估算 Part 的 Token 数量
     */
    fun estimate(part: Part): Int {
        return when (part) {
            is TextPart -> estimateText(part.text)
            is ToolPart -> estimateToolPart(part)
            else -> 100  // 其他类型默认 100 tokens
        }
    }

    /**
     * 估算文本的 Token 数量
     */
    fun estimateText(text: String?): Int {
        if (text.isNullOrEmpty()) {
            return 0
        }
        return maxOf(0, (text.length.toFloat() / CHARS_PER_TOKEN).toInt())
    }

    /**
     * 估算工具 Part 的 Token 数量
     */
    private fun estimateToolPart(toolPart: ToolPart): Int {
        var total = 0

        // 工具名称和参数
        total += estimateText(toolPart.toolName)
        total += 50  // 参数开销

        // 结果数据
        val result = toolPart.result
        if (result != null && result.data != null) {
            val data = result.data.toString()
            total += estimateText(data)
        }

        return total
    }

    /**
     * 估算字符串的 Token 数量
     */
    fun estimate(str: String?): Int {
        return estimateText(str)
    }

    /**
     * 计算压缩率
     */
    fun compressionRatio(originalTokens: Int, compressedTokens: Int): Double {
        if (originalTokens == 0) {
            return 0.0
        }
        return compressedTokens.toDouble() / originalTokens
    }

    /**
     * 格式化 Token 数量
     */
    fun format(tokens: Int): String {
        return when {
            tokens < 1_000 -> "$tokens tokens"
            tokens < 1_000_000 -> String.format("%.1fK tokens", tokens / 1_000.0)
            else -> String.format("%.1fM tokens", tokens / 1_000_000.0)
        }
    }
}
