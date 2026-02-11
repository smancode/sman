package com.smancode.sman.ide.model

import java.time.Instant

/**
 * Part 数据模型（用于前后端通信）
 */
sealed class PartData {
    abstract val id: String
    abstract val type: GraphModels.PartType
    abstract val messageId: String
    abstract val sessionId: String
    abstract val createdTime: Instant
    abstract val updatedTime: Instant

    // 用于 JSON 反序列化的通用 data 字段
    abstract val data: Map<String, Any>
}

/**
 * Todo Item
 */
data class TodoItem(
    val id: String,
    val content: String,
    val status: String  // PENDING/IN_PROGRESS/COMPLETED
)

/**
 * 图模型数据容器
 */
object GraphModels {

    /**
     * Part 类型枚举
     */
    enum class PartType {
        TEXT, TOOL, REASONING, GOAL, PROGRESS, TODO, USER
    }

    /**
     * 文本 Part
     */
    data class TextPartData(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        override val createdTime: Instant,
        override val updatedTime: Instant,
        override val data: Map<String, Any>
    ) : PartData() {
        override val type: PartType = PartType.TEXT
        val text: String get() = data["text"] as? String ?: ""
    }

    /**
     * 工具 Part
     * <p>
     * 新架构使用简单枚举状态：PENDING, RUNNING, COMPLETED, ERROR
     */
    data class ToolPartData(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        override val createdTime: Instant,
        override val updatedTime: Instant,
        override val data: Map<String, Any>
    ) : PartData() {
        override val type: PartType = PartType.TOOL
        val toolName: String get() = data["toolName"] as? String ?: ""
        val state: String get() = data["state"] as? String ?: "PENDING"
        val title: String? get() = data["title"] as? String
        val content: String? get() = data["content"] as? String
        val error: String? get() = data["error"] as? String
    }

    /**
     * 推理 Part
     */
    data class ReasoningPartData(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        override val createdTime: Instant,
        override val updatedTime: Instant,
        override val data: Map<String, Any>
    ) : PartData() {
        override val type: PartType = PartType.REASONING
        val text: String get() = data["text"] as? String ?: ""
    }

    /**
     * 目标 Part
     */
    data class GoalPartData(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        override val createdTime: Instant,
        override val updatedTime: Instant,
        override val data: Map<String, Any>
    ) : PartData() {
        override val type: PartType = PartType.GOAL
        val title: String get() = data["title"] as? String ?: ""
        val description: String get() = data["description"] as? String ?: ""
        val status: String get() = data["status"] as? String ?: "PENDING"
    }

    /**
     * 进度 Part
     */
    data class ProgressPartData(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        override val createdTime: Instant,
        override val updatedTime: Instant,
        override val data: Map<String, Any>
    ) : PartData() {
        override val type: PartType = PartType.PROGRESS
        val currentStep: Int get() = (data["currentStep"] as? Number)?.toInt() ?: 0
        val totalSteps: Int get() = (data["totalSteps"] as? Number)?.toInt() ?: 0
        val stepName: String get() = data["stepName"] as? String ?: ""
    }

    /**
     * 用户消息 Part
     */
    data class UserPartData(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        override val createdTime: Instant,
        override val updatedTime: Instant,
        override val data: Map<String, Any>
    ) : PartData() {
        override val type: PartType = PartType.USER
        val text: String get() = data["text"] as? String ?: ""
    }

    /**
     * Todo Part
     */
    data class TodoPartData(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        override val createdTime: Instant,
        override val updatedTime: Instant,
        override val data: Map<String, Any>
    ) : PartData() {
        override val type: PartType = PartType.TODO

        val items: List<TodoItem>
            get() = (data["items"] as? List<*>)?.mapNotNull {
                (it as? Map<*,*>)?.let { map ->
                    TodoItem(
                        id = map["id"] as? String ?: "",
                        content = map["content"] as? String ?: "",
                        status = map["status"] as? String ?: "PENDING"
                    )
                }
            } ?: emptyList()
    }

    /**
     * WebSocket 消息
     */
    data class WebSocketMessage(
        val type: String,  // connected/part/complete/error/pong
        val sessionId: String? = null,
        val part: Map<String, Any>? = null,
        val message: String? = null,
        val timestamp: Long? = null
    )

    /**
     * 分析请求
     */
    data class AnalyzeRequest(
        val sessionId: String,
        val projectKey: String,
        val input: String
    )

    /**
     * 分析响应
     */
    data class AnalyzeResponse(
        val sessionId: String,
        val status: String,
        val response: String? = null
    )
}
