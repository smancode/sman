package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

@DisplayName("分析模型测试套件")
class AnalysisModelTest {

    // ========== AnalysisContext 测试 ==========

    @Nested
    @DisplayName("AnalysisContext 测试")
    inner class AnalysisContextTests {

        @Test
        @DisplayName("应能创建空的上下文")
        fun `should create empty context`() {
            val context = AnalysisContext(
                relatedFiles = emptyMap(),
                existingPuzzles = emptyList(),
                userQuery = null
            )

            assertTrue(context.relatedFiles.isEmpty())
            assertTrue(context.existingPuzzles.isEmpty())
            assertNull(context.userQuery)
        }

        @Test
        @DisplayName("应能创建包含文件内容的上下文")
        fun `should create context with files`() {
            val files = mapOf(
                "src/main/kotlin/User.kt" to "class User(val name: String)",
                "src/main/kotlin/Order.kt" to "class Order(val id: String)"
            )

            val context = AnalysisContext(
                relatedFiles = files,
                existingPuzzles = emptyList(),
                userQuery = null
            )

            assertEquals(2, context.relatedFiles.size)
            assertTrue(context.relatedFiles.containsKey("src/main/kotlin/User.kt"))
        }

        @Test
        @DisplayName("应能创建包含用户查询的上下文")
        fun `should create context with user query`() {
            val context = AnalysisContext(
                relatedFiles = emptyMap(),
                existingPuzzles = emptyList(),
                userQuery = "这个项目的用户认证是怎么实现的？"
            )

            assertEquals("这个项目的用户认证是怎么实现的？", context.userQuery)
        }

        @Test
        @DisplayName("应能创建包含已有拼图的上下文")
        fun `should create context with existing puzzles`() {
            val puzzle = Puzzle(
                id = "test-puzzle",
                type = PuzzleType.STRUCTURE,
                status = PuzzleStatus.COMPLETED,
                content = "# Test",
                completeness = 0.8,
                confidence = 0.9,
                lastUpdated = Instant.now(),
                filePath = ".sman/puzzles/test.md"
            )

            val context = AnalysisContext(
                relatedFiles = emptyMap(),
                existingPuzzles = listOf(puzzle),
                userQuery = null
            )

            assertEquals(1, context.existingPuzzles.size)
            assertEquals("test-puzzle", context.existingPuzzles[0].id)
        }
    }

    // ========== AnalysisResult 测试 ==========

    @Nested
    @DisplayName("AnalysisResult 测试")
    inner class AnalysisResultTests {

        @Test
        @DisplayName("应能创建分析结果")
        fun `should create analysis result`() {
            val result = AnalysisResult(
                title = "用户模块分析",
                content = "# 用户模块\n\n包含用户认证和授权逻辑。",
                tags = listOf("user", "auth", "security"),
                confidence = 0.85,
                sourceFiles = listOf("src/main/kotlin/User.kt")
            )

            assertEquals("用户模块分析", result.title)
            assertTrue(result.content.contains("用户模块"))
            assertEquals(3, result.tags.size)
            assertEquals(0.85, result.confidence)
            assertEquals(1, result.sourceFiles.size)
        }

        @Test
        @DisplayName("置信度应在有效范围内")
        fun `confidence should be in valid range`() {
            val result = AnalysisResult(
                title = "Test",
                content = "Content",
                tags = emptyList(),
                confidence = 0.5,
                sourceFiles = emptyList()
            )

            assertTrue(result.confidence in 0.0..1.0)
        }

        @Test
        @DisplayName("应能创建最小结果")
        fun `should create minimal result`() {
            val result = AnalysisResult(
                title = "Minimal",
                content = "",
                tags = emptyList(),
                confidence = 0.0,
                sourceFiles = emptyList()
            )

            assertEquals("Minimal", result.title)
            assertTrue(result.tags.isEmpty())
        }
    }
}
