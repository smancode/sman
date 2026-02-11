package com.smancode.sman.model.message

import com.smancode.sman.model.part.Part
import java.time.Instant

/**
 * 消息
 *
 * 消息是会话中的一个节点，包含一个或多个 Part。
 * 每个 Part 可以独立创建、更新、删除。
 */
class Message {

    /**
     * 消息 ID
     */
    var id: String? = null

    /**
     * 所属 Session ID
     */
    var sessionId: String? = null

    /**
     * 消息角色
     */
    var role: Role? = null

    /**
     * 消息内容（兼容旧接口）
     */
    var content: String? = null
        set(value) {
            field = value
            touch()
        }

    /**
     * Part 列表
     */
    var parts: MutableList<Part> = mutableListOf()
        set(value) {
            field = value
            touch()
        }

    /**
     * Token 使用统计
     */
    var tokenUsage: TokenUsage? = null

    /**
     * 创建时间
     */
    var createdTime: Instant = Instant.now()

    /**
     * 更新时间
     */
    var updatedTime: Instant = Instant.now()

    constructor() {
        this.parts = mutableListOf()
        this.createdTime = Instant.now()
        this.updatedTime = Instant.now()
    }

    constructor(id: String, sessionId: String, role: Role, content: String?) : this() {
        this.id = id
        this.sessionId = sessionId
        this.role = role
        this.content = content
    }

    /**
     * 添加 Part
     *
     * @param part Part
     */
    fun addPart(part: Part) {
        this.parts.add(part)
        touch()
    }

    /**
     * 移除 Part
     *
     * @param partId Part ID
     * @return 被移除的 Part，如果不存在返回 null
     */
    fun removePart(partId: String): Part? {
        val index = parts.indexOfFirst { it.id == partId }
        return if (index >= 0) {
            val removed = parts.removeAt(index)
            touch()
            removed
        } else null
    }

    /**
     * 获取 Part
     *
     * @param partId Part ID
     * @return Part，如果不存在返回 null
     */
    fun getPart(partId: String): Part? = parts.find { it.id == partId }

    /**
     * 更新时间戳
     */
    fun touch() {
        this.updatedTime = Instant.now()
    }

    /**
     * 获取总耗时（毫秒）
     */
    fun getTotalDuration(): Long = java.time.Duration.between(createdTime, updatedTime).toMillis()

    /**
     * 检查是否为用户消息
     */
    fun isUserMessage(): Boolean = role == Role.USER

    /**
     * 检查是否为助手消息
     */
    fun isAssistantMessage(): Boolean = role == Role.ASSISTANT

    /**
     * 检查是否为系统消息
     */
    fun isSystemMessage(): Boolean = role == Role.SYSTEM
}
