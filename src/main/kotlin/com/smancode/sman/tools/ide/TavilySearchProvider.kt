package com.smancode.sman.tools.ide

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Tavily 搜索提供者
 *
 * 使用 Tavily API 进行网络搜索，作为 Exa 限流时的付费备选方案
 *
 * 特点：
 * - 专为 AI Agent 设计的结构化搜索结果
 * - 1000 次/月免费额度
 * - 国内可直接访问
 */
class TavilySearchProvider(
    private val apiKey: String
) {
    private val logger = LoggerFactory.getLogger(TavilySearchProvider::class.java)
    private val objectMapper = ObjectMapper()

    companion object {
        private const val BASE_URL = "https://api.tavily.com"
        private const val ENDPOINT = "/search"
        private const val DEFAULT_NUM_RESULTS = 8
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val TIMEOUT_SECONDS = 25L
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 检查 Tavily 是否可用（API Key 非空）
     */
    fun isAvailable(): Boolean = apiKey.isNotEmpty()

    private fun buildSearchRequest(query: String, numResults: Int = DEFAULT_NUM_RESULTS): String {
        val requestBody = mapOf(
            "api_key" to apiKey,
            "query" to query,
            "max_results" to numResults,
            "search_depth" to "basic",  // basic 节省配额，advanced 消耗更多
            "include_answer" to false,  // 不需要 AI 生成的答案
            "include_raw_content" to false  // 不需要原始 HTML
        )
        return objectMapper.writeValueAsString(requestBody)
    }

    /**
     * 执行搜索
     *
     * @return 搜索结果文本（Markdown 格式）
     * @throws TavilySearchException 搜索失败时抛出
     */
    fun search(query: String, numResults: Int = DEFAULT_NUM_RESULTS): String {
        if (!isAvailable()) {
            throw TavilySearchException("Tavily API Key 未配置")
        }

        val requestBody = buildSearchRequest(query, numResults)
        val url = "$BASE_URL$ENDPOINT"

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            handleResponse(response, query)
        } catch (e: java.net.SocketTimeoutException) {
            logger.error("Tavily 请求超时: {}", e.message)
            throw TavilySearchException("Tavily 请求超时（${TIMEOUT_SECONDS}秒）")
        } catch (e: Exception) {
            logger.error("Tavily 执行失败: {}", e.message, e)
            throw TavilySearchException("Tavily 执行失败: ${e.message}")
        }
    }

    private fun handleResponse(response: okhttp3.Response, query: String): String {
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            logger.error("Tavily 请求失败: ${response.code} - $responseBody")

            // 特殊处理认证错误
            if (response.code == 401) {
                throw TavilySearchException("Tavily API Key 无效")
            }

            throw TavilySearchException("Tavily 请求失败: ${response.code}")
        }

        val results = parseSearchResponse(responseBody)
        return formatResults(query, results)
    }

    private fun parseSearchResponse(responseBody: String): List<TavilySearchResult> {
        return try {
            val jsonNode = objectMapper.readTree(responseBody)

            // 检查错误
            if (jsonNode.has("error")) {
                val error = jsonNode.path("error").asText()
                throw TavilySearchException("Tavily 返回错误: $error")
            }

            // 提取结果
            val resultsNode = jsonNode.path("results")
            if (resultsNode.isNull || !resultsNode.isArray) {
                return emptyList()
            }

            resultsNode.mapNotNull { item ->
                val title = item.path("title").asText("")
                val url = item.path("url").asText("")
                if (title.isEmpty() && url.isEmpty()) {
                    null
                } else {
                    TavilySearchResult(
                        title = title,
                        url = url,
                        content = item.path("content").asText("")
                    )
                }
            }
        } catch (e: TavilySearchException) {
            throw e
        } catch (e: Exception) {
            logger.error("解析 Tavily 响应失败: {}", e.message, e)
            throw TavilySearchException("解析搜索结果失败: ${e.message}")
        }
    }

    private fun formatResults(query: String, results: List<TavilySearchResult>): String {
        return buildString {
            appendLine("## 搜索结果 (Tavily)")
            appendLine()
            appendLine("**查询**: $query")
            appendLine("**结果数**: ${results.size}")
            appendLine()

            if (results.isEmpty()) {
                appendLine("未找到相关结果")
            } else {
                results.forEachIndexed { index, result ->
                    appendLine("### ${index + 1}. ${result.title}")
                    appendLine("**URL**: ${result.url}")
                    if (result.content.isNotEmpty()) {
                        appendLine()
                        appendLine(result.content)
                    }
                    appendLine()
                }
            }
        }
    }

    /**
     * Tavily 搜索结果
     */
    data class TavilySearchResult(
        val title: String,
        val url: String,
        val content: String
    )

    /**
     * Tavily 搜索异常
     */
    class TavilySearchException(message: String) : Exception(message)
}
