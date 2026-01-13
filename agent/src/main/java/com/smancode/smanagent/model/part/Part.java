package com.smancode.smanagent.model.part;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

/**
 * Part 基类
 * <p>
 * Part 是消息的最小组成单元，支持独立创建、更新、删除。
 * 每个 Part 都有自己的类型和状态，可以独立渲染。
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextPart.class, name = "TEXT"),
        @JsonSubTypes.Type(value = ToolPart.class, name = "TOOL"),
        @JsonSubTypes.Type(value = ReasoningPart.class, name = "REASONING"),
        @JsonSubTypes.Type(value = GoalPart.class, name = "GOAL"),
        @JsonSubTypes.Type(value = ProgressPart.class, name = "PROGRESS"),
        @JsonSubTypes.Type(value = TodoPart.class, name = "TODO")
})
public abstract class Part {

    /**
     * Part ID（唯一标识）
     */
    protected String id;

    /**
     * 所属 Message ID
     */
    protected String messageId;

    /**
     * 所属 Session ID
     */
    protected String sessionId;

    /**
     * Part 类型
     */
    protected PartType type;

    /**
     * 创建时间
     */
    protected Instant createdTime;

    /**
     * 更新时间
     */
    protected Instant updatedTime;

    protected Part() {
        this.createdTime = Instant.now();
        this.updatedTime = Instant.now();
    }

    protected Part(String id, String messageId, String sessionId, PartType type) {
        this.id = id;
        this.messageId = messageId;
        this.sessionId = sessionId;
        this.type = type;
        this.createdTime = Instant.now();
        this.updatedTime = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public PartType getType() {
        return type;
    }

    public void setType(PartType type) {
        this.type = type;
    }

    public Instant getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(Instant createdTime) {
        this.createdTime = createdTime;
    }

    public Instant getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(Instant updatedTime) {
        this.updatedTime = updatedTime;
    }

    /**
     * 更新时间戳
     */
    public void touch() {
        this.updatedTime = Instant.now();
    }
}
