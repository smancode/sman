package com.smancode.sman.model.part

/**
 * 进度 Part
 *
 * 用于显示当前进度信息。
 */
class ProgressPart : Part {

    /**
     * 当前正在做的事情
     */
    var current: String? = null

    /**
     * 当前步骤（从 1 开始）
     */
    var currentStep: Int? = null

    /**
     * 总步骤数
     */
    var totalSteps: Int? = null

    constructor() : super() {
        this.type = PartType.PROGRESS
    }

    constructor(id: String, messageId: String, sessionId: String) : super(id, messageId, sessionId, PartType.PROGRESS)

    /**
     * 更新进度
     *
     * @param currentStep 当前步骤
     * @param totalSteps  总步骤数
     */
    fun updateProgress(currentStep: Int?, totalSteps: Int?) {
        this.currentStep = currentStep
        this.totalSteps = totalSteps
        touch()
    }

    /**
     * 获取显示文本
     */
    fun getDisplayText(): String {
        return if (totalSteps != null && currentStep != null) {
            "[$currentStep/$totalSteps] $current"
        } else {
            current ?: ""
        }
    }

    /**
     * 检查是否完成
     */
    fun isCompleted(): Boolean {
        return if (totalSteps != null && currentStep != null) {
            currentStep!! >= totalSteps!!
        } else false
    }
}
