package com.smancode.smanagent.model.message;

import com.smancode.smanagent.model.part.Part;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息
 * <p>
 * 消息是会话中的一个节点，包含一个或多个 Part。
 * 每个 Part 可以独立创建、更新、删除。
 */
public class Message {

    /**
     * 消息 ID
     */
    private String id;

    /**
     * 所属 Session ID
     */
    private String sessionId;

    /**
     * 消息角色
     */
    private Role role;

    /**
     * 消息内容（兼容旧接口）
     */
    private String content;

    /**
     * Part 列表
     */
    private List<Part> parts;

    /**
     * Token 使用统计
     */
    private TokenUsage tokenUsage;

    /**
     * 创建时间
     */
    private Instant createdTime;

    /**
     * 更新时间
     */
    private Instant updatedTime;

    public Message() {
        this.parts = new ArrayList<>();
        this.createdTime = Instant.now();
        this.updatedTime = Instant.now();
    }

    public Message(String id, String sessionId, Role role, String content) {
        this();
        this.id = id;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
        touch();
    }

    public List<Part> getParts() {
        return parts;
    }

    public void setParts(List<Part> parts) {
        this.parts = parts;
        touch();
    }

    /**
     * 添加 Part
     *
     * @param part Part
     */
    public void addPart(Part part) {
        this.parts.add(part);
        touch();
    }

    /**
     * 移除 Part
     *
     * @param partId Part ID
     * @return 被移除的 Part，如果不存在返回 null
     */
    public Part removePart(String partId) {
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).getId().equals(partId)) {
                Part removed = parts.remove(i);
                touch();
                return removed;
            }
        }
        return null;
    }

    /**
     * 获取 Part
     *
     * @param partId Part ID
     * @return Part，如果不存在返回 null
     */
    public Part getPart(String partId) {
        for (Part part : parts) {
            if (part.getId().equals(partId)) {
                return part;
            }
        }
        return null;
    }

    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(TokenUsage tokenUsage) {
        this.tokenUsage = tokenUsage;
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

    /**
     * 获取总耗时（毫秒）
     */
    public long getTotalDuration() {
        return java.time.Duration.between(createdTime, updatedTime).toMillis();
    }

    /**
     * 检查是否为用户消息
     */
    public boolean isUserMessage() {
        return role == Role.USER;
    }

    /**
     * 检查是否为助手消息
     */
    public boolean isAssistantMessage() {
        return role == Role.ASSISTANT;
    }

    /**
     * 检查是否为系统消息
     */
    public boolean isSystemMessage() {
        return role == Role.SYSTEM;
    }
}
