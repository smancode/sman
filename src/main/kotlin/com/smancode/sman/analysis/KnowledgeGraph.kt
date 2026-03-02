package com.smancode.sman.analysis

import java.io.File

/**
 * 知识图谱
 */
class KnowledgeGraph(private val projectPath: String) {

    private val classes = mutableMapOf<String, ClassInfo>()
    private val relationships = mutableMapOf<String, MutableSet<String>>()

    init { build() }

    fun build() {
        File(projectPath).walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java") }
            .filter { !it.absolutePath.contains("/build/") }
            .forEach { file -> analyzeFile(file) }
    }

    private fun analyzeFile(file: File) {
        val content = file.readText()
        val name = file.nameWithoutExtension
        
        val type = when {
            content.contains("interface ") -> "interface"
            content.contains("enum ") -> "enum"
            content.contains("abstract ") -> "abstract"
            else -> "class"
        }
        
        classes[name] = ClassInfo(name, type)
        
        // 找继承
        Regex("""extends\s+(\w+)""").find(content)?.groupValues?.get(1)?.let {
            addRel(name, it, "extends")
        }
        
        // 找实现
        Regex("""implements\s+([\w,]+)""").find(content)?.groupValues?.get(1)?.split(",")?.forEach {
            addRel(name, it.trim(), "implements")
        }
        
        // 找方法调用
        Regex("""(\w+)\.(\w+)\(""").findAll(content).forEach { m ->
            val target = m.groupValues[1]
            if (target.first().isUpperCase() && target != name) {
                addRel(name, target, "calls")
            }
        }
    }

    private fun addRel(from: String, to: String, type: String) {
        if (from == to) return
        relationships.getOrPut(from) { mutableSetOf() }.add("$type:$to")
    }

    fun findRelated(className: String): List<Pair<String, String>> {
        return relationships[className]?.map { rel ->
            val parts = rel.split(":")
            parts[1] to parts[0]
        } ?: emptyList()
    }

    fun stats(): String = "类: ${classes.size}, 关系: ${relationships.size}"

    data class ClassInfo(val name: String, val type: String)
}
