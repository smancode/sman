package com.smancode.sman.analysis

import org.slf4j.LoggerFactory

/**
 * L3: 业务场景分析器
 * 追踪完整调用链
 */
class L3ScenarioAnalyzer(private val projectPath: String) {
    private val logger = LoggerFactory.getLogger(L3ScenarioAnalyzer::class.java)

    fun analyze(entryPoint: String): L3Result {
        logger.info("L3: 追踪业务场景 from {}", entryPoint)
        return L3Result(
            entryPoint = entryPoint,
            callChain = traceCallChain(entryPoint),
            dataFlow = analyzeDataFlow(entryPoint),
            involvedModules = findInvolvedModules(entryPoint)
        )
    }

    private fun traceCallChain(entryPoint: String): CallChain {
        // TODO: 集成 IntelliJ PSI 进行实际追踪
        return CallChain(entryPoint, listOf(CallNode("Service", "method", 1)))
    }

    private fun analyzeDataFlow(entryPoint: String): DataFlow {
        return DataFlow(entryPoint, emptyList(), emptyList())
    }

    private fun findInvolvedModules(entryPoint: String): List<String> {
        return listOf("module-a", "module-b")
    }
}

data class L3Result(
    val entryPoint: String,
    val callChain: CallChain,
    val dataFlow: DataFlow,
    val involvedModules: List<String>
)
data class CallChain(val entry: String, val nodes: List<CallNode>)
data class CallNode(val className: String, val methodName: String, val depth: Int)
data class DataFlow(val source: String, val transforms: List<String>, val sinks: List<String>)
