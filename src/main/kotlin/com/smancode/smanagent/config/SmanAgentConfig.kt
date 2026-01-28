package com.smancode.smanagent.config

import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.Properties

/**
 * SmanAgent 配置管理器
 *
 * 从配置文件和环境变量中读取配置
 */
object SmanAgentConfig {

    private val logger = LoggerFactory.getLogger(SmanAgentConfig::class.java)

    private val properties: Properties by lazy {
        loadProperties()
    }

    // ==================== 类型安全的配置读取方法 ====================

    /**
     * 获取 String 配置
     */
    private fun getString(key: String, default: String): String {
        return getProperty(key) ?: default
    }

    /**
     * 获取 Int 配置
     */
    private fun getInt(key: String, default: Int): Int {
        return getProperty(key)?.toIntOrNull() ?: default
    }

    /**
     * 获取 Long 配置
     */
    private fun getLong(key: String, default: Long): Long {
        return getProperty(key)?.toLongOrNull() ?: default
    }

    /**
     * 获取 Double 配置
     */
    private fun getDouble(key: String, default: Double): Double {
        return getProperty(key)?.toDoubleOrNull() ?: default
    }

    /**
     * 获取 Boolean 配置
     */
    private fun getBoolean(key: String, default: Boolean): Boolean {
        return getProperty(key)?.toBoolean() ?: default
    }

    // ==================== LLM API 配置 ====================

    /**
     * LLM API Key
     *
     * 支持环境变量占位符格式：${ENV_VAR_NAME}
     */
    val llmApiKey: String by lazy {
        val configValue = getString("llm.api.key", "")
        resolveApiKey(configValue) ?: throw IllegalStateException(
            """
            缺少 LLM API Key 配置。

            请在以下位置之一配置：

            1. 配置文件: src/main/resources/smanagent.properties
               llm.api.key=your_api_key_here

            2. 环境变量: LLM_API_KEY
               export LLM_API_KEY=your_api_key_here

            3. 环境变量占位符: $'{ENV_VAR_NAME}'
               在配置文件中: llm.api.key=$'{YOUR_API_KEY_VAR}'

            4. IDE 运行配置: Run → Edit Configurations → Environment variables
            """.trimIndent()
        )
    }

    /**
     * 解析 API Key 配置
     *
     * 支持三种格式：
     * 1. 环境变量占位符: ${ENV_VAR_NAME}
     * 2. 直接配置的值
     * 3. 从默认环境变量 LLM_API_KEY 读取
     */
    private fun resolveApiKey(configValue: String): String? {
        // 1. 检查环境变量占位符: ${ENV_VAR_NAME}
        val placeholderMatch = Regex("""^\$\{(.+)}$""").find(configValue)
        if (placeholderMatch != null) {
            val envVarName = placeholderMatch.groupValues[1]
            return System.getenv(envVarName)
        }

        // 2. 直接配置的值
        if (configValue.isNotEmpty()) {
            return configValue
        }

        // 3. 尝试默认环境变量
        return System.getenv("LLM_API_KEY")
    }

    /**
     * LLM API 基础 URL
     */
    val llmBaseUrl: String by lazy {
        getString("llm.base.url", "https://open.bigmodel.cn/api/paas/v4/chat/completions")
    }

    /**
     * LLM 模型名称
     */
    val llmModelName: String by lazy {
        getString("llm.model.name", "glm-4-flash")
    }

    /**
     * LLM 响应最大 Token 数
     */
    val llmResponseMaxTokens: Int by lazy {
        getInt("llm.response.max.tokens", 8192)
    }

    /**
     * 最大重试次数
     */
    val llmRetryMax: Int by lazy {
        getInt("llm.retry.max", 3)
    }

    /**
     * 重试基础延迟（毫秒）
     */
    val llmRetryBaseDelay: Long by lazy {
        getLong("llm.retry.base.delay", 1000L)
    }

    /**
     * 连接超时（毫秒）
     */
    val llmConnectTimeout: Int by lazy {
        getInt("llm.timeout.connect", 30000)
    }

    /**
     * 读取超时（毫秒）
     */
    val llmReadTimeout: Int by lazy {
        getInt("llm.timeout.read", 60000)
    }

    /**
     * 写入超时（毫秒）
     */
    val llmWriteTimeout: Int by lazy {
        getInt("llm.timeout.write", 30000)
    }

    // ==================== ReAct 循环配置 ====================

    /**
     * ReAct 最大步数
     */
    val reactMaxSteps: Int by lazy {
        getInt("react.max.steps", 10)
    }

    /**
     * 启用流式输出
     */
    val reactEnableStreaming: Boolean by lazy {
        getBoolean("react.enable.streaming", true)
    }

    // ==================== 上下文压缩配置 ====================

    /**
     * 压缩最大 Token 数
     */
    val compactionMaxTokens: Int by lazy {
        getInt("compaction.max.tokens", 100000)
    }

    /**
     * 压缩阈值（Token 数）
     */
    val compactionThreshold: Int by lazy {
        getInt("compaction.threshold", 80000)
    }

    /**
     * 启用智能压缩
     */
    val compactionEnableIntelligent: Boolean by lazy {
        getBoolean("compaction.enable.intelligent", true)
    }

    // ==================== 模型参数配置 ====================

    /**
     * 温度参数
     */
    val llmTemperature: Double by lazy {
        getDouble("llm.temperature", 0.7)
    }

    /**
     * 默认最大 Token 数
     */
    val llmDefaultMaxTokens: Int by lazy {
        getInt("llm.default.max.tokens", 4096)
    }

    /**
     * 加载配置文件
     */
    private fun loadProperties(): Properties {
        val props = Properties()
        var inputStream: InputStream? = null

        try {
            // 从 classpath 加载配置文件
            inputStream = javaClass.getResourceAsStream("/smanagent.properties")
            if (inputStream != null) {
                props.load(inputStream)
                logger.info("成功加载配置文件: smanagent.properties")
            } else {
                logger.warn("配置文件 smanagent.properties 未找到，将使用默认配置和环境变量")
            }
        } catch (e: Exception) {
            logger.error("加载配置文件失败", e)
        } finally {
            inputStream?.close()
        }

        return props
    }

    /**
     * 获取配置属性
     */
    private fun getProperty(key: String): String? {
        return try {
            properties.getProperty(key)
        } catch (e: Exception) {
            logger.error("读取配置项失败: key={}", key, e)
            null
        }
    }

    /**
     * 重新加载配置
     */
    fun reload(): SmanAgentConfig {
        logger.info("重新加载配置")
        return this
    }

    /**
     * 获取配置摘要（用于日志）
     */
    fun getConfigSummary(): String {
        return """
            LLM 配置:
            - BaseUrl: $llmBaseUrl
            - Model: $llmModelName
            - ResponseMaxTokens: $llmResponseMaxTokens
            - Retry: ${llmRetryMax}次, 基础延迟${llmRetryBaseDelay}ms
            - 超时: 连接${llmConnectTimeout}ms, 读取${llmReadTimeout}ms, 写入${llmWriteTimeout}ms

            ReAct 循环配置:
            - 最大步数: $reactMaxSteps
            - 流式输出: $reactEnableStreaming

            上下文压缩配置:
            - 最大Tokens: $compactionMaxTokens
            - 压缩阈值: $compactionThreshold
            - 智能压缩: $compactionEnableIntelligent

            模型参数配置:
            - 温度: $llmTemperature
            - 默认最大Tokens: $llmDefaultMaxTokens
        """.trimIndent()
    }
}
