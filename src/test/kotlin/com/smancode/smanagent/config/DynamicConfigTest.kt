package com.smancode.smanagent.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 动态配置测试 - 验证配置可以实时更新
 */
@DisplayName("动态配置测试")
class DynamicConfigTest {

    private val originalConfig = SmanAgentConfig.UserConfig()

    @BeforeEach
    fun setUp() {
        // 保存原始配置
        // 注意：由于 SmanAgentConfig 是 object，需要小心处理状态
    }

    @AfterEach
    fun tearDown() {
        // 恢复原始配置
        SmanAgentConfig.setUserConfig(originalConfig)
    }

    @Test
    @DisplayName("配置动态更新 - API Key 变更生效")
    fun testConfigChange_apiKey() {
        // Given: 设置初始配置
        val config1 = SmanAgentConfig.UserConfig(
            llmApiKey = "key1",
            llmBaseUrl = "https://api1.example.com",
            llmModelName = "model1"
        )
        SmanAgentConfig.setUserConfig(config1)

        // When: 读取配置
        val key1 = SmanAgentConfig.llmApiKey

        // Then: 验证配置正确
        assertEquals("key1", key1)

        // When: 更新配置
        val config2 = SmanAgentConfig.UserConfig(
            llmApiKey = "key2",
            llmBaseUrl = "https://api2.example.com",
            llmModelName = "model2"
        )
        SmanAgentConfig.setUserConfig(config2)

        // Then: 验证新配置生效
        val key2 = SmanAgentConfig.llmApiKey
        assertEquals("key2", key2)
    }

    @Test
    @DisplayName("配置动态更新 - Base URL 变更生效")
    fun testConfigChange_baseUrl() {
        // Given: 设置初始配置
        val config1 = SmanAgentConfig.UserConfig(
            llmBaseUrl = "https://api1.example.com/v1"
        )
        SmanAgentConfig.setUserConfig(config1)

        // When: 读取配置
        val url1 = SmanAgentConfig.llmBaseUrl

        // Then: 验证配置正确
        assertEquals("https://api1.example.com/v1", url1)

        // When: 更新配置
        val config2 = SmanAgentConfig.UserConfig(
            llmBaseUrl = "https://api2.example.com/v2"
        )
        SmanAgentConfig.setUserConfig(config2)

        // Then: 验证新配置生效
        val url2 = SmanAgentConfig.llmBaseUrl
        assertEquals("https://api2.example.com/v2", url2)
    }

    @Test
    @DisplayName("配置动态更新 - 模型名称变更生效")
    fun testConfigChange_modelName() {
        // Given: 设置初始配置
        val config1 = SmanAgentConfig.UserConfig(
            llmModelName = "model-v1"
        )
        SmanAgentConfig.setUserConfig(config1)

        // When: 读取配置
        val model1 = SmanAgentConfig.llmModelName

        // Then: 验证配置正确
        assertEquals("model-v1", model1)

        // When: 更新配置
        val config2 = SmanAgentConfig.UserConfig(
            llmModelName = "model-v2"
        )
        SmanAgentConfig.setUserConfig(config2)

        // Then: 验证新配置生效
        val model2 = SmanAgentConfig.llmModelName
        assertEquals("model-v2", model2)
    }

    @Test
    @DisplayName("空配置回退 - 使用默认值")
    fun testEmptyConfigFallback() {
        // Given: 设置空配置
        val emptyConfig = SmanAgentConfig.UserConfig(
            llmBaseUrl = "",
            llmModelName = ""
        )
        SmanAgentConfig.setUserConfig(emptyConfig)

        // When & Then: 应该使用默认值
        val baseUrl = SmanAgentConfig.llmBaseUrl
        val modelName = SmanAgentConfig.llmModelName

        // 验证使用默认值（或配置文件中的值）
        assert(baseUrl.isNotEmpty()) { "Base URL 不应为空" }
        assert(modelName.isNotEmpty()) { "模型名称不应为空" }
    }
}
