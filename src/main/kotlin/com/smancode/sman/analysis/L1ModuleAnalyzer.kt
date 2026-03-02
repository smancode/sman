package com.smancode.sman.analysis

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * L1: 模块分析器
 * 理解每个模块的职责和边界
 */
class L1ModuleAnalyzer(
    private val projectPath: String,
    private val moduleName: String
) {
    private val logger = LoggerFactory.getLogger(L1ModuleAnalyzer::class.java)
    private val rootPath = Paths.get(projectPath)

    fun analyze(): L1Result {
        logger.info("L1: 分析模块 {}", moduleName)
        return L1Result(
            moduleName = moduleName,
            classes = analyzeClasses(),
            dependencies = analyzeDependencies(),
            interfaces = extractInterfaces()
        )
    }

    private fun analyzeClasses(): List<ClassInfo> {
        val classes = mutableListOf<ClassInfo>()
        Files.walk(rootPath)
            .filter { it.toFile().isFile }
            .filter { it.fileName.toString().endsWith(".java") }
            .filter { !it.toString().contains("/target/") }
            .forEach { file ->
                try {
                    val content = file.toFile().readText()
                    val name = file.toFile().nameWithoutExtension
                    val type = when {
                        content.contains("class ") -> "class"
                        content.contains("interface ") -> "interface"
                        content.contains("enum ") -> "enum"
                        else -> "other"
                    }
                    val methods = Regex("""(public|private|protected)?\s+\w+\s+(\w+)\s*\(""")
                        .findAll(content).map { it.groupValues[2] }.toList()
                    classes.add(ClassInfo(name, type, methods))
                } catch (e: Exception) { }
            }
        return classes
    }

    private fun analyzeDependencies(): List<Dependency> {
        val deps = mutableListOf<Dependency>()
        Files.walk(rootPath)
            .filter { it.toFile().isFile }
            .filter { it.fileName.toString() == "pom.xml" }
            .forEach { file ->
                try {
                    val content = file.toFile().readText()
                    Regex("""<artifactId>([\w-]+)</artifactId>""").findAll(content).forEach {
                        deps.add(Dependency("module", it.groupValues[1], "maven"))
                    }
                } catch (e: Exception) { }
            }
        return deps
    }

    private fun extractInterfaces(): List<InterfaceInfo> {
        return analyzeClasses()
            .filter { it.type == "interface" }
            .map { InterfaceInfo(it.name, it.methods) }
    }
}

data class ClassInfo(val name: String, val type: String, val methods: List<String>)
data class Dependency(val from: String, val to: String, val type: String)
data class InterfaceInfo(val name: String, val methods: List<String>)

    data class L1Result(
    val moduleName: String,
    val classes: List<ClassInfo>,
    val dependencies: List<Dependency>,
    val interfaces: List<InterfaceInfo>
)
