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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
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
 * 重试策略
 *
 * 提供统一的重试逻辑，包括：
 * - 判断是否应该重试
 * - 计算退避延迟
 * - 执行重试等待
 */
private class RetryStrategy(
    private val maxRetries: Int,
    private val baseDelay: Long = 1000L,
    private val logger: org.slf4j.Logger
) {
    /**
     * 判断异常是否应该重试
     */
    fun shouldRetry(e: Exception, retryCount: Int): Boolean {
        if (retryCount >= maxRetries) return false

        val message = e.message ?: return false

        return when {
            // 超时错误
            message.contains("timeout", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) ||
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
     * 计算重试延迟（指数退避）
     */
    fun calculateDelay(retryCount: Int): Long = baseDelay * retryCount

    /**
     * 等待指定时间
     */
    fun sleep(milliseconds: Long) {
        try {
            Thread.sleep(milliseconds)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException("重试等待被中断", e)
        }
    }

    /**
     * 执行带重试的操作
     *
     * @param operation 要执行的操作
     * @param operationName 操作名称（用于日志）
     * @return 操作结果
     */
    fun <T> executeWithRetry(
        operation: () -> T,
        operationName: String
    ): T {
        var retryCount = 0

        while (true) {
            try {
                return operation()
            } catch (e: Exception) {
                logger.warn("{} 失败 (尝试 {}): {}", operationName, retryCount, e.message)

                if (retryCount >= maxRetries) {
                    throw RuntimeException("$operationName 失败：超过最大重试次数 ($maxRetries)", e)
                }

                if (shouldRetry(e, retryCount)) {
                    retryCount++
                    val delay = calculateDelay(retryCount)
                    logger.info("等待 {}ms 后进行第 {} 次重试...", delay, retryCount)
                    sleep(delay)
                } else {
                    throw RuntimeException("$operationName 失败: ${e.message}", e)
                }
            }
        }
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
 * BGE-M3 客户端
 *
 * 调用 BGE-M3 服务进行文本向量化
 *
 * 特性：
 * - 自动重试（超时、429、5xx 错误）
 * - 指数退避策略
 * - 详细的日志记录
 */
class BgeM3Client(
    private val config: BgeM3Config
) {
    private val logger = LoggerFactory.getLogger(BgeM3Client::class.java)
    private val retryStrategy = RetryStrategy(maxRetries = 3, logger = logger)
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

    /**
     * 生成文本向量（带重试）
     *
     * @param text 输入文本
     * @return 向量数组
     */
    fun embed(text: String): FloatArray {
        require(text.isNotBlank()) { "输入文本不能为空" }

        return retryStrategy.executeWithRetry(
            operation = { doEmbed(text) },
            operationName = "BGE-M3 调用"
        )
    }

    /**
     * 执行单次嵌入请求
     */
    private fun doEmbed(text: String): FloatArray {
        val escapedText = JsonEscapeUtils.escape(text)
        val requestJson = """{"input":"$escapedText","model":"${config.modelName}"}"""

        logger.debug("Calling BGE-M3 endpoint: {}", config.endpoint)

        val request = Request.Builder()
            .url("${config.endpoint}$EMBEDDINGS_ENDPOINT")
            .post(requestJson.toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("BGE-M3 调用失败: HTTP ${response.code}")
            }

            val responseBody = response.body?.string() ?: throw RuntimeException("响应为空")
            val embedResponse = parseEmbedResponse(responseBody)
            return embedResponse.embedding
        }
    }

    /**
     * 批量生成向量（带重试）
     *
     * @param texts 输入文本列表
     * @return 向量数组列表
     */
    fun batchEmbed(texts: List<String>): List<FloatArray> {
        require(texts.isNotEmpty()) { "输入文本列表不能为空" }
        require(texts.size <= config.batchSize) { "批次大小不能超过 ${config.batchSize}" }

        return retryStrategy.executeWithRetry(
            operation = { doBatchEmbed(texts) },
            operationName = "BGE-M3 批量调用"
        )
    }

    /**
     * 执行单次批量嵌入请求
     */
    private fun doBatchEmbed(texts: List<String>): List<FloatArray> {
        val escapedTexts = texts.joinToString(",") { "\"${JsonEscapeUtils.escape(it)}\"" }
        val requestJson = """{"input":[$escapedTexts],"model":"${config.modelName}"}"""

        val request = Request.Builder()
            .url("${config.endpoint}$EMBEDDINGS_ENDPOINT")
            .post(requestJson.toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("BGE-M3 批量调用失败: HTTP ${response.code}")
            }

            val responseBody = response.body?.string() ?: throw RuntimeException("响应为空")
            val embedResponse = parseBatchEmbedResponse(responseBody)
            return embedResponse.data?.map { it.embedding } ?: throw RuntimeException("批量响应 data 为空")
        }
    }

    /**
     * 解析嵌入响应（使用 Jackson）
     */
    private fun parseEmbedResponse(json: String): EmbedResponse {
        logger.debug("解析嵌入响应: {}", json)

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
     * 解析批量嵌入响应（使用 Jackson）
     */
    private fun parseBatchEmbedResponse(json: String): BatchEmbedResponse {
        logger.debug("解析批量嵌入响应: {}", json.take(500))

        return try {
            val bgeResponse = objectMapper.readValue<BgeEmbedResponse>(json)
            val data = bgeResponse.data?.map {
                EmbedData(embedding = it.embedding, index = it.index)
            }
            BatchEmbedResponse(data = data)
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            logger.debug("尝试解析为简化格式")
            try {
                objectMapper.readValue<BatchEmbedResponse>(json)
            } catch (ex: Exception) {
                logger.error("解析批量响应失败: json={}, error={}", json.take(500), ex.message)
                throw RuntimeException("无法解析批量嵌入响应: ${ex.message}", ex)
            }
        }
    }

    /**
     * 关闭客户端
     */
    fun close() = HttpClientUtils.closeClient(client)
}

/**
 * Reranker 客户端
 *
 * 调用 BGE-Reranker 服务进行结果重排
 *
 * 特性：
 * - 自动重试（超时、429、5xx 错误）
 * - 指数退避策略
 * - 失败时优雅降级（返回原始顺序）
 */
class RerankerClient(
    private val config: RerankerConfig
) {
    private val logger = LoggerFactory.getLogger(RerankerClient::class.java)
    private val retryStrategy = RetryStrategy(
        maxRetries = config.retry,
        logger = logger
    )
    private val client = HttpClientUtils.createClient(config.timeoutSeconds)

    companion object {
        private const val CONTENT_TYPE_JSON = "application/json"
        private const val RERANK_ENDPOINT = "/rerank"
    }

    /**
     * 重排结果（带重试）
     *
     * @param query 查询文本
     * @param documents 候选文档列表
     * @param topK 返回 top K
     * @return 重排序后的索引
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
            retryStrategy.executeWithRetry(
                operation = { doRerank(query, documents, topK) },
                operationName = "Reranker 调用"
            )
        } catch (e: Exception) {
            logger.warn("Reranker 调用失败，使用原始顺序: {}", e.message)
            documents.indices.toList()
        }
    }

    /**
     * 重排结果（带分数过滤）
     *
     * @param query 查询文本
     * @param documents 候选文档列表
     * @param topK 返回 top K
     * @return 重排序后的索引和分数，分数低于 threshold 的结果被过滤
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
            retryStrategy.executeWithRetry(
                operation = { doRerankWithScores(query, documents, topK) },
                operationName = "Reranker 调用（带分数）"
            )
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

        logger.debug("Calling Reranker endpoint: {}", config.baseUrl)

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

        logger.debug("Calling Reranker endpoint: {}", config.baseUrl)

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
     *
     * Reranker 响应格式：{"results": [{"index": 0, "relevance_score": 0.95}, ...]}
     */
    private fun parseRerankResponseWithScores(json: String): List<Pair<Int, Double>> {
        val resultsPattern = Regex("\"results\"\\s*:\\s*\\[(.*?)\\]")
        val match = resultsPattern.find(json) ?: throw RuntimeException("无法解析重排响应")

        val resultsStr = match.groupValues[1]

        // 解析每个结果的 index 和 relevance_score
        val resultPattern = Regex("\\{[^}]*\"index\"\\s*:\\s*(\\d+)[^}]*\"relevance_score\"\\s*:\\s*([0-9.]+)[^}]*\\}")

        return resultPattern.findAll(resultsStr)
            .map { match ->
                val index = match.groupValues[1].toInt()
                val score = match.groupValues[2].toDouble()
                index to score
            }
            .filter { (_, score) -> score >= config.threshold }
            .toList()
    }

    /**
     * 关闭客户端
     */
    fun close() = HttpClientUtils.closeClient(client)
}

/**
 * FloatArray Jackson 反序列化模块
 *
 * 支持 Jackson 正确反序列化 FloatArray
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
 *
 * 为包含 FloatArray 的数据类提供标准的 equals 和 hashCode 实现
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
 * 嵌入响应（简化版，用于向后兼容）
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
 * 嵌入数据（简化版，用于向后兼容）
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

/**
 * 批量嵌入响应（简化版，用于向后兼容）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BatchEmbedResponse(
    val data: List<EmbedData>? = null
)
