package com.smancode.smanagent.model.part;

/**
 * 目标 Part
 * <p>
 * 用于显示当前的工作目标。
 */
public class GoalPart extends Part {

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
    private GoalStatus status;

    /**
     * 结论（完成时设置）
     */
    private String conclusion;

    public GoalPart() {
        super();
        this.type = PartType.GOAL;
        this.status = GoalStatus.PENDING;
    }

    public GoalPart(String id, String messageId, String sessionId) {
        super(id, messageId, sessionId, PartType.GOAL);
        this.status = GoalStatus.PENDING;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        touch();
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
        touch();
    }

    public GoalStatus getStatus() {
        return status;
    }

    public void setStatus(GoalStatus status) {
        this.status = status;
        touch();
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
        if (conclusion != null && !conclusion.isEmpty()) {
            this.status = GoalStatus.COMPLETED;
        }
        touch();
    }

    /**
     * 开始工作
     */
    public void start() {
        this.status = GoalStatus.IN_PROGRESS;
        touch();
    }

    /**
     * 完成工作
     *
     * @param conclusion 结论
     */
    public void complete(String conclusion) {
        this.conclusion = conclusion;
        this.status = GoalStatus.COMPLETED;
        touch();
    }

    /**
     * 取消工作
     */
    public void cancel() {
        this.status = GoalStatus.CANCELLED;
        touch();
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
        COMPLETED,

        /**
         * 已取消
         */
        CANCELLED
    }
}
