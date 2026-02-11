package com.smancode.sman.base.mocks

import com.smancode.sman.tools.AbstractTool
import com.smancode.sman.tools.ParameterDef
import com.smancode.sman.tools.ToolResult

/**
 * 虚拟工具（用于测试）
 *
 * 总是返回成功的工具
 */
class DummyTool : AbstractTool() {

    var executeCount = 0
        private set

    var lastProjectKey: String? = null
        private set

    var lastParams: Map<String, Any>? = null
        private set

    var fixedResult: String? = null

    override fun getName(): String = "dummy"

    override fun getDescription(): String = "A dummy tool for testing"

    override fun getParameters(): Map<String, ParameterDef> = mapOf(
        "message" to ParameterDef("message", String::class.java, false, "Test message")
    )

    override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
        executeCount++
        lastProjectKey = projectKey
        lastParams = params

        val message = getOptString(params, "message", "default")
        val resultText = if (fixedResult != null) fixedResult!! else "Dummy executed: $message"
        return ToolResult.success(resultText, null, resultText)
    }

    fun reset() {
        executeCount = 0
        lastProjectKey = null
        lastParams = null
        fixedResult = null
    }
}
