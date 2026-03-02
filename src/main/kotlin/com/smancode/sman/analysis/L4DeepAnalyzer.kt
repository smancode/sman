package com.smancode.sman.analysis

import java.io.File

class L4DeepAnalyzer(private val projectPath: String) {
    fun analyze(): L4Result {
        return L4Result(xmlBusinessLogic = emptyList(), antiPatterns = findAnti())
    }

    private fun findAnti(): List<AntiP> {
        val result = mutableListOf<AntiP>()
        File(projectPath).walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java") }
            .filter { !it.absolutePath.contains("/build/") }
            .take(100)
            .forEach { file ->
                try {
                    val content = file.readText()
                    val lines = content.lines().size
                    if (lines > 2000) result.add(AntiP("god_class", "类过大"))
                    if (content.contains("TODO")) result.add(AntiP("tech_debt", "待办"))
                } catch (e: Exception) { }
            }
        return result.take(20)
    }
}

data class L4Result(val xmlBusinessLogic: List<XmlLogic>, val antiPatterns: List<AntiP>)
data class XmlLogic(val file: String, val desc: String)
data class AntiP(val type: String, val desc: String)
