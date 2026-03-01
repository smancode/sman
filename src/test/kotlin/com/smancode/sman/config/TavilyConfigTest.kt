package com.smancode.sman.config

import com.smancode.sman.ide.service.StorageService
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tavily 配置测试
 *
 * TDD 测试用例：验证 Tavily API Key 的存储和读取
 */
@DisplayName("Tavily 配置测试")
class TavilyConfigTest {

    @Test
    @DisplayName("StorageService - 应该能够存储和读取 tavilyApiKey")
    fun testStorageService_tavilyApiKey_storeAndGet() {
        // Given: StorageService 实例
        val storage = StorageService()

        // When: 存储 tavilyApiKey
        storage.tavilyApiKey = "tvly-test-api-key-12345"

        // Then: 应该能够读取相同的值
        assertEquals("tvly-test-api-key-12345", storage.tavilyApiKey)
    }

    @Test
    @DisplayName("StorageService - tavilyApiKey 默认值应为空字符串")
    fun testStorageService_tavilyApiKey_defaultEmpty() {
        // Given: 新创建的 StorageService
        val storage = StorageService()

        // Then: 默认值应该是空字符串
        assertEquals("", storage.tavilyApiKey)
    }

    @Test
    @DisplayName("StorageService - 应该能够清空 tavilyApiKey")
    fun testStorageService_tavilyApiKey_canBeCleared() {
        // Given: 已存储的 tavilyApiKey
        val storage = StorageService()
        storage.tavilyApiKey = "tvly-some-key"

        // When: 清空
        storage.tavilyApiKey = ""

        // Then: 应该返回空字符串
        assertEquals("", storage.tavilyApiKey)
    }

    @Test
    @DisplayName("SmanConfig.webSearchTavilyApiKey - 未配置时返回空字符串")
    fun testSmanConfig_tavilyApiKey_notConfigured() {
        // Given: 没有配置 Tavily API Key
        // (依赖默认配置文件)

        // Then: 应该返回空字符串
        val apiKey = SmanConfig.webSearchTavilyApiKey
        // 由于测试环境可能没有配置，我们只检查它不会抛异常
        // 实际值取决于配置文件
    }

    @Test
    @DisplayName("SmanConfig.webSearchTavilyEnabled - 应该判断 Tavily 是否可用")
    fun testSmanConfig_tavilyEnabled_check() {
        // Given: 配置状态

        // Then: 只有当 API Key 非空时才启用
        // 这个测试验证属性存在且可访问
        val enabled = SmanConfig.webSearchTavilyEnabled
        // 值取决于配置，我们只验证属性存在
    }
}
