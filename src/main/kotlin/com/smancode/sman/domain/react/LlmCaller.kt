package com.smancode.sman.domain.react

import com.smancode.sman.config.SmanCodeProperties
import com.smancode.sman.config.SmanConfig
import com.smancode.sman.model.session.Session
import com.smancode.sman.smancode.llm.LlmService
import org.slf4j.LoggerFactory

/**
 * LLM 调用器
 *
 * 负责：
 * - LLM 调用逻辑
 * - 流式处理
 * - 错误重试
 * - 响应获取
 */
class LlmCaller(
    private val smanCodeProperties: SmanCodeProperties
) {
    private val logger = LoggerFactory.getLogger(LlmCaller::class.java)

    /**
     * 调用 LLM 并返回响应
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 响应文本
     */
    fun call(systemPrompt: String, userPrompt: String): String? {
        return try {
            // 每次都使用最新配置创建 LLM 服务
            val llmService = SmanConfig.createLlmService()
            llmService.simpleRequest(systemPrompt, userPrompt)
        } catch (e: Exception) {
            logger.error("LLM 调用失败: {}", e.message)
            null
        }
    }

    /**
     * 创建 LLM 服务实例
     *
     * @return LLM 服务实例
     */
    fun createLlmService(): LlmService {
        return SmanConfig.createLlmService()
    }

    /**
     * 获取最大步数配置
     */
    fun getMaxSteps(): Int = smanCodeProperties.react.maxSteps

    /**
     * 检查是否启用了流式处理
     */
    fun isStreamingEnabled(): Boolean = smanCodeProperties.react.enableStreaming

    /**
     * 获取 LLM 响应的最大 Token 数
     */
    fun getMaxResponseTokens(): Int = smanCodeProperties.llm.maxTokens
}
