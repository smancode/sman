package com.smancode.smanagent.model.part

import com.smancode.smanagent.tools.ToolResult

/**
 * 工具调用 Part
 *
 * 极简设计（参考 OpenCode）：
 * - 简单的枚举状态（而非复杂的状态类继承）
 * - 状态流转：PENDING → RUNNING → COMPLETED/ERROR
 * - 包含工具名称、参数、结果
 */
class ToolPart : Part {

    /**
     * 工具名称
     */
    var toolName: String? = null

    /**
     * 工具参数
     */
    var parameters: Map<String, Any>? = null

    /**
     * 工具执行结果
     */
    var result: ToolResult? = null

    /**
     * LLM 生成的摘要（由 LLM 基于完整结果生成）
     */
    var summary: String? = null

    /**
     * 当前状态（极简枚举）
     */
    var state: ToolState = ToolState.PENDING
        set(value) {
            // 状态转换校验
            if (field == ToolState.COMPLETED || field == ToolState.ERROR) {
                throw IllegalStateException("已完成或错误的工具不能转换状态")
            }
            field = value
            touch()
        }

    constructor() : super() {
        this.type = PartType.TOOL
        this.state = ToolState.PENDING
    }

    constructor(id: String, messageId: String, sessionId: String, toolName: String) : super(id, messageId, sessionId, PartType.TOOL) {
        this.toolName = toolName
        this.state = ToolState.PENDING
    }

    /**
     * 工具状态枚举（极简设计）
     */
    enum class ToolState {
        /**
         * 等待执行
         */
        PENDING,

        /**
         * 执行中
         */
        RUNNING,

        /**
         * 已完成
         */
        COMPLETED,

        /**
         * 错误
         */
        ERROR
    }
}
