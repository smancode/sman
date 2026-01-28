package com.smancode.smanagent.tools.ide

import com.intellij.openapi.project.Project
import com.smancode.smanagent.tools.Tool
import com.smancode.smanagent.tools.ToolRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
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
    @DisplayName("创建工具列表 - 应该创建 6 个工具")
    fun testCreateTools_count_shouldBeSix() {
        // Given: 模拟项目对象
        val project = createMockProject()

        // When: 创建工具列表
        val tools = LocalToolFactory.createTools(project)

        // Then: 应该创建 6 个工具
        assertEquals(6, tools.size, "应该创建 6 个本地工具")
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
            "read_file",
            "grep_file",
            "find_file",
            "call_chain",
            "extract_xml",
            "apply_change"
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
}
