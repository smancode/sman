package com.smancode.sman.tools.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

@DisplayName("LoadPuzzleTool 测试套件")
class LoadPuzzleToolTest {

    private lateinit var puzzleStore: PuzzleStore
    private lateinit var tool: LoadPuzzleTool

    @BeforeEach
    fun setUp() {
        puzzleStore = mockk()
        tool = LoadPuzzleTool(puzzleStore)
    }

    // ========== 基础功能测试 ==========

    @Nested
    @DisplayName("基础功能测试")
    inner class BasicTests {

        @Test
        @DisplayName("应能成功加载存在的拼图")
        fun `should load existing puzzle`() {
            val puzzle = createTestPuzzle("api-user", PuzzleType.API, "# User API\n\n用户管理接口")
            every { puzzleStore.load("api-user") } returns Result.success(puzzle)

            val result = tool.execute("test-project", mapOf("puzzle_id" to "api-user"))

            assertTrue(result.isSuccess)
            assertTrue(result.displayContent?.contains("api-user") == true)
            assertTrue(result.displayContent?.contains("User API") == true)
        }

        @Test
        @DisplayName("拼图不存在应返回失败")
        fun `should fail when puzzle not found`() {
            every { puzzleStore.load("nonexistent") } returns Result.success(null)

            val result = tool.execute("test-project", mapOf("puzzle_id" to "nonexistent"))

            assertFalse(result.isSuccess)
            assertTrue(result.error?.contains("不存在") == true)
        }

        @Test
        @DisplayName("加载失败应返回错误")
        fun `should return error on load failure`() {
            every { puzzleStore.load(any()) } returns Result.failure(RuntimeException("IO Error"))

            val result = tool.execute("test-project", mapOf("puzzle_id" to "test"))

            assertFalse(result.isSuccess)
            assertTrue(result.error?.contains("失败") == true)
        }
    }

    // ========== 参数校验测试 ==========

    @Nested
    @DisplayName("参数校验测试")
    inner class ValidationTests {

        @Test
        @DisplayName("缺少 puzzle_id 应抛异常")
        fun `should throw when puzzle_id missing`() {
            assertThrows<IllegalArgumentException> {
                tool.execute("test-project", emptyMap())
            }
        }

        @Test
        @DisplayName("空 puzzle_id 应抛异常")
        fun `should throw when puzzle_id empty`() {
            assertThrows<IllegalArgumentException> {
                tool.execute("test-project", mapOf("puzzle_id" to ""))
            }
        }
    }

    // ========== 内容格式测试 ==========

    @Nested
    @DisplayName("内容格式测试")
    inner class FormatTests {

        @Test
        @DisplayName("输出应包含拼图元数据")
        fun `should include puzzle metadata`() {
            val puzzle = createTestPuzzle("test-id", PuzzleType.FLOW, "# Test")
            every { puzzleStore.load("test-id") } returns Result.success(puzzle)

            val result = tool.execute("test-project", mapOf("puzzle_id" to "test-id"))

            val content = result.displayContent ?: ""
            assertTrue(content.contains("Type"))
            assertTrue(content.contains("Status"))
            assertTrue(content.contains("Completeness"))
            assertTrue(content.contains("Confidence"))
        }

        @Test
        @DisplayName("输出应包含完整内容")
        fun `should include full content`() {
            val content = "# API 文档\n\n## 接口列表\n\n### GET /users\n获取用户列表"
            val puzzle = createTestPuzzle("api-users", PuzzleType.API, content)
            every { puzzleStore.load("api-users") } returns Result.success(puzzle)

            val result = tool.execute("test-project", mapOf("puzzle_id" to "api-users"))

            assertTrue(result.displayContent?.contains("接口列表") == true)
            assertTrue(result.displayContent?.contains("GET /users") == true)
        }
    }

    // ========== 内容截断测试 ==========

    @Nested
    @DisplayName("内容截断测试")
    inner class TruncationTests {

        @Test
        @DisplayName("超大拼图应被截断")
        fun `should truncate large puzzle`() {
            val largeContent = "a".repeat(10000) // ~4000 tokens
            val puzzle = createTestPuzzle("large", PuzzleType.STRUCTURE, largeContent)
            every { puzzleStore.load("large") } returns Result.success(puzzle)

            val result = tool.execute("test-project", mapOf("puzzle_id" to "large"))

            // 内容应该被截断
            assertTrue(result.displayContent?.length!! < largeContent.length + 500)
            assertTrue(result.displayContent?.contains("截断") == true || result.displayContent?.contains("truncated") == true)
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
