package com.smancode.smanagent.base.mocks

import com.smancode.smanagent.tools.AbstractTool
import com.smancode.smanagent.tools.ParameterDef
import com.smancode.smanagent.tools.ToolResult

/**
 * 失败工具（用于测试异常处理）
 *
 * 总是返回失败的工具
 */
class FailingTool : AbstractTool() {

    var errorMessage: String = "Tool execution failed"

    var exceptionToThrow: Throwable? = null

    override fun getName(): String = "failing"

    override fun getDescription(): String = "A tool that always fails for testing"

    override fun getParameters(): Map<String, ParameterDef> = emptyMap()

    override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
        exceptionToThrow?.let { throw it }

        return ToolResult.failure(errorMessage)
    }

    fun reset() {
        errorMessage = "Tool execution failed"
        exceptionToThrow = null
    }
}
