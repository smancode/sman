package com.smancode.smanagent.model.session;

import com.smancode.smanagent.model.message.Message;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话
 * <p>
 * Session 代表一个完整的对话会话，包含多个 Message。
 * 每个 Message 可以包含多个 Part。
 * <p>
 * 极简设计（参考 OpenCode）：
 * - 只有 3 种状态：IDLE, BUSY, RETRY
 * - 移除 Goal 内部类，目标信息通过 GoalPart 表达
 * - 所有状态通过 Part 系统管理
 */
public class Session {

    /**
     * 会话 ID
     */
    private String id;

    /**
     * WebSocket Session ID（根会话才有，用于工具转发）
     */
    private String webSocketSessionId;

    /**
     * 项目信息
     */
    private ProjectInfo projectInfo;

    /**
     * 用户内网IP地址
     */
    private String userIp;

    /**
     * 用户电脑名称（hostname）
     */
    private String userName;

    /**
     * 会话状态（极简：只有 3 种）
     */
    private SessionStatus status;

    /**
     * 消息列表（线性消息流）
     */
    private List<Message> messages;

    /**
     * 创建时间
     */
    private Instant createdTime;

    /**
     * 更新时间
     */
    private Instant updatedTime;

    /**
     * 上次 commit 时间（用于增量统计文件变更）
     */
    private Instant lastCommitTime;

    public Session() {
        this.status = SessionStatus.IDLE;
        this.messages = new ArrayList<>();
        this.createdTime = Instant.now();
        this.updatedTime = Instant.now();
    }

    public Session(String id, ProjectInfo projectInfo) {
        this();
        this.id = id;
        this.projectInfo = projectInfo;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWebSocketSessionId() {
        return webSocketSessionId;
    }

    public void setWebSocketSessionId(String webSocketSessionId) {
        this.webSocketSessionId = webSocketSessionId;
    }

    public ProjectInfo getProjectInfo() {
        return projectInfo;
    }

    public void setProjectInfo(ProjectInfo projectInfo) {
        this.projectInfo = projectInfo;
        touch();
    }

    public String getUserIp() {
        return userIp;
    }

    public void setUserIp(String userIp) {
        this.userIp = userIp;
        touch();
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
        touch();
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
        touch();
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
        touch();
    }

    /**
     * 添加消息
     *
     * @param message 消息
     */
    public void addMessage(Message message) {
        this.messages.add(message);
        touch();
    }

    /**
     * 获取最新消息
     *
     * @return 最新消息，如果没有返回 null
     */
    public Message getLatestMessage() {
        if (messages.isEmpty()) {
            return null;
        }
        return messages.get(messages.size() - 1);
    }

    /**
     * 获取最新助手消息
     *
     * @return 最新助手消息，如果没有返回 null
     */
    public Message getLatestAssistantMessage() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isAssistantMessage()) {
                return messages.get(i);
            }
        }
        return null;
    }

    /**
     * 获取最新用户消息
     *
     * @return 最新用户消息，如果没有返回 null
     */
    public Message getLatestUserMessage() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isUserMessage()) {
                return messages.get(i);
            }
        }
        return null;
    }

    /**
     * 检查是否有新的用户消息（在最新助手消息之后）
     *
     * @param lastAssistantId 最新助手消息 ID
     * @return 是否有新用户消息
     */
    public boolean hasNewUserMessageAfter(String lastAssistantId) {
        if (lastAssistantId == null) {
            return !messages.isEmpty() && getLatestMessage().isUserMessage();
        }

        // 找到助手消息的位置
        int assistantIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getId().equals(lastAssistantId)) {
                assistantIndex = i;
                break;
            }
        }

        // 如果找不到助手消息，返回 false
        if (assistantIndex == -1) {
            return false;
        }

        // 检查助手消息之后是否有用户消息
        for (int i = assistantIndex + 1; i < messages.size(); i++) {
            if (messages.get(i).isUserMessage()) {
                return true;
            }
        }

        return false;
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
     * 标记为忙碌
     */
    public void markBusy() {
        this.status = SessionStatus.BUSY;
        touch();
    }

    /**
     * 标记为空闲
     */
    public void markIdle() {
        this.status = SessionStatus.IDLE;
        touch();
    }

    /**
     * 检查是否忙碌
     */
    public boolean isBusy() {
        return status == SessionStatus.BUSY;
    }

    /**
     * 检查是否空闲
     */
    public boolean isIdle() {
        return status == SessionStatus.IDLE;
    }

    public Instant getLastCommitTime() {
        return lastCommitTime;
    }

    public void setLastCommitTime(Instant lastCommitTime) {
        this.lastCommitTime = lastCommitTime;
        touch();
    }
}
