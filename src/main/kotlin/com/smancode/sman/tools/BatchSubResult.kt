package com.smancode.sman.tools

/**
 * 批量工具的子结果
 *
 * 用于 batch 工具记录每个子工具的执行结果
 */
class BatchSubResult {

    /**
     * 子工具名称
     */
    var toolName: String? = null

    /**
     * 是否成功
     */
    var isSuccess: Boolean = false

    /**
     * 工具结果（复用 ToolResult）
     */
    var result: ToolResult? = null

    /**
     * 错误信息（如果失败）
     */
    var error: String? = null

    constructor()

    constructor(toolName: String, success: Boolean, result: ToolResult?, error: String?) {
        this.toolName = toolName
        this.isSuccess = success
        this.result = result
        this.error = error
    }
}
