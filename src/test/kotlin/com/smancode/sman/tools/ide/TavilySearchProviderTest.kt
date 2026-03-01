package com.smancode.sman.tools.ide

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tavily 搜索提供者测试
 *
 * 只测试公共 API，内部实现细节通过集成测试验证
 */
@DisplayName("Tavily 搜索提供者测试")
class TavilySearchProviderTest {

    // ==================== API Key 校验测试 ====================

    @Test
    @DisplayName("API Key 校验 - 空 Key 应该返回不可用")
    fun testIsAvailable_emptyKey() {
        // Given: 空的 API Key
        val provider = TavilySearchProvider("")

        // Then: 应该不可用
        assertFalse(provider.isAvailable())
    }

    @Test
    @DisplayName("API Key 校验 - 非空 Key 应该返回可用")
    fun testIsAvailable_nonEmptyKey() {
        // Given: 非空 API Key
        val provider = TavilySearchProvider("tvly-some-key")

        // Then: 应该可用
        assertTrue(provider.isAvailable())
    }

    // ==================== 搜索结果数据类测试 ====================

    @Test
    @DisplayName("搜索结果 - TavilySearchResult 数据类应该正确存储数据")
    fun testTavilySearchResult_dataClass() {
        // Given/When: 创建搜索结果
        val result = TavilySearchProvider.TavilySearchResult(
            title = "Test Title",
            url = "https://example.com",
            content = "Test content"
        )

        // Then: 数据应该正确存储
        assertTrue(result.title == "Test Title")
        assertTrue(result.url == "https://example.com")
        assertTrue(result.content == "Test content")
    }

    // 注意：限流检测已移至 WebSearchTool.isExaRateLimited() 测试
    // Tavily 的 search() 方法通过公共 API 进行集成测试
}
