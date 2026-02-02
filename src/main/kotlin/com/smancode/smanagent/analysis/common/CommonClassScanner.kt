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
        val isJava = file.toString().endsWith(".java")

        // 提取包名
        val packagePattern = if (isJava) {
            Regex("package\\s+([\\w.]+);")
        } else {
            Regex("package\\s+([\\w.]+)")
        }
        val packageMatch = packagePattern.find(content)
        val packageName = packageMatch?.groupValues?.get(1) ?: ""

        // 提取类名（支持 Java 修饰符）
        val classPattern = if (isJava) {
            // Java: public class X, private interface Y, enum class Z 等
            Regex("(?:public|private|protected|static|final|abstract|sealed)\\s+)?(?:class|interface|enum)\\s+(\\w+)")
        } else {
            // Kotlin: class X, interface Y, object Z
            Regex("(?:class|object|interface|data\\s+class)\\s+(\\w+)")
        }
        val classMatch = classPattern.find(content) ?: return null
        val className = classMatch.groupValues[1]
        val qualifiedName = if (packageName.isNotEmpty()) {
            "$packageName.$className"
        } else {
            className
        }

        // 提取方法
        val methods = parseMethods(content, isJava)

        // 提取字段
        val fields = parseFields(content, isJava)

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

        logger.debug("检查公共类: className={}, packageName={}", className, packageName)

        // 工具类后缀（完整匹配，避免部分匹配）
        val utilSuffixes = listOf("util", "utils", "helper", "helpers", "tool", "tools")
        if (utilSuffixes.any { suffix -> className.endsWith(suffix) || className.endsWith("${suffix}class") || className.endsWith("${suffix}s") }) {
            logger.debug("  -> 匹配工具类后缀: {}", className)
            return true
        }

        // 工具类包名（直接包含）
        val utilPackages = listOf(
            ".util.", ".utils.", ".helper.", ".helpers.", ".tool.", ".tools.",
            ".common.", ".shared."
        )
        if (utilPackages.any { packageName.contains(it) }) {
            logger.debug("  -> 匹配工具类包名: {}", packageName)
            return true
        }

        // core.util 等包名
        if (packageName.contains(".util") || packageName.contains(".helper")) {
            logger.debug("  -> 匹配 util/helper 包: {}", packageName)
            return true
        }

        // 静态方法多
        if (classInfo.methods.count { it.isStatic } > 3) {
            logger.debug("  -> 静态方法多: {}", classInfo.methods.count { it.isStatic })
            return true
        }

        return false
    }

    /**
     * 解析方法
     */
    private fun parseMethods(content: String, isJava: Boolean): List<MethodInfo> {
        val methods = mutableListOf<MethodInfo>()

        val funPattern = if (isJava) {
            // Java: public/private/protected/static/final returnType methodName(params)
            Regex("(?:public|private|protected|static|final|synchronized|native)\\s+)?" +
                  "(?:[\\w<>\\[\\],\\s]+?)\\s+" +
                  "(\\w+)\\s*" +
                  "\\(([^)]*)\\)" +
                  "(?:\\s+throws\\s+[\\w.,\\s]+)?")
        } else {
            // Kotlin: fun methodName(params): returnType
            Regex("(fun\\s+(\\w+)\\s*\\(([^)]*)\\)(?:\\s*:\\s*(\\w+))?")
        }

        funPattern.findAll(content).forEach { match ->
            val name = match.groupValues[2]
            val parametersStr = if (isJava) match.groupValues[3] else match.groupValues[1]
            val returnType = if (isJava) {
                // Java 返回类型在方法名之前
                val beforeMethod = match.groupValues[1].trim()
                if (beforeMethod.contains(" ")) {
                    beforeMethod.substringBeforeLast(" ").trim()
                } else {
                    "void" // 默认返回类型
                }
            } else {
                match.groupValues[3].ifEmpty { "Unit" }
            }

            // 检查是否静态方法
            val isStatic = if (isJava) {
                match.groupValues[1].contains("static")
            } else {
                false // Kotlin 没有 static，使用 companion object
            }

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
    private fun parseFields(content: String, isJava: Boolean): List<FieldInfo> {
        val fields = mutableListOf<FieldInfo>()

        if (isJava) {
            // Java: private/public/protected/static/final Type fieldName;
            val fieldPattern = Regex(
                "(?:private|public|protected|static|final|transient|volatile)\\s+" +
                "(?:[\\w<>\\[\\],]+?)\\s+" +
                "(\\w+)\\s*(?:=.*)?;"
            )

            fieldPattern.findAll(content).forEach { match ->
                val name = match.groupValues[2]
                val type = match.groupValues[1].trim().takeLastWhile { it != ' ' }
                fields.add(FieldInfo(name = name, type = type))
            }
        } else {
            // Kotlin: val/var fieldName: Type
            val fieldPattern = Regex(
                "(?:val|var)\\s+(\\w+)\\s*(?::\\s*(\\w+))?"
            )

            fieldPattern.findAll(content).forEach { match ->
                val name = match.groupValues[1]
                val type = match.groupValues[2].ifEmpty { "Any" }
                fields.add(FieldInfo(name = name, type = type))
            }
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
