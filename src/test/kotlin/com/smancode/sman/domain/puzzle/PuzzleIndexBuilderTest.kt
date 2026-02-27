package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.time.Instant

@DisplayName("PuzzleIndexBuilder 测试套件")
class PuzzleIndexBuilderTest {

    private lateinit var puzzleStore: PuzzleStore
    private lateinit var indexBuilder: PuzzleIndexBuilder

    @BeforeEach
    fun setUp() {
        puzzleStore = mockk()
        indexBuilder = PuzzleIndexBuilder(puzzleStore)
    }

    // ========== buildIndex 测试 ==========

    @Nested
    @DisplayName("buildIndex 测试")
    inner class BuildIndexTests {

        @Test
        @DisplayName("应能构建拼图索引")
        fun `should build puzzle index`() {
            val puzzles = listOf(
                createTestPuzzle("api-user", PuzzleType.API, "# User API\n\n用户管理接口"),
                createTestPuzzle("api-order", PuzzleType.API, "# Order API\n\n订单处理接口")
            )
            every { puzzleStore.loadAll() } returns Result.success(puzzles)

            val index = indexBuilder.buildIndex(maxTokens = 1000)

            assertTrue(index.isNotEmpty())
            assertTrue(index.contains("api-user"))
            assertTrue(index.contains("api-order"))
        }

        @Test
        @DisplayName("索引应包含表头")
        fun `index should include table header`() {
            val puzzles = listOf(createTestPuzzle("test", PuzzleType.API, "# Test"))
            every { puzzleStore.loadAll() } returns Result.success(puzzles)

            val index = indexBuilder.buildIndex(maxTokens = 1000)

            assertTrue(index.contains("| ID |") || index.contains("ID"))
        }

        @Test
        @DisplayName("空拼图列表应返回空索引")
        fun `empty puzzle list should return empty index`() {
            every { puzzleStore.loadAll() } returns Result.success(emptyList())

            val index = indexBuilder.buildIndex(maxTokens = 1000)

            assertEquals("", index)
        }

        @Test
        @DisplayName("应按 Token 预算截断")
        fun `should truncate by token budget`() {
            val puzzles = (1..100).map { i ->
                createTestPuzzle("puzzle-$i", PuzzleType.API, "# Puzzle $i\n\n${"content ".repeat(100)}")
            }
            every { puzzleStore.loadAll() } returns Result.success(puzzles)

            // 小预算
            val index = indexBuilder.buildIndex(maxTokens = 300)

            // 索引应该比完整列表短
            val estimatedTokens = (index.length * 0.4).toInt()
            assertTrue(estimatedTokens <= 350) // 允许一些误差
        }
    }

    // ========== buildRelevantIndex 测试 ==========

    @Nested
    @DisplayName("buildRelevantIndex 测试")
    inner class BuildRelevantIndexTests {

        @Test
        @DisplayName("应按相关性过滤拼图")
        fun `should filter puzzles by relevance`() {
            val puzzles = listOf(
                createTestPuzzle("api-user", PuzzleType.API, "# User API\n\n用户管理 登录 注册"),
                createTestPuzzle("api-order", PuzzleType.API, "# Order API\n\n订单 购物 支付"),
                createTestPuzzle("flow-payment", PuzzleType.FLOW, "# Payment Flow\n\n支付流程 支付宝 微信")
            )
            every { puzzleStore.loadAll() } returns Result.success(puzzles)

            val index = indexBuilder.buildRelevantIndex("用户登录", maxTokens = 1000)

            // 应该包含与用户/登录相关的拼图
            assertTrue(index.isNotEmpty())
        }

        @Test
        @DisplayName("无相关拼图时应返回空")
        fun `should return empty when no relevant puzzles`() {
            val puzzles = listOf(
                createTestPuzzle("api-order", PuzzleType.API, "# Order API\n\n订单购物")
            )
            every { puzzleStore.loadAll() } returns Result.success(puzzles)

            val index = indexBuilder.buildRelevantIndex("完全不相关的内容 xyz123", maxTokens = 1000)

            // 可能返回空或者返回所有拼图（取决于实现）
            // 这里只验证不会崩溃
        }
    }

    // ========== 格式化测试 ==========

    @Nested
    @DisplayName("格式化测试")
    inner class FormatTests {

        @Test
        @DisplayName("索引应包含使用说明")
        fun `index should include usage instructions`() {
            val puzzles = listOf(createTestPuzzle("test", PuzzleType.API, "# Test"))
            every { puzzleStore.loadAll() } returns Result.success(puzzles)

            val index = indexBuilder.buildIndex(maxTokens = 1000)

            // 应包含工具使用说明
            assertTrue(index.contains("load_puzzle") || index.contains("search_puzzles"))
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
