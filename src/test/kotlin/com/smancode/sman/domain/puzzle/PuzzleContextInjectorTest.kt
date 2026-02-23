package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.PuzzleType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

@DisplayName("PuzzleContextInjector 测试套件")
class PuzzleContextInjectorTest {

    private lateinit var puzzleStore: PuzzleStore
    private lateinit var injector: PuzzleContextInjector

    @BeforeEach
    fun setUp() {
        puzzleStore = mockk(relaxed = true)
        injector = PuzzleContextInjector(puzzleStore)
    }

    // ========== Puzzle 选择测试 ==========

    @Nested
    @DisplayName("Puzzle 选择测试")
    inner class PuzzleSelectionTests {

        @Test
        @DisplayName("selectPuzzles - 应根据查询选择相关 Puzzle")
        fun `selectPuzzles should select relevant puzzles based on query`() {
            val apiPuzzle = createTestPuzzle(
                id = "api-user",
                type = PuzzleType.API,
                content = "# UserController\n处理用户相关请求。"
            )
            val dataPuzzle = createTestPuzzle(
                id = "data-order",
                type = PuzzleType.DATA,
                content = "# Order\n订单数据模型。"
            )

            every { puzzleStore.loadAll() } returns Result.success(listOf(apiPuzzle, dataPuzzle))

            val selected = injector.selectPuzzles("用户接口如何工作？", maxPuzzles = 3)

            assertTrue(selected.isNotEmpty())
            // API 类型的 Puzzle 应该被优先选择
            assertTrue(selected.any { it.type == PuzzleType.API })
        }

        @Test
        @DisplayName("selectPuzzles - 应限制返回数量")
        fun `selectPuzzles should limit return count`() {
            val puzzles = (1..10).map { i ->
                createTestPuzzle(id = "puzzle-$i", type = PuzzleType.API)
            }
            every { puzzleStore.loadAll() } returns Result.success(puzzles)

            val selected = injector.selectPuzzles("查询", maxPuzzles = 3)

            assertTrue(selected.size <= 3)
        }

        @Test
        @DisplayName("selectPuzzles - 空 Puzzle 列表应返回空")
        fun `selectPuzzles should return empty for empty puzzle list`() {
            every { puzzleStore.loadAll() } returns Result.success(emptyList())

            val selected = injector.selectPuzzles("查询", maxPuzzles = 3)

            assertTrue(selected.isEmpty())
        }
    }

    // ========== 格式化测试 ==========

    @Nested
    @DisplayName("格式化测试")
    inner class FormattingTests {

        @Test
        @DisplayName("formatAsContext - 应格式化为 System Prompt")
        fun `formatAsContext should format as system prompt`() {
            val puzzle = createTestPuzzle(
                id = "api-user",
                type = PuzzleType.API,
                content = "# UserController\n\n## 端点\n- GET /api/users"
            )

            val context = injector.formatAsContext(listOf(puzzle))

            assertTrue(context.contains("项目知识"))
            assertTrue(context.contains("UserController"))
            assertTrue(context.contains("GET /api/users"))
        }

        @Test
        @DisplayName("formatAsContext - 多个 Puzzle 应合并")
        fun `formatAsContext should merge multiple puzzles`() {
            val puzzle1 = createTestPuzzle(id = "api-1", content = "API 内容 1")
            val puzzle2 = createTestPuzzle(id = "api-2", content = "API 内容 2")

            val context = injector.formatAsContext(listOf(puzzle1, puzzle2))

            assertTrue(context.contains("API 内容 1"))
            assertTrue(context.contains("API 内容 2"))
        }

        @Test
        @DisplayName("formatAsContext - 空列表应返回空字符串")
        fun `formatAsContext should return empty string for empty list`() {
            val context = injector.formatAsContext(emptyList())

            assertEquals("", context)
        }
    }

    // ========== Token 预算测试 ==========

    @Nested
    @DisplayName("Token 预算测试")
    inner class TokenBudgetTests {

        @Test
        @DisplayName("estimateTokens - 应估算 Token 数量")
        fun `estimateTokens should estimate token count`() {
            val content = "This is a test content with multiple words"

            val tokens = injector.estimateTokens(content)

            assertTrue(tokens > 0)
        }

        @Test
        @DisplayName("truncateToBudget - 应截断到预算内")
        fun `truncateToBudget should truncate to budget`() {
            val puzzle = createTestPuzzle(
                id = "long",
                content = "这是一段很长的内容，包含了大量的文字。".repeat(100)
            )

            val truncated = injector.truncateToBudget(listOf(puzzle), maxTokens = 100)

            // 返回的是字符串，验证 Token 数量
            assertTrue(injector.estimateTokens(truncated) <= 120) // 允许 20% 误差
        }
    }

    // ========== 完整注入测试 ==========

    @Nested
    @DisplayName("完整注入测试")
    inner class FullInjectionTests {

        @Test
        @DisplayName("inject - 应返回完整的上下文")
        fun `inject should return complete context`() {
            val puzzle = createTestPuzzle(
                id = "api-user",
                type = PuzzleType.API,
                content = "# UserController\n用户控制器。"
            )
            every { puzzleStore.loadAll() } returns Result.success(listOf(puzzle))

            val context = injector.inject("用户接口", maxTokens = 2000)

            assertNotNull(context)
            assertTrue(context.contains("UserController"))
        }

        @Test
        @DisplayName("inject - 无 Puzzle 时应返回空字符串")
        fun `inject should return empty string when no puzzles`() {
            every { puzzleStore.loadAll() } returns Result.success(emptyList())

            val context = injector.inject("查询", maxTokens = 2000)

            assertEquals("", context)
        }
    }

    // ========== 辅助方法 ==========

    private fun createTestPuzzle(
        id: String = "test-puzzle",
        type: PuzzleType = PuzzleType.API,
        content: String = "Test content",
        completeness: Double = 0.8,
        confidence: Double = 0.9
    ): com.smancode.sman.shared.model.Puzzle {
        return com.smancode.sman.shared.model.Puzzle(
            id = id,
            type = type,
            status = com.smancode.sman.shared.model.PuzzleStatus.COMPLETED,
            content = content,
            completeness = completeness,
            confidence = confidence,
            lastUpdated = Instant.now(),
            filePath = ".sman/puzzles/$id.md"
        )
    }
}
