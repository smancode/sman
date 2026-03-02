package com.smancode.sman.analysis

import java.io.File
import java.util.concurrent.ConcurrentHashMap

class CallChainAnalyzer(private val projectPath: String) {
    private val graph = ConcurrentHashMap<String, MutableSet<String>>()

    init { build() }

    private fun build() {
        File(projectPath).walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java") }
            .filter { !it.absolutePath.contains("/build/") }
            .forEach { file ->
                try {
                    val content = file.readText()
                    val cls = file.nameWithoutExtension
                    graph.getOrPut(cls) { mutableSetOf() }
                    
                    Regex("""(\w+)\.(\w+)\(""").findAll(content).forEach { m ->
                        val target = m.groupValues[1]
                        if (target.first().isUpperCase() && target != cls) {
                            graph[cls]?.add(target)
                        }
                    }
                } catch (e: Exception) { }
            }
    }

    fun traceCallChain(cls: String, method: String): List<CallNode> {
        return listOf(CallNode(cls, method, 0))
    }

    data class CallNode(val className: String, val methodName: String, val depth: Int)
}
