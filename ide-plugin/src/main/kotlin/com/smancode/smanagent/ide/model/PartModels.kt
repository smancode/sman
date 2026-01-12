package com.smancode.smanagent.ide.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant

/**
 * Part 数据模型（用于前后端通信）
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = GraphModels.TextPartData::class, name = "TEXT"),
    JsonSubTypes.Type(value = GraphModels.ToolPartData::class, name = "TOOL"),
    JsonSubTypes.Type(value = GraphModels.ReasoningPartData::class, name = "REASONING"),
    JsonSubTypes.Type(value = GraphModels.GoalPartData::class, name = "GOAL"),
    JsonSubTypes.Type(value = GraphModels.ProgressPartData::class, name = "PROGRESS"),
    JsonSubTypes.Type(value = GraphModels.TodoPartData::class, name = "TODO")
)
sealed class PartData {
    abstract val id: String
    abstract val messageId: String
    abstract val sessionId: String
    abstract val createdTime: Instant
    abstract val updatedTime: Instant
}

/**
 * 图模型数据容器
 */
object GraphModels {

    /**
     * Part 类型枚举
     */
    enum class PartType {
        TEXT, TOOL, REASONING, GOAL, PROGRESS, TODO
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
        val text: String
    ) : PartData()

    /**
     * 工具 Part
     */
    data class ToolPartData(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        override val createdTime: Instant,
        override val updatedTime: Instant,
        val toolName: String,
        val state: String,  // PendingState/RunningState/CompletedState/ErrorState
        val title: String? = null,
        val content: String? = null,
        val error: String? = null
    ) : PartData()

    /**
     * 推理 Part
     */
    data class ReasoningPartData(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        override val createdTime: Instant,
        override val updatedTime: Instant,
        val text: String
    ) : PartData()

    /**
     * 目标 Part
     */
    data class GoalPartData(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        override val createdTime: Instant,
        override val updatedTime: Instant,
        val title: String,
        val description: String,
        val status: String  // PENDING/IN_PROGRESS/COMPLETED/CANCELLED
    ) : PartData()

    /**
     * 进度 Part
     */
    data class ProgressPartData(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        override val createdTime: Instant,
        override val updatedTime: Instant,
        val currentStep: Int,
        val totalSteps: Int,
        val stepName: String
    ) : PartData()

    /**
     * Todo Part
     */
    data class TodoPartData(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        override val createdTime: Instant,
        override val updatedTime: Instant,
        val items: List<TodoItem>
    ) : PartData() {
        data class TodoItem(
            val id: String,
            val content: String,
            val status: String  // PENDING/IN_PROGRESS/COMPLETED
        )
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
