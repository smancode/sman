package com.smancode.sman.base.mocks

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Mock LLM 服务（用于测试）
 *
 * 注意：由于 LlmService 是 final 类且有复杂的依赖，这里创建一个简单的 Mock 接口
 */
class MockLlmService {

    private val responseQueue = ConcurrentLinkedQueue<String>()

    private val requestLog = mutableListOf<RequestLog>()

    var throwException: Boolean = false

    var exceptionMessage: String = "LLM service error"

    /**
     * 添加响应到队列
     */
    fun queueResponse(jsonResponse: String) {
        responseQueue.add(jsonResponse)
    }

    /**
     * 清空响应队列
     */
    fun clearResponses() {
        responseQueue.clear()
    }

    /**
     * 获取请求日志
     */
    fun getRequestLog(): List<RequestLog> = requestLog.toList()

    /**
     * 清空请求日志
     */
    fun clearRequestLog() {
        requestLog.clear()
    }

    fun simpleRequest(systemPrompt: String?, userPrompt: String): String {
        requestLog.add(RequestLog(systemPrompt, userPrompt))

        if (throwException) {
            throw RuntimeException(exceptionMessage)
        }

        return responseQueue.poll()
            ?: throw IllegalStateException("No mock response available. Call queueResponse() first.")
    }

    /**
     * 请求日志
     */
    data class RequestLog(
        val systemPrompt: String?,
        val userPrompt: String
    )
}
