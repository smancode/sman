package com.smancode.smanagent.config

import com.smancode.smanagent.analysis.config.BgeM3Config
import com.smancode.smanagent.analysis.config.RerankerConfig
import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.config.VectorDbType
import com.smancode.smanagent.smancode.llm.LlmService
import com.smancode.smanagent.smancode.llm.config.LlmEndpoint
import com.smancode.smanagent.smancode.llm.config.LlmPoolConfig
import com.smancode.smanagent.smancode.llm.config.LlmRetryPolicy
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.Properties

/**
 * SmanAgent 配置管理器
 *
 * 从配置文件、环境变量和用户设置中读取配置
 *
 * 优先级：用户设置 > 环境变量 > 配置文件 > 默认值
 */
object SmanAgentConfig {

    private val logger = LoggerFactory.getLogger(SmanAgentConfig::class.java)

    // 用户配置缓存（从 StorageService 读取）
    private var userConfig: UserConfig? = null

    /**
     * 用户配置
     */
    data class UserConfig(
        val llmApiKey: String = "",
        val llmBaseUrl: String = "",
        val llmModelName: String = ""
    )

    private val properties: Properties by lazy {
        loadProperties()
    }

    // ==================== 类型安全的配置读取方法 ====================

    private fun getString(key: String, default: String): String = getProperty(key) ?: default

    private inline fun <reified T : Any> getConfigValue(key: String, default: T): T {
        val value = getProperty(key) ?: return default
        return when (T::class) {
            String::class -> value as T
            Int::class -> value.toIntOrNull() as? T ?: default
            Long::class -> value.toLongOrNull() as? T ?: default
            Double::class -> value.toDoubleOrNull() as? T ?: default
            Boolean::class -> value.toBoolean() as? T ?: default
            else -> default
        }
    }

    // ==================== LLM API 配置 ====================

    /**
     * 设置用户配置（从 StorageService 调用）
     */
    fun setUserConfig(config: UserConfig) {
        userConfig = config
        logger.info("更新用户配置: baseUrl={}, model={}",
            config.llmBaseUrl.takeIf { it.isNotEmpty() } ?: "(未设置)",
            config.llmModelName.takeIf { it.isNotEmpty() } ?: "(未设置)")
    }

    /**
     * 读取配置的通用方法：用户设置 > 配置文件/环境变量 > 默认值
     */
    private inline fun <T> readConfig(
        userValue: T?,
        configKey: String,
        default: T,
        crossinline fromConfig: (String) -> T?
    ): T {
        return userValue?.takeIf { it.toString().isNotEmpty() }
            ?: fromConfig(getString(configKey, ""))
            ?: default
    }

    /**
     * LLM API Key（优先级：用户设置 > 环境变量 > 配置文件）
     */
    val llmApiKey: String
        get() = userConfig?.llmApiKey?.takeIf { it.isNotEmpty() }
            ?: resolveApiKey(getString("llm.api.key", ""))
            ?: throw IllegalStateException(
                """
                |缺少 LLM API Key 配置。
                |
                |请在以下位置之一配置：
                |
                |1. SmanAgent 设置界面（推荐）
                |   打开工具窗口，点击设置按钮，填写 API Key
                |
                |2. 配置文件: src/main/resources/smanagent.properties
                |   llm.api.key=your_api_key_here
                |
                |3. 环境变量: LLM_API_KEY
                |   export LLM_API_KEY=your_api_key_here
                |
                |4. IDE 运行配置: Run → Edit Configurations → Environment variables
                """.trimMargin()
            )

    /**
     * LLM API 基础 URL（优先级：用户设置 > 配置文件 > 默认值）
     */
    val llmBaseUrl: String
        get() = readConfig(
            userValue = userConfig?.llmBaseUrl,
            configKey = "llm.base.url",
            default = "https://open.bigmodel.cn/api/coding/paas/v4",
            fromConfig = { it }
        ).also {
            logger.info("使用 Base URL: $it")
        }

    /**
     * LLM 模型名称（优先级：用户设置 > 配置文件 > 默认值）
     */
    val llmModelName: String
        get() = readConfig(
            userValue = userConfig?.llmModelName,
            configKey = "llm.model.name",
            default = "glm-4-flash",
            fromConfig = { it }
        ).also {
            logger.info("使用模型: $it")
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

    // ==================== 其他 LLM 配置 ====================

    val llmResponseMaxTokens: Int by lazy { getConfigValue("llm.response.max.tokens", 8192) }
    val llmRetryMax: Int by lazy { getConfigValue("llm.retry.max", 3) }
    val llmRetryBaseDelay: Long by lazy { getConfigValue("llm.retry.base.delay", 1000L) }
    val llmConnectTimeout: Int by lazy { getConfigValue("llm.timeout.connect", 30000) }
    val llmReadTimeout: Int by lazy { getConfigValue("llm.timeout.read", 60000) }
    val llmWriteTimeout: Int by lazy { getConfigValue("llm.timeout.write", 30000) }

    // ==================== ReAct 循环配置 ====================

    val reactMaxSteps: Int by lazy { getConfigValue("react.max.steps", 10) }
    val reactEnableStreaming: Boolean by lazy { getConfigValue("react.enable.streaming", true) }

    // ==================== 上下文压缩配置 ====================

    val compactionMaxTokens: Int by lazy { getConfigValue("compaction.max.tokens", 100000) }
    val compactionThreshold: Int by lazy { getConfigValue("compaction.threshold", 80000) }
    val compactionEnableIntelligent: Boolean by lazy { getConfigValue("compaction.enable.intelligent", true) }

    // ==================== 模型参数配置 ====================

    val llmTemperature: Double by lazy { getConfigValue("llm.temperature", 0.7) }
    val llmDefaultMaxTokens: Int by lazy { getConfigValue("llm.default.max.tokens", 4096) }

    // ==================== 向量数据库配置 ====================

    val vectorDbType: VectorDbType by lazy {
        val typeStr = getString("vector.db.type", "JVECTOR").uppercase()
        try {
            VectorDbType.valueOf(typeStr)
        } catch (e: IllegalArgumentException) {
            logger.warn("未知的向量数据库类型: $typeStr，使用默认值 JVECTOR")
            VectorDbType.JVECTOR
        }
    }

    /**
     * 创建向量数据库配置（按项目隔离）
     *
     * 每个项目应该调用此方法创建独立的配置
     *
     * @param projectKey 项目标识符
     * @return 向量数据库配置（路径已包含 projectKey）
     */
    fun createVectorDbConfig(projectKey: String): VectorDatabaseConfig {
        val userHome = System.getProperty("user.home")
        return VectorDatabaseConfig.create(
            projectKey = projectKey,
            type = vectorDbType,
            jvector = com.smancode.smanagent.analysis.config.JVectorConfig(
                dimension = getConfigValue("vector.db.jvector.dimension", 1024),
                M = getConfigValue("vector.db.jvector.M", 16),
                efConstruction = getConfigValue("vector.db.jvector.efConstruction", 100),
                efSearch = getConfigValue("vector.db.jvector.efSearch", 50),
                enablePersist = getConfigValue("vector.db.jvector.enablePersist", true),
                rerankerThreshold = getConfigValue("vector.db.jvector.rerankerThreshold", 0.1)
            ),
            baseDir = userHome,
            vectorDimension = getConfigValue("vector.db.dimension", 1024),
            l1CacheSize = getConfigValue("vector.db.l1.cache.size", 500)
        )
    }

    /**
     * @deprecated 使用 createVectorDbConfig(projectKey) 代替
     * 此方法仅用于向后兼容，不应在新代码中使用
     */
    @Deprecated("使用 createVectorDbConfig(projectKey) 代替")
    val vectorDbConfig: VectorDatabaseConfig by lazy {
        // 使用默认 projectKey = "default"（不推荐）
        createVectorDbConfig("default")
    }

    // ==================== BGE-M3 配置 ====================

    val bgeM3Config: BgeM3Config? by lazy {
        val endpoint = getString("bge.endpoint", "")
        if (endpoint.isBlank()) {
            logger.warn("BGE-M3 未配置，向量化功能将不可用")
            null
        } else {
            BgeM3Config(
                endpoint = endpoint,
                modelName = getString("bge.model.name", "BAAI/bge-m3"),
                dimension = getConfigValue("bge.dimension", 1024),
                timeoutSeconds = getConfigValue("bge.timeout.seconds", 30),
                batchSize = getConfigValue("bge.batch.size", 10)
            )
        }
    }

    // ==================== BGE-Reranker 配置 ====================

    val rerankerConfig: RerankerConfig by lazy {
        RerankerConfig(
            enabled = getConfigValue("reranker.enabled", true),
            baseUrl = getString("reranker.base.url", "http://localhost:8001/v1"),
            model = getString("reranker.model", "BAAI/bge-reranker-v2-m3"),
            apiKey = getString("reranker.api.key", ""),
            timeoutSeconds = getConfigValue("reranker.timeout.seconds", 30),
            retry = getConfigValue("reranker.retry", 2),
            maxRounds = getConfigValue("reranker.max.rounds", 3),
            topK = getConfigValue("reranker.top.k", 15),
            threshold = getConfigValue("reranker.threshold", 0.1)
        )
    }

    // ==================== 项目分析配置 ====================

    /**
     * 强制刷新项目分析（跳过缓存）
     *
     * 开发阶段设置为 true，每次都重新分析
     * 生产环境设置为 false，利用 MD5 缓存提升性能
     */
    val analysisForceRefresh: Boolean by lazy {
        getConfigValue("analysis.force.refresh", false)
    }

    /**
     * 是否启用 LLM 代码向量化
     *
     * 启用后会对每个源文件调用 LLM 进行精读分析，生成业务描述
     */
    val analysisLlmVectorizationEnabled: Boolean by lazy {
        getConfigValue("analysis.llm.vectorization.enabled", false)
    }

    /**
     * LLM 代码向量化全量刷新
     *
     * 设置为 true 时，会忽略 MD5 缓存，对所有文件重新进行 LLM 分析
     */
    val analysisLlmVectorizationFullRefresh: Boolean by lazy {
        getConfigValue("analysis.llm.vectorization.full.refresh", false)
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
    fun getConfigSummary(): String = """
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

        向量数据库配置:
        - 类型: $vectorDbType
        - 维度: ${vectorDbConfig.vectorDimension}
        - L1缓存: ${vectorDbConfig.l1CacheSize}条
        - H2路径: ${vectorDbConfig.databasePath}

        BGE-M3 配置:
        - 端点: ${bgeM3Config?.endpoint ?: "(未配置)"}
        - 模型: ${bgeM3Config?.modelName ?: "(未配置)"}

        BGE-Reranker 配置:
        - 启用: ${rerankerConfig.enabled}
        - 端点: ${rerankerConfig.baseUrl}
        - 模型: ${rerankerConfig.model}
    """.trimIndent()

    /**
     * 创建 LLM 服务（使用当前最新配置）
     * 每次调用都创建新实例，确保使用最新配置
     */
    fun createLlmService(): LlmService {
        val poolConfig = LlmPoolConfig().apply {
            endpoints.add(
                LlmEndpoint().apply {
                    baseUrl = llmBaseUrl
                    apiKey = llmApiKey
                    model = llmModelName
                    maxTokens = llmResponseMaxTokens
                    isEnabled = true
                }
            )
            retry = LlmRetryPolicy().apply {
                maxRetries = llmRetryMax
                baseDelay = llmRetryBaseDelay
            }
        }
        return LlmService(poolConfig)
    }
}
