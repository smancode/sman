package com.smancode.sman.tools.puzzle

import com.smancode.sman.domain.puzzle.PuzzleIndexEntry
import com.smancode.sman.domain.puzzle.estimateTokens
import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.tools.AbstractTool
import com.smancode.sman.tools.ParameterDef
import com.smancode.sman.tools.Tool
import com.smancode.sman.tools.ToolResult
import org.slf4j.LoggerFactory

/**
 * LoadPuzzle 工具
 *
 * LLM 通过此工具加载完整的拼图内容。
 * 配合 PuzzleIndexBuilder 的轻量级索引使用：
 * 1. 索引注入到 System Prompt（~500 tokens）
 * 2. LLM 根据需要调用此工具加载完整内容
 *
 * 使用场景：
 * - LLM 在处理用户请求时，发现需要某个拼图的详细信息
 * - 例如：用户问"订单流程是什么"，LLM 先看索引发现有 "flow-order"
 *   然后调用 load_puzzle("flow-order") 获取完整流程文档
 */
class LoadPuzzleTool(
    private val puzzleStore: PuzzleStore
) : AbstractTool(), Tool {

    private val logger = LoggerFactory.getLogger(LoadPuzzleTool::class.java)

    companion object {
        /** 单个拼图最大 Token 数（防止上下文爆炸） */
        private const val MAX_TOKENS_PER_PUZZLE = 2000
    }

    override fun getName(): String = "load_puzzle"

    override fun getDescription(): String = """
        Load full content of a specific puzzle by ID.
        Use this when you need detailed information about a specific puzzle
        that was listed in the Available Project Knowledge table.

        Each puzzle contains structured knowledge about the project:
        - STRUCTURE: Project architecture and module organization
        - TECH_STACK: Technologies, frameworks, and dependencies
        - API: API endpoints and interfaces
        - DATA: Data models and database schemas
        - FLOW: Business flows and call chains
        - RULE: Business rules and constraints
    """.trimIndent()

    override fun getParameters(): Map<String, ParameterDef> = mapOf(
        "puzzle_id" to ParameterDef(
            name = "puzzle_id",
            type = String::class.java,
            required = true,
            description = "The ID of the puzzle to load (e.g., 'api-user', 'flow-order')"
        )
    )

    override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        // 1. 参数校验
        val puzzleId = getReqString(params, "puzzle_id")

        // 2. 加载拼图
        return try {
            val result = puzzleStore.load(puzzleId)

            result.fold(
                onSuccess = { puzzle ->
                    if (puzzle == null) {
                        createFailureResult("拼图不存在: $puzzleId", startTime)
                    } else {
                        val content = formatPuzzleContent(puzzle)
                        val tokenCount = estimateTokens(content)

                        if (tokenCount > MAX_TOKENS_PER_PUZZLE) {
                            logger.warn("拼图内容过长: puzzleId={}, tokens={}", puzzleId, tokenCount)
                            val truncated = truncateContent(content, MAX_TOKENS_PER_PUZZLE)
                            createSuccessResult(truncated, puzzleId, startTime, tokenCount, true)
                        } else {
                            createSuccessResult(content, puzzleId, startTime, tokenCount, false)
                        }
                    }
                },
                onFailure = { e ->
                    logger.error("加载拼图失败: puzzleId={}", puzzleId, e)
                    createFailureResult("加载拼图失败: ${e.message}", startTime)
                }
            )
        } catch (e: Exception) {
            logger.error("LoadPuzzle 执行异常: puzzleId={}", puzzleId, e)
            createFailureResult("执行失败: ${e.message}", startTime)
        }
    }

    /**
     * 格式化拼图内容
     */
    private fun formatPuzzleContent(puzzle: com.smancode.sman.shared.model.Puzzle): String {
        return buildString {
            appendLine("## Puzzle: ${puzzle.id}")
            appendLine()
            appendLine("**Type**: ${puzzle.type.name}")
            appendLine("**Status**: ${puzzle.status.name}")
            appendLine("**Completeness**: ${(puzzle.completeness * 100).toInt()}%")
            appendLine("**Confidence**: ${(puzzle.confidence * 100).toInt()}%")
            appendLine("**Last Updated**: ${puzzle.lastUpdated}")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine(puzzle.content)
        }
    }

    /**
     * 截断内容以符合 Token 限制
     */
    private fun truncateContent(content: String, maxTokens: Int): String {
        val maxChars = (maxTokens / PuzzleIndexEntry.TOKENS_PER_CHAR).toInt()
        return if (content.length <= maxChars) {
            content
        } else {
            content.take(maxChars) + "\n\n... [内容已截断，请使用 search_puzzles 获取更精确的信息]"
        }
    }

    /**
     * 创建成功结果
     */
    private fun createSuccessResult(
        content: String,
        puzzleId: String,
        startTime: Long,
        tokenCount: Int,
        truncated: Boolean
    ): ToolResult {
        val duration = System.currentTimeMillis() - startTime
        val message = if (truncated) {
            "Loaded puzzle: $puzzleId (truncated to ~$MAX_TOKENS_PER_PUZZLE tokens)"
        } else {
            "Loaded puzzle: $puzzleId (~$tokenCount tokens)"
        }

        logger.info("LoadPuzzle 完成: puzzleId={}, tokens={}, time={}ms", puzzleId, tokenCount, duration)

        return ToolResult.success(message, "load_puzzle", content).also {
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
}
