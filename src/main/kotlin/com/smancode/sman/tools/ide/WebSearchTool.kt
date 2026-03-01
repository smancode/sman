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
 * 搜索策略：Exa（免费） → Tavily（付费兜底）
 *
 * 工作流程：
 * 1. 优先使用 Exa AI MCP 服务（免费，无需 API Key）
 * 2. 如果 Exa 限流（HTTP 429），检查是否配置了 Tavily
 * 3. 如果 Tavily 可用，降级到 Tavily 继续搜索
 * 4. 如果 Tavily 不可用，返回限流错误提示
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

    // Tavily 提供者（懒加载）
    private val tavilyProvider: TavilySearchProvider by lazy {
        TavilySearchProvider(SmanConfig.webSearchTavilyApiKey)
    }

    companion object {
        private const val BASE_URL = "https://mcp.exa.ai"
        private const val ENDPOINT = "/mcp"
        private const val DEFAULT_NUM_RESULTS = 8
        private const val JSON_MEDIA_TYPE = "application/json"
    }

    override fun getName(): String = "web_search"

    override fun getDescription(): String = """
        Search the web using Exa AI (free) with Tavily fallback (paid).
        - Provides up-to-date information for current events and recent data
        - Use this tool for accessing information beyond your knowledge cutoff
        - Automatically falls back to Tavily if Exa is rate limited
    """.trimIndent()

    override fun getParameters(): Map<String, ParameterDef> = mapOf(
        "query" to ParameterDef("query", String::class.java, true, "Web search query"),
        "numResults" to ParameterDef("numResults", Int::class.java, false, "Number of results", DEFAULT_NUM_RESULTS),
        "type" to ParameterDef("type", String::class.java, false, "Search type: auto/fast/deep", "auto"),
        "livecrawl" to ParameterDef("livecrawl", String::class.java, false, "Live crawl: fallback/preferred", "fallback")
    )

    override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        val query = extractQuery(params)
        if (query == null) {
            return createFailureResult("缺少 query 参数", startTime)
        }
        if (query.isBlank()) {
            return createFailureResult("query 不能为空", startTime)
        }

        if (!SmanConfig.webSearchEnabled) {
            return createFailureResult(buildServiceDisabledMessage(), startTime)
        }

        val numResults = getOptionalInt(params, "numResults", SmanConfig.webSearchDefaultNumResults)
        val type = getOptionalString(params, "type", "auto")
        val livecrawl = getOptionalString(params, "livecrawl", "fallback")

        return executeSearchWithFallback(query, numResults, type, livecrawl, startTime)
    }

    /**
     * 执行搜索（带 Exa -> Tavily 降级策略）
     */
    private fun executeSearchWithFallback(
        query: String,
        numResults: Int,
        type: String,
        livecrawl: String,
        startTime: Long
    ): ToolResult {
        val exaResult = executeExaSearch(query, numResults, type, livecrawl)

        if (exaResult.needsFallback) {
            logger.warn("Exa 搜索限流，尝试降级到 Tavily")

            if (shouldFallbackToTavily(exaResult.httpCode)) {
                return executeTavilySearch(query, numResults, startTime)
            }
            return createFailureResult(buildRateLimitMessage(), startTime)
        }

        return exaResult.toolResult
    }

    /**
     * 执行 Exa 搜索
     */
    private fun executeExaSearch(
        query: String,
        numResults: Int,
        type: String,
        livecrawl: String
    ): ExaSearchResult {
        val requestBody = buildMcpRequest(query, numResults, type, livecrawl)
        val url = "$BASE_URL$ENDPOINT"

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val httpCode = response.code
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                logger.error("Exa 请求失败: $httpCode - $responseBody")

                // 检查是否限流
                if (isExaRateLimited(httpCode, responseBody)) {
                    return ExaSearchResult(
                        needsFallback = true,
                        httpCode = httpCode,
                        toolResult = createFailureResult("Exa 限流", 0)
                    )
                }

                return ExaSearchResult(
                    needsFallback = false,
                    httpCode = httpCode,
                    toolResult = createFailureResult("WebSearch 请求失败: $httpCode", 0)
                )
            }

            val resultText = parseSearchResponse(responseBody, query)
            logger.info("Exa 搜索成功: query={}", query)

            ExaSearchResult(
                needsFallback = false,
                httpCode = httpCode,
                toolResult = createSuccessResult(resultText, 0)
            )
        } catch (e: java.net.SocketTimeoutException) {
            logger.error("Exa 请求超时: {}", e.message)
            ExaSearchResult(
                needsFallback = false,  // 超时不降级
                httpCode = 0,
                toolResult = createFailureResult("WebSearch 请求超时（${SmanConfig.webSearchTimeout}秒）", 0)
            )
        } catch (e: Exception) {
            logger.error("Exa 执行失败: {}", e.message, e)
            ExaSearchResult(
                needsFallback = false,
                httpCode = 0,
                toolResult = createFailureResult("WebSearch 执行失败: ${e.message}", 0)
            )
        }
    }

    /**
     * 执行 Tavily 搜索（降级方案）
     */
    private fun executeTavilySearch(query: String, numResults: Int, startTime: Long): ToolResult {
        return try {
            val resultText = tavilyProvider.search(query, numResults)
            logger.info("Tavily 搜索成功（降级）: query={}", query)
            logCompletion(query, resultText, startTime, "Tavily")
            createSuccessResult(resultText, startTime)
        } catch (e: TavilySearchProvider.TavilySearchException) {
            logger.error("Tavily 搜索失败: {}", e.message)
            createFailureResult("Tavily 搜索失败: ${e.message}", startTime)
        }
    }

    // ==================== 限流检测与降级决策 ====================

    /**
     * 判断 Exa 是否被限流
     */
    fun isExaRateLimited(httpCode: Int, responseBody: String = ""): Boolean {
        if (httpCode == 429) return true

        // 检查响应体中的限流关键词
        val lowerBody = responseBody.lowercase()
        return lowerBody.contains("rate limit") || lowerBody.contains("too many requests")
    }

    /**
     * 判断是否应该降级到 Tavily
     */
    fun shouldFallbackToTavily(exaHttpCode: Int): Boolean {
        // 只有在 Exa 限流且 Tavily 可用时才降级
        return isExaRateLimited(exaHttpCode) && SmanConfig.webSearchTavilyEnabled
    }

    /**
     * 构建限流错误消息
     */
    fun buildRateLimitMessage(): String {
        return if (SmanConfig.webSearchTavilyEnabled) {
            """**WebSearch 搜索请求被限流**

Exa 免费服务请求频率超限。

请稍后重试，或考虑升级到 Tavily 付费服务以获得更稳定的搜索体验。"""
        } else {
            """**WebSearch 搜索请求被限流**

Exa 免费服务请求频率超限。

### 升级方案
配置 Tavily API Key 可在 Exa 限流时自动切换：

1. 获取 API Key: https://tavily.com（1000 次/月免费）
2. 在设置界面配置 Tavily API Key
3. 或在 sman.properties 中添加：
   ```
   websearch.tavily.api.key=tvly-your-api-key
   ```
"""
        }
    }

    // ==================== 私有辅助方法 ====================

    private fun extractQuery(params: Map<String, Any>): String? {
        return try {
            getReqString(params, "query")
        } catch (e: IllegalArgumentException) {
            null
        }
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

    private fun logCompletion(query: String, resultText: String, startTime: Long, source: String = "Exa") {
        val duration = System.currentTimeMillis() - startTime
        val resultCount = resultText.lines().count { it.startsWith("### ") }
        logger.info("WebSearch 完成: source={}, query={}, numResults={}, time={}ms", source, query, resultCount, duration)
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

    // ==================== 内部数据类 ====================

    /**
     * Exa 搜索结果
     */
    private data class ExaSearchResult(
        val needsFallback: Boolean,
        val httpCode: Int,
        val toolResult: ToolResult
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val score: Double
    )
}
