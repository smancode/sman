package com.smancode.sman.tools.ide

import com.intellij.openapi.project.Project
import com.smancode.sman.tools.ToolRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WebSearch 工具测试
 *
 * 验证 WebSearchTool 正确创建和注册
 */
@DisplayName("WebSearch 工具测试")
class WebSearchToolTest {

    private fun createMockProject(): Project {
        val mockProject = mockk<Project>()
        every { mockProject.basePath } returns "/tmp/test-project"
        every { mockProject.name } returns "test-project"
        return mockProject
    }

    @Test
    @DisplayName("工具注册 - web_search 工具应该可访问")
    fun testWebSearchTool_registered() {
        // Given: 工具注册表
        val registry = ToolRegistry()
        val tools = LocalToolFactory.createTools(createMockProject())

        // When: 注册工具
        registry.registerTools(tools)

        // Then: web_search 工具应该可访问
        val tool = registry.getTool("web_search")
        assertNotNull(tool, "web_search 工具应该可访问")
        assertEquals("web_search", tool.getName(), "工具名称应该匹配")
    }

    @Test
    @DisplayName("工具元数据 - 验证工具描述和参数")
    fun testWebSearchTool_metadata() {
        // Given: 工具注册表
        val registry = ToolRegistry()
        val tools = LocalToolFactory.createTools(createMockProject())
        registry.registerTools(tools)

        // When: 获取 web_search 工具
        val tool = registry.getTool("web_search")
        assertNotNull(tool)

        // Then: 验证工具描述和参数
        val description = tool.getDescription()
        assertTrue(description.isNotEmpty(), "工具应该有描述")
        assertTrue(description.contains("web") || description.contains("search"),
            "工具描述应该包含搜索相关描述")

        val params = tool.getParameters()
        assertTrue(params.isNotEmpty(), "工具应该有参数定义")

        // 验证必需参数
        val queryParam = params["query"]
        assertNotNull(queryParam, "应该有 query 参数")
        assertTrue(queryParam.isRequired, "query 参数应该是必需的")
    }

    @Test
    @DisplayName("工具参数 - 验证可选参数及其默认值")
    fun testWebSearchTool_optionalParameters() {
        // Given: 工具注册表
        val registry = ToolRegistry()
        val tools = LocalToolFactory.createTools(createMockProject())
        registry.registerTools(tools)

        // When: 获取 web_search 工具
        val tool = registry.getTool("web_search")
        assertNotNull(tool)

        val params = tool.getParameters()

        // 验证 numResults 参数
        val numResultsParam = params["numResults"]
        assertNotNull(numResultsParam, "应该有 numResults 参数")
        assertFalse(numResultsParam.isRequired, "numResults 参数应该是可选的")
        assertEquals(8, numResultsParam.defaultValue, "numResults 默认值应该是 8")

        // 验证 type 参数
        val typeParam = params["type"]
        assertNotNull(typeParam, "应该有 type 参数")
        assertFalse(typeParam.isRequired, "type 参数应该是可选的")
        assertEquals("auto", typeParam.defaultValue, "type 默认值应该是 auto")

        // 验证 livecrawl 参数
        val livecrawlParam = params["livecrawl"]
        assertNotNull(livecrawlParam, "应该有 livecrawl 参数")
        assertFalse(livecrawlParam.isRequired, "livecrawl 参数应该是可选的")
        assertEquals("fallback", livecrawlParam.defaultValue, "livecrawl 默认值应该是 fallback")
    }

    @Test
    @DisplayName("工具列表 - 验证 web_search 已添加到工具列表")
    fun testWebSearchTool_inToolList() {
        // Given: 工具列表
        val tools = LocalToolFactory.createTools(createMockProject())

        // Then: 应该包含 web_search
        val toolNames = tools.map { it.getName() }
        assertTrue(toolNames.contains("web_search"), "工具列表应该包含 web_search")
    }

    @Test
    @DisplayName("白名单拒绝测试 - 缺少 query 参数")
    fun testWebSearchTool_missingQuery_shouldFail() {
        // Given: web_search 工具
        val registry = ToolRegistry()
        val tools = LocalToolFactory.createTools(createMockProject())
        registry.registerTools(tools)
        val tool = registry.getTool("web_search")
        assertNotNull(tool)

        // When: 缺少 query 参数
        val result = tool.execute("test-project", mapOf())

        // Then: 应该返回失败
        assertFalse(result.isSuccess, "缺少 query 参数应该返回失败")
        assertTrue(result.error?.contains("query") == true,
            "错误信息应该包含 'query'")
    }

    @Test
    @DisplayName("白名单拒绝测试 - query 为空")
    fun testWebSearchTool_emptyQuery_shouldFail() {
        // Given: web_search 工具
        val registry = ToolRegistry()
        val tools = LocalToolFactory.createTools(createMockProject())
        registry.registerTools(tools)
        val tool = registry.getTool("web_search")
        assertNotNull(tool)

        // When: query 为空字符串
        val result = tool.execute("test-project", mapOf("query" to "   "))

        // Then: 应该返回失败
        assertFalse(result.isSuccess, "query 为空应该返回失败")
    }
}
