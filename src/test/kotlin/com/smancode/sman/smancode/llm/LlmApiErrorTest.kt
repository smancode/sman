package com.smancode.sman.smancode.llm

import com.smancode.sman.smancode.llm.config.LlmPoolConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * LLM API 错误处理测试
 */
@DisplayName("LLM API 错误处理测试")
class LlmApiErrorTest {

    private val llmService = LlmService(LlmPoolConfig())

    @Test
    @DisplayName("GLM API 错误格式 - 401 令牌过期")
    fun testGlmApiError_format401() {
        assertApiErrorContains(
            response = """{"code":401,"msg":"令牌已过期或验证不正确","success":false}""",
            expectedCode = "401",
            expectedMsg = "令牌已过期或验证不正确"
        )
    }

    @Test
    @DisplayName("GLM API 错误格式 - 400 参数错误")
    fun testGlmApiError_format400() {
        assertApiErrorContains(
            response = """{"code":400,"msg":"参数错误","success":false}""",
            expectedCode = "400",
            expectedMsg = "参数错误"
        )
    }

    @Test
    @DisplayName("OpenAI 格式错误 - invalid_request")
    fun testOpenAIFormatError() {
        assertApiErrorContains(
            response = """{"error":{"message":"Invalid API key","type":"invalid_request_error"}}""",
            expectedCode = "invalid_request_error",
            expectedMsg = "Invalid API key"
        )
    }

    /**
     * 断言 API 错误包含特定信息
     */
    private fun assertApiErrorContains(response: String, expectedCode: String, expectedMsg: String) {
        val exception = assertFailsWith<InvocationTargetException> {
            testCheckForApiError(response)
        }

        val cause = exception.targetException
        assertTrue(cause is RuntimeException, "原因应该是 RuntimeException")
        assertTrue(cause.message!!.contains(expectedCode), "应该包含错误代码: $expectedCode")
        assertTrue(cause.message!!.contains(expectedMsg), "应该包含错误消息: $expectedMsg")
    }

    @Test
    @DisplayName("正常响应 - 不抛异常")
    fun testNormalResponse_noException() {
        val normalResponse = """
            {
                "choices": [{
                    "message": {"content": "Hello"}
                }]
            }
        """.trimIndent()

        // 不应该抛异常
        testCheckForApiError(normalResponse)
    }

    @Test
    @DisplayName("非 JSON 响应 - 不抛异常")
    fun testNonJsonResponse_noException() {
        val nonJsonResponse = "Just plain text response"

        // 不应该抛异常
        testCheckForApiError(nonJsonResponse)
    }

    @Test
    @DisplayName("成功响应 - code 为 0")
    fun testSuccessResponse_codeZero() {
        val successResponse = """
            {"code":0,"msg":"success","success":true}
        """.trimIndent()

        // 不应该抛异常
        testCheckForApiError(successResponse)
    }

    /**
     * 测试辅助方法
     */
    private fun testCheckForApiError(response: String) {
        val method = LlmService::class.java.getDeclaredMethod(
            "checkForApiError", String::class.java
        ).apply {
            isAccessible = true
        }
        method.invoke(llmService, response)
    }
}
