package com.smancode.sman.config

import org.slf4j.LoggerFactory

/**
 * SmanCode 配置属性
 *
 * 从 SmanConfig 读取配置，不使用硬编码默认值
 */
class SmanCodeProperties {

    private val logger = LoggerFactory.getLogger(SmanCodeProperties::class.java)
    private val config = SmanConfig

    /**
     * ReAct 循环配置
     */
    val react: ReactConfig = ReactConfig(
        maxSteps = config.reactMaxSteps,
        enableStreaming = config.reactEnableStreaming
    )

    /**
     * 上下文压缩配置
     */
    val compaction: CompactionConfig = CompactionConfig(
        maxTokens = config.compactionMaxTokens,
        compressionThreshold = config.compactionThreshold,
        enableIntelligentCompaction = config.compactionEnableIntelligent
    )

    /**
     * LLM 配置
     */
    val llm: LlmConfig = LlmConfig(
        temperature = config.llmTemperature,
        maxTokens = config.llmDefaultMaxTokens
    )

    init {
        logger.info("SmanCode 配置加载完成")
    }

    /**
     * ReAct 循环配置
     */
    class ReactConfig(
        val maxSteps: Int,
        val enableStreaming: Boolean
    )

    /**
     * 上下文压缩配置
     */
    class CompactionConfig(
        val maxTokens: Int,
        val compressionThreshold: Int,
        val enableIntelligentCompaction: Boolean
    )

    /**
     * LLM 配置
     */
    class LlmConfig(
        val model: String = "claude-sonnet-4.5",
        val temperature: Double,
        val maxTokens: Int
    )
}
