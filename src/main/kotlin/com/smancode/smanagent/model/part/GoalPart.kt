package com.smancode.smanagent.model.part

/**
 * 目标 Part
 *
 * 用于显示当前的工作目标。
 */
class GoalPart : Part {

    /**
     * 目标标题
     */
    var title: String? = null

    /**
     * 目标描述
     */
    var description: String? = null

    /**
     * 目标状态
     */
    var status: GoalStatus = GoalStatus.PENDING

    /**
     * 结论（完成时设置）
     */
    var conclusion: String? = null
        set(value) {
            field = value
            if (!value.isNullOrEmpty()) {
                this.status = GoalStatus.COMPLETED
            }
            touch()
        }

    constructor() : super() {
        this.type = PartType.GOAL
        this.status = GoalStatus.PENDING
    }

    constructor(id: String, messageId: String, sessionId: String) : super(id, messageId, sessionId, PartType.GOAL) {
        this.status = GoalStatus.PENDING
    }

    /**
     * 开始工作
     */
    fun start() {
        this.status = GoalStatus.IN_PROGRESS
        touch()
    }

    /**
     * 完成工作
     *
     * @param conclusion 结论
     */
    fun complete(conclusion: String) {
        this.conclusion = conclusion
        this.status = GoalStatus.COMPLETED
        touch()
    }

    /**
     * 取消工作
     */
    fun cancel() {
        this.status = GoalStatus.CANCELLED
        touch()
    }

    /**
     * 目标状态枚举
     */
    enum class GoalStatus {
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
