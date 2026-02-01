package com.smancode.smanagent.analysis.enum

import com.smancode.smanagent.analysis.structure.ProjectSourceFinder
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Enum 扫描器
 *
 * 扫描项目中的枚举类，提取枚举常量和业务含义
 */
class EnumScanner {

    private val logger = LoggerFactory.getLogger(EnumScanner::class.java)

    /**
     * 扫描枚举类
     *
     * @param projectPath 项目路径
     * @return 枚举类列表
     */
    fun scan(projectPath: Path): List<EnumInfo> {
        val enums = mutableListOf<EnumInfo>()

        try {
            val kotlinFiles = ProjectSourceFinder.findAllKotlinFiles(projectPath)
            val javaFiles = ProjectSourceFinder.findAllJavaFiles(projectPath)
            val allFiles = kotlinFiles + javaFiles

            logger.info("扫描 {} 个源文件检测枚举 (Kotlin: {}, Java: {})",
                allFiles.size, kotlinFiles.size, javaFiles.size)

            allFiles.forEach { file ->
                try {
                    parseEnumFile(file)?.let { enums.add(it) }
                } catch (e: Exception) {
                    logger.debug("解析枚举文件失败: $file", e)
                }
            }
        } catch (e: Exception) {
            logger.error("枚举扫描失败", e)
        }

        return enums.also { logger.info("检测到 {} 个枚举", it.size) }
    }

    /**
     * 解析枚举文件（支持 Kotlin 和 Java）
     */
    private fun parseEnumFile(file: Path): EnumInfo? {
        val content = file.readText()
        val fileName = file.toString()

        val isKotlin = fileName.endsWith(".kt")
        val isJava = fileName.endsWith(".java")

        // 检查是否包含枚举
        val hasEnum = if (isKotlin) {
            content.contains("enum class")
        } else if (isJava) {
            content.contains("enum ") && content.contains("{")
        } else {
            false
        }

        if (!hasEnum) return null

        // 提取包名
        val packagePattern = if (isKotlin) {
            Regex("package\\s+([\\w.]+)")
        } else {
            Regex("package\\s+([\\w.]+);")
        }
        val packageMatch = packagePattern.find(content)
        val packageName = packageMatch?.groupValues?.get(1) ?: ""

        // 提取枚举名
        val enumPattern = if (isKotlin) {
            Regex("enum\\s+class\\s+(\\w+)")
        } else {
            Regex("enum\\s+(\\w+)")
        }
        val enumMatch = enumPattern.find(content) ?: return null
        val enumName = enumMatch.groupValues[1]
        val qualifiedName = if (packageName.isNotEmpty()) {
            "$packageName.$enumName"
        } else {
            enumName
        }

        // 提取枚举常量
        val constants = if (isKotlin) {
            extractKotlinEnumConstants(content)
        } else {
            extractJavaEnumConstants(content)
        }

        // 推断业务含义
        val businessMeaning = inferBusinessMeaning(enumName, constants)

        return EnumInfo(
            enumName = enumName,
            qualifiedName = qualifiedName,
            packageName = packageName,
            constants = constants,
            businessMeaning = businessMeaning
        )
    }

    /**
     * 提取 Kotlin 枚举常量
     */
    private fun extractKotlinEnumConstants(content: String): List<EnumConstant> {
        val constants = mutableListOf<EnumConstant>()

        // 匹配枚举常量
        val constantPattern = Regex("(\\w+)\\s*(?:\\(.*?\\))?\\s*(?:\\{|,)")
        val enumStart = content.indexOf("enum class")
        val enumContent = if (enumStart >= 0) {
            content.substring(enumStart)
        } else {
            content
        }

        constantPattern.findAll(enumContent).forEach { match ->
            val constantName = match.groupValues[1]
            // 过滤掉关键字
            if (constantName != "enum" && constantName != "class" &&
                constantName[0].isUpperCase()) {
                constants.add(EnumConstant(
                    name = constantName,
                    description = null
                ))
            }
        }

        return constants
    }

    /**
     * 提取 Java 枚举常量
     */
    private fun extractJavaEnumConstants(content: String): List<EnumConstant> {
        val constants = mutableListOf<EnumConstant>()

        // 找到 enum 开始位置
        val enumStart = content.indexOf("enum")
        if (enumStart < 0) return constants

        // 找到 enum 块的 { 和 }
        val braceStart = content.indexOf('{', enumStart)
        if (braceStart < 0) return constants

        var braceCount = 0
        var braceEnd = -1
        for (i in braceStart until content.length) {
            when (content[i]) {
                '{' -> braceCount++
                '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        braceEnd = i
                        break
                    }
                }
            }
        }

        if (braceEnd < 0) return constants

        val enumContent = content.substring(braceStart + 1, braceEnd)

        // 匹配常量名（支持带参数的常量）
        val constantPattern = Regex("(\\w+)(?:\\s*\\([^)]*\\))?\\s*(?:,|;)")
        constantPattern.findAll(enumContent).forEach { match ->
            val constantName = match.groupValues[1]
            // 过滤掉关键字
            if (constantName.isNotEmpty() && constantName[0].isUpperCase()) {
                constants.add(EnumConstant(
                    name = constantName,
                    description = null
                ))
            }
        }

        return constants
    }

    /**
     * 推断业务含义
     */
    private fun inferBusinessMeaning(enumName: String, constants: List<EnumConstant>): String {
        // 常见的枚举模式
        val commonEnums = mapOf(
            "Status" to "状态",
            "Type" to "类型",
            "State" to "状态",
            "ErrorCode" to "错误码",
            "ResultCode" to "结果码",
            "LogLevel" to "日志级别",
            "OperationType" to "操作类型",
            "PaymentMethod" to "支付方式",
            "TransactionType" to "交易类型",
            "AccountType" to "账户类型",
            "LoanStatus" to "贷款状态",
            "CustomerType" to "客户类型",
            "ContractStatus" to "合同状态"
        )

        // 完全匹配
        commonEnums[enumName]?.let { return it }

        // 后缀匹配
        if (enumName.endsWith("Status") || enumName.endsWith("State")) {
            return "${enumName.removeSuffix("Status").removeSuffix("State")}状态"
        }

        if (enumName.endsWith("Type")) {
            return "${enumName.removeSuffix("Type")}类型"
        }

        if (enumName.endsWith("Code")) {
            return "${enumName.removeSuffix("Code")}编码"
        }

        // 默认返回枚举名
        return enumName
    }
}

/**
 * 枚举信息
 */
@Serializable
data class EnumInfo(
    val enumName: String,
    val qualifiedName: String,
    val packageName: String,
    val constants: List<EnumConstant>,
    val businessMeaning: String
)

/**
 * 枚举常量
 */
@Serializable
data class EnumConstant(
    val name: String,
    val description: String?
)
