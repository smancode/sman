package com.smancode.smanagent.analysis.scanner

import com.smancode.smanagent.analysis.model.ClassAstInfo
import com.smancode.smanagent.analysis.model.FieldInfo
import com.smancode.smanagent.analysis.model.MethodInfo
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
     * 扫描单个文件（简化版，仅解析Kotlin文件）
     *
     * @param file 文件路径
     * @return AST 信息，如果文件不包含类返回 null
     */
    fun scanFile(file: Path): ClassAstInfo? {
        try {
            if (!file.toString().endsWith(".kt")) {
                return null
            }

            val content = file.readText()
            return parseKotlinFile(file, content)

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
        val methods = parseMethods(content)

        // 提取字段（简化版，只识别val/var）
        val fields = parseFields(content)

        return ClassAstInfo(
            className = qualifiedName,
            simpleName = className,
            packageName = packageName,
            methods = methods,
            fields = fields
        )
    }

    /**
     * 解析方法（简化版）
     */
    private fun parseMethods(content: String): List<MethodInfo> {
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
     * 解析字段（简化版）
     */
    private fun parseFields(content: String): List<FieldInfo> {
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
}
