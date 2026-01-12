package com.smancode.smanagent.model.part;

/**
 * 进度 Part
 * <p>
 * 用于显示当前进度信息。
 */
public class ProgressPart extends Part {

    /**
     * 当前正在做的事情
     */
    private String current;

    /**
     * 当前步骤（从 1 开始）
     */
    private Integer currentStep;

    /**
     * 总步骤数
     */
    private Integer totalSteps;

    public ProgressPart() {
        super();
        this.type = PartType.PROGRESS;
    }

    public ProgressPart(String id, String messageId, String sessionId) {
        super(id, messageId, sessionId, PartType.PROGRESS);
    }

    public String getCurrent() {
        return current;
    }

    public void setCurrent(String current) {
        this.current = current;
        touch();
    }

    public Integer getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(Integer currentStep) {
        this.currentStep = currentStep;
        touch();
    }

    public Integer getTotalSteps() {
        return totalSteps;
    }

    public void setTotalSteps(Integer totalSteps) {
        this.totalSteps = totalSteps;
        touch();
    }

    /**
     * 更新进度
     *
     * @param currentStep 当前步骤
     * @param totalSteps  总步骤数
     */
    public void updateProgress(Integer currentStep, Integer totalSteps) {
        this.currentStep = currentStep;
        this.totalSteps = totalSteps;
        touch();
    }

    /**
     * 获取显示文本
     */
    public String getDisplayText() {
        if (totalSteps != null && currentStep != null) {
            return String.format("[%d/%d] %s", currentStep, totalSteps, current);
        }
        return current;
    }

    /**
     * 检查是否完成
     */
    public boolean isCompleted() {
        if (totalSteps != null && currentStep != null) {
            return currentStep >= totalSteps;
        }
        return false;
    }
}
