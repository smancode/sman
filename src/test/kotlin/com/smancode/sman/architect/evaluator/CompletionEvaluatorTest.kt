package com.smancode.sman.architect.evaluator

import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.architect.model.ArchitectGoal
import com.smancode.sman.architect.model.EvaluationResult
import com.smancode.sman.model.message.Message
import com.smancode.sman.model.part.TextPart
import com.smancode.sman.model.part.ToolPart
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * CompletionEvaluator 防呆测试
 *
 * 重点测试内容质量检测能力
 */
@DisplayName("CompletionEvaluator 防呆测试")
class CompletionEvaluatorTest {

    private lateinit var evaluator: CompletionEvaluator
    private val mockLlmService = mockk<com.smancode.sman.smancode.llm.LlmService>(relaxed = true)

    @BeforeEach
    fun setUp() {
        evaluator = CompletionEvaluator(mockLlmService)
    }

    // ==================== 内容质量检测测试 ====================

    @Nested
    @DisplayName("内容质量检测测试")
    inner class ContentQualityCheckTest {

        @Test
        @DisplayName("只有思考块的内容应该被拒绝")
        fun `should reject content with only thinking block`() {
            // Given: 只有思考块的内容
            val content = """
                <think&gt;
                用户要求我作为架构师分析项目结构。这是一个复杂的分析任务...
                &lt;/think&gt;
            """.trimIndent()

            // 创建带工具调用的响应（内容质量检测需要工具调用）
            val response = createMockResponse(content, hasToolCalls = true)
            val goal = ArchitectGoal.fromType(AnalysisType.PROJECT_STRUCTURE)

            // When
            val result = evaluator.evaluate(goal, response)

            // Then
            assertFalse(result.isComplete)
            assertTrue(result.confidence < 0.5, "低置信度应该表示内容质量差")
            assertTrue(result.followUpQuestions.isNotEmpty(), "应该有追问提示")
        }

        @Test
        @DisplayName("问候语内容应该被拒绝")
        fun `should reject greeting content`() {
            // Given: 问候语内容
            val content = """
                你好！我是 Sman，Java 代码分析助手。

                请告诉我你的需求，例如：
                - "帮我分析项目的整体结构"
                - "查找放款相关的代码在哪里"
            """.trimIndent()

            // 创建带工具调用的响应（内容质量检测需要工具调用）
            val response = createMockResponse(content, hasToolCalls = true)
            val goal = ArchitectGoal.fromType(AnalysisType.TECH_STACK)

            // When
            val result = evaluator.evaluate(goal, response)

            // Then
            assertFalse(result.isComplete)
            assertTrue(result.confidence < 0.5)
            assertTrue(result.followUpQuestions.isNotEmpty())
        }

        @Test
        @DisplayName("有效的分析内容应该被接受")
        fun `should accept valid analysis content`() {
            // Given: 有效的分析内容
            val content = """
                ## 项目模块概览

                | 模块 | 业务含义 | 主要组件 |
                |------|----------|----------|
                | **common** | 通用模块 | DTO、Config |
                | **core** | 核心服务层 | 目录扫描、报告生成 |

                ## 工具调用记录

                - **find_file**: 找到 3 个配置文件
                - **read_file**: 读取 settings.gradle
            """.trimIndent()

            // 创建带工具调用的响应
            val response = createMockResponse(content, hasToolCalls = true)
            val goal = ArchitectGoal.fromType(AnalysisType.PROJECT_STRUCTURE)

            // Mock LLM 评估响应
            every { mockLlmService.simpleRequest(any(), any()) } returns """
                {
                    "completeness": 0.8,
                    "isComplete": true,
                    "summary": "分析完成",
                    "todos": [],
                    "followUpQuestions": [],
                    "confidence": 0.9
                }
            """.trimIndent()

            // When
            val result = evaluator.evaluate(goal, response)

            // Then
            // 内容应该通过质量检测，进入 LLM 评估
            assertTrue(result.confidence >= 0.5, "有效内容的置信度应该较高")
        }

        @Test
        @DisplayName("过短的内容应该被拒绝")
        fun `should reject too short content`() {
            // Given: 过短的内容
            val content = "【分析问题】开始识别项目技术栈。"

            // 创建带工具调用的响应（内容质量检测需要工具调用）
            val response = createMockResponse(content, hasToolCalls = true)
            val goal = ArchitectGoal.fromType(AnalysisType.TECH_STACK)

            // When
            val result = evaluator.evaluate(goal, response)

            // Then
            assertFalse(result.isComplete)
            assertTrue(result.confidence < 0.5)
        }

        @Test
        @DisplayName("没有结构化格式的短内容应该被拒绝")
        fun `should reject unstructured short content`() {
            // Given: 没有结构的短内容
            val content = "这是一个 Java 项目，使用了 Spring Boot 框架。"

            // 创建带工具调用的响应（内容质量检测需要工具调用）
            val response = createMockResponse(content, hasToolCalls = true)
            val goal = ArchitectGoal.fromType(AnalysisType.CONFIG_FILES)

            // When
            val result = evaluator.evaluate(goal, response)

            // Then
            assertFalse(result.isComplete)
            assertTrue(result.confidence < 0.5)
        }

        @Test
        @DisplayName("空内容应该返回失败")
        fun `should return failure for empty content`() {
            // Given: 空内容（没有工具调用）
            val response = createMockResponse("", hasToolCalls = false)
            val goal = ArchitectGoal.fromType(AnalysisType.API_ENTRIES)

            // When
            val result = evaluator.evaluate(goal, response)

            // Then
            assertFalse(result.isComplete)
            assertEquals(0.0, result.completeness)
            // 没有工具调用时会被拒绝
            assertTrue(result.summary.contains("代码扫描") || result.summary.contains("工具"))
        }

        @Test
        @DisplayName("只有工具调用没有分析文字应该被拒绝")
        fun `should reject response with only tool calls but no analysis`() {
            // Given: 只有工具调用，没有分析文字
            val response = createMockResponse("", hasToolCalls = true)
            val goal = ArchitectGoal.fromType(AnalysisType.API_ENTRIES)

            // When
            val result = evaluator.evaluate(goal, response)

            // Then
            assertFalse(result.isComplete)
            // 工具调用会被转换为文本，所以实际上是"只有工具调用记录"的短内容
            assertTrue(result.confidence < 0.5)
        }

        @Test
        @DisplayName("没有工具调用的响应应该被拒绝")
        fun `should reject response without tool calls`() {
            // Given: 内容有效但没有工具调用
            val content = """
                ## 项目模块概览

                | 模块 | 业务含义 | 主要组件 |
                |------|----------|----------|
                | **common** | 通用模块 | DTO、Config |
            """.trimIndent()

            // 创建没有工具调用的响应
            val response = createMockResponse(content, hasToolCalls = false)
            val goal = ArchitectGoal.fromType(AnalysisType.PROJECT_STRUCTURE)

            // When
            val result = evaluator.evaluate(goal, response)

            // Then
            assertFalse(result.isComplete)
            assertEquals(0.0, result.completeness)
            assertTrue(result.summary.contains("代码扫描") || result.summary.contains("工具"))
        }
    }

    // ==================== 辅助方法 ====================

    private fun createMockResponse(content: String, hasToolCalls: Boolean = false): Message {
        val message = Message()
        message.id = java.util.UUID.randomUUID().toString()
        message.sessionId = "test-session"

        if (content.isNotEmpty()) {
            val textPart = TextPart().apply {
                this.text = content
            }
            message.parts.add(textPart)
        }

        // 如果需要工具调用，添加 ToolPart
        if (hasToolCalls) {
            val toolPart = ToolPart().apply {
                toolName = "find_file"
                summary = "查找到配置文件"
            }
            message.parts.add(toolPart)
        }

        return message
    }
}
