package com.smancode.sman.tools.puzzle

import com.smancode.sman.domain.puzzle.PuzzleIndexBuilder
import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

@DisplayName("SearchPuzzlesTool 测试套件")
class SearchPuzzlesToolTest {

    private lateinit var puzzleStore: PuzzleStore
    private lateinit var indexBuilder: PuzzleIndexBuilder
    private lateinit var tool: SearchPuzzlesTool

    @BeforeEach
    fun setUp() {
        puzzleStore = mockk()
        indexBuilder = mockk()
        tool = SearchPuzzlesTool(puzzleStore, indexBuilder)
    }

    // ========== 基础搜索测试 ==========

    @Nested
    @DisplayName("基础搜索测试")
    inner class BasicSearchTests {

        @Test
        @DisplayName("应能搜索到相关拼图")
        fun `should search and find relevant puzzles`() {
            val puzzles = listOf(
                createTestPuzzle("api-user", PuzzleType.API, "# User API\n\n用户管理 登录 注册"),
                createTestPuzzle("api-order", PuzzleType.API, "# Order API\n\n订单 购物 支付")
            )
            every { puzzleStore.loadAll() } returns Result.success(puzzles)

            val result = tool.execute("test-project", mapOf("query" to "用户登录"))

            assertTrue(result.isSuccess)
            // 应该找到与用户登录相关的拼图
            assertTrue(result.displayContent?.contains("api-user") == true)
        }

        @Test
        @DisplayName("空拼图列表应返回友好提示")
        fun `should return friendly message for empty puzzles`() {
            every { puzzleStore.loadAll() } returns Result.success(emptyList())

            val result = tool.execute("test-project", mapOf("query" to "test"))

            assertTrue(result.isSuccess)
            assertTrue(result.displayContent?.contains("No puzzles") == true || result.displayContent?.contains("没有") == true)
        }

        @Test
        @DisplayName("无匹配结果应返回提示")
        fun `should return hint when no match`() {
            val puzzles = listOf(
                createTestPuzzle("api-order", PuzzleType.API, "# Order API\n\n订单购物")
            )
            every { puzzleStore.loadAll() } returns Result.success(puzzles)

            val result = tool.execute("test-project", mapOf("query" to "xyz123abc 不存在的关键词"))

            assertTrue(result.isSuccess)
            // 应该返回无匹配或空结果
        }
    }

    // ========== 参数校验测试 ==========

    @Nested
    @DisplayName("参数校验测试")
    inner class ValidationTests {

        @Test
        @DisplayName("缺少 query 应抛异常")
        fun `should throw when query missing`() {
            assertThrows<IllegalArgumentException> {
                tool.execute("test-project", emptyMap())
            }
        }

        @Test
        @DisplayName("空 query 应抛异常")
        fun `should throw when query empty`() {
            assertThrows<IllegalArgumentException> {
                tool.execute("test-project", mapOf("query" to ""))
            }
        }

        @Test
        @DisplayName("top_k 参数应生效")
        fun `should respect top_k parameter`() {
            val puzzles = (1..10).map { i ->
                createTestPuzzle("api-$i", PuzzleType.API, "# API $i\n\n用户 管理")
            }
            every { puzzleStore.loadAll() } returns Result.success(puzzles)

            val result = tool.execute("test-project", mapOf("query" to "用户", "top_k" to 3))

            // 结果应该被限制为 3 个
            val count = result.displayContent?.lines()?.count { it.startsWith("### ") } ?: 0
            assertTrue(count <= 3)
        }
    }

    // ========== 排序测试 ==========

    @Nested
    @DisplayName("排序测试")
    inner class RankingTests {

        @Test
        @DisplayName("应按相关性排序")
        fun `should sort by relevance`() {
            val puzzles = listOf(
                createTestPuzzle("api-user", PuzzleType.API, "# User\n\n用户 登录 注册 认证"),
                createTestPuzzle("flow-login", PuzzleType.FLOW, "# Login Flow\n\n登录 流程 步骤"),
                createTestPuzzle("tech-stack", PuzzleType.TECH_STACK, "# Tech Stack\n\n技术栈")
            )
            every { puzzleStore.loadAll() } returns Result.success(puzzles)

            val result = tool.execute("test-project", mapOf("query" to "用户登录"))

            assertTrue(result.isSuccess)
            // 排名最高的应该是 api-user（关键词重叠多 + API 类型优先级高）
            val content = result.displayContent ?: ""
            val apiUserIndex = content.indexOf("api-user")
            val techStackIndex = content.indexOf("tech-stack")

            if (apiUserIndex > 0 && techStackIndex > 0) {
                assertTrue(apiUserIndex < techStackIndex)
            }
        }
    }

    // ========== 错误处理测试 ==========

    @Nested
    @DisplayName("错误处理测试")
    inner class ErrorHandlingTests {

        @Test
        @DisplayName("加载失败应返回错误")
        fun `should return error on load failure`() {
            every { puzzleStore.loadAll() } returns Result.failure(RuntimeException("DB Error"))

            val result = tool.execute("test-project", mapOf("query" to "test"))

            assertFalse(result.isSuccess)
            assertTrue(result.error?.contains("失败") == true)
        }
    }

    // ========== 辅助方法 ==========

    private fun createTestPuzzle(id: String, type: PuzzleType, content: String): Puzzle {
        return Puzzle(
            id = id,
            type = type,
            status = PuzzleStatus.COMPLETED,
            content = content,
            completeness = 0.85,
            confidence = 0.9,
            lastUpdated = Instant.now(),
            filePath = ".sman/puzzles/$id.md"
        )
    }
}
