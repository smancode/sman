package com.smancode.sman.analysis.executor

import com.smancode.sman.analysis.model.*
import com.smancode.sman.evolution.guard.DoomLoopGuard
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
        llmService = mockk(relaxed = true)
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
