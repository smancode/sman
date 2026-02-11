package com.smancode.sman.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals

/**
 * 配置优先级测试
 */
@DisplayName("配置优先级测试")
class ConfigPriorityTest {

    @Test
    @DisplayName("默认值 - 无人配置")
    fun testDefault_whenNoConfig() {
        // 确保没有用户配置
        SmanConfig.setUserConfig(SmanConfig.UserConfig("", "", ""))

        // 读取配置应该使用配置文件或默认值
        val baseUrl = SmanConfig.llmBaseUrl
        val modelName = SmanConfig.llmModelName

        // 配置文件: smanagent.properties
        // llm.base.url=https://open.bigmodel.cn/api/coding/paas/v4
        // llm.model.name=GLM-4.7
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", baseUrl)
        assertEquals("GLM-4.7", modelName)
    }

    @Test
    @DisplayName("用户配置优先级最高")
    fun testUserConfig_priority() {
        // 设置用户配置
        SmanConfig.setUserConfig(SmanConfig.UserConfig(
            llmApiKey = "user_key",
            llmBaseUrl = "https://user.api.example.com",
            llmModelName = "user-model"
        ))

        val baseUrl = SmanConfig.llmBaseUrl
        val modelName = SmanConfig.llmModelName

        assertEquals("https://user.api.example.com", baseUrl)
        assertEquals("user-model", modelName)
    }

    @Test
    @DisplayName("用户配置为空 - 使用配置文件")
    fun testEmptyUserConfig_fallbackToProperties() {
        // 设置空的用户配置
        SmanConfig.setUserConfig(SmanConfig.UserConfig(
            llmApiKey = "",
            llmBaseUrl = "",
            llmModelName = ""
        ))

        val baseUrl = SmanConfig.llmBaseUrl
        val modelName = SmanConfig.llmModelName

        // 应该使用配置文件中的值
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", baseUrl)
        assertEquals("GLM-4.7", modelName)
    }

    @Test
    @DisplayName("动态更新 - 配置变更生效")
    fun testDynamicUpdate() {
        // 初始配置
        SmanConfig.setUserConfig(SmanConfig.UserConfig(
            llmApiKey = "key1",
            llmBaseUrl = "https://api1.example.com",
            llmModelName = "model1"
        ))

        val baseUrl1 = SmanConfig.llmBaseUrl
        assertEquals("https://api1.example.com", baseUrl1)

        // 更新配置
        SmanConfig.setUserConfig(SmanConfig.UserConfig(
            llmApiKey = "key2",
            llmBaseUrl = "https://api2.example.com",
            llmModelName = "model2"
        ))

        val baseUrl2 = SmanConfig.llmBaseUrl
        assertEquals("https://api2.example.com", baseUrl2)
    }
}
