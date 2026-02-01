package com.smancode.smanagent.analysis.common

import com.smancode.smanagent.analysis.structure.ProjectSourceFinder
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * 公共类扫描器
 *
 * 扫描工具类、帮助类等公共代码
 */
class CommonClassScanner {

    private val logger = LoggerFactory.getLogger(CommonClassScanner::class.java)

    /**
     * 扫描公共类
     *
     * @param projectPath 项目路径
     * @return 公共类信息列表
     */
    fun scan(projectPath: Path): List<CommonClassInfo> {
        val commonClasses = mutableListOf<CommonClassInfo>()

        try {
            val kotlinFiles = ProjectSourceFinder.findAllKotlinFiles(projectPath)
            val javaFiles = ProjectSourceFinder.findAllJavaFiles(projectPath)
            val allFiles = kotlinFiles + javaFiles

            logger.info("扫描 {} 个源文件检测公共类 (Kotlin: {}, Java: {})",
                allFiles.size, kotlinFiles.size, javaFiles.size)

            allFiles.forEach { file ->
                try {
                    parseCommonClass(file)?.let { if (isCommonClass(it)) commonClasses.add(it) }
                } catch (e: Exception) {
                    logger.debug("解析文件失败: $file", e)
                }
            }
        } catch (e: Exception) {
            logger.error("公共类扫描失败", e)
        }

        return commonClasses.also { logger.info("检测到 {} 个公共类", it.size) }
    }

    /**
     * 解析公共类
     */
    private fun parseCommonClass(file: Path): CommonClassInfo? {
        val content = file.readText()

        // 提取包名
        val packagePattern = Regex("package\\s+([\\w.]+)")
        val packageMatch = packagePattern.find(content)
        val packageName = packageMatch?.groupValues?.get(1) ?: ""

        // 提取类名
        val classPattern = Regex("(?:class|object|interface)\\s+(\\w+)")
        val classMatch = classPattern.find(content) ?: return null
        val className = classMatch.groupValues[1]
        val qualifiedName = if (packageName.isNotEmpty()) {
            "$packageName.$className"
        } else {
            className
        }

        // 提取方法
        val methods = parseMethods(content)

        // 提取字段
        val fields = parseFields(content)

        return CommonClassInfo(
            className = className,
            qualifiedName = qualifiedName,
            packageName = packageName,
            methods = methods,
            fields = fields
        )
    }

    /**
     * 判断是否为公共类
     */
    private fun isCommonClass(classInfo: CommonClassInfo): Boolean {
        val className = classInfo.className.lowercase()
        val packageName = classInfo.packageName.lowercase()

        // 工具类后缀
        val utilSuffixes = listOf("util", "utils", "helper", "helpers", "tool", "tools")
        if (utilSuffixes.any { className.endsWith(it) }) {
            return true
        }

        // 工具类包名
        val utilPackages = listOf(
            "util", "utils", "helper", "helpers", "tool", "tools",
            "common", "shared", "core"
        )
        if (utilPackages.any { packageName.contains(it) }) {
            return true
        }

        // 静态方法多
        if (classInfo.methods.count { it.isStatic } > 3) {
            return true
        }

        return false
    }

    /**
     * 解析方法
     */
    private fun parseMethods(content: String): List<MethodInfo> {
        val methods = mutableListOf<MethodInfo>()

        val funPattern = Regex(
            "(fun\\s+(\\w+)\\s*\\(([^)]*)\\)(?:\\s*:\\s*(\\w+))?"
        )

        funPattern.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val parametersStr = match.groupValues[2]
            val returnType = match.groupValues[3].ifEmpty { "Unit" }

            // 检查是否静态方法
            val isStatic = false // Kotlin 没有 static，使用 companion object

            methods.add(MethodInfo(
                name = name,
                returnType = returnType,
                parameters = parseParameters(parametersStr),
                isStatic = isStatic
            ))
        }

        return methods
    }

    /**
     * 解析字段
     */
    private fun parseFields(content: String): List<FieldInfo> {
        val fields = mutableListOf<FieldInfo>()

        val fieldPattern = Regex(
            "(?:val|var)\\s+(\\w+)\\s*(?::\\s*(\\w+))?"
        )

        fieldPattern.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val type = match.groupValues[2].ifEmpty { "Any" }

            fields.add(FieldInfo(
                name = name,
                type = type
            ))
        }

        return fields
    }

    /**
     * 解析参数
     */
    private fun parseParameters(parametersStr: String): List<String> {
        if (parametersStr.isBlank()) {
            return emptyList()
        }

        return parametersStr.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}

/**
 * 公共类信息
 */
@Serializable
data class CommonClassInfo(
    val className: String,
    val qualifiedName: String,
    val packageName: String,
    val methods: List<MethodInfo>,
    val fields: List<FieldInfo>
)

/**
 * 方法信息
 */
@Serializable
data class MethodInfo(
    val name: String,
    val returnType: String,
    val parameters: List<String>,
    val isStatic: Boolean
)

/**
 * 字段信息
 */
@Serializable
data class FieldInfo(
    val name: String,
    val type: String
)
