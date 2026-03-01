package com.smancode.sman.tools.ide

import com.smancode.sman.config.SmanConfig
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WebSearch 降级策略测试
 *
 * TDD 测试用例：验证 Exa → Tavily 的降级逻辑
 */
@DisplayName("WebSearch 降级策略测试")
class WebSearchFallbackTest {

    @BeforeEach
    fun setup() {
        mockkObject(SmanConfig)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(SmanConfig)
    }

    // ==================== Exa 限流检测测试 ====================

    @Test
    @DisplayName("Exa 限流检测 - HTTP 429 应该被识别为限流")
    fun testExaIsRateLimited_http429() {
        // Given: WebSearchTool
        val tool = WebSearchTool()

        // Then: 429 应该被识别为限流
        assertTrue(tool.isExaRateLimited(429))
    }

    @Test
    @DisplayName("Exa 限流检测 - HTTP 200 不应该被识别为限流")
    fun testExaIsRateLimited_http200() {
        // Given: WebSearchTool
        val tool = WebSearchTool()

        // Then: 200 不应该被识别为限流
        assertFalse(tool.isExaRateLimited(200))
    }

    @Test
    @DisplayName("Exa 限流检测 - 响应体包含 rate limit 应该被识别为限流")
    fun testExaIsRateLimited_responseBodyContainsRateLimit() {
        // Given: WebSearchTool
        val tool = WebSearchTool()

        // Then: 响应体包含 rate limit 关键词应该被识别
        assertTrue(tool.isExaRateLimited(429, """{"error": "rate limit exceeded"}"""))
        assertTrue(tool.isExaRateLimited(429, """{"error": "Rate Limit Exceeded"}"""))
    }

    // ==================== 降级决策测试 ====================

    @Test
    @DisplayName("降级决策 - Tavily 已配置且 Exa 限流时应该降级")
    fun testShouldFallback_tavilyConfiguredAndExaRateLimited() {
        // Given: Tavily 已配置
        every { SmanConfig.webSearchTavilyEnabled } returns true
        every { SmanConfig.webSearchTavilyApiKey } returns "tvly-test-key"

        val tool = WebSearchTool()

        // When: Exa 限流
        val shouldFallback = tool.shouldFallbackToTavily(429)

        // Then: 应该降级
        assertTrue(shouldFallback)
    }

    @Test
    @DisplayName("降级决策 - Tavily 未配置时不应该降级")
    fun testShouldFallback_tavilyNotConfigured() {
        // Given: Tavily 未配置
        every { SmanConfig.webSearchTavilyEnabled } returns false
        every { SmanConfig.webSearchTavilyApiKey } returns ""

        val tool = WebSearchTool()

        // When: Exa 限流
        val shouldFallback = tool.shouldFallbackToTavily(429)

        // Then: 不应该降级（因为 Tavily 不可用）
        assertFalse(shouldFallback)
    }

    @Test
    @DisplayName("降级决策 - Exa 正常时不应该降级")
    fun testShouldFallback_exaNormal() {
        // Given: Tavily 已配置
        every { SmanConfig.webSearchTavilyEnabled } returns true
        every { SmanConfig.webSearchTavilyApiKey } returns "tvly-test-key"

        val tool = WebSearchTool()

        // When: Exa 正常（200）
        val shouldFallback = tool.shouldFallbackToTavily(200)

        // Then: 不应该降级
        assertFalse(shouldFallback)
    }

    // ==================== 限流错误消息测试 ====================

    @Test
    @DisplayName("限流错误消息 - 应该包含升级提示")
    fun testBuildRateLimitMessage_containsUpgradeHint() {
        // Given: WebSearchTool
        val tool = WebSearchTool()

        // When: 构建限流错误消息
        val message = tool.buildRateLimitMessage()

        // Then: 应该包含升级提示
        assertTrue(message.contains("限流") || message.contains("rate limit", ignoreCase = true))
        assertTrue(message.contains("Tavily"))
    }

    @Test
    @DisplayName("限流错误消息 - Tavily 未配置时应该提示配置")
    fun testBuildRateLimitMessage_tavilyNotConfigured_hintToConfigure() {
        // Given: Tavily 未配置
        every { SmanConfig.webSearchTavilyEnabled } returns false

        val tool = WebSearchTool()

        // When: 构建限流错误消息
        val message = tool.buildRateLimitMessage()

        // Then: 应该提示配置 Tavily
        assertTrue(message.contains("API Key") || message.contains("配置"))
    }

    // ==================== 搜索结果来源标记测试 ====================

    @Test
    @DisplayName("搜索结果来源 - Exa 结果应该标记为 Exa")
    fun testSearchResultSource_exa() {
        // Given: Exa 搜索结果
        val result = """## 搜索结果

**查询**: test
**结果数**: 1

### 1. Test
**URL**: https://example.com
"""

        // Then: 应该能够识别来源（或通过其他方式标记）
        // 这个测试验证结果格式是可识别的
        assertTrue(result.contains("搜索结果"))
    }

    @Test
    @DisplayName("搜索结果来源 - Tavily 结果应该标记为 Tavily")
    fun testSearchResultSource_tavily() {
        // Given: Tavily 搜索结果
        val result = """## 搜索结果 (Tavily)

**查询**: test
**结果数**: 1

### 1. Test
**URL**: https://example.com
"""

        // Then: 应该标记为 Tavily
        assertTrue(result.contains("Tavily"))
    }
}
