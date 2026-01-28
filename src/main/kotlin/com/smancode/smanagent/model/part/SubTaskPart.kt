package com.smancode.smanagent.model.part

/**
 * 子任务 Part
 *
 * 用于表示一个可执行的小目标（参考 DIVE 的 DiveTarget 和 OpenCode 的 SubtaskPart）
 *
 * 设计理念：
 * - Goal: 用户的大目标（如"分析支付流程的异常处理"）
 * - SubTask: 可执行的小目标（如"分析 PaymentService 如何处理支付失败"）
 * - Tool: 具体工具调用（如 read_file, call_chain）
 */
class SubTaskPart : Part {

    /**
     * 目标对象（类名、文件名、方法名等）
     */
    var target: String? = null

    /**
     * 要回答的问题（小目标的具体描述）
     */
    var question: String? = null

    /**
     * 为什么要做这个子任务（与用户大目标的关联）
     */
    var reason: String? = null

    /**
     * 需要使用的工具列表
     */
    var requiredTools: MutableList<String> = mutableListOf()

    /**
     * 子任务状态
     */
    var status: SubTaskStatus = SubTaskStatus.PENDING

    /**
     * 执行结果/结论
     */
    var conclusion: String? = null
        set(value) {
            field = value
            if (!value.isNullOrEmpty()) {
                this.status = SubTaskStatus.COMPLETED
            }
            touch()
        }

    /**
     * 失败原因（如果状态为 BLOCKED）
     */
    var blockReason: String? = null

    /**
     * 依赖的其他 SubTask ID 列表
     *
     * 如果不为空，必须等依赖的 SubTask 完成后才能执行。
     * 用于支持并行执行：无依赖的 SubTask 可以并行跑。
     */
    var dependsOn: MutableList<String> = mutableListOf()

    constructor() : super() {
        this.type = PartType.SUBTASK
        this.status = SubTaskStatus.PENDING
        this.requiredTools = mutableListOf()
        this.dependsOn = mutableListOf()
    }

    constructor(id: String, messageId: String, sessionId: String) : super(id, messageId, sessionId, PartType.SUBTASK) {
        this.status = SubTaskStatus.PENDING
        this.requiredTools = mutableListOf()
        this.dependsOn = mutableListOf()
    }

    /**
     * 开始执行子任务
     */
    fun start() {
        this.status = SubTaskStatus.IN_PROGRESS
        touch()
    }

    /**
     * 完成子任务
     */
    fun complete(conclusion: String) {
        this.conclusion = conclusion
        this.status = SubTaskStatus.COMPLETED
        touch()
    }

    /**
     * 阻塞子任务（无法继续执行）
     */
    fun block(reason: String) {
        this.blockReason = reason
        this.status = SubTaskStatus.BLOCKED
        touch()
    }

    /**
     * 取消子任务
     */
    fun cancel() {
        this.status = SubTaskStatus.CANCELLED
        touch()
    }

    /**
     * 添加依赖
     */
    fun addDependency(subTaskId: String?) {
        if (!subTaskId.isNullOrEmpty()) {
            if (dependsOn.isEmpty()) {
                dependsOn = mutableListOf()
            }
            if (!dependsOn.contains(subTaskId)) {
                dependsOn.add(subTaskId)
            }
        }
        touch()
    }

    /**
     * 检查是否有依赖
     */
    fun hasDependencies(): Boolean = dependsOn.isNotEmpty()

    /**
     * 子任务状态枚举
     */
    enum class SubTaskStatus {
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
