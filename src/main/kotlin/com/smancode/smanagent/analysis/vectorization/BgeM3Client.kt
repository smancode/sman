package com.smancode.smanagent.analysis.vectorization

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.smancode.smanagent.analysis.config.BgeM3Config
import com.smancode.smanagent.analysis.config.RerankerConfig
import com.smancode.smanagent.analysis.retry.*
import com.smancode.smanagent.analysis.vectorization.TruncationStrategy as VectorTruncationStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP 客户端工具
 *
 * 提供通用的 HTTP 客户端创建和资源清理功能
 */
private object HttpClientUtils {
    private const val DEFAULT_TIMEOUT_SECONDS = 30

    fun createClient(timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()

    fun closeClient(client: OkHttpClient) {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/**
 * JSON 字符串转义工具
 */
private object JsonEscapeUtils {
    /**
     * 转义 JSON 字符串值
     */
    fun escape(value: String): String =
        value
            .replace("\\", "\\\\")  // 反斜杠必须先替换
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}

/**
 * BGE-M3 客户端（增强版）
 *
 * 调用 BGE-M3 服务进行文本向量化
 *
 * 特性：
 * - 自适应文本截断（检测长度错误并逐步缩小）
 * - 增强版重试策略（指数退避 + 抖动）
 * - 并发限流（控制并发请求数）
 * - 熔断器保护（连续失败后暂停）
 * - 分片批处理（批量失败后单条重试）
 * - 监控指标收集
 */
class BgeM3Client(
    private val config: BgeM3Config
) {
    private val logger = LoggerFactory.getLogger(BgeM3Client::class.java)

    // 自适应截断器
    private val truncation = AdaptiveTruncation(
        maxTokens = config.maxTokens,
        stepSize = config.truncationStepSize,
        maxRetries = config.maxTruncationRetries,
        strategy = when (config.truncationStrategy) {
            com.smancode.smanagent.analysis.config.TruncationStrategy.HEAD -> VectorTruncationStrategy.HEAD
            com.smancode.smanagent.analysis.config.TruncationStrategy.TAIL -> VectorTruncationStrategy.TAIL
            com.smancode.smanagent.analysis.config.TruncationStrategy.MIDDLE -> VectorTruncationStrategy.MIDDLE
            com.smancode.smanagent.analysis.config.TruncationStrategy.SMART -> VectorTruncationStrategy.SMART
        }
    )

    // 增强版重试执行器
    private val retryExecutor = EnhancedRetryExecutor(
        policy = EnhancedRetryPolicy(
            maxRetries = config.maxRetries,
            baseDelayMs = config.baseDelayMs,
            retryCondition = { e ->
                // 排除长度错误（由截断器处理）和熔断器错误
                !truncation.isLengthError(e) &&
                e !is CircuitBreakerOpenException &&
                shouldRetryLogic(e)
            }
        ),
        metricsCollector = RetryMetricsCollector.GLOBAL
    )

    // 并发限流器
    private val limiter = ConcurrencyLimiter.forBge(config.concurrentLimit)

    // 熔断器
    private val circuitBreaker = CircuitBreaker.forBge(
        failureThreshold = config.circuitBreakerThreshold
    )

    private val client = HttpClientUtils.createClient(config.timeoutSeconds)
    private val objectMapper = jacksonObjectMapper().apply {
        registerKotlinModule()
        registerModule(FloatArrayModule())
        configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    companion object {
        private const val CONTENT_TYPE_JSON = "application/json"
        private const val EMBEDDINGS_ENDPOINT = "/v1/embeddings"
    }

    init {
        logger.info("初始化 BGE-M3 客户端: endpoint={}, maxTokens={}, concurrentLimit={}",
            config.endpoint, config.maxTokens, config.concurrentLimit)
    }

    /**
     * 判断是否应该重试（原有逻辑）
     */
    private fun shouldRetryLogic(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: return false

        return when {
            // 超时错误
            message.contains("timeout") ||
            message.contains("timed out") ||
            message.contains("SocketTimeoutException") -> true

            // 429 Too Many Requests
            message.contains("429") -> true

            // 5xx 服务器错误
            message.contains("50") || message.contains("HTTP error 5") -> true

            // 连接错误
            message.contains("ConnectException") ||
            message.contains("Connection refused") -> true

            else -> false
        }
    }

    /**
     * 生成文本向量（带自适应截断）
     *
     * @param text 输入文本
     * @param identifier 标识符（用于日志，可选）
     * @return 向量数组
     */
    fun embed(text: String, identifier: String = "unknown"): FloatArray {
        require(text.isNotBlank()) { "输入文本不能为空" }

        // 预处理截断
        val processedText = truncation.preprocessText(text, identifier)

        return limiter.executeBlocking {
            circuitBreaker.executeBlocking {
                retryExecutor.executeWithRetryBlocking("BGE-Embed-$identifier") {
                    doEmbed(processedText)
                }
            }
        }
    }

    /**
     * 批量生成向量
     *
     * @param texts 输入文本列表
     * @return 向量数组列表
     */
    fun batchEmbed(texts: List<String>): List<FloatArray> {
        require(texts.isNotEmpty()) { "输入文本列表不能为空" }
        require(texts.size <= config.batchSize) { "批次大小不能超过 ${config.batchSize}" }

        // 串行处理
        return texts.map { text ->
            embed(text, "batch-${text.hashCode()}")
        }
    }

    /**
     * 执行单次嵌入请求
     */
    private fun doEmbed(text: String): FloatArray {
        val escapedText = JsonEscapeUtils.escape(text)
        val requestJson = """{"input":"$escapedText","model":"${config.modelName}"}"""

        logger.debug("调用 BGE-M3: text.length={}, endpoint={}", text.length, config.endpoint)

        val request = Request.Builder()
            .url("${config.endpoint}$EMBEDDINGS_ENDPOINT")
            .post(requestJson.toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                throw IOException("BGE-M3 调用失败: HTTP ${response.code}, body=$errorBody")
            }

            val responseBody = response.body?.string() ?: throw RuntimeException("响应为空")
            val embedResponse = parseEmbedResponse(responseBody)
            return embedResponse.embedding
        }
    }

    /**
     * 解析嵌入响应（使用 Jackson）
     */
    private fun parseEmbedResponse(json: String): EmbedResponse {
        logger.debug("解析嵌入响应: {}", json.take(200))

        require(json.isNotBlank()) { "响应为空" }

        return tryParseBgeResponse(json) ?: tryParseSimpleResponse(json)
    }

    /**
     * 尝试解析为 BGE 完整响应格式
     */
    private fun tryParseBgeResponse(json: String): EmbedResponse? =
        try {
            val bgeResponse = objectMapper.readValue<BgeEmbedResponse>(json)
            val embedding = bgeResponse.data?.firstOrNull()?.embedding
                ?: throw RuntimeException("BGE 响应中没有 embedding 数据")

            require(embedding.isNotEmpty()) { "embedding 数组为空" }

            EmbedResponse(embedding = embedding)
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            logger.debug("无法解析为 BGE 格式，尝试简化格式")
            null
        }

    /**
     * 尝试解析为简化响应格式
     */
    private fun tryParseSimpleResponse(json: String): EmbedResponse =
        try {
            objectMapper.readValue<EmbedResponse>(json)
        } catch (e: Exception) {
            logger.error("解析简化响应失败: json={}, error={}", json.take(500), e.message)
            throw RuntimeException("无法解析嵌入响应: ${e.message}", e)
        }

    /**
     * 获取统计信息
     */
    fun getStatistics(): BgeClientStatistics {
        return BgeClientStatistics(
            limiterStats = limiter.getStatistics(),
            circuitBreakerStats = circuitBreaker.getStatistics(),
            truncationStats = truncation.getStatistics(),
            retryMetrics = retryExecutor.getPolicy()
        )
    }

    /**
     * 关闭客户端
     */
    fun close() = HttpClientUtils.closeClient(client)

    /**
     * 重置熔断器
     */
    fun resetCircuitBreaker() {
        circuitBreaker.reset()
    }

    /**
     * 重置截断历史
     */
    fun clearTruncationHistory() {
        truncation.clearHistory()
    }
}

/**
 * BGE 客户端统计信息
 */
data class BgeClientStatistics(
    val limiterStats: ConcurrencyLimiter.ConcurrencyStatistics,
    val circuitBreakerStats: CircuitBreaker.CircuitBreakerStatistics,
    val truncationStats: AdaptiveTruncation.TruncationStatistics,
    val retryMetrics: EnhancedRetryPolicy
) {
    override fun toString(): String {
        return """
            |BGE 客户端统计:
            |$limiterStats
            |$circuitBreakerStats
            |$truncationStats
            |重试策略: maxRetries=${retryMetrics.maxRetries}
        """.trimMargin()
    }
}

/**
 * Reranker 客户端（保持原有实现，兼容旧代码）
 */
class RerankerClient(
    private val config: RerankerConfig
) {
    private val logger = LoggerFactory.getLogger(RerankerClient::class.java)

    // 使用增强版重试执行器
    private val retryExecutor = EnhancedRetryExecutor(
        policy = EnhancedRetryPolicy(
            maxRetries = config.retry,
            baseDelayMs = 1000,
            retryCondition = { e ->
                val message = e.message?.lowercase() ?: return@EnhancedRetryPolicy false
                message.contains("timeout") ||
                message.contains("429") ||
                message.contains("50") ||
                message.contains("connect")
            }
        ),
        metricsCollector = RetryMetricsCollector.GLOBAL
    )

    private val client = HttpClientUtils.createClient(config.timeoutSeconds)

    companion object {
        private const val CONTENT_TYPE_JSON = "application/json"
        private const val RERANK_ENDPOINT = "/rerank"
    }

    /**
     * 重排结果（带重试）
     */
    fun rerank(query: String, documents: List<String>, topK: Int = config.topK): List<Int> {
        if (!config.enabled) {
            logger.debug("Reranker 未启用，返回原始顺序")
            return documents.indices.toList()
        }

        require(query.isNotBlank()) { "查询文本不能为空" }
        require(documents.isNotEmpty()) { "文档列表不能为空" }
        require(topK > 0) { "topK 必须大于 0" }

        return try {
            retryExecutor.executeWithRetryBlocking("Reranker") {
                doRerank(query, documents, topK)
            }
        } catch (e: Exception) {
            logger.warn("Reranker 调用失败，使用原始顺序: {}", e.message)
            documents.indices.toList()
        }
    }

    /**
     * 重排结果（带分数过滤）
     */
    fun rerankWithScores(query: String, documents: List<String>, topK: Int = config.topK): List<Pair<Int, Double>> {
        if (!config.enabled) {
            logger.debug("Reranker 未启用，返回原始顺序")
            return documents.indices.map { it to 1.0 }
        }

        require(query.isNotBlank()) { "查询文本不能为空" }
        require(documents.isNotEmpty()) { "文档列表不能为空" }
        require(topK > 0) { "topK 必须大于 0" }

        return try {
            retryExecutor.executeWithRetryBlocking("Reranker-WithScores") {
                doRerankWithScores(query, documents, topK)
            }
        } catch (e: Exception) {
            logger.warn("Reranker 调用失败，使用原始顺序: {}", e.message)
            documents.indices.map { it to 1.0 }
        }
    }

    /**
     * 执行单次重排请求
     */
    private fun doRerank(query: String, documents: List<String>, topK: Int): List<Int> {
        val escapedQuery = JsonEscapeUtils.escape(query)
        val escapedDocs = documents.joinToString(",") { "\"${JsonEscapeUtils.escape(it)}\"" }
        val requestJson = """
            {"model":"${config.model}","query":"$escapedQuery","documents":[$escapedDocs],"top_k":$topK}
        """.trimIndent().replace("\n", "")

        logger.debug("调用 Reranker: endpoint={}", config.baseUrl)

        val request = Request.Builder()
            .url("${config.baseUrl}$RERANK_ENDPOINT")
            .post(requestJson.toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Reranker 调用失败: HTTP ${response.code}")
            }

            val responseBody = response.body?.string() ?: throw RuntimeException("响应为空")
            return parseRerankResponse(responseBody)
        }
    }

    /**
     * 执行单次重排请求（带分数）
     */
    private fun doRerankWithScores(query: String, documents: List<String>, topK: Int): List<Pair<Int, Double>> {
        val escapedQuery = JsonEscapeUtils.escape(query)
        val escapedDocs = documents.joinToString(",") { "\"${JsonEscapeUtils.escape(it)}\"" }
        val requestJson = """
            {"model":"${config.model}","query":"$escapedQuery","documents":[$escapedDocs],"top_k":$topK}
        """.trimIndent().replace("\n", "")

        logger.debug("调用 Reranker: endpoint={}", config.baseUrl)

        val request = Request.Builder()
            .url("${config.baseUrl}$RERANK_ENDPOINT")
            .post(requestJson.toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Reranker 调用失败: HTTP ${response.code}")
            }

            val responseBody = response.body?.string() ?: throw RuntimeException("响应为空")
            return parseRerankResponseWithScores(responseBody)
        }
    }

    /**
     * 解析重排响应
     */
    private fun parseRerankResponse(json: String): List<Int> {
        val resultsPattern = Regex("\"results\"\\s*:\\s*\\[(.*?)\\]")
        val match = resultsPattern.find(json) ?: throw RuntimeException("无法解析重排响应")

        val resultsStr = match.groupValues[1]
        val indexPattern = Regex("\"index\"\\s*:\\s*(\\d+)")

        return indexPattern.findAll(resultsStr)
            .map { it.groupValues[1].toInt() }
            .toList()
    }

    /**
     * 解析重排响应（带分数）
     */
    private fun parseRerankResponseWithScores(json: String): List<Pair<Int, Double>> {
        val resultsPattern = Regex("\"results\"\\s*:\\s*\\[(.*?)\\]")
        val match = resultsPattern.find(json) ?: throw RuntimeException("无法解析重排响应")

        val resultsStr = match.groupValues[1]
        val resultPattern = Regex("\\{[^}]*\"index\"\\s*:\\s*(\\d+)[^}]*\"relevance_score\"\\s*:\\s*([0-9.]+)[^}]*\\}")

        val allResults = resultPattern.findAll(resultsStr)
            .map { match ->
                val index = match.groupValues[1].toInt()
                val score = match.groupValues[2].toDouble()
                index to score
            }
            .toList()

        logger.info("Reranker 原始分数: {}", allResults.map { (idx, score) -> "[$idx]=$score" })

        // 过滤低于阈值的结果
        val filtered = allResults.filter { (_, score) -> score >= config.threshold }
        if (filtered.size < allResults.size) {
            logger.info("Reranker 过滤: 原始={}, 阈值={}, 过滤后={}", allResults.size, config.threshold, filtered.size)
        }

        return filtered
    }

    /**
     * 关闭客户端
     */
    fun close() = HttpClientUtils.closeClient(client)
}

/**
 * FloatArray Jackson 反序列化模块
 */
private class FloatArrayModule : SimpleModule() {
    init {
        addDeserializer(FloatArray::class.java, object : JsonDeserializer<FloatArray>() {
            override fun deserialize(p: JsonParser, ctxt: DeserializationContext): FloatArray {
                if (!p.isExpectedStartArrayToken) {
                    ctxt.instantiationException(FloatArray::class.java, "Expected array")
                }
                val result = mutableListOf<Float>()
                while (p.nextToken() != null) {
                    val value = p.currentToken()
                    if (value == com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                        break
                    }
                    if (value == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_FLOAT ||
                        value == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT) {
                        result.add(p.valueAsDouble.toFloat())
                    }
                }
                return result.toFloatArray()
            }
        })
    }
}

/**
 * FloatArray 比较工具
 */
private object FloatArrayComparator {
    fun equals(a: FloatArray, b: FloatArray): Boolean = a.contentEquals(b)
    fun hashCode(a: FloatArray): Int = a.contentHashCode()
}

/**
 * BGE-M3 API 完整响应结构
 */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class BgeEmbedResponse(
    val data: List<BgeEmbedData>? = null,
    val model: String? = null,
    val `object`: String? = null,
    val usage: BgeUsage? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BgeEmbedData(
    val embedding: FloatArray,
    val index: Int? = null,
    val `object`: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BgeEmbedData
        return FloatArrayComparator.equals(embedding, other.embedding)
    }

    override fun hashCode(): Int = FloatArrayComparator.hashCode(embedding)
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BgeUsage(
    val prompt_tokens: Int? = null,
    val total_tokens: Int? = null
)

/**
 * 嵌入响应（简化版）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EmbedResponse(
    val embedding: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EmbedResponse
        return FloatArrayComparator.equals(embedding, other.embedding)
    }

    override fun hashCode(): Int = FloatArrayComparator.hashCode(embedding)
}

/**
 * 嵌入数据（简化版）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EmbedData(
    val embedding: FloatArray,
    val index: Int? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EmbedData
        return FloatArrayComparator.equals(embedding, other.embedding)
    }

    override fun hashCode(): Int = FloatArrayComparator.hashCode(embedding)
}
