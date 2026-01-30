package com.smancode.smanagent.analysis.vectorization

import com.smancode.smanagent.analysis.config.BgeM3Config
import com.smancode.smanagent.analysis.config.RerankerConfig
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * BGE-M3 客户端
 *
 * 调用 BGE-M3 服务进行文本向量化
 */
class BgeM3Client(
    private val config: BgeM3Config
) {

    private val logger = LoggerFactory.getLogger(BgeM3Client::class.java)
    private val client = OkHttpClient.Builder()
        .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    /**
     * 生成文本向量
     *
     * @param text 输入文本
     * @return 向量数组
     */
    fun embed(text: String): FloatArray {
        require(text.isNotBlank()) {
            "输入文本不能为空"
        }

        val requestJson = """
            {
                "input": "${text.replace("\"", "\\\"").replace("\n", "\\n")}",
                "model": "${config.modelName}"
            }
        """.trimIndent()

        logger.debug("Calling BGE-M3 endpoint: ${config.endpoint}")

        val requestBody = requestJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${config.endpoint}/v1/embeddings")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("BGE-M3 调用失败: ${response.code}")
            }

            val responseBody = response.body?.string() ?: throw RuntimeException("响应为空")
            val embedResponse = parseEmbedResponse(responseBody)
            return embedResponse.embedding
        }
    }

    /**
     * 批量生成向量
     *
     * @param texts 输入文本列表
     * @return 向量数组列表
     */
    fun batchEmbed(texts: List<String>): List<FloatArray> {
        require(texts.isNotEmpty()) {
            "输入文本列表不能为空"
        }
        require(texts.size <= config.batchSize) {
            "批次大小不能超过 ${config.batchSize}"
        }

        val requestJson = """
            {
                "input": ${texts.map { "\"${it.replace("\"", "\\\"")}" }},
                "model": "${config.modelName}"
            }
        """.trimIndent()

        val requestBody = requestJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${config.endpoint}/v1/embeddings")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("BGE-M3 批量调用失败: ${response.code}")
            }

            val responseBody = response.body?.string() ?: throw RuntimeException("响应为空")
            val embedResponse = parseBatchEmbedResponse(responseBody)
            return embedResponse.data.map { it.embedding }
        }
    }

    /**
     * 解析嵌入响应
     */
    private fun parseEmbedResponse(json: String): EmbedResponse {
        // 简化的 JSON 解析（生产环境应使用 proper JSON 库）
        val embeddingPattern = Regex("\"embedding\"\\s*:\\s*\\[([\\d.,\\s]+)\\]")
        val match = embeddingPattern.find(json) ?: throw RuntimeException("无法解析嵌入响应")

        val embeddingStr = match.groupValues[1]
        val embedding = embeddingStr.split(",")
            .map { it.trim().toFloat() }
            .toFloatArray()

        return EmbedResponse(embedding = embedding)
    }

    /**
     * 解析批量嵌入响应
     */
    private fun parseBatchEmbedResponse(json: String): BatchEmbedResponse {
        // 简化的 JSON 解析
        val dataPattern = Regex("\"data\"\\s*:\\s*\\[(.*?)\\]")
        val match = dataPattern.find(json) ?: throw RuntimeException("无法解析批量嵌入响应")

        val dataStr = match.groupValues[1]
        val items = dataStr.split("},\\{")
            .map { item ->
                val embeddingPattern = Regex("\"embedding\"\\s*:\\s*\\[([\\d.,\\s]+)\\]")
                val embeddingMatch = embeddingPattern.find(item) ?: return@map null
                val embeddingStr = embeddingMatch.groupValues[1]
                val embedding = embeddingStr.split(",")
                    .map { it.trim().toFloat() }
                    .toFloatArray()
                EmbedData(embedding = embedding)
            }
            .filterNotNull()

        return BatchEmbedResponse(data = items)
    }

    /**
     * 关闭客户端
     */
    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/**
 * Reranker 客户端
 *
 * 调用 BGE-Reranker 服务进行结果重排
 */
class RerankerClient(
    private val config: RerankerConfig
) {

    private val logger = LoggerFactory.getLogger(RerankerClient::class.java)
    private val client = OkHttpClient.Builder()
        .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    /**
     * 重排结果
     *
     * @param query 查询文本
     * @param documents 候选文档列表
     * @param topK 返回 top K
     * @return 重排序后的索引
     */
    fun rerank(query: String, documents: List<String>, topK: Int = config.topK): List<Int> {
        if (!config.enabled) {
            return documents.indices.toList()
        }

        require(query.isNotBlank()) {
            "查询文本不能为空"
        }
        require(documents.isNotEmpty()) {
            "文档列表不能为空"
        }
        require(topK > 0) {
            "topK 必须大于 0"
        }

        val requestJson = """
            {
                "model": "${config.model}",
                "query": "${query.replace("\"", "\\\"")}",
                "documents": ${documents.map { "\"${it.replace("\"", "\\\"")}" }},
                "top_k": $topK
            }
        """.trimIndent()

        val requestBody = requestJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${config.baseUrl}/rerank")
            .post(requestBody)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.warn("Reranker 调用失败: ${response.code}，使用原始顺序")
                return documents.indices.toList()
            }

            val responseBody = response.body?.string() ?: return documents.indices.toList()
            return parseRerankResponse(responseBody)
        }
    }

    /**
     * 解析重排响应
     */
    private fun parseRerankResponse(json: String): List<Int> {
        // 简化的 JSON 解析
        val resultsPattern = Regex("\"results\"\\s*:\\s*\\[(.*?)\\]")
        val match = resultsPattern.find(json) ?: throw RuntimeException("无法解析重排响应")

        val resultsStr = match.groupValues[1]
        val indexPattern = Regex("\"index\"\\s*:\\s*(\\d+)")

        return indexPattern.findAll(resultsStr)
            .map { it.groupValues[1].toInt() }
            .toList()
    }

    /**
     * 关闭客户端
     */
    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/**
 * 嵌入响应
 */
@Serializable
data class EmbedResponse(
    val embedding: FloatArray
)

/**
 * 嵌入数据
 */
@Serializable
data class EmbedData(
    val embedding: FloatArray
)

/**
 * 批量嵌入响应
 */
@Serializable
data class BatchEmbedResponse(
    val data: List<EmbedData>
)
