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

            logger.info("========== CommonClassScanner 开始 ==========")
            logger.info("扫描 {} 个源文件检测公共类 (Kotlin: {}, Java: {})",
                allFiles.size, kotlinFiles.size, javaFiles.size)

            var processedCount = 0
            var matchedCount = 0

            allFiles.forEach { file ->
                try {
                    processedCount++
                    val classInfo = parseCommonClass(file)
                    if (classInfo != null) {
                        logger.info("[$processedCount/${allFiles.size}] 解析文件: $file -> className=${classInfo.className}, packageName=${classInfo.packageName}")
                        if (isCommonClass(classInfo)) {
                            commonClasses.add(classInfo)
                            matchedCount++
                            logger.info("  -> ✓ 添加到公共类列表 (当前总数: $matchedCount)")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("解析文件失败: $file, error: ${e.message}")
                }
            }

            logger.info("========== CommonClassScanner 完成 ==========")
            logger.info("处理文件数: $processedCount, 检测到公共类: $matchedCount")
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
            // Java: 简化版 - 直接匹配 class/interface/enum
            Regex("(?:class|interface|enum)\\s+(\\w+)")
        } else {
            // Kotlin: class X, interface Y, object Z
            Regex("(?:class|object|interface|data\\s+class)\\s+(\\w+)")
        }
        val classMatch = classPattern.find(content)

        if (classMatch == null) {
            logger.warn("未找到类声明: file=$file, isJava=$isJava")
            return null
        }

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

        logger.info("检查公共类: className={}, packageName={}", className, packageName)

        // 工具类后缀（完整匹配，避免部分匹配）
        val utilSuffixes = listOf("util", "utils", "helper", "helpers", "tool", "tools")
        if (utilSuffixes.any { suffix -> className.endsWith(suffix) || className.endsWith("${suffix}class") || className.endsWith("${suffix}s") }) {
            logger.info("  -> ✓ 匹配工具类后缀: {}", className)
            return true
        }

        // 工具类包名（直接包含）
        val utilPackages = listOf(
            ".util.", ".utils.", ".helper.", ".helpers.", ".tool.", ".tools.",
            ".common.", ".shared."
        )
        if (utilPackages.any { packageName.contains(it) }) {
            logger.info("  -> ✓ 匹配工具类包名: {}", packageName)
            return true
        }

        // core.util 等包名
        if (packageName.contains(".util") || packageName.contains(".helper")) {
            logger.info("  -> ✓ 匹配 util/helper 包: {}", packageName)
            return true
        }

        // 静态方法多
        if (classInfo.methods.count { it.isStatic } > 3) {
            logger.info("  -> ✓ 静态方法多: {}", classInfo.methods.count { it.isStatic })
            return true
        }

        logger.info("  -> ✗ 不匹配工具类条件")
        return false
    }

    /**
     * 解析方法
     */
    private fun parseMethods(content: String, isJava: Boolean): List<MethodInfo> {
        val methods = mutableListOf<MethodInfo>()

        if (isJava) {
            // Java: 匹配方法声明（更简单的模式）
            // 1. 先找到所有 public/private/protected/... 开头的行为
            // 2. 然后提取方法名和参数

            // 简化版：匹配 (修饰符) 返回类型 方法名(参数)
            val methodPattern = Regex(
                "(?:public|private|protected|static|final|synchronized|native|abstract)\\s+" +
                "(?:[\\w<>\\[\\],\\s]+)\\s+" +  // 返回类型（可能包含泛型）
                "(\\w+)\\s*" +  // 方法名 - 捕获组1
                "\\(([^)]*)\\)"  // 参数 - 捕获组2
            )

            methodPattern.findAll(content).forEach { match ->
                val name = match.groupValues[1]  // 方法名
                val parametersStr = match.groupValues[2]  // 参数

                // 简化返回类型检测
                val returnType = "void"  // 暂时简化

                // 检查是否静态方法（通过检查原始匹配内容）
                val fullMatch = match.value
                val isStatic = fullMatch.contains("static")

                methods.add(MethodInfo(
                    name = name,
                    returnType = returnType,
                    parameters = parseParameters(parametersStr),
                    isStatic = isStatic
                ))
            }
        } else {
            // Kotlin: fun methodName(params): returnType
            val funPattern = Regex("fun\\s+(\\w+)\\s*\\(([^)]*)\\)(?:\\s*:\\s*(\\w+))?")

            funPattern.findAll(content).forEach { match ->
                val name = match.groupValues[1]
                val parametersStr = match.groupValues[2]
                val returnType = match.groupValues[3].ifEmpty { "Unit" }

                methods.add(MethodInfo(
                    name = name,
                    returnType = returnType,
                    parameters = parseParameters(parametersStr),
                    isStatic = false
                ))
            }
        }

        return methods
    }

    /**
     * 解析字段
     */
    private fun parseFields(content: String, isJava: Boolean): List<FieldInfo> {
        val fields = mutableListOf<FieldInfo>()

        if (isJava) {
            // Java: (修饰符) 类型 字段名;
            // 捕获组1: 类型, 捕获组2: 名称
            val fieldPattern = Regex(
                "(?:private|public|protected|static|final|transient|volatile)\\s+" +
                "([\\w<>\\[\\]]+)\\s+" +  // 类型 - 捕获组1
                "(\\w+)\\s*;"  // 名称 - 捕获组2
            )

            fieldPattern.findAll(content).forEach { match ->
                val name = match.groupValues[2]
                val type = match.groupValues[1].trim()
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
