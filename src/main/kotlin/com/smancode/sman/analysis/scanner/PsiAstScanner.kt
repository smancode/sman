package com.smancode.sman.analysis.scanner

import com.smancode.sman.analysis.model.ClassAstInfo
import com.smancode.sman.analysis.model.FieldInfo
import com.smancode.sman.analysis.model.MethodInfo
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * PSI AST 扫描器（简化版，不依赖IntelliJ PSI）
 *
 * 注意：这是临时实现，完整的PSI扫描需要在IntelliJ插件环境中运行
 */
class PsiAstScanner {

    private val logger = LoggerFactory.getLogger(PsiAstScanner::class.java)

    /**
     * 扫描单个文件（支持 Kotlin 和 Java）
     *
     * @param file 文件路径
     * @return AST 信息，如果文件不包含类返回 null
     */
    fun scanFile(file: Path): ClassAstInfo? {
        try {
            val content = file.readText()
            val fileName = file.toString()

            return when {
                fileName.endsWith(".kt") -> parseKotlinFile(file, content)
                fileName.endsWith(".java") -> parseJavaFile(file, content)
                else -> null
            }

        } catch (e: Exception) {
            logger.error("Failed to scan file: $file", e)
            return null
        }
    }

    /**
     * 解析 Kotlin 文件（简化版）
     *
     * @param file 文件路径
     * @param content 文件内容
     * @return 类信息
     */
    private fun parseKotlinFile(file: Path, content: String): ClassAstInfo? {
        // 提取包名
        val packagePattern = Regex("package\\s+([\\w.]+)")
        val packageMatch = packagePattern.find(content)
        val packageName = packageMatch?.groupValues?.get(1) ?: ""

        // 提取类名
        val classPattern = Regex("(?:class|object|interface)\\s+(\\w+)")
        val classMatch = classPattern.find(content)
        val className = classMatch?.groupValues?.get(1) ?: return null

        val qualifiedName = if (packageName.isNotEmpty()) {
            "$packageName.$className"
        } else {
            className
        }

        // 提取方法
        val methods = parseKotlinMethods(content)

        // 提取字段（简化版，只识别val/var）
        val fields = parseKotlinFields(content)

        return ClassAstInfo(
            className = qualifiedName,
            simpleName = className,
            packageName = packageName,
            methods = methods,
            fields = fields
        )
    }

    /**
     * 解析 Java 文件（简化版）
     *
     * @param file 文件路径
     * @param content 文件内容
     * @return 类信息
     */
    private fun parseJavaFile(file: Path, content: String): ClassAstInfo? {
        // 提取包名
        val packagePattern = Regex("package\\s+([\\w.]+);")
        val packageMatch = packagePattern.find(content)
        val packageName = packageMatch?.groupValues?.get(1) ?: ""

        // 提取类名（支持 class, interface, enum, @interface）
        val classPattern = Regex("(?:public\\s+)?(?:abstract\\s+)?(?:class|interface|enum|@interface)\\s+(\\w+)")
        val classMatch = classPattern.find(content)
        val className = classMatch?.groupValues?.get(1) ?: return null

        val qualifiedName = if (packageName.isNotEmpty()) {
            "$packageName.$className"
        } else {
            className
        }

        // 提取方法
        val methods = parseJavaMethods(content)

        // 提取字段
        val fields = parseJavaFields(content)

        return ClassAstInfo(
            className = qualifiedName,
            simpleName = className,
            packageName = packageName,
            methods = methods,
            fields = fields
        )
    }

    /**
     * 解析 Kotlin 方法（简化版）
     */
    private fun parseKotlinMethods(content: String): List<MethodInfo> {
        val methods = mutableListOf<MethodInfo>()

        // 匹配 fun 方法签名
        val funPattern = Regex(
            "fun\\s+(\\w+)\\s*\\(([^)]*)\\)(?:\\s*:\\s*(\\w+))?"
        )

        funPattern.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val parametersStr = match.groupValues[2]
            val returnType = match.groupValues[3].ifEmpty { "Unit" }

            // 解析参数
            val parameters = if (parametersStr.isBlank()) {
                emptyList()
            } else {
                parametersStr.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { param ->
                        val parts = param.split(":")
                        if (parts.size >= 2) {
                            "${parts[0].trim()}: ${parts[1].trim()}"
                        } else {
                            param
                        }
                    }
            }

            methods.add(MethodInfo(
                name = name,
                returnType = returnType,
                parameters = parameters,
                annotations = emptyList()
            ))
        }

        return methods
    }

    /**
     * 解析 Java 方法（简化版）
     */
    private fun parseJavaMethods(content: String): List<MethodInfo> {
        val methods = mutableListOf<MethodInfo>()

        // 匹配 Java 方法签名：public/private/protected Type name(params)
        val methodPattern = Regex(
            "(?:public|private|protected|static|final|synchronized|native)\\s+" +
            "(?:\\w+(?:<[^>]+>)?\\s+)?" +  // 返回类型（支持泛型）
            "(\\w+)\\s*" +                   // 方法名
            "\\(([^)]*)\\)"                   // 参数列表
        )

        methodPattern.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val parametersStr = match.groupValues[2]

            // 跳过一些不是方法的情况
            if (name in listOf("if", "for", "while", "switch", "catch")) return@forEach

            // 解析参数
            val parameters = if (parametersStr.isBlank()) {
                emptyList()
            } else {
                parametersStr.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { param ->
                        // Java 参数格式: Type name
                        val parts = param.trim().split("\\s+".toRegex())
                        if (parts.size >= 2) {
                            "${parts[1]}: ${parts[0]}"
                        } else {
                            param
                        }
                    }
            }

            methods.add(MethodInfo(
                name = name,
                returnType = "Unknown",  // 简化，暂不提取
                parameters = parameters,
                annotations = emptyList()
            ))
        }

        return methods
    }

    /**
     * 解析 Kotlin 字段（简化版）
     */
    private fun parseKotlinFields(content: String): List<FieldInfo> {
        val fields = mutableListOf<FieldInfo>()

        // 匹配 val/var 字段
        val fieldPattern = Regex(
            "(?:val|var)\\s+(\\w+)\\s*(?::\\s*(\\w+))?"
        )

        fieldPattern.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val type = match.groupValues[2].ifEmpty { "Any" }

            fields.add(FieldInfo(
                name = name,
                type = type,
                annotations = emptyList()
            ))
        }

        return fields
    }

    /**
     * 解析 Java 字段（简化版）
     */
    private fun parseJavaFields(content: String): List<FieldInfo> {
        val fields = mutableListOf<FieldInfo>()

        // 匹配 Java 字段：private/public/protected Type name
        val fieldPattern = Regex(
            "(?:private|public|protected|static|final|transient|volatile)\\s+" +
            "(\\w+(?:<[^>]+>)?)\\s+" +  // 类型
            "(\\w+)" +                     // 字段名
            "\\s*;"                        // 分号
        )

        fieldPattern.findAll(content).forEach { match ->
            val type = match.groupValues[1]
            val name = match.groupValues[2]

            fields.add(FieldInfo(
                name = name,
                type = type,
                annotations = emptyList()
            ))
        }

        return fields
    }
}
