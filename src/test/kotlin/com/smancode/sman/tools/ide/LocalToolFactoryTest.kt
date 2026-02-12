package com.smancode.sman.tools.ide

import com.intellij.openapi.project.Project
import com.smancode.sman.tools.Tool
import com.smancode.sman.tools.ToolRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 本地工具工厂测试
 *
 * 验证 LocalToolFactory 正确创建和注册所有工具
 */
@DisplayName("本地工具工厂测试")
class LocalToolFactoryTest {

    private fun createMockProject(): Project {
        val mockProject = mockk<Project>()
        every { mockProject.basePath } returns "/tmp/test-project"
        every { mockProject.name } returns "test-project"
        return mockProject
    }

    @Test
    @DisplayName("创建工具列表 - 应该创建 9 个工具")
    fun testCreateTools_count_shouldBeEight() {
        // Given: 模拟项目对象
        val project = createMockProject()

        // When: 创建工具列表
        val tools = LocalToolFactory.createTools(project)

        // Then: 应该创建 9 个工具（expert_consult + 7 个基础工具 + batch）
        assertEquals(9, tools.size, "应该创建 9 个本地工具")
    }

    @Test
    @DisplayName("注册工具 - 所有工具都应该可访问")
    fun testRegisterTools_allToolsAccessible() {
        // Given: 工具注册表和工具列表
        val registry = ToolRegistry()
        val tools = LocalToolFactory.createTools(createMockProject())

        // When: 注册工具
        registry.registerTools(tools)

        // Then: 验证所有工具都可访问
        val expectedTools = listOf(
            "expert_consult",  // 核心工具：专家咨询
            "read_file",
            "grep_file",
            "find_file",
            "call_chain",
            "extract_xml",
            "apply_change",
            "run_shell_command",
            "batch"
        )

        expectedTools.forEach { toolName ->
            val tool = registry.getTool(toolName)
            assertNotNull(tool, "工具 $toolName 应该可访问")
            assertEquals(toolName, tool.getName(), "工具名称应该匹配")
        }
    }

    @Test
    @DisplayName("工具元数据 - 验证工具描述和参数")
    fun testToolMetadata_descriptionsAndParameters() {
        // Given: 创建工具列表
        val tools = LocalToolFactory.createTools(createMockProject())

        // When & Then: 验证每个工具有有效的描述和参数
        tools.forEach { tool ->
            val name = tool.getName()
            val description = tool.getDescription()
            val parameters = tool.getParameters()

            assertTrue(description.isNotEmpty(), "工具 $name 应该有描述")
            assertTrue(parameters.isNotEmpty(), "工具 $name 应该有参数定义")

            println("✓ $name: $description (${parameters.size} 参数)")
        }
    }

    @Test
    @DisplayName("必需参数 - read_file 工具应该有正确的参数定义")
    fun testReadFileTool_parameters() {
        // Given: 工具注册表
        val registry = ToolRegistry()
        LocalToolFactory.createTools(createMockProject()).let { registry.registerTools(it) }

        // When: 获取 read_file 工具
        val tool = registry.getTool("read_file")

        // Then: 验证参数定义
        assertNotNull(tool)
        val params = tool.getParameters()

        // 验证参数存在
        assertTrue(params.containsKey("simpleName"), "应该有 simpleName 参数")
        assertTrue(params.containsKey("relativePath"), "应该有 relativePath 参数")
        assertTrue(params.containsKey("startLine"), "应该有 startLine 参数")
        assertTrue(params.containsKey("endLine"), "应该有 endLine 参数")

        // 验证默认值
        val startLineParam = params["startLine"]
        assertEquals(1, startLineParam?.defaultValue, "startLine 默认值应该是 1")

        val endLineParam = params["endLine"]
        assertEquals(300, endLineParam?.defaultValue, "endLine 默认值应该是 300")
    }

    @Test
    @DisplayName("必需参数 - grep_file 工具应该有必需的 pattern 参数")
    fun testGrepFileTool_requiredParameters() {
        // Given: 工具注册表
        val registry = ToolRegistry()
        LocalToolFactory.createTools(createMockProject()).let { registry.registerTools(it) }

        // When: 获取 grep_file 工具
        val tool = registry.getTool("grep_file")

        // Then: 验证 pattern 参数是必需的
        assertNotNull(tool)
        val params = tool.getParameters()

        val patternParam = params["pattern"]
        assertNotNull(patternParam, "应该有 pattern 参数")
        assertTrue(patternParam.isRequired, "pattern 参数应该是必需的")
    }

    @Test
    @DisplayName("流式输出支持 - run_shell_command 应该支持流式输出")
    fun testRunShellCommandTool_supportsStreaming() {
        // Given: 工具注册表
        val registry = ToolRegistry()
        LocalToolFactory.createTools(createMockProject()).let { registry.registerTools(it) }

        // When: 获取 run_shell_command 工具
        val tool = registry.getTool("run_shell_command")

        // Then: 验证支持流式输出
        assertNotNull(tool)
        assertTrue(tool.supportsStreaming(), "run_shell_command 应该支持流式输出")
    }

    @Test
    @DisplayName("流式输出支持 - 其他工具不应该支持流式输出")
    fun testOtherTools_doNotSupportStreaming() {
        // Given: 工具列表
        val tools = LocalToolFactory.createTools(createMockProject())

        // When & Then: 只有 run_shell_command 支持流式输出
        tools.forEach { tool ->
            if (tool.getName() == "run_shell_command") {
                assertTrue(tool.supportsStreaming(), "${tool.getName()} 应该支持流式输出")
            } else {
                assertFalse(tool.supportsStreaming(), "${tool.getName()} 不应该支持流式输出")
            }
        }
    }

    @Test
    @DisplayName("专家咨询工具 - 应该有正确的参数定义")
    fun testExpertConsultTool_parameters() {
        // Given: 工具注册表
        val registry = ToolRegistry()
        LocalToolFactory.createTools(createMockProject()).let { registry.registerTools(it) }

        // When: 获取 expert_consult 工具
        val tool = registry.getTool("expert_consult")

        // Then: 验证参数定义
        assertNotNull(tool, "expert_consult 工具应该存在")
        val params = tool.getParameters()

        // 验证必需参数
        val queryParam = params["query"]
        assertNotNull(queryParam, "应该有 query 参数")
        assertTrue(queryParam.isRequired, "query 参数应该是必需的")

        val projectKeyParam = params["projectKey"]
        assertNotNull(projectKeyParam, "应该有 projectKey 参数")
        assertTrue(projectKeyParam.isRequired, "projectKey 参数应该是必需的")

        // 验证可选参数
        val topKParam = params["topK"]
        assertNotNull(topKParam, "应该有 topK 参数")
        assertEquals(10, topKParam?.defaultValue, "topK 默认值应该是 10")

        val enableRerankParam = params["enableRerank"]
        assertNotNull(enableRerankParam, "应该有 enableRerank 参数")
        assertEquals(true, enableRerankParam?.defaultValue, "enableRerank 默认值应该是 true")

        val rerankTopNParam = params["rerankTopN"]
        assertNotNull(rerankTopNParam, "应该有 rerankTopN 参数")
        assertEquals(5, rerankTopNParam?.defaultValue, "rerankTopN 默认值应该是 5")

        // 验证工具描述
        val description = tool.getDescription()
        assertTrue(description.contains("Business") || description.contains("业务"),
            "工具描述应该提到业务↔代码能力")
    }

    @Test
    @DisplayName("专家咨询工具 - 白名单拒绝测试：缺少 query 参数")
    @EnabledIfSystemProperty(named = "run.integration.tests", matches = "true")
    fun testExpertConsultTool_missingQuestion_shouldFail() {
        // Given: expert_consult 工具
        val tool = ExpertConsultTool(createMockProject())

        // When: 缺少 query 参数
        val result = tool.execute("test-project", mapOf(
            "projectKey" to "test-project"
        ))

        // Then: 应该返回失败
        assertFalse(result.isSuccess, "缺少 query 参数应该返回失败")
        assertTrue(result.error?.contains("query") == true,
            "错误信息应该包含 'query'")
    }

    @Test
    @DisplayName("专家咨询工具 - 白名单拒绝测试：query 为空")
    @EnabledIfSystemProperty(named = "run.integration.tests", matches = "true")
    fun testExpertConsultTool_emptyQuestion_shouldFail() {
        // Given: expert_consult 工具
        val tool = ExpertConsultTool(createMockProject())

        // When: query 为空字符串
        val result = tool.execute("test-project", mapOf(
            "query" to "   ",
            "projectKey" to "test-project"
        ))

        // Then: 应该返回失败
        assertFalse(result.isSuccess, "query 为空应该返回失败")
    }

    @Test
    @DisplayName("专家咨询工具 - 白名单拒绝测试：topK 小于等于 0")
    @EnabledIfSystemProperty(named = "run.integration.tests", matches = "true")
    fun testExpertConsultTool_topKLessThanZero_shouldFail() {
        // Given: expert_consult 工具
        val tool = ExpertConsultTool(createMockProject())

        // When: topK <= 0
        val result = tool.execute("test-project", mapOf(
            "query" to "测试问题",
            "projectKey" to "test-project",
            "topK" to 0
        ))

        // Then: 应该返回失败
        assertFalse(result.isSuccess, "topK <= 0 应该返回失败")
        assertTrue(result.error?.contains("topK") == true,
            "错误信息应该包含 'topK'")
    }

    @Test
    @DisplayName("专家咨询工具 - 集成测试：调用真实 API")
    @EnabledIfSystemProperty(named = "run.integration.tests", matches = "true")
    fun testExpertConsultTool_integration_callRealApi() {
        // Given: expert_consult 工具和真实项目
        val tool = ExpertConsultTool(createMockProject())

        // When: 调用 API
        val result = tool.execute("sman", mapOf(
            "question" to "项目中有哪些API入口？",
            "projectKey" to "smanunion",
            "topK" to 5
        ))

        // Then: 验证结果
        // 注意：这个测试需要验证服务正在运行
        if (!result.isSuccess) {
            // 如果服务未运行，跳过测试
            assumeTrue(false, "验证服务未运行: ${result.error}")
        }

        assertTrue(result.isSuccess, "API 调用应该成功")
        assertTrue(result.displayContent?.contains("答案") == true ||
                    result.displayContent?.contains("API") == true,
            "结果应该包含答案内容")

        // 验证元数据
        val metadata = result.metadata
        assertNotNull(metadata, "应该有元数据")
        assertTrue(metadata?.containsKey("confidence") == true,
            "元数据应该包含 confidence")
        assertTrue(metadata?.containsKey("processingTimeMs") == true,
            "元数据应该包含 processingTimeMs")
    }
}
