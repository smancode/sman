package com.smancode.smanagent.model.part;

import java.util.ArrayList;
import java.util.List;

/**
 * 子任务 Part
 * <p>
 * 用于表示一个可执行的小目标（参考 DIVE 的 DiveTarget 和 OpenCode 的 SubtaskPart）
 * <p>
 * 设计理念：
 * - Goal: 用户的大目标（如"分析支付流程的异常处理"）
 * - SubTask: 可执行的小目标（如"分析 PaymentService 如何处理支付失败"）
 * - Tool: 具体工具调用（如 read_file, call_chain）
 */
public class SubTaskPart extends Part {

    /**
     * 目标对象（类名、文件名、方法名等）
     */
    private String target;

    /**
     * 要回答的问题（小目标的具体描述）
     */
    private String question;

    /**
     * 为什么要做这个子任务（与用户大目标的关联）
     */
    private String reason;

    /**
     * 需要使用的工具列表
     */
    private List<String> requiredTools;

    /**
     * 子任务状态
     */
    private SubTaskStatus status;

    /**
     * 执行结果/结论
     */
    private String conclusion;

    /**
     * 失败原因（如果状态为 BLOCKED）
     */
    private String blockReason;

    /**
     * 依赖的其他 SubTask ID 列表
     * <p>
     * 如果不为空，必须等依赖的 SubTask 完成后才能执行。
     * 用于支持并行执行：无依赖的 SubTask 可以并行跑。
     */
    private List<String> dependsOn;

    public SubTaskPart() {
        super();
        this.type = PartType.SUBTASK;
        this.status = SubTaskStatus.PENDING;
        this.requiredTools = new ArrayList<>();
        this.dependsOn = new ArrayList<>();
    }

    public SubTaskPart(String id, String messageId, String sessionId) {
        super(id, messageId, sessionId, PartType.SUBTASK);
        this.status = SubTaskStatus.PENDING;
        this.requiredTools = new ArrayList<>();
        this.dependsOn = new ArrayList<>();
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
        touch();
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
        touch();
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
        touch();
    }

    public List<String> getRequiredTools() {
        return requiredTools;
    }

    public void setRequiredTools(List<String> requiredTools) {
        this.requiredTools = requiredTools;
        touch();
    }

    public SubTaskStatus getStatus() {
        return status;
    }

    public void setStatus(SubTaskStatus status) {
        this.status = status;
        touch();
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
        if (conclusion != null && !conclusion.isEmpty()) {
            this.status = SubTaskStatus.COMPLETED;
        }
        touch();
    }

    public String getBlockReason() {
        return blockReason;
    }

    public void setBlockReason(String blockReason) {
        this.blockReason = blockReason;
        this.status = SubTaskStatus.BLOCKED;
        touch();
    }

    /**
     * 开始执行子任务
     */
    public void start() {
        this.status = SubTaskStatus.IN_PROGRESS;
        touch();
    }

    /**
     * 完成子任务
     */
    public void complete(String conclusion) {
        this.conclusion = conclusion;
        this.status = SubTaskStatus.COMPLETED;
        touch();
    }

    /**
     * 阻塞子任务（无法继续执行）
     */
    public void block(String reason) {
        this.blockReason = reason;
        this.status = SubTaskStatus.BLOCKED;
        touch();
    }

    /**
     * 取消子任务
     */
    public void cancel() {
        this.status = SubTaskStatus.CANCELLED;
        touch();
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn != null ? dependsOn : new ArrayList<>();
        touch();
    }

    /**
     * 添加依赖
     */
    public void addDependency(String subTaskId) {
        if (subTaskId != null && !subTaskId.isEmpty()) {
            if (this.dependsOn == null || this.dependsOn.isEmpty()) {
                this.dependsOn = new ArrayList<>();
            }
            if (!this.dependsOn.contains(subTaskId)) {
                this.dependsOn.add(subTaskId);
            }
        }
        touch();
    }

    /**
     * 检查是否有依赖
     */
    public boolean hasDependencies() {
        return dependsOn != null && !dependsOn.isEmpty();
    }

    /**
     * 子任务状态枚举
     */
    public enum SubTaskStatus {
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
         * 被阻塞（无法继续，如类不存在）
         */
        BLOCKED,

        /**
         * 已取消
         */
        CANCELLED
    }
}
