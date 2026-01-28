package com.smancode.smanagent.model.part

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

/**
 * Part 基类
 *
 * Part 是消息的最小组成单元，支持独立创建、更新、删除。
 * 每个 Part 都有自己的类型和状态，可以独立渲染。
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = TextPart::class, name = "TEXT"),
    JsonSubTypes.Type(value = ToolPart::class, name = "TOOL"),
    JsonSubTypes.Type(value = ReasoningPart::class, name = "REASONING"),
    JsonSubTypes.Type(value = GoalPart::class, name = "GOAL"),
    JsonSubTypes.Type(value = ProgressPart::class, name = "PROGRESS"),
    JsonSubTypes.Type(value = TodoPart::class, name = "TODO"),
    JsonSubTypes.Type(value = SubTaskPart::class, name = "SUBTASK")
)
abstract class Part {
    /**
     * Part ID（唯一标识）
     */
    var id: String? = null

    /**
     * 所属 Message ID
     */
    var messageId: String? = null

    /**
     * 所属 Session ID
     */
    var sessionId: String? = null

    /**
     * Part 类型
     */
    var type: PartType? = null

    /**
     * 创建时间
     */
    var createdTime: Instant = Instant.now()

    /**
     * 更新时间
     */
    var updatedTime: Instant = Instant.now()

    protected constructor()

    protected constructor(id: String, messageId: String, sessionId: String, type: PartType) {
        this.id = id
        this.messageId = messageId
        this.sessionId = sessionId
        this.type = type
        this.createdTime = Instant.now()
        this.updatedTime = Instant.now()
    }

    /**
     * 更新时间戳
     */
    fun touch() {
        this.updatedTime = Instant.now()
    }
}
