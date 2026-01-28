package com.smancode.smanagent.model.session

import com.smancode.smanagent.model.message.Message
import java.time.Instant

/**
 * 会话
 *
 * Session 代表一个完整的对话会话，包含多个 Message。
 * 每个 Message 可以包含多个 Part。
 *
 * 极简设计（参考 OpenCode）：
 * - 只有 3 种状态：IDLE, BUSY, RETRY
 * - 移除 Goal 内部类，目标信息通过 GoalPart 表达
 * - 所有状态通过 Part 系统管理
 */
class Session {

    /**
     * 会话 ID
     */
    var id: String? = null

    /**
     * WebSocket Session ID（根会话才有，用于工具转发）
     */
    var webSocketSessionId: String? = null

    /**
     * 项目信息
     */
    var projectInfo: ProjectInfo? = null
        set(value) {
            field = value
            touch()
        }

    /**
     * 用户内网IP地址
     */
    var userIp: String? = null
        set(value) {
            field = value
            touch()
        }

    /**
     * 用户电脑名称（hostname）
     */
    var userName: String? = null
        set(value) {
            field = value
            touch()
        }

    /**
     * 会话状态（极简：只有 3 种）
     */
    var status: SessionStatus = SessionStatus.IDLE
        set(value) {
            field = value
            touch()
        }

    /**
     * 消息列表（线性消息流）
     */
    var messages: MutableList<Message> = mutableListOf()
        set(value) {
            field = value
            touch()
        }

    /**
     * 创建时间
     */
    var createdTime: Instant = Instant.now()

    /**
     * 更新时间
     */
    var updatedTime: Instant = Instant.now()

    /**
     * 上次 commit 时间（用于增量统计文件变更）
     */
    var lastCommitTime: Instant? = null
        set(value) {
            field = value
            touch()
        }

    constructor() {
        this.status = SessionStatus.IDLE
        this.messages = mutableListOf()
        this.createdTime = Instant.now()
        this.updatedTime = Instant.now()
    }

    constructor(id: String, projectInfo: ProjectInfo?) : this() {
        this.id = id
        this.projectInfo = projectInfo
    }

    /**
     * 添加消息
     *
     * @param message 消息
     */
    fun addMessage(message: Message) {
        this.messages.add(message)
        touch()
    }

    /**
     * 获取最新消息
     *
     * @return 最新消息，如果没有返回 null
     */
    fun getLatestMessage(): Message? = if (messages.isEmpty()) null else messages.last()

    /**
     * 获取最新助手消息
     *
     * @return 最新助手消息，如果没有返回 null
     */
    fun getLatestAssistantMessage(): Message? =
        messages.asReversed().firstOrNull { it.isAssistantMessage() }

    /**
     * 获取最新用户消息
     *
     * @return 最新用户消息，如果没有返回 null
     */
    fun getLatestUserMessage(): Message? =
        messages.asReversed().firstOrNull { it.isUserMessage() }

    /**
     * 检查是否有新的用户消息（在最新助手消息之后）
     *
     * @param lastAssistantId 最新助手消息 ID
     * @return 是否有新用户消息
     */
    fun hasNewUserMessageAfter(lastAssistantId: String?): Boolean {
        if (lastAssistantId == null) {
            return messages.isNotEmpty() && getLatestMessage()?.isUserMessage() == true
        }

        // 找到助手消息的位置
        val assistantIndex = messages.indexOfFirst { it.id == lastAssistantId }
        if (assistantIndex == -1) {
            return false
        }

        // 检查助手消息之后是否有用户消息
        return messages.drop(assistantIndex + 1).any { it.isUserMessage() }
    }

    /**
     * 更新时间戳
     */
    fun touch() {
        this.updatedTime = Instant.now()
    }

    /**
     * 标记为忙碌
     */
    fun markBusy() {
        this.status = SessionStatus.BUSY
        touch()
    }

    /**
     * 标记为空闲
     */
    fun markIdle() {
        this.status = SessionStatus.IDLE
        touch()
    }

    /**
     * 检查是否忙碌
     */
    fun isBusy(): Boolean = status == SessionStatus.BUSY

    /**
     * 检查是否空闲
     */
    fun isIdle(): Boolean = status == SessionStatus.IDLE

    // ========== 属性访问方式（兼容 Java 风格调用） ==========

    /**
     * 最新用户消息（属性访问方式）
     */
    val latestUserMessage: Message?
        @JvmName("getLatestUserMessageProp")
        get() = getLatestUserMessage()

    /**
     * 最新助手消息（属性访问方式）
     */
    val latestAssistantMessage: Message?
        @JvmName("getLatestAssistantMessageProp")
        get() = getLatestAssistantMessage()
}
