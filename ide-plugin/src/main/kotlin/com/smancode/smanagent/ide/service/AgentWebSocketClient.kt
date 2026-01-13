package com.smancode.smanagent.ide.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.smancode.smanagent.ide.model.GraphModels.PartType
import com.smancode.smanagent.ide.model.GraphModels.TextPartData
import com.smancode.smanagent.ide.model.GraphModels.UserPartData
import com.smancode.smanagent.ide.model.GraphModels.ToolPartData
import com.smancode.smanagent.ide.model.GraphModels.ReasoningPartData
import com.smancode.smanagent.ide.model.GraphModels.GoalPartData
import com.smancode.smanagent.ide.model.GraphModels.ProgressPartData
import com.smancode.smanagent.ide.model.GraphModels.TodoPartData
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * Agent WebSocket 客户端
 * <p>
 * 连接到后端 WebSocket 服务，接收实时 Part 推送。
 */
class AgentWebSocketClient(
    private val serverUrl: String,
    private val onPartCallback: ((com.smancode.smanagent.ide.model.PartData) -> Unit)? = null
) {

    private val logger = LoggerFactory.getLogger(AgentWebSocketClient::class.java)
    private val objectMapper = jacksonObjectMapper()

    private var client: WebSocketClient? = null

    // 回调
    var onConnected: ((Map<String, Any>) -> Unit)? = null
    var onComplete: ((Map<String, Any>) -> Unit)? = null
    var onError: ((Map<String, Any>) -> Unit)? = null
    var onPong: ((Map<String, Any>) -> Unit)? = null

    /**
     * 连接到服务器
     */
    fun connect(): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()

        try {
            println("[SmanAgent] 连接到 WebSocket 服务器: $serverUrl")
            logger.info("连接到 WebSocket 服务器: $serverUrl")

            client = object : WebSocketClient(URI(serverUrl)) {

                override fun onOpen(handshake: ServerHandshake) {
                    println("[SmanAgent] WebSocket 连接成功")
                    logger.info("WebSocket 连接成功")
                    javax.swing.SwingUtilities.invokeLater {
                        onConnected?.invoke(mapOf("status" to "connected"))
                    }
                    future.complete(null)
                }

                override fun onMessage(message: String) {
                    logger.debug("收到消息: {}", message)
                    handleMessage(message)
                }

                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    println("[SmanAgent] WebSocket 连接关闭: code=$code, reason=$reason")
                    logger.info("WebSocket 连接关闭: code={}, reason={}", code, reason)
                }

                override fun onError(ex: Exception) {
                    println("[SmanAgent] WebSocket 错误: ${ex.message}")
                    logger.error("WebSocket 错误", ex)
                    if (!future.isDone) {
                        future.completeExceptionally(ex)
                    }
                }
            }

            client?.connect()

        } catch (e: Exception) {
            println("[SmanAgent] WebSocket 连接失败: ${e.message}")
            e.printStackTrace()
            logger.error("WebSocket 连接失败", e)
            future.completeExceptionally(e)
        }

        return future
    }

    /**
     * 关闭连接
     */
    fun close() {
        try {
            client?.close()
            client = null
            println("[SmanAgent] WebSocket 已断开")
            logger.info("WebSocket 已断开")
        } catch (e: Exception) {
            logger.error("断开 WebSocket 失败", e)
        }
    }

    /**
     * 发送通用消息
     */
    fun send(data: Map<String, Any>) {
        try {
            val message = objectMapper.writeValueAsString(data)
            client?.send(message)
            logger.debug("发送消息: {}", data)
        } catch (e: Exception) {
            logger.error("发送消息失败", e)
        }
    }

    /**
     * 发送分析请求
     */
    fun analyze(sessionId: String, projectKey: String, input: String) {
        val request = mapOf(
            "type" to "analyze",
            "sessionId" to sessionId,
            "projectKey" to projectKey,
            "input" to input
        )
        send(request)
        logger.info("发送分析请求: {}", input)
    }

    /**
     * 发送聊天请求
     */
    fun chat(sessionId: String, input: String) {
        val request = mapOf(
            "type" to "chat",
            "sessionId" to sessionId,
            "input" to input
        )
        send(request)
        logger.info("发送聊天请求: {}", input)
    }

    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean {
        return client?.isOpen == true
    }

    /**
     * 处理收到的消息
     */
    private fun handleMessage(message: String) {
        try {
            val data = objectMapper.readValue<Map<String, Any>>(message)
            val type = data["type"] as? String ?: ""

            when (type) {
                "connected" -> onConnected?.invoke(data)
                "part" -> {
                    val part = parsePartData(data["part"] as? Map<String, Any> ?: emptyMap())
                    onPartCallback?.invoke(part)
                }
                "complete" -> onComplete?.invoke(data)
                "error" -> onError?.invoke(data)
                "pong" -> onPong?.invoke(data)
                else -> logger.warn("未知消息类型: {}", type)
            }

        } catch (e: Exception) {
            logger.error("处理消息失败: {}", message, e)
        }
    }

    /**
     * 解析 PartData
     */
    private fun parsePartData(data: Map<String, Any>): com.smancode.smanagent.ide.model.PartData {
        val id = data["id"] as? String ?: ""
        val typeStr = data["type"] as? String ?: "TEXT"
        val type = try {
            PartType.valueOf(typeStr)
        } catch (e: Exception) {
            PartType.TEXT
        }
        val createdTime = try {
            Instant.parse(data["createdTime"] as? String ?: "")
        } catch (e: Exception) {
            Instant.now()
        }
        val updatedTime = try {
            Instant.parse(data["updatedTime"] as? String ?: "")
        } catch (e: Exception) {
            Instant.now()
        }
        val messageId = data["messageId"] as? String ?: ""
        val sessionId = data["sessionId"] as? String ?: ""
        // 从 part 的 data 字段中提取特定类型的数据
        val partData = data["data"] as? Map<String, Any> ?: emptyMap()

        return when (type) {
            PartType.TEXT -> TextPartData(id, messageId, sessionId, createdTime, updatedTime, partData)
            PartType.USER -> UserPartData(id, messageId, sessionId, createdTime, updatedTime, partData)
            PartType.TOOL -> ToolPartData(id, messageId, sessionId, createdTime, updatedTime, partData)
            PartType.REASONING -> ReasoningPartData(id, messageId, sessionId, createdTime, updatedTime, partData)
            PartType.GOAL -> GoalPartData(id, messageId, sessionId, createdTime, updatedTime, partData)
            PartType.PROGRESS -> ProgressPartData(id, messageId, sessionId, createdTime, updatedTime, partData)
            PartType.TODO -> TodoPartData(id, messageId, sessionId, createdTime, updatedTime, partData)
        }
    }
}
