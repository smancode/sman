package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleType
import org.slf4j.LoggerFactory

/**
 * Puzzle 上下文注入器
 *
 * 负责将项目知识（Puzzle）注入到 LLM 上下文中：
 * - 根据查询选择相关的 Puzzle
 * - 格式化为 System Prompt
 * - 支持 Token 预算控制
 */
class PuzzleContextInjector(
    private val puzzleStore: PuzzleStore
) {
    private val logger = LoggerFactory.getLogger(PuzzleContextInjector::class.java)

    companion object {
        /** 每个字符大约的 Token 数（中文约 0.5，英文约 0.25） */
        private const val TOKENS_PER_CHAR = 0.4

        /** Puzzle 类型优先级（API > FLOW > RULE > DATA > STRUCTURE > TECH_STACK） */
        private val TYPE_PRIORITY = mapOf(
            PuzzleType.API to 6,
            PuzzleType.FLOW to 5,
            PuzzleType.RULE to 4,
            PuzzleType.DATA to 3,
            PuzzleType.STRUCTURE to 2,
            PuzzleType.TECH_STACK to 1
        )
    }

    /**
     * 注入上下文
     *
     * @param query 用户查询
     * @param maxTokens 最大 Token 预算
     * @return 格式化的上下文字符串
     */
    fun inject(query: String, maxTokens: Int): String {
        val puzzles = selectPuzzles(query, maxPuzzles = 10)
        if (puzzles.isEmpty()) {
            return ""
        }

        return truncateToBudget(puzzles, maxTokens)
    }

    /**
     * 根据查询选择相关的 Puzzle
     *
     * @param query 用户查询
     * @param maxPuzzles 最大返回数量
     * @return 相关的 Puzzle 列表
     */
    fun selectPuzzles(query: String, maxPuzzles: Int): List<Puzzle> {
        val allPuzzles = puzzleStore.loadAll().getOrNull() ?: emptyList()
        if (allPuzzles.isEmpty()) {
            return emptyList()
        }

        // 提取查询关键词
        val queryKeywords = extractKeywords(query)

        // 计算每个 Puzzle 的相关性分数
        val scored = allPuzzles.map { puzzle ->
            val puzzleKeywords = extractKeywords(puzzle.content)
            val overlap = queryKeywords.intersect(puzzleKeywords).size
            val typePriority = TYPE_PRIORITY[puzzle.type] ?: 0
            val score = overlap * 10 + typePriority + (puzzle.confidence * 5).toInt()
            puzzle to score
        }

        // 按分数排序，返回 top N
        return scored
            .sortedByDescending { it.second }
            .take(maxPuzzles)
            .map { it.first }
    }

    /**
     * 格式化为上下文
     *
     * @param puzzles Puzzle 列表
     * @return 格式化的字符串
     */
    fun formatAsContext(puzzles: List<Puzzle>): String {
        if (puzzles.isEmpty()) return ""

        val sections = puzzles.map { puzzle ->
            """
            ## ${getTypeLabel(puzzle.type)}: ${puzzle.id}

            ${puzzle.content.trim()}
            """.trimIndent()
        }

        return """
            # 项目知识

            以下是项目相关的知识，请在回答时参考：

            ${sections.joinToString("\n\n")}
        """.trimIndent()
    }

    /**
     * 估算 Token 数量
     *
     * @param text 文本内容
     * @return 估算的 Token 数
     */
    fun estimateTokens(text: String): Int {
        return (text.length * TOKENS_PER_CHAR).toInt()
    }

    /**
     * 截断到预算内
     *
     * @param puzzles Puzzle 列表
     * @param maxTokens 最大 Token 数
     * @return 截断后的上下文字符串
     */
    fun truncateToBudget(puzzles: List<Puzzle>, maxTokens: Int): String {
        val result = StringBuilder()
        var currentTokens = 0

        for (puzzle in puzzles) {
            val section = formatPuzzleSection(puzzle)
            val sectionTokens = estimateTokens(section)

            if (currentTokens + sectionTokens <= maxTokens) {
                result.append(section).append("\n\n")
                currentTokens += sectionTokens
            } else {
                // 尝试截断内容
                val remainingTokens = maxTokens - currentTokens
                if (remainingTokens > 100) {
                    val truncatedContent = truncateContent(puzzle.content, remainingTokens - 50)
                    result.append(formatPuzzleSection(puzzle.copy(content = truncatedContent)))
                }
                break
            }
        }

        return result.toString().trim()
    }

    // 私有方法

    private fun extractKeywords(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[\\s,，。.!！?？:：\"\"''\\[\\]()（）]+"))
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun getTypeLabel(type: PuzzleType): String {
        return when (type) {
            PuzzleType.STRUCTURE -> "项目结构"
            PuzzleType.TECH_STACK -> "技术栈"
            PuzzleType.API -> "API 接口"
            PuzzleType.DATA -> "数据模型"
            PuzzleType.FLOW -> "业务流程"
            PuzzleType.RULE -> "业务规则"
        }
    }

    private fun formatPuzzleSection(puzzle: Puzzle): String {
        return """
            ## ${getTypeLabel(puzzle.type)}: ${puzzle.id}

            ${puzzle.content.trim()}
        """.trimIndent()
    }

    private fun truncateContent(content: String, maxTokens: Int): String {
        val maxChars = (maxTokens / TOKENS_PER_CHAR).toInt()
        if (content.length <= maxChars) {
            return content
        }
        return content.take(maxChars) + "\n... (已截断)"
    }
}
