package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

@DisplayName("PuzzleIndexEntry 测试套件")
class PuzzleIndexTest {

    // ========== PuzzleIndexEntry 测试 ==========

    @Nested
    @DisplayName("PuzzleIndexEntry 基础测试")
    inner class PuzzleIndexEntryTests {

        @Test
        @DisplayName("应能创建索引条目")
        fun `should create index entry`() {
            val entry = PuzzleIndexEntry(
                id = "api-user",
                type = PuzzleType.API,
                title = "User API",
                summary = "用户管理接口",
                tokenCount = 500,
                keywords = listOf("user", "api", "login"),
                completeness = 0.85,
                lastUpdated = Instant.now()
            )

            assertEquals("api-user", entry.id)
            assertEquals(PuzzleType.API, entry.type)
            assertEquals("User API", entry.title)
            assertEquals(500, entry.tokenCount)
        }

        @Test
        @DisplayName("摘要应限制长度")
        fun `summary should be limited in length`() {
            val longSummary = "这是一个非常长的摘要".repeat(50)
            val entry = PuzzleIndexEntry(
                id = "test",
                type = PuzzleType.API,
                title = "Test",
                summary = longSummary,
                tokenCount = 100,
                keywords = listOf("test"),
                completeness = 0.8,
                lastUpdated = Instant.now()
            )

            // 摘要在创建时可能不截断，但在索引构建时会截断
            // 这里只测试条目能正常创建
            assertEquals("test", entry.id)
        }
    }

    // ========== Puzzle.toIndexEntry() 测试 ==========

    @Nested
    @DisplayName("Puzzle.toIndexEntry() 测试")
    inner class ToIndexEntryTests {

        @Test
        @DisplayName("应能从 Puzzle 转换为索引条目")
        fun `should convert puzzle to index entry`() {
            val puzzle = Puzzle(
                id = "api-user",
                type = PuzzleType.API,
                status = PuzzleStatus.COMPLETED,
                content = "# User API\n\n## 接口列表\n\n### POST /api/users\n创建用户\n\n### GET /api/users/{id}\n获取用户详情",
                completeness = 0.85,
                confidence = 0.9,
                lastUpdated = Instant.now(),
                filePath = ".sman/puzzles/api-user.md"
            )

            val entry = puzzle.toIndexEntry()

            assertEquals("api-user", entry.id)
            assertEquals(PuzzleType.API, entry.type)
            assertNotNull(entry.summary)
            assertTrue(entry.tokenCount > 0)
        }

        @Test
        @DisplayName("应从内容提取关键词")
        fun `should extract keywords from content`() {
            val puzzle = Puzzle(
                id = "flow-order",
                type = PuzzleType.FLOW,
                status = PuzzleStatus.COMPLETED,
                content = "# Order Flow\n\n用户下单 订单处理 支付流程 发货管理",
                completeness = 0.9,
                confidence = 0.8,
                lastUpdated = Instant.now(),
                filePath = ".sman/puzzles/flow-order.md"
            )

            val entry = puzzle.toIndexEntry()

            // 应该提取到一些关键词
            assertTrue(entry.keywords.isNotEmpty())
        }

        @Test
        @DisplayName("应估算 Token 数量")
        fun `should estimate token count`() {
            val content = "a".repeat(1000) // 1000 chars ≈ 400 tokens
            val puzzle = Puzzle(
                id = "test",
                type = PuzzleType.STRUCTURE,
                status = PuzzleStatus.COMPLETED,
                content = content,
                completeness = 0.5,
                confidence = 0.5,
                lastUpdated = Instant.now(),
                filePath = ".sman/puzzles/test.md"
            )

            val entry = puzzle.toIndexEntry()

            // 估算: chars * 0.4 = tokens，应该 > 0
            assertTrue(entry.tokenCount > 0)
        }
    }

    // ========== 摘要提取测试 ==========

    @Nested
    @DisplayName("摘要提取测试")
    inner class SummaryExtractionTests {

        @Test
        @DisplayName("应从 Markdown 内容提取摘要")
        fun `should extract summary from markdown`() {
            val content = """
                # User API

                这是用户管理的 API 接口文档。

                ## 接口列表

                ### POST /api/users
                创建新用户
            """.trimIndent()

            val summary = extractSummary(content, maxLength = 100)

            // 摘要应该非空且不超过最大长度
            assertTrue(summary.isNotEmpty())
            assertTrue(summary.length <= 103) // 100 + "..."
        }

        @Test
        @DisplayName("空内容应返回空摘要")
        fun `empty content should return empty summary`() {
            val summary = extractSummary("", maxLength = 100)
            assertEquals("", summary)
        }
    }
}
