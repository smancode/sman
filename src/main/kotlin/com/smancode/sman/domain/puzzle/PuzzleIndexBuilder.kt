package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.PuzzleType
import org.slf4j.LoggerFactory

/**
 * 拼图索引构建器
 *
 * 构建轻量级拼图索引，用于注入到 System Prompt 中。
 * 索引包含拼图的 ID、类型、摘要、Token 估算，
 * 让 LLM 决定是否需要加载完整内容。
 */
class PuzzleIndexBuilder(
    private val puzzleStore: PuzzleStore
) {
    private val logger = LoggerFactory.getLogger(PuzzleIndexBuilder::class.java)

    companion object {
        /** 类型显示名称 */
        private val TYPE_LABELS = mapOf(
            PuzzleType.STRUCTURE to "结构",
            PuzzleType.TECH_STACK to "技术栈",
            PuzzleType.API to "API",
            PuzzleType.DATA to "数据",
            PuzzleType.FLOW to "流程",
            PuzzleType.RULE to "规则"
        )
    }

    /**
     * 构建拼图索引
     *
     * @param maxTokens 最大 Token 数
     * @return 格式化的索引字符串
     */
    fun buildIndex(maxTokens: Int = 500): String {
        val puzzles = puzzleStore.loadAll().getOrNull() ?: emptyList()
        if (puzzles.isEmpty()) {
            logger.debug("没有可用的拼图")
            return ""
        }

        val entries = puzzles.map { it.toIndexEntry() }
            .sortedByDescending { PuzzleIndexEntry.TYPE_PRIORITY[it.type] ?: 0 }

        return formatIndex(entries, maxTokens)
    }

    /**
     * 构建相关拼图索引
     *
     * @param query 用户查询
     * @param maxTokens 最大 Token 数
     * @return 格式化的索引字符串
     */
    fun buildRelevantIndex(query: String, maxTokens: Int = 500): String {
        val puzzles = puzzleStore.loadAll().getOrNull() ?: emptyList()
        if (puzzles.isEmpty()) {
            return ""
        }

        val queryKeywords = extractKeywords(query)

        val scoredEntries = puzzles.map { puzzle ->
            val entry = puzzle.toIndexEntry()
            val overlap = queryKeywords.intersect(entry.keywords.toSet()).size
            val typePriority = PuzzleIndexEntry.TYPE_PRIORITY[entry.type] ?: 0
            val score = overlap * 10 + typePriority
            entry to score
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }

        if (scoredEntries.isEmpty()) {
            // 无相关拼图，返回通用索引
            return buildIndex(maxTokens)
        }

        return formatIndex(scoredEntries.map { it.first }, maxTokens)
    }

    /**
     * 格式化索引
     */
    private fun formatIndex(entries: List<PuzzleIndexEntry>, maxTokens: Int): String {
        if (entries.isEmpty()) return ""

        val header = """
## Available Project Knowledge (Puzzles)

The following puzzle pieces are available. Use `load_puzzle` tool to get full content.

| ID | Type | Summary | Tokens |
|----|------|---------|--------|
""".trimIndent()

        val sb = StringBuilder(header)
        var currentTokens = estimateTokens(header)

        for (entry in entries) {
            val row = formatRow(entry)
            val rowTokens = estimateTokens(row)

            if (currentTokens + rowTokens > maxTokens) {
                break
            }

            sb.append("\n").append(row)
            currentTokens += rowTokens
        }

        sb.append("\n\n**Usage**: Call `load_puzzle(puzzle_id)` to get full content, or `search_puzzles(query)` for semantic search.")

        return sb.toString()
    }

    /**
     * 格式化索引行
     */
    private fun formatRow(entry: PuzzleIndexEntry): String {
        val typeLabel = TYPE_LABELS[entry.type] ?: entry.type.name
        return "| ${entry.id} | $typeLabel | ${entry.summary.take(50)} | ~${entry.tokenCount} |"
    }

}
