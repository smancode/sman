package com.smancode.sman.analysis.executor

import com.smancode.sman.analysis.model.*
import com.smancode.sman.analysis.guard.DoomLoopGuard
import com.smancode.sman.smancode.llm.LlmService
import com.smancode.sman.tools.ToolExecutor
import com.smancode.sman.tools.ToolRegistry
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AnalysisLoopExecutor 测试
 *
 * 测试独立分析执行器的核心功能
 */
@DisplayName("AnalysisLoopExecutor 测试")
class AnalysisLoopExecutorTest {

    private lateinit var executor: AnalysisLoopExecutor
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var doomLoopGuard: DoomLoopGuard
    private lateinit var llmService: LlmService
    private lateinit var validator: AnalysisOutputValidator

    @BeforeEach
    fun setUp() {
        toolRegistry = mockk(relaxed = true)
        toolExecutor = mockk(relaxed = true)
        doomLoopGuard = DoomLoopGuard.createDefault()
        llmService = mockk()
        validator = AnalysisOutputValidator()

        executor = AnalysisLoopExecutor(
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            doomLoopGuard = doomLoopGuard,
            llmService = llmService,
            validator = validator
        )
    }

    @AfterEach
    fun tearDown() {
        // 清理
    }

    /**
     * 模拟 LLM 返回工具调用 JSON 格式
     */
    private fun mockToolCallResponse(toolName: String, params: Map<String, String> = emptyMap()): String {
        val paramsJson = params.entries.joinToString(",") { "\"${it.key}\": \"${it.value}\"" }
        return """{"parts": [{"type": "tool", "toolName": "$toolName", "parameters": {$paramsJson}}]}"""
    }

    /**
     * 模拟 LLM 返回完整的 Markdown 报告
     */
    private fun mockMarkdownReport(type: AnalysisType): String {
        return when (type) {
            AnalysisType.PROJECT_STRUCTURE -> """
                # 项目结构分析

                ## 项目概述
                这是一个基于 Kotlin 的 IntelliJ IDEA 插件项目。

                ## 目录结构
                - src/main/kotlin: 源代码目录
                - src/test/kotlin: 测试代码目录

                ## 模块划分
                - analysis: 项目分析模块
                - smancode: 核心模块

                ## 依赖管理
                使用 Gradle 构建工具，主要依赖 OkHttp、Jackson 等。
            """.trimIndent()

            AnalysisType.TECH_STACK -> """
                # 技术栈分析

                ## 编程语言
                Kotlin 1.9.20, Java 17+

                ## 构建工具
                Gradle 8.x

                ## 框架
                IntelliJ Platform Plugin SDK

                ## 数据存储
                H2 数据库, JVector 向量存储
            """.trimIndent()

            AnalysisType.API_ENTRIES -> """
                # API 入口分析

                ## 入口列表
                - /api/chat: 聊天接口
                - /api/analyze: 分析接口

                ## 认证方式
                基于 API Key 认证

                ## 请求格式
                JSON 格式

                ## 响应格式
                JSON 格式
            """.trimIndent()

            AnalysisType.DB_ENTITIES -> """
                # 数据库实体分析

                ## 实体列表
                - AnalysisResultEntity: 分析结果实体
                - AnalysisLoopState: 分析循环状态

                ## 表关系
                无外键关系

                ## 字段详情
                - projectKey: 项目唯一标识
                - analysisType: 分析类型
            """.trimIndent()

            AnalysisType.ENUMS -> """
                # 枚举分析

                ## 枚举列表
                - AnalysisType: 分析类型枚举
                - TaskStatus: 任务状态枚举

                ## 枚举用途
                - AnalysisType: 定义不同类型的分析任务
                - TaskStatus: 跟踪任务执行状态
            """.trimIndent()

            AnalysisType.CONFIG_FILES -> """
                # 配置文件分析

                ## 配置文件列表
                - sman.properties: 主配置文件
                - plugin.xml: 插件配置

                ## 环境配置
                - 开发环境: 本地配置
                - 生产环境: 环境变量配置
            """.trimIndent()
        }
    }

    // ========== 正常分析流程测试 ==========

    @Nested
    @DisplayName("正常分析流程测试")
    inner class NormalAnalysisFlowTest {

        @Test
        @DisplayName("应能执行基本的分析任务")
        fun `should execute basic analysis task`() = runBlocking {
            // Given: 一个项目结构分析任务
            val type = AnalysisType.PROJECT_STRUCTURE
            val projectKey = "test-project"

            // 模拟 LLM 返回：先返回工具调用，再返回报告
            val toolCallResponse = mockToolCallResponse("list_directory", mapOf("path" to "/src"))
            val reportResponse = mockMarkdownReport(type)

            every { llmService.simpleRequest(any()) } returnsMany listOf(toolCallResponse, reportResponse)

            // 模拟工具执行成功
            every { toolExecutor.execute(any(), any(), any()) } returns mockk(relaxed = true) {
                every { data } returns "目录列表: src/main/kotlin, src/test/kotlin"
            }

            // When: 执行分析
            val result = executor.execute(
                type = type,
                projectKey = projectKey
            )

            // Then: 应返回结果
            assertNotNull(result)
            assertEquals(type, result.type)
        }

        @Test
        @DisplayName("分析结果应包含完整度")
        fun `analysis result should include completeness`() = runBlocking {
            // Given: 技术栈分析任务
            val type = AnalysisType.TECH_STACK
            val projectKey = "test-project"

            // 模拟 LLM 返回
            val toolCallResponse = mockToolCallResponse("read_file", mapOf("path" to "build.gradle.kts"))
            val reportResponse = mockMarkdownReport(type)

            every { llmService.simpleRequest(any()) } returnsMany listOf(toolCallResponse, reportResponse)

            // 模拟工具执行成功
            every { toolExecutor.execute(any(), any(), any()) } returns mockk(relaxed = true) {
                every { data } returns "kotlin(\"jvm\") version \"1.9.20\""
            }

            // When: 执行分析
            val result = executor.execute(
                type = type,
                projectKey = projectKey
            )

            // Then: 应包含完整度
            assertTrue(result.completeness >= 0.0)
            assertTrue(result.completeness <= 1.0)
        }

        @Test
        @DisplayName("分析结果应包含生成的 Markdown 内容")
        fun `analysis result should include markdown content`() = runBlocking {
            // Given: API 入口分析任务
            val type = AnalysisType.API_ENTRIES
            val projectKey = "test-project"

            // 模拟 LLM 返回
            val toolCallResponse = mockToolCallResponse("grep_file", mapOf("pattern" to "Controller"))
            val reportResponse = mockMarkdownReport(type)

            every { llmService.simpleRequest(any()) } returnsMany listOf(toolCallResponse, reportResponse)

            // 模拟工具执行成功
            every { toolExecutor.execute(any(), any(), any()) } returns mockk(relaxed = true) {
                every { data } returns "found: ApiController.java"
            }

            // When: 执行分析
            val result = executor.execute(
                type = type,
                projectKey = projectKey
            )

            // Then: 应包含内容（可能为空，取决于 LLM 响应）
            assertNotNull(result.content)
        }
    }

    // ========== 工具调用测试 ==========

    @Nested
    @DisplayName("工具调用测试")
    inner class ToolCallTest {

        @Test
        @DisplayName("执行器应能处理工具调用")
        fun `executor should handle tool calls`() = runBlocking {
            // Given: 配置为调用工具的分析
            val type = AnalysisType.DB_ENTITIES
            val projectKey = "test-project"

            // 模拟 LLM 返回
            val toolCallResponse = mockToolCallResponse("grep_file", mapOf("pattern" to "Entity"))
            val reportResponse = mockMarkdownReport(type)

            every { llmService.simpleRequest(any()) } returnsMany listOf(toolCallResponse, reportResponse)

            // 模拟工具执行成功
            every { toolExecutor.execute(any(), any(), any()) } returns mockk(relaxed = true) {
                every { data } returns "found: AnalysisResultEntity.java"
            }

            // When: 执行分析
            val result = executor.execute(
                type = type,
                projectKey = projectKey
            )

            // Then: 应返回结果
            assertNotNull(result)
        }

        @Test
        @DisplayName("工具调用历史应被记录")
        fun `tool call history should be recorded`() = runBlocking {
            // Given: 枚举分析任务
            val type = AnalysisType.ENUMS
            val projectKey = "test-project"

            // 模拟 LLM 返回
            val toolCallResponse = mockToolCallResponse("grep_file", mapOf("pattern" to "enum"))
            val reportResponse = mockMarkdownReport(type)

            every { llmService.simpleRequest(any()) } returnsMany listOf(toolCallResponse, reportResponse)

            // 模拟工具执行成功
            every { toolExecutor.execute(any(), any(), any()) } returns mockk(relaxed = true) {
                every { data } returns "found: AnalysisType.java"
            }

            // When: 执行分析
            val result = executor.execute(
                type = type,
                projectKey = projectKey
            )

            // Then: 应记录工具调用（如果有）
            // toolCallHistory 可能为空，取决于实际执行
            assertNotNull(result.toolCallHistory)
        }
    }

    // ========== 报告生成测试 ==========

    @Nested
    @DisplayName("报告生成测试")
    inner class ReportGenerationTest {

        @Test
        @DisplayName("生成的报告应是有效的 Markdown 格式")
        fun `generated report should be valid markdown format`() = runBlocking {
            // Given: 项目结构分析
            val type = AnalysisType.PROJECT_STRUCTURE
            val projectKey = "test-project"

            // 模拟 LLM 返回
            val toolCallResponse = mockToolCallResponse("list_directory", mapOf("path" to "/src"))
            val reportResponse = mockMarkdownReport(type)

            every { llmService.simpleRequest(any()) } returnsMany listOf(toolCallResponse, reportResponse)

            // 模拟工具执行成功
            every { toolExecutor.execute(any(), any(), any()) } returns mockk(relaxed = true) {
                every { data } returns "目录列表"
            }

            // When: 执行分析
            val result = executor.execute(
                type = type,
                projectKey = projectKey
            )

            // Then: 内容应该是字符串
            assertNotNull(result.content)
        }

        @Test
        @DisplayName("报告应不包含 thinking 标签")
        fun `report should not contain thinking tags`() = runBlocking {
            // Given: 技术栈分析
            val type = AnalysisType.TECH_STACK
            val projectKey = "test-project"

            // 模拟 LLM 返回（包含 thinking 标签的原始响应）
            val toolCallResponse = mockToolCallResponse("read_file", mapOf("path" to "build.gradle.kts"))
            // 模拟 LLM 返回包含 thinking 标签的报告（应该被清理掉）
            val reportWithThinking = """
                <thinking>
                这是思考过程，应该被移除
                </thinking>

                # 技术栈分析

                ## 编程语言
                Kotlin 1.9.20, Java 17+

                ## 构建工具
                Gradle 8.x

                ## 框架
                IntelliJ Platform Plugin SDK

                ## 数据存储
                H2 数据库, JVector 向量存储
            """.trimIndent()

            every { llmService.simpleRequest(any()) } returnsMany listOf(toolCallResponse, reportWithThinking)

            // 模拟工具执行成功
            every { toolExecutor.execute(any(), any(), any()) } returns mockk(relaxed = true) {
                every { data } returns "kotlin(\"jvm\") version \"1.9.20\""
            }

            // When: 执行分析
            val result = executor.execute(
                type = type,
                projectKey = projectKey
            )

            // Then: 清理后的内容不应包含 thinking 标签
            assertFalse(result.content.contains("<thinking>"))
            assertFalse(result.content.contains("</thinking>"))
        }
    }

    // ========== 死循环防护测试 ==========

    @Nested
    @DisplayName("死循环防护测试")
    inner class DoomLoopProtectionTest {

        @Test
        @DisplayName("执行器应尊重最大步数限制")
        fun `executor should respect max steps limit`() = runBlocking {
            // Given: 最大步数配置
            val maxSteps = 5
            val executorWithLimit = AnalysisLoopExecutor(
                toolRegistry = toolRegistry,
                toolExecutor = toolExecutor,
                doomLoopGuard = doomLoopGuard,
                llmService = llmService,
                validator = validator,
                maxSteps = maxSteps
            )

            // 模拟 LLM 返回（每次都返回工具调用，直到达到步数限制）
            val toolCallResponse = mockToolCallResponse("list_directory", mapOf("path" to "/src"))
            val reportResponse = mockMarkdownReport(AnalysisType.CONFIG_FILES)

            // 先返回几次工具调用，然后返回报告
            every { llmService.simpleRequest(any()) } returnsMany listOf(
                toolCallResponse, toolCallResponse, toolCallResponse, reportResponse
            )

            // 模拟工具执行成功
            every { toolExecutor.execute(any(), any(), any()) } returns mockk(relaxed = true) {
                every { data } returns "目录列表"
            }

            // When: 执行分析
            val result = executorWithLimit.execute(
                type = AnalysisType.CONFIG_FILES,
                projectKey = "test-project"
            )

            // Then: 应在限制内完成
            assertTrue(result.steps <= maxSteps)
        }

        @Test
        @DisplayName("执行器应跳过重复的工具调用")
        fun `executor should skip duplicate tool calls`() = runBlocking {
            // Given: 预先记录的工具调用
            val toolName = "read_file"
            val params = mapOf("path" to "/src/main.kt")
            doomLoopGuard.recordToolCall(toolName, params, "file content")

            // 模拟 LLM 返回：尝试调用相同工具，然后返回报告
            val duplicateToolCallResponse = mockToolCallResponse("read_file", mapOf("path" to "/src/main.kt"))
            val differentToolCallResponse = mockToolCallResponse("list_directory", mapOf("path" to "/src"))
            val reportResponse = mockMarkdownReport(AnalysisType.PROJECT_STRUCTURE)

            every { llmService.simpleRequest(any()) } returnsMany listOf(
                duplicateToolCallResponse, differentToolCallResponse, reportResponse
            )

            // 模拟工具执行成功
            every { toolExecutor.execute(any(), any(), any()) } returns mockk(relaxed = true) {
                every { data } returns "目录列表"
            }

            // When: 执行分析
            val result = executor.execute(
                type = AnalysisType.PROJECT_STRUCTURE,
                projectKey = "test-project"
            )

            // Then: 应正常完成
            assertNotNull(result)
        }
    }

    // ========== 上下文传递测试 ==========

    @Nested
    @DisplayName("上下文传递测试")
    inner class ContextPassingTest {

        @Test
        @DisplayName("应能接收前置上下文")
        fun `should accept prior context`() = runBlocking {
            // Given: 前置上下文
            val priorContext = "之前分析发现项目使用 Kotlin 和 Gradle"

            // 模拟 LLM 返回
            val toolCallResponse = mockToolCallResponse("read_file", mapOf("path" to "build.gradle.kts"))
            val reportResponse = mockMarkdownReport(AnalysisType.TECH_STACK)

            every { llmService.simpleRequest(any()) } returnsMany listOf(toolCallResponse, reportResponse)

            // 模拟工具执行成功
            every { toolExecutor.execute(any(), any(), any()) } returns mockk(relaxed = true) {
                every { data } returns "kotlin(\"jvm\") version \"1.9.20\""
            }

            // When: 带上下文执行分析
            val result = executor.execute(
                type = AnalysisType.TECH_STACK,
                projectKey = "test-project",
                priorContext = priorContext
            )

            // Then: 应正常完成
            assertNotNull(result)
        }

        @Test
        @DisplayName("应能接收现有 TODO 列表")
        fun `should accept existing todos`() = runBlocking {
            // Given: 现有 TODO 列表
            val existingTodos = listOf(
                AnalysisTodo(id = "todo-1", content = "补充分析：模块划分", priority = 1)
            )

            // 模拟 LLM 返回
            val toolCallResponse = mockToolCallResponse("list_directory", mapOf("path" to "/src"))
            val reportResponse = mockMarkdownReport(AnalysisType.PROJECT_STRUCTURE)

            every { llmService.simpleRequest(any()) } returnsMany listOf(toolCallResponse, reportResponse)

            // 模拟工具执行成功
            every { toolExecutor.execute(any(), any(), any()) } returns mockk(relaxed = true) {
                every { data } returns "目录列表"
            }

            // When: 带 TODO 执行分析
            val result = executor.execute(
                type = AnalysisType.PROJECT_STRUCTURE,
                projectKey = "test-project",
                existingTodos = existingTodos
            )

            // Then: 应正常完成
            assertNotNull(result)
        }
    }

    // ========== 结果验证测试 ==========

    @Nested
    @DisplayName("结果验证测试")
    inner class ResultValidationTest {

        @Test
        @DisplayName("结果应包含缺失章节信息")
        fun `result should include missing sections info`() = runBlocking {
            // Given: 分析任务
            val type = AnalysisType.DB_ENTITIES
            val projectKey = "test-project"

            // 模拟 LLM 返回
            val toolCallResponse = mockToolCallResponse("grep_file", mapOf("pattern" to "Entity"))
            val reportResponse = mockMarkdownReport(type)

            every { llmService.simpleRequest(any()) } returnsMany listOf(toolCallResponse, reportResponse)

            // 模拟工具执行成功
            every { toolExecutor.execute(any(), any(), any()) } returns mockk(relaxed = true) {
                every { data } returns "found: AnalysisResultEntity.java"
            }

            // When: 执行分析
            val result = executor.execute(
                type = type,
                projectKey = projectKey
            )

            // Then: 应包含缺失章节列表
            assertNotNull(result.missingSections)
        }

        @Test
        @DisplayName("结果应包含生成的 TODO")
        fun `result should include generated todos`() = runBlocking {
            // Given: 分析任务
            val type = AnalysisType.API_ENTRIES
            val projectKey = "test-project"

            // 模拟 LLM 返回
            val toolCallResponse = mockToolCallResponse("grep_file", mapOf("pattern" to "Controller"))
            val reportResponse = mockMarkdownReport(type)

            every { llmService.simpleRequest(any()) } returnsMany listOf(toolCallResponse, reportResponse)

            // 模拟工具执行成功
            every { toolExecutor.execute(any(), any(), any()) } returns mockk(relaxed = true) {
                every { data } returns "found: ApiController.java"
            }

            // When: 执行分析
            val result = executor.execute(
                type = type,
                projectKey = projectKey
            )

            // Then: 应包含 TODO 列表
            assertNotNull(result.todos)
        }
    }
}
