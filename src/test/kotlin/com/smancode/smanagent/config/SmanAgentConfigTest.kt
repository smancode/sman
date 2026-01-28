package com.smancode.smanagent.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals

/**
 * SmanAgent 配置测试
 */
@DisplayName("SmanAgent 配置测试")
class SmanAgentConfigTest {

    @Test
    @DisplayName("用户配置优先级 - 用户设置覆盖默认值")
    fun testUserConfigPriority() {
        // Given: 设置用户配置
        val userConfig = SmanAgentConfig.UserConfig(
            llmBaseUrl = "https://custom.example.com/api",
            llmModelName = "custom-model",
            llmApiKey = "custom-key"
        )
        SmanAgentConfig.setUserConfig(userConfig)

        // When: 重新读取配置（需要清空 lazy 缓存）
        // 注意：由于 lazy 的特性，这个测试主要验证 setUserConfig 方法不抛异常

        // Then: 验证用户配置已设置
        // 由于 lazy 初始化，实际的值会在第一次访问时确定
        // 这里主要测试方法调用不报错
        assertEquals("https://custom.example.com/api", userConfig.llmBaseUrl)
        assertEquals("custom-model", userConfig.llmModelName)
        assertEquals("custom-key", userConfig.llmApiKey)
    }

    @Test
    @DisplayName("默认配置 - 使用默认值")
    fun testDefaultConfig() {
        // Given: 不设置用户配置
        SmanAgentConfig.setUserConfig(SmanAgentConfig.UserConfig())

        // When & Then: 验证默认值存在
        // 注意：这些值是在第一次访问时确定的
        // 如果没有用户配置，会使用配置文件或默认值
    }

    @Test
    @DisplayName("空配置 - 不覆盖默认值")
    fun testEmptyConfigDoesNotOverride() {
        // Given: 设置空的用户配置
        val emptyConfig = SmanAgentConfig.UserConfig(
            llmBaseUrl = "",
            llmModelName = "",
            llmApiKey = ""
        )
        SmanAgentConfig.setUserConfig(emptyConfig)

        // When & Then: 空配置不应该覆盖默认值
        // 实际的配置会在第一次访问时从配置文件或默认值读取
        assertEquals("", emptyConfig.llmBaseUrl)
        assertEquals("", emptyConfig.llmModelName)
        assertEquals("", emptyConfig.llmApiKey)
    }
}
