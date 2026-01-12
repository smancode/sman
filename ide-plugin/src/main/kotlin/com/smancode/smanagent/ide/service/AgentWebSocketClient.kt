package com.smancode.smanagent.ide.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.smancode.smanagent.ide.model.GraphModels.PartData
import com.smancode.smanagent.ide.renderer.CliMessageRenderer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.CompletableFuture

/**
 * Agent WebSocket 客户端
 * <p>
 * 连接到后端 WebSocket 服务，接收实时 Part 推送。
 */
class AgentWebSocketClient(private val serverUrl: String) {

    private val logger = LoggerFactory.getLogger(AgentWebSocketClient::class.java)
    private val objectMapper = jacksonObjectMapper()

    private var session: WebSocketSession? = null
    private val client = StandardWebSocketClient()

    /**
     * 连接到服务器
     */
    fun connect(): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()

        try {
            logger.info("连接到 WebSocket 服务器: $serverUrl")

            session = client.doExecute(handler, serverUrl).get()

            logger.info("WebSocket 连接成功")
            future.complete(null)

        } catch (e: Exception) {
            logger.error("WebSocket 连接失败", e)
            future.completeExceptionally(e)
        }

        return future
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        try {
            session?.close()
            session = null
            logger.info("WebSocket 已断开")
        } catch (e: Exception) {
            logger.error("断开 WebSocket 失败", e)
        }
    }

    /**
     * 发送分析请求
     */
    fun analyze(sessionId: String, projectKey: String, input: String) {
        try {
            val request = mapOf(
                "type" to "analyze",
                "sessionId" to sessionId,
                "projectKey" to projectKey,
                "input" to input
            )

            val message = objectMapper.writeValueAsString(request)
            session?.sendMessage(TextMessage(message))

            logger.debug("发送分析请求: {}", input)

        } catch (e: Exception) {
            logger.error("发送分析请求失败", e)
        }
    }

    /**
     * 发送聊天请求
     */
    fun chat(sessionId: String, input: String) {
        try {
            val request = mapOf(
                "type" to "chat",
                "sessionId" to sessionId,
                "input" to input
            )

            val message = objectMapper.writeValueAsString(request)
            session?.sendMessage(TextMessage(message))

            logger.debug("发送聊天请求: {}", input)

        } catch (e: Exception) {
            logger.error("发送聊天请求失败", e)
        }
    }

    /**
     * WebSocket 消息处理器
     */
    private val handler = object : TextWebSocketHandler() {

        override fun afterConnectionEstablished(session: WebSocketSession) {
            logger.info("WebSocket 连接已建立")
        }

        override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
            try {
                val payload = message.payload
                logger.debug("收到消息: {}", payload)

                val data = objectMapper.readValue<Map<String, Any>>(payload)
                val type = data["type"] as? String ?: ""

                when (type) {
                    "connected" -> onConnected(data)
                    "part" -> onPart(data)
                    "complete" -> onComplete(data)
                    "error" -> onError(data)
                    "pong" -> onPong(data)
                    else -> logger.warn("未知消息类型: {}", type)
                }

            } catch (e: Exception) {
                logger.error("处理消息失败", e)
            }
        }

        override fun afterConnectionClosed(session: WebSocketSession, status: org.springframework.web.socket.CloseStatus) {
            logger.info("WebSocket 连接已关闭: {}", status)
            onDisconnected?.invoke()
        }

        override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
            logger.error("WebSocket 传输错误", exception)
            onError?.invoke(exception)
        }
    }

    // ==================== 回调函数 ====================

    var onConnected: ((Map<String, Any>) -> Unit)? = null
    var onPart: ((Map<String, Any>) -> Unit)? = null
    var onComplete: ((Map<String, Any>) -> Unit)? = null
    var onError: ((Map<String, Any>) -> Unit)? = null
    var onPong: ((Map<String, Any>) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean {
        return session?.isOpen ?: false
    }
}
