package com.smancode.sman.domain.puzzle

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@DisplayName("DefaultLlmAnalyzer 测试套件")
class DefaultLlmAnalyzerTest {

    private lateinit var llmService: com.smancode.sman.smancode.llm.LlmService
    private lateinit var analyzer: DefaultLlmAnalyzer

    @BeforeEach
    fun setUp() {
        llmService = mockk()
        analyzer = DefaultLlmAnalyzer(llmService)
    }

    // ========== 基础分析测试 ==========

    @Nested
    @DisplayName("基础分析测试")
    inner class BasicAnalysisTests {

        @Test
        @DisplayName("应能调用 LLM 服务并返回结果")
        fun `should call LLM service and return result`() = runBlocking {
            // Mock LLM 响应
            val llmResponse = """
                ### TITLE
                用户模块分析

                ### TAGS
                user, auth, spring

                ### CONFIDENCE
                0.85

                ### CONTENT
                # 用户模块分析

                该模块包含用户认证和授权逻辑。
            """.trimIndent()

            // Mock 单参数版本（DefaultLlmAnalyzer 使用的）
            every { llmService.simpleRequest(any<String>()) } returns llmResponse

            val context = AnalysisContext.empty()
            val result = analyzer.analyze("src/main/kotlin/User.kt", context)

            assertNotNull(result)
            assertEquals("用户模块分析", result.title)
            assertTrue(result.tags.contains("user"))
            assertEquals(0.85, result.confidence)
            assertTrue(result.content.contains("用户模块"))
        }

        @Test
        @DisplayName("应将 target 路径包含在 Prompt 中")
        fun `should include target path in prompt`() = runBlocking {
            var capturedPrompt: String? = null
            every {
                llmService.simpleRequest(any())
            } answers {
                capturedPrompt = firstArg() as String
                createDefaultResponse()
            }

            analyzer.analyze("src/main/kotlin/auth/Login.kt", AnalysisContext.empty())

            assertTrue(capturedPrompt?.contains("src/main/kotlin/auth/Login.kt") == true)
        }

        @Test
        @DisplayName("应将文件内容包含在 Prompt 中")
        fun `should include file content in prompt`() = runBlocking {
            var capturedPrompt: String? = null
            every {
                llmService.simpleRequest(any())
            } answers {
                capturedPrompt = firstArg() as String
                createDefaultResponse()
            }

            val files = mapOf("User.kt" to "class User(val name: String)")
            val context = AnalysisContext(relatedFiles = files, existingPuzzles = emptyList())

            analyzer.analyze("User.kt", context)

            assertTrue(capturedPrompt?.contains("class User(val name: String)") == true)
        }
    }

    // ========== 响应解析测试 ==========

    @Nested
    @DisplayName("响应解析测试")
    inner class ResponseParsingTests {

        @Test
        @DisplayName("应正确解析 TITLE")
        fun `should parse TITLE correctly`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns """
                ### TITLE
                测试标题

                ### TAGS
                test

                ### CONFIDENCE
                0.5

                ### CONTENT
                内容
            """.trimIndent()

            val result = analyzer.analyze("test.kt", AnalysisContext.empty())

            assertEquals("测试标题", result.title)
        }

        @Test
        @DisplayName("应正确解析 TAGS")
        fun `should parse TAGS correctly`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns """
                ### TITLE
                Test

                ### TAGS
                spring, boot, kotlin, api

                ### CONFIDENCE
                0.5

                ### CONTENT
                内容
            """.trimIndent()

            val result = analyzer.analyze("test.kt", AnalysisContext.empty())

            assertEquals(4, result.tags.size)
            assertTrue(result.tags.contains("spring"))
            assertTrue(result.tags.contains("kotlin"))
        }

        @Test
        @DisplayName("应正确解析 CONFIDENCE")
        fun `should parse CONFIDENCE correctly`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns """
                ### TITLE
                Test

                ### TAGS
                test

                ### CONFIDENCE
                0.75

                ### CONTENT
                内容
            """.trimIndent()

            val result = analyzer.analyze("test.kt", AnalysisContext.empty())

            assertEquals(0.75, result.confidence)
        }

        @Test
        @DisplayName("应正确解析 CONTENT")
        fun `should parse CONTENT correctly`() = runBlocking {
            val expectedContent = """
                # 项目分析

                ## 技术栈
                - Kotlin
                - Spring Boot
            """.trimIndent()

            every { llmService.simpleRequest(any<String>()) } returns """
                ### TITLE
                Test

                ### TAGS
                test

                ### CONFIDENCE
                0.5

                ### CONTENT
                $expectedContent
            """.trimIndent()

            val result = analyzer.analyze("test.kt", AnalysisContext.empty())

            assertTrue(result.content.contains("技术栈"))
            assertTrue(result.content.contains("Kotlin"))
        }
    }

    // ========== 容错测试 ==========

    @Nested
    @DisplayName("容错测试")
    inner class FaultToleranceTests {

        @Test
        @DisplayName("缺少 TITLE 时应使用默认值")
        fun `should use default title when missing`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns """
                ### TAGS
                test

                ### CONFIDENCE
                0.5

                ### CONTENT
                内容
            """.trimIndent()

            val result = analyzer.analyze("test.kt", AnalysisContext.empty())

            assertNotNull(result.title)
            assertTrue(result.title.isNotEmpty())
        }

        @Test
        @DisplayName("缺少 CONFIDENCE 时应使用默认值")
        fun `should use default confidence when missing`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns """
                ### TITLE
                Test

                ### TAGS
                test

                ### CONTENT
                内容
            """.trimIndent()

            val result = analyzer.analyze("test.kt", AnalysisContext.empty())

            assertTrue(result.confidence >= 0.0)
            assertTrue(result.confidence <= 1.0)
        }

        @Test
        @DisplayName("LLM 调用失败时应抛出 AnalysisException")
        fun `should throw AnalysisException on LLM failure`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } throws RuntimeException("LLM 错误")

            assertThrows<AnalysisException> {
                runBlocking {
                    analyzer.analyze("test.kt", AnalysisContext.empty())
                }
            }
        }

        @Test
        @DisplayName("sourceFiles 应包含分析的文件")
        fun `should include source files in result`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns createDefaultResponse()

            val result = analyzer.analyze("src/User.kt", AnalysisContext.empty())

            assertTrue(result.sourceFiles.contains("src/User.kt"))
        }
    }

    // ========== 辅助方法 ==========

    private fun createDefaultResponse(): String {
        return """
            ### TITLE
            Default

            ### TAGS
            test

            ### CONFIDENCE
            0.5

            ### CONTENT
            Default content
        """.trimIndent()
    }
}
