package com.smancode.sman.tools.puzzle

import com.smancode.sman.domain.puzzle.PuzzleIndexBuilder
import com.smancode.sman.domain.puzzle.PuzzleIndexEntry
import com.smancode.sman.domain.puzzle.estimateTokens
import com.smancode.sman.domain.puzzle.extractKeywords
import com.smancode.sman.domain.puzzle.extractSummary
import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.tools.AbstractTool
import com.smancode.sman.tools.ParameterDef
import com.smancode.sman.tools.Tool
import com.smancode.sman.tools.ToolResult
import org.slf4j.LoggerFactory

/**
 * SearchPuzzles 工具
 *
 * LLM 通过此工具搜索相关拼图。
 * 支持两种模式：
 * 1. 关键词匹配：基于 extractKeywords 提取的关键词进行匹配
 * 2. 相关性排序：按类型优先级 + 关键词重叠度排序
 *
 * 注意：当前版本使用关键词匹配，未来可扩展为向量语义搜索。
 */
class SearchPuzzlesTool(
    private val puzzleStore: PuzzleStore,
    private val indexBuilder: PuzzleIndexBuilder
) : AbstractTool(), Tool {

    private val logger = LoggerFactory.getLogger(SearchPuzzlesTool::class.java)

    companion object {
        /** 默认返回数量 */
        private const val DEFAULT_TOP_K = 5
    }

    override fun getName(): String = "search_puzzles"

    override fun getDescription(): String = """
        Search for relevant puzzles by query.
        Returns a list of matching puzzles with their summaries.

        Use this when:
        - You need to find puzzles related to a specific topic
        - The puzzle index doesn't clearly indicate which puzzle to load
        - You want to discover relevant knowledge before loading full content

        The search uses keyword matching and type-based ranking.
        Results are ordered by relevance score.
    """.trimIndent()

    override fun getParameters(): Map<String, ParameterDef> = mapOf(
        "query" to ParameterDef(
            name = "query",
            type = String::class.java,
            required = true,
            description = "Search query describing what you're looking for"
        ),
        "top_k" to ParameterDef(
            name = "top_k",
            type = Int::class.java,
            required = false,
            description = "Maximum number of results to return (default: 5)",
            defaultValue = DEFAULT_TOP_K
        )
    )

    override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        // 1. 参数校验
        val query = getReqString(params, "query")
        val topK = getOptInt(params, "top_k", DEFAULT_TOP_K)

        // 2. 加载并搜索拼图
        return try {
            val loadResult = puzzleStore.loadAll()

            if (loadResult.isFailure) {
                logger.error("加载拼图失败: query={}", query, loadResult.exceptionOrNull())
                return createFailureResult("加载拼图失败: ${loadResult.exceptionOrNull()?.message}", startTime)
            }

            val puzzles = loadResult.getOrThrow()

            if (puzzles.isEmpty()) {
                createEmptyResult(query, startTime)
            } else {
                val results = searchPuzzles(puzzles, query, topK)
                if (results.isEmpty()) {
                    createNoMatchResult(query, startTime)
                } else {
                    createSuccessResult(query, results, startTime)
                }
            }
        } catch (e: Exception) {
            logger.error("SearchPuzzles 执行异常: query={}", query, e)
            createFailureResult("搜索失败: ${e.message}", startTime)
        }
    }

    /**
     * 搜索拼图
     */
    private fun searchPuzzles(puzzles: List<Puzzle>, query: String, topK: Int): List<SearchResult> {
        val queryKeywords = extractKeywords(query)

        return puzzles.map { puzzle ->
            val puzzleKeywords = extractKeywords(puzzle.content)
            val overlap = queryKeywords.intersect(puzzleKeywords.toSet()).size
            val typePriority = PuzzleIndexEntry.TYPE_PRIORITY[puzzle.type] ?: 0
            val score = overlap * 10 + typePriority

            SearchResult(
                puzzle = puzzle,
                score = score,
                keywordOverlap = overlap
            )
        }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * 格式化搜索结果
     */
    private fun formatResults(query: String, results: List<SearchResult>): String {
        return buildString {
            appendLine("## Puzzle Search Results")
            appendLine()
            appendLine("**Query**: $query")
            appendLine("**Found**: ${results.size} relevant puzzle(s)")
            appendLine()
            appendLine("---")
            appendLine()

            results.forEachIndexed { index, result ->
                val puzzle = result.puzzle
                val tokenEstimate = estimateTokens(puzzle.content)

                appendLine("### ${index + 1}. ${puzzle.id}")
                appendLine("- **Type**: ${puzzle.type.name}")
                appendLine("- **Relevance Score**: ${result.score} (keyword overlap: ${result.keywordOverlap})")
                appendLine("- **Completeness**: ${(puzzle.completeness * 100).toInt()}%")
                appendLine("- **Estimated Tokens**: ~$tokenEstimate")
                appendLine("- **Summary**: ${extractSummary(puzzle.content, PuzzleIndexEntry.MAX_SUMMARY_LENGTH)}")
                appendLine()
                appendLine("Use `load_puzzle(\"${puzzle.id}\")` to get full content.")
                appendLine()
            }
        }
    }

    /**
     * 创建成功结果
     */
    private fun createSuccessResult(query: String, results: List<SearchResult>, startTime: Long): ToolResult {
        val content = formatResults(query, results)
        val duration = System.currentTimeMillis() - startTime

        logger.info("SearchPuzzles 完成: query={}, results={}, time={}ms", query, results.size, duration)

        return ToolResult.success("Found ${results.size} puzzles", "search_puzzles", content).also {
            it.executionTimeMs = duration
        }
    }

    /**
     * 创建空结果
     */
    private fun createEmptyResult(query: String, startTime: Long): ToolResult {
        val duration = System.currentTimeMillis() - startTime
        val content = """
            ## Puzzle Search Results

            **Query**: $query
            **Found**: 0 puzzles

            No puzzles are available in this project yet.
            Puzzles are generated automatically when the system analyzes the project.
        """.trimIndent()

        return ToolResult.success("No puzzles available", "search_puzzles", content).also {
            it.executionTimeMs = duration
        }
    }

    /**
     * 创建无匹配结果
     */
    private fun createNoMatchResult(query: String, startTime: Long): ToolResult {
        val duration = System.currentTimeMillis() - startTime
        val content = """
            ## Puzzle Search Results

            **Query**: $query
            **Found**: 0 matching puzzles

            No puzzles match your query. Try:
            - Using different keywords
            - Checking the puzzle index for available puzzles
            - Loading puzzles directly if you know the ID
        """.trimIndent()

        return ToolResult.success("No matching puzzles", "search_puzzles", content).also {
            it.executionTimeMs = duration
        }
    }

    /**
     * 创建失败结果
     */
    private fun createFailureResult(message: String, startTime: Long): ToolResult {
        val duration = System.currentTimeMillis() - startTime
        return ToolResult.failure(message).also {
            it.executionTimeMs = duration
        }
    }

    /**
     * 搜索结果
     */
    private data class SearchResult(
        val puzzle: Puzzle,
        val score: Int,
        val keywordOverlap: Int
    )
}
