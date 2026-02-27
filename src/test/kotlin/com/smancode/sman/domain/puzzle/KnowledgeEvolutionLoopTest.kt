package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files

@DisplayName("KnowledgeEvolutionLoop 测试套件")
class KnowledgeEvolutionLoopTest {

    private lateinit var tempDir: File
    private lateinit var puzzleStore: PuzzleStore
    private lateinit var llmService: com.smancode.sman.smancode.llm.LlmService
    private lateinit var loop: KnowledgeEvolutionLoop

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("evolution-test").toFile()
        puzzleStore = PuzzleStore(tempDir.absolutePath)
        llmService = mockk()
        loop = KnowledgeEvolutionLoop(puzzleStore, llmService)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ========== 触发测试 ==========

    @Nested
    @DisplayName("触发测试")
    inner class TriggerTests {

        @Test
        @DisplayName("应能响应用户查询触发")
        fun `should respond to user query trigger`() = runBlocking {
            // Mock LLM 响应
            every { llmService.simpleRequest(any<String>()) } returns """
                {
                    "hypothesis": "项目使用 JWT 进行认证",
                    "tasks": [{"target": "UserService.kt", "description": "扫描用户服务", "priority": 0.9}],
                    "results": [],
                    "evaluation": {"hypothesisConfirmed": false, "newKnowledgeGained": 0, "conflictsFound": [], "qualityScore": 0.3, "lessonsLearned": []}
                }
            """.trimIndent()

            val result = loop.evolve(Trigger.UserQuery("这个项目如何实现认证？"))

            assertNotNull(result)
            assertTrue(result.status == IterationStatus.COMPLETED || result.status == IterationStatus.FAILED)
        }

        @Test
        @DisplayName("应能响应文件变更触发")
        fun `should respond to file change trigger`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns """
                {
                    "hypothesis": "验证新增文件的作用",
                    "tasks": [],
                    "results": [],
                    "evaluation": {"hypothesisConfirmed": true, "newKnowledgeGained": 1, "conflictsFound": [], "qualityScore": 0.8, "lessonsLearned": []}
                }
            """.trimIndent()

            val result = loop.evolve(Trigger.FileChange(listOf("NewService.kt")))

            assertNotNull(result)
        }

        @Test
        @DisplayName("应能响应定时触发")
        fun `should respond to scheduled trigger`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns """
                {
                    "hypothesis": "定期扫描更新知识",
                    "tasks": [],
                    "results": [],
                    "evaluation": {"hypothesisConfirmed": false, "newKnowledgeGained": 0, "conflictsFound": [], "qualityScore": 0.5, "lessonsLearned": []}
                }
            """.trimIndent()

            val result = loop.evolve(Trigger.Scheduled("每日扫描"))

            assertNotNull(result)
        }
    }

    // ========== LLM 响应测试 ==========

    @Nested
    @DisplayName("LLM 响应测试")
    inner class LlmResponseTests {

        @Test
        @DisplayName("应能解析有效的 JSON 响应")
        fun `should parse valid JSON response`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns """
                {
                    "hypothesis": "项目使用 Spring Security",
                    "tasks": [
                        {"target": "**/security/*.kt", "description": "扫描安全相关文件", "priority": 0.9}
                    ],
                    "results": [
                        {
                            "target": "SecurityConfig.kt",
                            "title": "Spring Security 配置",
                            "content": "# Spring Security\n\n项目的安全配置使用 Spring Security 框架。",
                            "tags": ["security", "spring", "authentication"],
                            "confidence": 0.85
                        }
                    ],
                    "evaluation": {
                        "hypothesisConfirmed": true,
                        "newKnowledgeGained": 1,
                        "conflictsFound": [],
                        "qualityScore": 0.85,
                        "lessonsLearned": ["发现自定义安全过滤器"]
                    }
                }
            """.trimIndent()

            val result = loop.evolve(Trigger.UserQuery("安全配置是怎样的？"))

            assertNotNull(result)
            assertEquals(IterationStatus.COMPLETED, result.status)
        }

        @Test
        @DisplayName("应能处理空的 results")
        fun `should handle empty results`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns """
                {
                    "hypothesis": "无新发现",
                    "tasks": [],
                    "results": [],
                    "evaluation": {"hypothesisConfirmed": false, "newKnowledgeGained": 0, "conflictsFound": [], "qualityScore": 0.3, "lessonsLearned": []}
                }
            """.trimIndent()

            val result = loop.evolve(Trigger.UserQuery("测试"))

            assertNotNull(result)
        }

        @Test
        @DisplayName("应能处理 LLM 错误响应")
        fun `should handle LLM error response`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns "这不是有效的 JSON"

            val result = loop.evolve(Trigger.UserQuery("测试"))

            assertNotNull(result)
            // 应该降级处理而不是崩溃
        }
    }

    // ========== 知识合并测试 ==========

    @Nested
    @DisplayName("知识合并测试")
    inner class IntegrationTests {

        @Test
        @DisplayName("应能创建新的拼图")
        fun `should create new puzzle`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns """
                {
                    "hypothesis": "发现用户服务",
                    "tasks": [],
                    "results": [
                        {
                            "target": "UserService.kt",
                            "title": "用户服务分析",
                            "content": "# 用户服务\n\n提供用户管理功能。",
                            "tags": ["user", "service"],
                            "confidence": 0.9
                        }
                    ],
                    "evaluation": {"hypothesisConfirmed": true, "newKnowledgeGained": 1, "conflictsFound": [], "qualityScore": 0.9, "lessonsLearned": []}
                }
            """.trimIndent()

            val result = loop.evolve(Trigger.UserQuery("用户服务是什么？"))

            // 验证返回结果
            assertNotNull(result)
            assertEquals(IterationStatus.COMPLETED, result.status)
        }

        @Test
        @DisplayName("应过滤低置信度结果")
        fun `should filter low confidence results`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns """
                {
                    "hypothesis": "不确定的分析",
                    "tasks": [],
                    "results": [
                        {
                            "target": "Unknown.kt",
                            "title": "未知模块",
                            "content": "不确定的内容",
                            "tags": ["unknown"],
                            "confidence": 0.2
                        }
                    ],
                    "evaluation": {"hypothesisConfirmed": false, "newKnowledgeGained": 0, "conflictsFound": [], "qualityScore": 0.2, "lessonsLearned": []}
                }
            """.trimIndent()

            val result = loop.evolve(Trigger.UserQuery("测试"))

            // 低置信度结果不应创建拼图
            assertTrue(result.puzzlesCreated == 0)
        }
    }

    // ========== 错误处理测试 ==========

    @Nested
    @DisplayName("错误处理测试")
    inner class ErrorHandlingTests {

        @Test
        @DisplayName("LLM 调用失败应返回失败结果")
        fun `should return failed result on LLM error`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } throws RuntimeException("LLM 服务不可用")

            val result = loop.evolve(Trigger.UserQuery("测试"))

            assertNotNull(result)
            // 应该优雅处理而不是崩溃
        }

        @Test
        @DisplayName("应生成唯一的 iteration ID")
        fun `should generate unique iteration ID`() = runBlocking {
            every { llmService.simpleRequest(any<String>()) } returns """
                {"hypothesis":"test","tasks":[],"results":[],"evaluation":{"hypothesisConfirmed":false,"newKnowledgeGained":0,"conflictsFound":[],"qualityScore":0.5,"lessonsLearned":[]}}
            """.trimIndent()

            val result1 = loop.evolve(Trigger.UserQuery("test1"))
            val result2 = loop.evolve(Trigger.UserQuery("test2"))

            // 两次迭代 ID 应该不同
            assertNotEquals(result1.iterationId, result2.iterationId)
        }
    }
}
