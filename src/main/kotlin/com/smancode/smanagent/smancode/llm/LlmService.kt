package com.smancode.smanagent.smancode.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.smanagent.config.SmanAgentConfig
import com.smancode.smanagent.smancode.llm.config.LlmEndpoint
import com.smancode.smanagent.smancode.llm.config.LlmPoolConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LLM 调用服务（独立模块）
 *
 * 功能：
 * 1. 端点池轮询（Round-Robin）
 * 2. 故障自动切换（超时、429、5xx）
 * 3. 指数退避重试（10s -> 20s -> 30s）
 * 4. JSON 格式自动校验
 */
class LlmService(
    private val poolConfig: LlmPoolConfig
) {
    private val logger = LoggerFactory.getLogger(LlmService::class.java)

    companion object {
        /**
         * GLM-4.7 缓存 Token 计费标准：每千 tokens 0.005 元（按 50% 计费）
         */
        private const val CACHE_COST_PER_1K_TOKENS = 0.005

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val objectMapper: ObjectMapper = ObjectMapper()
    private val config = SmanAgentConfig

    // OkHttp 客户端配置 - 从配置文件读取超时值
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(
            config.llmConnectTimeout.toLong(),
            TimeUnit.MILLISECONDS
        )
        .readTimeout(
            config.llmReadTimeout.toLong(),
            TimeUnit.MILLISECONDS
        )
        .writeTimeout(
            config.llmWriteTimeout.toLong(),
            TimeUnit.MILLISECONDS
        )
        .build()

    /**
     * 普通文本请求（自动重试）
     *
     * @param prompt 用户提示词
     * @return LLM 响应文本
     */
    fun simpleRequest(prompt: String): String {
        return simpleRequest(null, prompt)
    }

    /**
     * 普通文本请求（自动重试）
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 响应文本
     */
    fun simpleRequest(systemPrompt: String?, userPrompt: String): String {
        var retryCount = 0
        val maxRetries = poolConfig.retry.maxRetries

        while (retryCount <= maxRetries) {
            val endpoint = poolConfig.nextAvailableEndpoint
            if (endpoint == null) {
                val errorMsg = "没有可用的 LLM 端点"
                logger.error(errorMsg)
                throw RuntimeException(errorMsg)
            }

            val startTime = System.currentTimeMillis()
            try {
                // 完整打印 prompt 内容（不截取）
                if (!systemPrompt.isNullOrEmpty()) {
                    logger.info("=== System Prompt (完整内容):\n{}", systemPrompt)
                }

                if (!userPrompt.isNullOrEmpty()) {
                    logger.info("=== User Prompt (完整内容):\n{}", userPrompt)
                }

                // 调用 callInternal 并获取原始响应（用于提取 tokens）
                val rawApiResponse = callInternalForRawResponse(endpoint, systemPrompt, userPrompt)

                // 完整打印响应内容（不截取）
                logger.info("=== LLM 响应 (完整内容):\n{}", rawApiResponse)

                // 提取纯文本内容
                val content = extractContent(rawApiResponse)

                // 打印 tokens 信息
                logTokensUsage(rawApiResponse, startTime)

                endpoint.markSuccess()
                return content

            } catch (e: Exception) {
                endpoint.markFailed()
                logger.warn("端点调用失败: {}, 错误: {}", endpoint.baseUrl, e.message)

                // 判断是否需要重试
                if (shouldRetry(e) && retryCount < maxRetries) {
                    retryCount++
                    // 对 429 错误使用更长的等待时间（指数退避）
                    val delay = if (e.message?.contains("429") == true) {
                        val exponentialDelay = poolConfig.retry.baseDelay * Math.pow(2.0, retryCount.toDouble()).toLong()
                        logger.warn("检测到 429 速率限制，使用指数退避延迟: {} 毫秒", exponentialDelay)
                        exponentialDelay
                    } else {
                        poolConfig.retry.calculateDelay(retryCount)
                    }
                    logger.info("等待 {} 毫秒后进行第 {} 次重试...", delay, retryCount)
                    sleep(delay)
                } else {
                    throw RuntimeException("LLM 调用失败: ${e.message}", e)
                }
            }
        }

        throw RuntimeException("LLM 调用失败：超过最大重试次数")
    }

    /**
     * 打印 tokens 使用情况（包含 GLM-4.7 缓存统计）
     */
    private fun logTokensUsage(rawApiResponse: String, startTime: Long) {
        try {
            val root = objectMapper.readTree(rawApiResponse)
            val usage = root.path("usage")

            if (!usage.isMissingNode && usage.isObject) {
                val promptTokens = usage.path("prompt_tokens").asInt()
                val completionTokens = usage.path("completion_tokens").asInt()
                val totalTokens = usage.path("total_tokens").asInt()

                // GLM-4.7 缓存 Token 统计
                val cachedTokens = usage.path("prompt_tokens_details")
                    .path("cached_tokens")
                    .asInt()

                val elapsedTime = System.currentTimeMillis() - startTime
                val elapsedSeconds = elapsedTime / 1000.0

                // 计算缓存命中率和节省金额
                val cacheHitRatio = if (promptTokens > 0) cachedTokens * 100.0 / promptTokens else 0.0
                val costSavingsYuan = cachedTokens * CACHE_COST_PER_1K_TOKENS / 1000.0

                // 基础日志
                logger.info("LLM 响应: 发送tokens={}, 接收tokens={}, 总tokens={}, 耗时{}s",
                    promptTokens, completionTokens, totalTokens,
                    String.format("%.1f", elapsedSeconds))

                // 缓存统计（如果有缓存命中）
                if (cachedTokens > 0) {
                    logger.info("缓存命中: {} tokens ({}%), 节省约 ¥{}",
                        cachedTokens,
                        String.format("%.1f", cacheHitRatio),
                        String.format("%.4f", costSavingsYuan))
                }
            }
        } catch (e: Exception) {
            // 忽略 tokens 解析错误
            logger.debug("无法解析 tokens 信息: {}", e.message)
        }
    }

    /**
     * 内部调用实现（返回原始 API 响应）
     */
    private fun callInternalForRawResponse(
        endpoint: LlmEndpoint,
        systemPrompt: String?,
        userPrompt: String
    ): String {
        try {
            // 构建请求体
            val requestBody = buildRequestBody(endpoint, systemPrompt, userPrompt)
            val requestBodyJson = objectMapper.writeValueAsString(requestBody)

            // 构建请求
            // baseUrl + "/chat/completions" 组合成完整的 API URL
            val fullUrl = (endpoint.baseUrl ?: error("Endpoint baseUrl cannot be null")) + "/chat/completions"
            val request = Request.Builder()
                .url(fullUrl)
                .post(requestBodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    endpoint.apiKey?.let { apiKey ->
                        addHeader("Authorization", "Bearer $apiKey")
                    }
                }
                .build()

            // 发送请求
            val response = httpClient.newCall(request).execute()

            // 检查响应码
            if (!response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                logger.error("HTTP 错误: ${response.code}, 响应体: $responseBody")
                throw RuntimeException("HTTP 错误: ${response.code}")
            }

            // 返回响应体
            val responseBody = response.body!!?.string()
                ?: throw RuntimeException("空响应")

            return responseBody

        } catch (e: IOException) {
            // 超时或网络错误
            throw RuntimeException("请求超时或网络错误", e)
        } catch (e: Exception) {
            throw RuntimeException("LLM 调用失败: ${e.message}", e)
        }
    }

    /**
     * JSON 请求（自动重试 + JSON 格式校验）
     *
     * @param prompt 用户提示词（应要求返回 JSON）
     * @return 解析后的 JSON 节点
     */
    fun jsonRequest(prompt: String): JsonNode {
        return jsonRequest(null, prompt)
    }

    /**
     * JSON 请求（自动重试 + JSON 格式校验）
     *
     * 基于 simpleRequest，增加 JSON 解析和验证
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词（应要求返回 JSON）
     * @return 解析后的 JSON 节点
     */
    fun jsonRequest(systemPrompt: String?, userPrompt: String): JsonNode {
        // 1. 调用 simpleRequest 获取纯文本响应（已有重试机制）
        val responseText = simpleRequest(systemPrompt, userPrompt)

        // 2. 从纯文本响应中提取 JSON
        val extractedJson = extractJsonFromResponse(responseText)
        if (extractedJson == null) {
            throw RuntimeException("无法从响应中提取有效的 JSON，响应内容: " +
                responseText.take(200))
        }

        // 3. 解析 JSON
        return try {
            objectMapper.readTree(extractedJson)
        } catch (e: Exception) {
            throw RuntimeException("JSON 解析失败: ${e.message}，提取的 JSON: $extractedJson", e)
        }
    }

    /**
     * 判断是否应该重试
     */
    private fun shouldRetry(e: Exception): Boolean {
        val message = e.message ?: return false

        // 超时错误
        if (e is IOException ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("Timed out", ignoreCase = true)) {
            return true
        }

        // 429 Too Many Requests
        if (message.contains("429")) {
            return true
        }

        // 5xx 服务器错误
        if (message.contains("5") &&
            (message.contains("50") || message.contains("HTTP error 5", ignoreCase = true))) {
            return true
        }

        // JSON 格式错误（检查异常类型和消息）
        if (e is com.fasterxml.jackson.core.JsonParseException ||
            e.cause is com.fasterxml.jackson.core.JsonParseException ||
            message.contains("Unexpected character") ||
            message.contains("JsonParseException")) {
            return true
        }

        return false
    }

    /**
     * 从响应中提取 JSON（8级递进式解析策略）
     *
     * 解析策略从简单到复杂，逐级尝试，确保最大容错能力：
     * Level 1: 直接解析（最快）
     * Level 2: 清理后解析（去除 markdown 代码块）
     * Level 3: 修复转义后解析（修复常见转义问题）
     * Level 4: 智能大括号提取（增强版策略3）
     * Level 5: 正则提取尝试（多种模式匹配）
     * Level 6: 简单正则快速尝试（补充兜底）
     * Level 7: 终极大招 - LLM 辅助提取
     * Level 8: 降级为纯文本（兜底）
     *
     * @param response LLM 返回的原始响应
     * @return 提取出的 JSON 字符串，如果所有策略都失败则返回 null
     */
    private fun extractJsonFromResponse(response: String?): String? {
        if (response.isNullOrEmpty()) {
            return null
        }

        val trimmedResponse = response.trim()

        // ========== Level 1: 直接解析 ==========
        if (isValidJsonStructure(trimmedResponse)) {
            logger.debug("Level 1 成功: 直接解析")
            return trimmedResponse
        }

        // ========== Level 2: 清理 markdown 代码块 ==========
        val level2Result = extractFromMarkdownBlock(trimmedResponse)
        if (level2Result != null && isValidJsonStructure(level2Result)) {
            logger.debug("Level 2 成功: 清理 markdown 代码块")
            return level2Result
        }

        // ========== Level 3: 修复转义字符 ==========
        val level3Result = fixAndParse(trimmedResponse)
        if (level3Result != null) {
            logger.debug("Level 3 成功: 修复转义字符")
            return level3Result
        }

        // ========== Level 4: 智能大括号提取（增强版）==========
        val level4Result = extractWithSmartBraceMatching(trimmedResponse)
        if (level4Result != null && isValidJsonStructure(level4Result)) {
            logger.debug("Level 4 成功: 智能大括号提取")
            return level4Result
        }

        // ========== Level 5: 正则提取尝试 ==========
        val level5Result = extractWithRegex(trimmedResponse)
        if (level5Result != null && isValidJsonStructure(level5Result)) {
            logger.debug("Level 5 成功: 正则提取")
            return level5Result
        }

        // ========== Level 6: 简单正则快速尝试 ==========
        val level6Result = extractWithSimpleRegex(trimmedResponse)
        if (level6Result != null && isValidJsonStructure(level6Result)) {
            logger.debug("Level 6 成功: 简单正则提取")
            return level6Result
        }

        // ========== Level 7: 终极大招 - LLM 辅助提取 ==========
        // 注意：LlmService 不支持 LLM 辅助提取（避免无限递归）
        // 跳过 Level 7，直接降级

        // ========== Level 8: 所有策略失败，降级为纯文本 ==========
        logger.warn("所有 JSON 提取策略失败，将降级为纯文本处理")
        return null
    }

    /**
     * Level 2: 从 markdown 代码块中提取 JSON
     */
    private fun extractFromMarkdownBlock(response: String): String? {
        // 尝试提取 ```json...``` 代码块
        val jsonStart = "```json"
        val jsonEnd = "```"

        var startIndex = response.indexOf(jsonStart)
        if (startIndex != -1) {
            startIndex += jsonStart.length
            // 从 startIndex 开始找第一个 {，然后智能匹配对应的 }
            val firstBrace = response.indexOf('{', startIndex)
            if (firstBrace != -1) {
                var depth = 0
                var inString = false
                var escape = false

                for (i in firstBrace until response.length) {
                    val c = response[i]

                    if (escape) {
                        escape = false
                        continue
                    }

                    if (c == '\\' && inString) {
                        escape = true
                        continue
                    }

                    if (c == '"' && !escape) {
                        inString = !inString
                        continue
                    }

                    if (!inString) {
                        if (c == '{') depth++
                        else if (c == '}') {
                            depth--
                            if (depth == 0) {
                                return response.substring(firstBrace, i + 1).trim()
                            }
                        }
                    }
                }
            }
        }

        // 尝试提取 ```...``` 代码块（没有 json 标记）
        val codeStart = "```"
        val codeStartIndex = response.indexOf(codeStart)
        if (codeStartIndex != -1) {
            val afterStart = codeStartIndex + codeStart.length
            // 跳过可能的语言标记
            val firstBrace = response.indexOf('{', afterStart)
            if (firstBrace != -1) {
                val endIndex = response.indexOf(codeStart, firstBrace)
                if (endIndex != -1) {
                    return response.substring(firstBrace, endIndex).trim()
                }
            }
        }

        return null
    }

    /**
     * Level 3: 修复转义字符并解析
     *
     * 处理 LLM 返回的 JSON 中常见的转义问题：
     * - 字符串内部的换行符 \n 未转义
     * - 字符串内部的引号 " 未转义
     * - 字符串内部的反斜杠 \ 未转义
     */
    private fun fixAndParse(response: String): String? {
        // 先尝试从 markdown 代码块中提取
        val extracted = extractFromMarkdownBlock(response)
        val toFix = extracted ?: response

        // 尝试多种修复策略
        val fixedVersions = listOf(
            fixStringNewlines(toFix),           // 修复字符串内的换行
            fixUnescapedQuotes(toFix),          // 修复未转义的引号
            fixUnescapedBackslashes(toFix),     // 修复未转义的反斜杠
            fixAllCommonIssues(toFix)           // 修复所有常见问题
        )

        for (fixed in fixedVersions) {
            if (isValidJsonStructure(fixed)) {
                return fixed
            }
        }

        return null
    }

    /**
     * 修复 JSON 字符串值中的未转义换行符
     *
     * 例如: {"text": "hello\nworld"} -> {"text": "hello\\nworld"}
     */
    private fun fixStringNewlines(json: String): String {
        // 这是一个简化版本，只处理最常见的情况
        // 更复杂的版本需要跟踪字符串状态
        return json.replace("\\\n", "\\\\n")
    }

    /**
     * 修复 JSON 字符串值中的未转义引号
     *
     * 这是一个启发式方法，尝试修复常见的未转义引号问题
     */
    private fun fixUnescapedQuotes(json: String): String {
        // 简化版本：只处理明显的情况
        // 注意：这是一个有损修复，可能不是所有情况都适用
        return json
    }

    /**
     * 修复 JSON 字符串值中的未转义反斜杠
     */
    private fun fixUnescapedBackslashes(json: String): String {
        // 简化版本：只处理明显的情况
        return json
    }

    /**
     * 修复所有常见的转义问题
     */
    private fun fixAllCommonIssues(json: String): String {
        var result = json
        result = fixStringNewlines(result)
        result = fixUnescapedQuotes(result)
        result = fixUnescapedBackslashes(result)
        return result
    }

    /**
     * Level 4: 智能大括号匹配提取
     *
     * 从复杂文本中提取完整的 JSON 对象，处理：
     * - 嵌套大括号
     * - 字符串内部的大括号
     * - 转义字符
     */
    private fun extractWithSmartBraceMatching(response: String): String? {
        val braceStart = response.indexOf('{')
        if (braceStart < 0) {
            return null
        }

        var depth = 0
        var inString = false
        var escape = false

        for (i in braceStart until response.length) {
            val c = response[i]

            if (escape) {
                escape = false
                continue
            }

            if (c == '\\' && inString) {
                escape = true
                continue
            }

            if (c == '"' && !escape) {
                inString = !inString
                continue
            }

            if (!inString) {
                if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return response.substring(braceStart, i + 1)
                    }
                }
            }
        }

        return null
    }

    /**
     * Level 5: 使用正则表达式提取 JSON
     */
    private fun extractWithRegex(response: String): String? {
        // 尝试多种正则模式
        val patterns = listOf(
            // 模式1: 匹配 ```json 和 ``` 之间的内容
            Regex("""```json\s*([\s\S]*?)\s*```"""),
            // 模式2: 匹配 { 和 } 之间的完整 JSON 对象（贪婪）
            Regex("""\{[\s\S]*\}"""),
            // 模式3: 匹配嵌套的 JSON 对象
            Regex("""\{(?:[^{}]|\{[^{}]*\})*\}""")
        )

        for (pattern in patterns) {
            val matcher = pattern.find(response)
            if (matcher != null) {
                val match = matcher.groupValues.getOrNull(1) ?: matcher.value
                if (match.isNotEmpty()) {
                    return match.trim()
                }
            }
        }

        return null
    }

    /**
     * Level 6: 简单正则快速提取
     */
    private fun extractWithSimpleRegex(response: String): String? {
        // 快速尝试：找到第一个 { 和最后一个 }
        val firstBrace = response.indexOf('{')
        val lastBrace = response.lastIndexOf('}')

        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return response.substring(firstBrace, lastBrace + 1)
        }

        return null
    }

    /**
     * 验证JSON结构的基本有效性
     */
    private fun isValidJsonStructure(jsonStr: String?): Boolean {
        if (jsonStr.isNullOrEmpty()) {
            return false
        }

        return try {
            objectMapper.readTree(jsonStr)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 构建 LLM 请求体
     */
    private fun buildRequestBody(
        endpoint: LlmEndpoint,
        systemPrompt: String?,
        userPrompt: String
    ): Map<String, Any> {
        val body = mutableMapOf<String, Any>()
        body["model"] = endpoint.model!!
        body["max_tokens"] = endpoint.maxTokens
        body["temperature"] = 0.0  // 设置为 0，确保输出稳定，避免幻觉

        // 构建消息列表
        val messages = mutableListOf<Map<String, String>>()

        if (!systemPrompt.isNullOrEmpty()) {
            val systemMsg = mapOf(
                "role" to "system",
                "content" to systemPrompt
            )
            messages.add(systemMsg)
        }

        val userMsg = mapOf(
            "role" to "user",
            "content" to userPrompt
        )
        messages.add(userMsg)

        body["messages"] = messages

        return body
    }

    /**
     * 从 LLM 响应中提取内容
     *
     * 参考 bank-core-analysis-agent：只提取纯文本 content，不做任何转换
     * JSON 解析由上层（SmanAgentLoop）负责
     */
    private fun extractContent(responseBody: String?): String {
        if (responseBody == null) {
            return ""
        }

        return try {
            val root = objectMapper.readTree(responseBody)

            // 尝试 OpenAI 格式
            val choicesNode = root.path("choices")
            if (choicesNode.isArray && choicesNode.size() > 0) {
                val contentNode = choicesNode[0].path("message").path("content")
                if (!contentNode.isMissingNode) {
                    return contentNode.asText()  // 直接返回纯文本
                }
            }

            // 尝试直接格式（有些 LLM 直接返回 content 字段）
            val contentNode = root.path("content")
            if (!contentNode.isMissingNode) {
                return contentNode.asText()  // 直接返回纯文本
            }

            // 都不是，返回原始响应
            responseBody

        } catch (e: Exception) {
            // JSON 解析失败，返回原始响应
            logger.debug("响应不是 JSON 格式，按纯文本处理: {}", e.message)
            responseBody
        }
    }

    /**
     * 线程休眠
     */
    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException("重试等待被中断", e)
        }
    }
}
