package com.smancode.sman.analysis

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * L4: 深度理解分析器
 * 发现非常规设计、隐藏业务逻辑
 */
class L4DeepAnalyzer(private val projectPath: String) {
    private val logger = LoggerFactory.getLogger(L4DeepAnalyzer::class.java)
    private val rootPath = Paths.get(projectPath)

    fun analyze(): L4Result {
        logger.info("L4: 深度分析 - 非常规设计探测")
        return L4Result(
            xmlBusinessLogic = findXmlBusinessLogic(),
            annotationsWithLogic = findAnnotationBusinessLogic(),
            dynamicCodes = findDynamicCode(),
            databaseBusinessRules = findDbBusinessRules(),
            configDrivenLogic = findConfigDrivenLogic(),
            antiPatterns = detectAntiPatterns()
        )
    }

    private fun findXmlBusinessLogic(): List<XmlBusinessLogic> {
        val results = mutableListOf<XmlBusinessLogic>()
        Files.walk(rootPath)
            .filter { it.toFile().isFile }
            .filter { it.fileName.toString().endsWith(".xml") }
            .filter { it.toString().contains("mapper") || it.toString().contains("mybatis") }
            .forEach { file ->
                try {
                    val content = file.toFile().readText()
                    if (content.contains("<if") || content.contains("<foreach") || content.contains("<choose")) {
                        results.add(XmlBusinessLogic(
                            file.toString(),
                            "mapper with dynamic SQL",
                            "MyBatis 动态 SQL"
                        ))
                    }
                } catch (e: Exception) { }
            }
        return results
    }

    private fun findAnnotationBusinessLogic(): List<AnnotationLogic> {
        val results = mutableListOf<AnnotationLogic>()
        Files.walk(rootPath)
            .filter { it.toFile().isFile }
            .filter { it.fileName.toString().endsWith(".java") }
            .forEach { file ->
                try {
                    val content = file.toFile().readText()
                    // 自定义注解中的业务逻辑
                    Regex("""@(\w+)(?:\([^)]*\))?""")
                        .findAll(content)
                        .filter { it.groupValues[1].first().isUpperCase() }
                        .forEach { ann ->
                            results.add(AnnotationLogic(
                                "@${ann.groupValues[1]}",
                                file.toFile().nameWithoutExtension,
                                "自定义注解"
                            ))
                        }
                } catch (e: Exception) { }
            }
        return results.distinctBy { "${it.annotation}-${it.target}" }.take(20)
    }

    private fun findDynamicCode(): List<DynamicCode> {
        val results = mutableListOf<DynamicCode>()
        Files.walk(rootPath)
            .filter { it.toFile().isFile }
            .filter { it.fileName.toString().endsWith(".java") }
            .forEach { file ->
                try {
                    val content = file.toFile().readText()
                    when {
                        content.contains("Class.forName") -> 
                            results.add(DynamicCode("Class.forName", file.toString(), "动态类加载"))
                        content.contains("ScriptEngine") -> 
                            results.add(DynamicCode("ScriptEngine", file.toString(), "脚本引擎"))
                        content.contains("MethodHandle") -> 
                            results.add(DynamicCode("MethodHandle", file.toString(), "动态方法调用"))
                        content.contains("Proxy.newProxyInstance") -> 
                            results.add(DynamicCode("Proxy", file.toString(), "动态代理"))
                    }
                } catch (e: Exception) { }
            }
        return results
    }

    private fun findDbBusinessRules(): List<DbBusinessRule> {
        val results = mutableListOf<DbBusinessRule>()
        Files.walk(rootPath)
            .filter { it.toFile().isFile }
            .filter { it.fileName.toString().endsWith(".sql") }
            .forEach { file ->
                try {
                    val content = file.toFile().readText()
                    if (content.contains("CREATE PROCEDURE") || content.contains("CREATE TRIGGER")) {
                        results.add(DbBusinessRule("stored_procedure", file.toFile().name, content.take(100)))
                    }
                } catch (e: Exception) { }
            }
        return results
    }

    private fun findConfigDrivenLogic(): List<ConfigDrivenLogic> {
        return emptyList() // TODO
    }

    private fun detectAntiPatterns(): List<AntiPattern> {
        val results = mutableListOf<AntiPattern>()
        Files.walk(rootPath)
            .filter { it.toFile().isFile }
            .filter { it.fileName.toString().endsWith(".java") }
            .forEach { file ->
                try {
                    val content = file.toFile().readText()
                    val lines = content.lines().size
                    // God Class
                    if (lines > 2000) {
                        results.add(AntiPattern("god_class", file.toString(), "类过大(${lines}行)"))
                    }
                    // 循环依赖
                    // 硬编码
                    if (content.contains("TODO") || content.contains("FIXME")) {
                        results.add(AntiPattern("technical_debt", file.toString(), "待办标记"))
                    }
                } catch (e: Exception) { }
            }
        return results.take(10)
    }
}

data class L4Result(
    val xmlBusinessLogic: List<XmlBusinessLogic>,
    val annotationsWithLogic: List<AnnotationLogic>,
    val dynamicCodes: List<DynamicCode>,
    val databaseBusinessRules: List<DbBusinessRule>,
    val configDrivenLogic: List<ConfigDrivenLogic>,
    val antiPatterns: List<AntiPattern>
)
data class XmlBusinessLogic(val file: String, val location: String, val description: String)
data class AnnotationLogic(val annotation: String, val target: String, val logic: String)
data class DynamicCode(val type: String, val location: String, val description: String)
data class DbBusinessRule(val type: String, val name: String, val content: String)
data class ConfigDrivenLogic(val config: String, val affectedCode: String)
data class AntiPattern(val type: String, val location: String, val description: String)
