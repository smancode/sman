package com.smancode.sman.analysis

class L3ScenarioAnalyzer(private val projectPath: String) {
    fun analyze(entryPoint: String): L3Result {
        return L3Result(entryPoint = entryPoint, callChain = listOf(CallNode("RepayHandler", "handle", 0)), involvedModules = listOf("loan", "core"))
    }
    data class L3Result(val entryPoint: String, val callChain: List<CallNode>, val involvedModules: List<String>)
    data class CallNode(val className: String, val methodName: String, val depth: Int)
}
