package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

@DisplayName("LlmAnalyzer 测试套件")
class LlmAnalyzerTest {

    // ========== 接口契约测试 ==========

    @Nested
    @DisplayName("接口契约测试")
    inner class ContractTests {

        @Test
        @DisplayName("analyze 方法应返回 AnalysisResult")
        fun `analyze should return AnalysisResult`() = runBlocking {
            // 使用简单的测试实现验证接口契约
            val analyzer = createTestAnalyzer { _, _ ->
                AnalysisResult(
                    title = "Test Result",
                    content = "# Test\n\nContent",
                    tags = listOf("test"),
                    confidence = 0.8,
                    sourceFiles = listOf("test.kt")
                )
            }

            val context = AnalysisContext.empty()
            val result = analyzer.analyze("test.kt", context)

            assertNotNull(result)
            assertEquals("Test Result", result.title)
        }

        @Test
        @DisplayName("analyze 应接收 target 和 context 参数")
        fun `analyze should accept target and context`() = runBlocking {
            var capturedTarget: String? = null
            var capturedContext: AnalysisContext? = null

            val analyzer = createTestAnalyzer { target, context ->
                capturedTarget = target
                capturedContext = context
                AnalysisResult(
                    title = "Test",
                    content = "",
                    tags = emptyList(),
                    confidence = 0.5,
                    sourceFiles = listOf(target)
                )
            }

            val files = mapOf("User.kt" to "class User")
            val context = AnalysisContext(
                relatedFiles = files,
                existingPuzzles = emptyList(),
                userQuery = "测试查询"
            )

            analyzer.analyze("User.kt", context)

            assertEquals("User.kt", capturedTarget)
            assertNotNull(capturedContext)
            assertEquals("测试查询", capturedContext?.userQuery)
        }
    }

    // ========== 异常处理测试 ==========

    @Nested
    @DisplayName("异常处理测试")
    inner class ExceptionTests {

        @Test
        @DisplayName("当分析失败时应抛出 AnalysisException")
        fun `should throw AnalysisException on failure`() = runBlocking {
            val analyzer = createTestAnalyzer { _, _ ->
                throw AnalysisException("LLM 调用失败")
            }

            assertThrows<AnalysisException> {
                runBlocking {
                    analyzer.analyze("test.kt", AnalysisContext.empty())
                }
            }
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 创建测试用的 Analyzer
     */
    private fun createTestAnalyzer(
        analyzeFn: suspend (String, AnalysisContext) -> AnalysisResult
    ): LlmAnalyzer {
        return object : LlmAnalyzer {
            override suspend fun analyze(target: String, context: AnalysisContext): AnalysisResult {
                return analyzeFn(target, context)
            }
        }
    }
}
