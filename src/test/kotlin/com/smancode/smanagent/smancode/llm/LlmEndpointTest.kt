package com.smancode.smanagent.smancode.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.smanagent.config.SmanAgentConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Order
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * LLM API 端点测试
 *
 * 测试不同的 API 端点和配置
 */
class LlmEndpointTest {

    private val logger = LoggerFactory.getLogger(LlmEndpointTest::class.java)
    private val objectMapper = ObjectMapper()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // 从配置加载 API Key
    private val apiKey: String = try {
        System.getenv("LLM_API_KEY") ?: throw IllegalArgumentException("LLM_API_KEY not set")
    } catch (e: Exception) {
        logger.warn("LLM_API_KEY not set, test will be skipped")
        ""
    }

    /**
     * 测试 coding 专用端点
     */
    @Test
    @Order(1)
    fun testCodingEndpoint() {
        if (apiKey.isEmpty()) {
            logger.warn("LLM_API_KEY not set, skipping test")
            return
        }

        val url = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions"
        logger.info("测试 coding 端点: $url")

        val requestBody = mapOf(
            "model" to "glm-4-flash",
            "messages" to listOf(
                mapOf("role" to "user", "content" to "你好")
            ),
            "max_tokens" to 50
        )

        val requestJson = objectMapper.writeValueAsString(requestBody)
        logger.debug("请求体: $requestJson")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        logger.info("响应码: ${response.code}")
        logger.info("响应体: $responseBody")

        val root = objectMapper.readTree(responseBody)
        val objectType = root.path("object").asText()

        if (objectType == "chat.completion") {
            val content = root.path("choices").get(0).path("message").path("content").asText()
            logger.info("✅ Coding 端点成功: $content")
        } else {
            val error = root.path("error").path("message").asText()
            logger.error("❌ Coding 端点失败: $error")
        }
    }

    /**
     * 测试通用端点
     */
    @Test
    @Order(2)
    fun testGenericEndpoint() {
        if (apiKey.isEmpty()) {
            logger.warn("LLM_API_KEY not set, skipping test")
            return
        }

        val url = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        logger.info("测试通用端点: $url")

        val requestBody = mapOf(
            "model" to "glm-4-flash",
            "messages" to listOf(
                mapOf("role" to "user", "content" to "你好")
            ),
            "max_tokens" to 50
        )

        val requestJson = objectMapper.writeValueAsString(requestBody)
        logger.debug("请求体: $requestJson")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        logger.info("响应码: ${response.code}")
        logger.info("响应体: $responseBody")

        val root = objectMapper.readTree(responseBody)
        val objectType = root.path("object").asText()

        if (objectType == "chat.completion") {
            val content = root.path("choices").get(0).path("message").path("content").asText()
            logger.info("✅ 通用端点成功: $content")
        } else {
            val error = root.path("error").path("message").asText()
            logger.error("❌ 通用端点失败: $error")
        }
    }

    /**
     * 测试 glm-4.7 模型（如果可用）
     */
    @Test
    @Order(3)
    fun testGlm4Point7Model() {
        if (apiKey.isEmpty()) {
            logger.warn("LLM_API_KEY not set, skipping test")
            return
        }

        val url = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions"
        logger.info("测试 glm-4.7 模型: $url")

        val requestBody = mapOf(
            "model" to "glm-4.7",
            "messages" to listOf(
                mapOf("role" to "user", "content" to "测试")
            ),
            "max_tokens" to 50
        )

        val requestJson = objectMapper.writeValueAsString(requestBody)
        logger.debug("请求体: $requestJson")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        logger.info("响应码: ${response.code}")

        val root = objectMapper.readTree(responseBody)
        val objectType = root.path("object").asText()

        if (objectType == "chat.completion") {
            logger.info("✅ glm-4.7 模型可用")
        } else {
            val error = root.path("error").path("message").asText()
            logger.warn("⚠️  glm-4.7 模型失败: $error")
        }
    }
}
