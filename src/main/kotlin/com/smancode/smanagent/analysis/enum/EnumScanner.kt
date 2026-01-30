package com.smancode.smanagent.analysis.enum

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

        val srcMain = projectPath.resolve("src/main/kotlin")
        if (!srcMain.toFile().exists()) {
            return enums
        }

        try {
            java.nio.file.Files.walk(srcMain)
                .filter { it.toFile().isFile }
                .filter { it.toString().endsWith(".kt") }
                .forEach { file ->
                    try {
                        val enumInfo = parseEnumFile(file)
                        if (enumInfo != null) {
                            enums.add(enumInfo)
                        }
                    } catch (e: Exception) {
                        logger.debug("Failed to parse enum file: $file")
                    }
                }
        } catch (e: Exception) {
            logger.error("Failed to scan enums", e)
        }

        return enums
    }

    /**
     * 解析枚举文件
     */
    private fun parseEnumFile(file: Path): EnumInfo? {
        val content = file.readText()

        // 检查是否包含枚举
        if (!content.contains("enum class")) {
            return null
        }

        // 提取包名
        val packagePattern = Regex("package\\s+([\\w.]+)")
        val packageMatch = packagePattern.find(content)
        val packageName = packageMatch?.groupValues?.get(1) ?: ""

        // 提取枚举名
        val enumPattern = Regex("enum\\s+class\\s+(\\w+)")
        val enumMatch = enumPattern.find(content) ?: return null
        val enumName = enumMatch.groupValues[1]
        val qualifiedName = if (packageName.isNotEmpty()) {
            "$packageName.$enumName"
        } else {
            enumName
        }

        // 提取枚举常量
        val constants = extractEnumConstants(content)

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
     * 提取枚举常量
     */
    private fun extractEnumConstants(content: String): List<EnumConstant> {
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
