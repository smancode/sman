package com.smancode.smanagent.analysis.dto

import com.smancode.smanagent.analysis.structure.ProjectSourceFinder
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * DTO 扫描器
 *
 * 扫描项目中的 DTO（Data Transfer Object）
 * DTO 用于 API 数据传输，通常在 dto 或 model/dto 包下
 */
class DtoScanner {

    private val logger = LoggerFactory.getLogger(DtoScanner::class.java)

    /**
     * 扫描所有 DTO
     *
     * @param projectPath 项目路径
     * @return DTO 信息列表
     */
    fun scan(projectPath: Path): List<DtoInfo> {
        val dtos = mutableListOf<DtoInfo>()

        try {
            val kotlinFiles = ProjectSourceFinder.findAllKotlinFiles(projectPath)
            val javaFiles = ProjectSourceFinder.findAllJavaFiles(projectPath)
            val allFiles = kotlinFiles + javaFiles

            logger.info("扫描 {} 个源文件检测 DTO (Kotlin: {}, Java: {})",
                allFiles.size, kotlinFiles.size, javaFiles.size)

            allFiles.forEach { file ->
                try {
                    parseDtoFile(file)?.let { dtos.add(it) }
                } catch (e: Exception) {
                    logger.debug("解析 DTO 文件失败: $file", e)
                }
            }
        } catch (e: Exception) {
            logger.error("DTO 扫描失败", e)
        }

        return dtos.also { logger.info("检测到 {} 个 DTO", it.size) }
    }

    /**
     * 解析 DTO 文件
     */
    private fun parseDtoFile(file: Path): DtoInfo? {
        val content = file.readText()
        val fileName = file.toString()
        val isJava = fileName.endsWith(".java")

        // 检查是否为 DTO
        if (!isDto(content, fileName)) {
            return null
        }

        // 提取包名
        val packagePattern = if (isJava) {
            Regex("package\\s+([\\w.]+);")
        } else {
            Regex("package\\s+([\\w.]+)")
        }
        val packageMatch = packagePattern.find(content)
        val packageName = packageMatch?.groupValues?.get(1) ?: ""

        // 提取类名
        val classPattern = if (isJava) {
            Regex("(?:public\\s+)?(?:abstract\\s+)?(?:class|interface|record)\\s+(\\w+)")
        } else {
            Regex("(?:class|object|interface|data\\s+class)\\s+(\\w+)")
        }
        val classMatch = classPattern.find(content) ?: return null
        val className = classMatch.groupValues[1]
        val qualifiedName = if (packageName.isNotEmpty()) {
            "$packageName.$className"
        } else {
            className
        }

        // 判断 DTO 类型
        val dtoType = classifyDtoType(className, packageName, content)

        // 提取字段
        val fields = extractFields(content, isJava)

        return DtoInfo(
            className = className,
            qualifiedName = qualifiedName,
            packageName = packageName,
            dtoType = dtoType,
            fieldCount = fields.size,
            fields = fields
        )
    }

    /**
     * 判断是否为 DTO
     */
    private fun isDto(content: String, fileName: String): Boolean {
        // 1. 检查是否在 dto 包中
        val inDtoPackage = fileName.contains("/dto/") ||
                           fileName.contains(".dto.") ||
                           fileName.contains("\\dto\\") ||
                           content.contains(".dto;") ||
                           content.contains(".dto.")

        // 2. 检查类名是否以 DTO、Request、Response、Req、Rsp 结尾
        val classPattern = Regex("(?:class|interface|record|data\\s+class)\\s+(\\w+)")
        val classMatch = classPattern.find(content)
        val className = classMatch?.groupValues?.get(1) ?: ""
        val hasDtoSuffix = className.endsWith("DTO") ||
                           className.endsWith("Dto") ||
                           className.endsWith("Request") ||
                           className.endsWith("Response") ||
                           className.endsWith("Req") ||
                           className.endsWith("Rsp") ||
                           className.endsWith("VO") ||
                           className.endsWith("Vo")

        // 3. 检查是否使用了 Lombok @Data 注解（Java）
        val hasDataAnnotation = content.contains("@Data") ||
                                 content.contains("@Getter") ||
                                 content.contains("@Setter")

        // 4. 检查是否实现了 Serializable
        val isSerializable = content.contains("implements Serializable") ||
                             content.contains("implements java.io.Serializable")

        // 5. 排除 Entity 类
        val isEntity = content.contains("@Entity") ||
                       content.contains("javax.persistence.Entity") ||
                       content.contains("jakarta.persistence.Entity") ||
                       (fileName.contains("/entity/") && !inDtoPackage)

        return (inDtoPackage || hasDtoSuffix || hasDataAnnotation) &&
               !isEntity && className.isNotEmpty()
    }

    /**
     * 分类 DTO 类型
     */
    private fun classifyDtoType(className: String, packageName: String, content: String): DtoType {
        return when {
            className.endsWith("Request") || className.endsWith("Req") -> DtoType.REQUEST
            className.endsWith("Response") || className.endsWith("Rsp") -> DtoType.RESPONSE
            className.endsWith("Query") || className.endsWith("Qry") -> DtoType.QUERY
            className.endsWith("Command") || className.endsWith("Cmd") -> DtoType.COMMAND
            className.endsWith("VO") || className.endsWith("Vo") -> DtoType.VIEW_OBJECT
            className.endsWith("DTO") || className.endsWith("Dto") -> {
                // 根据内容进一步判断
                when {
                    content.contains("request", ignoreCase = true) -> DtoType.REQUEST
                    content.contains("response", ignoreCase = true) -> DtoType.RESPONSE
                    else -> DtoType.GENERAL
                }
            }
            else -> DtoType.GENERAL
        }
    }

    /**
     * 提取字段信息
     */
    private fun extractFields(content: String, isJava: Boolean): List<String> {
        val fields = mutableListOf<String>()

        if (isJava) {
            // Java 字段：private Type name;
            val fieldPattern = Regex(
                "(?:private|public|protected)\\s+" +
                "(?:static\\s+)?" +
                "(?:final\\s+)?" +
                "([\\w<>,\\s]+?)\\s+" +
                "(\\w+)\\s*;"
            )
            fieldPattern.findAll(content).forEach { match ->
                val fieldType = match.groupValues[1].trim()
                val fieldName = match.groupValues[2]
                // 跳过序列化 ID
                if (!fieldName.equals("serialVersionUID", ignoreCase = true)) {
                    fields.add("$fieldName: $fieldType")
                }
            }
        } else {
            // Kotlin 字段：val/var name: Type
            val fieldPattern = Regex(
                "(?:val|var)\\s+(\\w+)\\s*(?::\\s*([\\w<>,?\\s]+))?"
            )
            fieldPattern.findAll(content).forEach { match ->
                val fieldName = match.groupValues[1]
                val fieldType = match.groupValues[2].ifEmpty { "Any" }
                fields.add("$fieldName: $fieldType")
            }
        }

        return fields
    }
}

/**
 * DTO 信息
 */
@Serializable
data class DtoInfo(
    val className: String,
    val qualifiedName: String,
    val packageName: String,
    val dtoType: DtoType,
    val fieldCount: Int,
    val fields: List<String>
)

/**
 * DTO 类型
 */
@Serializable
enum class DtoType {
    REQUEST,         // 请求 DTO
    RESPONSE,        // 响应 DTO
    QUERY,           // 查询 DTO
    COMMAND,         // 命令 DTO
    VIEW_OBJECT,     // 视图对象 (VO)
    GENERAL          // 通用 DTO
}
