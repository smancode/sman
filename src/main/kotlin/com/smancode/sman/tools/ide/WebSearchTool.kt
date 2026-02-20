package com.smancode.sman.tools.ide

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.config.SmanConfig
import com.smancode.sman.tools.AbstractTool
import com.smancode.sman.tools.ParameterDef
import com.smancode.sman.tools.Tool
import com.smancode.sman.tools.ToolResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * WebSearch 工具
 *
 * 使用 Exa AI MCP 服务进行实时网络搜索
 */
class WebSearchTool : AbstractTool(), Tool {

    private val logger = LoggerFactory.getLogger(WebSearchTool::class.java)
    private val objectMapper = ObjectMapper()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(SmanConfig.webSearchTimeout, TimeUnit.SECONDS)
            .readTimeout(SmanConfig.webSearchTimeout, TimeUnit.SECONDS)
            .writeTimeout(SmanConfig.webSearchTimeout, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val BASE_URL = "https://mcp.exa.ai"
        private const val ENDPOINT = "/mcp"
        private const val DEFAULT_NUM_RESULTS = 8
        private const val JSON_MEDIA_TYPE = "application/json"
    }

    override fun getName(): String = "web_search"

    override fun getDescription(): String = """
        Search the web using Exa AI - performs real-time web searches
        - Provides up-to-date information for current events and recent data
        - Use this tool for accessing information beyond your knowledge cutoff
    """.trimIndent()

    override fun getParameters(): Map<String, ParameterDef> = mapOf(
        "query" to ParameterDef("query", String::class.java, true, "Web search query"),
        "numResults" to ParameterDef("numResults", Int::class.java, false, "Number of results", DEFAULT_NUM_RESULTS),
        "type" to ParameterDef("type", String::class.java, false, "Search type: auto/fast/deep", "auto"),
        "livecrawl" to ParameterDef("livecrawl", String::class.java, false, "Live crawl: fallback/preferred", "fallback")
    )

    override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        // 1. 参数提取与校验
        val query = extractQuery(params)
        if (query == null) {
            return createFailureResult("缺少 query 参数", startTime)
        }
        if (query.isBlank()) {
            return createFailureResult("query 不能为空", startTime)
        }

        // 2. 检查是否启用
        if (!SmanConfig.webSearchEnabled) {
            return createFailureResult(buildServiceDisabledMessage(), startTime)
        }

        // 3. 可选参数提取
        val numResults = getOptionalInt(params, "numResults", SmanConfig.webSearchDefaultNumResults)
        val type = getOptionalString(params, "type", "auto")
        val livecrawl = getOptionalString(params, "livecrawl", "fallback")

        // 4. 执行搜索
        return executeSearch(query, numResults, type, livecrawl, startTime)
    }

    private fun extractQuery(params: Map<String, Any>): String? {
        return try {
            getReqString(params, "query")
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun executeSearch(
        query: String,
        numResults: Int,
        type: String,
        livecrawl: String,
        startTime: Long
    ): ToolResult {
        val requestBody = buildMcpRequest(query, numResults, type, livecrawl)
        val url = "$BASE_URL$ENDPOINT"

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            handleResponse(response, query, startTime)
        } catch (e: java.net.SocketTimeoutException) {
            logger.error("WebSearch 请求超时: {}", e.message)
            createFailureResult("WebSearch 请求超时（${SmanConfig.webSearchTimeout}秒）", startTime)
        } catch (e: Exception) {
            logger.error("WebSearch 执行失败: {}", e.message, e)
            createFailureResult("WebSearch 执行失败: ${e.message}", startTime)
        }
    }

    private fun handleResponse(response: okhttp3.Response, query: String, startTime: Long): ToolResult {
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            logger.error("WebSearch 请求失败: ${response.code} - $responseBody")
            return createFailureResult("WebSearch 请求失败: ${response.code}", startTime)
        }

        val resultText = parseSearchResponse(responseBody, query)
        logCompletion(query, resultText, startTime)
        return createSuccessResult(resultText, startTime)
    }

    private fun buildMcpRequest(query: String, numResults: Int, type: String, livecrawl: String): String {
        val args = mapOf(
            "query" to query,
            "type" to type,
            "numResults" to numResults,
            "livecrawl" to livecrawl
        )

        val rpcRequest = mapOf(
            "jsonrpc" to "2.0",
            "id" to 1,
            "method" to "tools/call",
            "params" to mapOf(
                "name" to "web_search_exa",
                "arguments" to args
            )
        )

        return objectMapper.writeValueAsString(rpcRequest)
    }

    private fun parseSearchResponse(responseBody: String, query: String): String {
        return try {
            val jsonNode = objectMapper.readTree(responseBody)

            // 检查错误
            jsonNode.path("error")?.asText()?.let { error ->
                return "搜索失败: $error"
            }

            // 提取结果
            val resultNode = jsonNode.path("result")
            if (resultNode.isNull || resultNode.isEmpty) {
                return formatEmptyResults(query)
            }

            // 解析结果列表
            val resultsNode = if (resultNode.isArray) resultNode else resultNode.path("results")
            val results = parseResults(resultsNode)

            return formatResults(query, results)

        } catch (e: Exception) {
            logger.error("解析搜索响应失败: {}", e.message, e)
            "搜索结果解析失败: ${e.message}\n\n原始响应:\n$responseBody"
        }
    }

    private fun parseResults(resultsNode: JsonNode): List<SearchResult> {
        return resultsNode.mapNotNull { item ->
            val title = item.path("title").asText("")
            val url = item.path("url").asText("")
            if (title.isEmpty() && url.isEmpty()) return@mapNotNull null

            SearchResult(
                title = title,
                url = url,
                snippet = item.path("snippet").asText("").ifEmpty { item.path("text").asText("") },
                score = item.path("score").asDouble(0.0)
            )
        }
    }

    private fun formatResults(query: String, results: List<SearchResult>): String {
        return buildString {
            appendLine("## 搜索结果")
            appendLine()
            appendLine("**查询**: $query")
            appendLine("**结果数**: ${results.size}")
            appendLine()

            results.forEachIndexed { index, result ->
                appendLine("### ${index + 1}. ${result.title}")
                appendLine("**URL**: ${result.url}")
                if (result.snippet.isNotEmpty()) {
                    appendLine()
                    appendLine(result.snippet)
                }
                appendLine()
            }
        }
    }

    private fun formatEmptyResults(query: String): String {
        return buildString {
            appendLine("## 搜索结果")
            appendLine()
            appendLine("**查询**: $query")
            appendLine("**结果**: 未找到相关结果")
        }
    }

    private fun createFailureResult(message: String, startTime: Long): ToolResult {
        return ToolResult.failure(message).also {
            it.executionTimeMs = System.currentTimeMillis() - startTime
        }
    }

    private fun createSuccessResult(resultText: String, startTime: Long): ToolResult {
        return ToolResult.success(resultText, "web_search", resultText).also {
            it.executionTimeMs = System.currentTimeMillis() - startTime
        }
    }

    private fun logCompletion(query: String, resultText: String, startTime: Long) {
        val duration = System.currentTimeMillis() - startTime
        val resultCount = resultText.lines().count { it.startsWith("### ") }
        logger.info("WebSearch 完成: query={}, numResults={}, time={}ms", query, resultCount, duration)
    }

    private fun buildServiceDisabledMessage(): String =
        """**WebSearch 服务已禁用**
          |
          |WebSearch 功能需要在配置文件中启用：
          |
          |### 配置方式
          |在 sman.properties 中添加：
          |```
          |websearch.enabled=true
          |```
          |
          |或通过环境变量：
          |```
          |export WEBSEARCH_ENABLED=true
          |```
        """.trimMargin()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val score: Double
    )
}
