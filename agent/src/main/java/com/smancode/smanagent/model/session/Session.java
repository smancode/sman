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
 */
public class Session {

    /**
     * 会话 ID
     */
    private String id;

    /**
     * 项目信息
     */
    private ProjectInfo projectInfo;

    /**
     * 会话状态
     */
    private SessionStatus status;

    /**
     * 当前目标
     */
    private Goal goal;

    /**
     * 消息列表
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

    public ProjectInfo getProjectInfo() {
        return projectInfo;
    }

    public void setProjectInfo(ProjectInfo projectInfo) {
        this.projectInfo = projectInfo;
        touch();
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
        touch();
    }

    public Goal getGoal() {
        return goal;
    }

    public void setGoal(Goal goal) {
        this.goal = goal;
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
     * 开始工作
     */
    public void startWorking() {
        this.status = SessionStatus.WORKING;
        touch();
    }

    /**
     * 完成工作
     */
    public void complete() {
        this.status = SessionStatus.DONE;
        touch();
    }

    /**
     * 取消工作
     */
    public void cancel() {
        this.status = SessionStatus.CANCELLED;
        touch();
    }

    /**
     * 检查是否在工作中
     */
    public boolean isWorking() {
        return status == SessionStatus.WORKING;
    }

    /**
     * 检查是否已完成
     */
    public boolean isDone() {
        return status == SessionStatus.DONE;
    }

    // ==================== Goal 内部类 ====================

    /**
     * 目标
     */
    public static class Goal {
        /**
         * 目标 ID
         */
        private String id;

        /**
         * 目标标题
         */
        private String title;

        /**
         * 目标描述
         */
        private String description;

        /**
         * 目标状态
         */
        private GoalStatus goalStatus;

        /**
         * 结论（完成时设置）
         */
        private String conclusion;

        public Goal() {
            this.goalStatus = GoalStatus.PENDING;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public GoalStatus getGoalStatus() {
            return goalStatus;
        }

        public void setGoalStatus(GoalStatus goalStatus) {
            this.goalStatus = goalStatus;
        }

        public String getConclusion() {
            return conclusion;
        }

        public void setConclusion(String conclusion) {
            this.conclusion = conclusion;
            if (conclusion != null && !conclusion.isEmpty()) {
                this.goalStatus = GoalStatus.COMPLETED;
            }
        }

        /**
         * 目标状态枚举
         */
        public enum GoalStatus {
            /**
             * 待处理
             */
            PENDING,

            /**
             * 进行中
             */
            IN_PROGRESS,

            /**
             * 已完成
             */
            COMPLETED
        }
    }
}
