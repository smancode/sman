package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleType
import java.time.Instant

/**
 * 拼图索引条目
 *
 * 轻量级拼图摘要，用于注入到 System Prompt 中，
 * 让 LLM 了解可用的知识拼图，按需加载。
 */
data class PuzzleIndexEntry(
    /** 拼图 ID */
    val id: String,

    /** 拼图类型 */
    val type: PuzzleType,

    /** 标题 */
    val title: String,

    /** 摘要（1-2 句话） */
    val summary: String,

    /** 估算的 Token 数量 */
    val tokenCount: Int,

    /** 关键词列表 */
    val keywords: List<String>,

    /** 完成度 (0.0 - 1.0) */
    val completeness: Double,

    /** 最后更新时间 */
    val lastUpdated: Instant
) {
    companion object {
        /** 摘要最大长度 */
        const val MAX_SUMMARY_LENGTH = 100

        /** Token 估算系数（每字符约 0.4 tokens） */
        const val TOKENS_PER_CHAR = 0.4

        /** 类型优先级（数字越大优先级越高） */
        val TYPE_PRIORITY = mapOf(
            PuzzleType.API to 6,
            PuzzleType.FLOW to 5,
            PuzzleType.RULE to 4,
            PuzzleType.DATA to 3,
            PuzzleType.STRUCTURE to 2,
            PuzzleType.TECH_STACK to 1
        )
    }
}

/**
 * 将 Puzzle 转换为索引条目
 */
fun Puzzle.toIndexEntry(): PuzzleIndexEntry {
    return PuzzleIndexEntry(
        id = this.id,
        type = this.type,
        title = extractTitle(this.content),
        summary = extractSummary(this.content, PuzzleIndexEntry.MAX_SUMMARY_LENGTH),
        tokenCount = estimateTokens(this.content),
        keywords = extractKeywords(this.content),
        completeness = this.completeness,
        lastUpdated = this.lastUpdated
    )
}

/**
 * 从 Markdown 内容提取标题
 */
fun extractTitle(content: String): String {
    val firstLine = content.lines().firstOrNull { it.isNotBlank() } ?: return ""
    return firstLine.removePrefix("#").trim().take(50)
}

/**
 * 从 Markdown 内容提取摘要
 *
 * @param content Markdown 内容
 * @param maxLength 最大长度
 * @return 摘要文本
 */
fun extractSummary(content: String, maxLength: Int): String {
    if (content.isBlank()) return ""

    // 跳过标题行，取正文内容
    val lines = content.lines()
        .dropWhile { it.trim().startsWith("#") || it.isBlank() }
        .take(3)
        .joinToString(" ")
        .trim()

    // 移除 Markdown 标记
    val cleanText = lines
        .replace(Regex("`[^`]+`"), "") // 移除行内代码
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1") // 移除粗体
        .replace(Regex("\\*([^*]+)\\*"), "$1") // 移除斜体
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1") // 移除链接

    return if (cleanText.length <= maxLength) {
        cleanText
    } else {
        cleanText.take(maxLength) + "..."
    }
}

/**
 * 从内容提取关键词
 */
fun extractKeywords(content: String): List<String> {
    // 简单的关键词提取：中文词汇和英文单词
    val words = content
        .lowercase()
        .split(Regex("[\\s,，。.!！?？:：\"\"''\\[\\]()（）\\n\\r\\t]+"))
        .filter { it.length >= 2 && it.length <= 10 }
        .filter { word ->
            // 过滤常见停用词
            !listOf("the", "and", "for", "are", "but", "not", "you", "all", "can", "的", "是", "在", "有", "和", "了", "不", "这")
                .contains(word)
        }
        .distinct()
        .take(10)

    return words
}

/**
 * 估算 Token 数量
 */
fun estimateTokens(content: String): Int {
    return (content.length * PuzzleIndexEntry.TOKENS_PER_CHAR).toInt()
}
