package com.smancode.smanagent.analysis.database

import com.smancode.smanagent.analysis.database.model.DbEntity
import com.smancode.smanagent.analysis.database.model.DbField
import com.smancode.smanagent.analysis.structure.ProjectSourceFinder
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.ExperimentalPathApi

/**
 * DbEntity 检测器（简化版，不依赖IntelliJ PSI）
 *
 * 从 Java/Kotlin 源码中提取数据库实体信息
 */
class DbEntityDetector {

    private val logger = LoggerFactory.getLogger(DbEntityDetector::class.java)

    /**
     * 检测所有 DbEntity
     *
     * @param projectPath 项目路径
     * @return DbEntity 列表
     */
    fun detect(projectPath: Path): List<DbEntity> {
        val result = mutableListOf<DbEntity>()

        try {
            val kotlinFiles = ProjectSourceFinder.findAllKotlinFiles(projectPath)
            val javaFiles = ProjectSourceFinder.findAllJavaFiles(projectPath)
            val allFiles = kotlinFiles + javaFiles

            logger.info("扫描 {} 个源文件检测 DbEntity (Kotlin: {}, Java: {})",
                allFiles.size, kotlinFiles.size, javaFiles.size)

            allFiles.forEach { file ->
                try {
                    buildDbEntityFromFile(file)?.let { result.add(it) }
                } catch (e: Exception) {
                    logger.debug("解析文件失败: $file", e)
                }
            }

        } catch (e: Exception) {
            logger.error("DbEntity 检测失败", e)
        }

        return result.also { logger.info("检测到 {} 个 DbEntity", it.size) }
    }

    /**
     * 从文件构建 DbEntity
     *
     * @param file 文件路径
     * @return DbEntity，如果文件不包含实体类返回 null
     */
    fun buildDbEntityFromFile(file: Path): DbEntity? {
        val content = file.readText()
        val fileName = file.toString()
        val isJava = fileName.endsWith(".java")

        // 检查是否为实体类
        if (!isEntityClass(content, isJava)) {
            return null
        }

        // 提取包名（支持 Java 和 Kotlin）
        val packagePattern = if (isJava) {
            Regex("package\\s+([\\w.]+);")
        } else {
            Regex("package\\s+([\\w.]+)")
        }
        val packageMatch = packagePattern.find(content)
        val packageName = packageMatch?.groupValues?.get(1) ?: ""

        // 提取类名（支持 Java 和 Kotlin）
        val classPattern = if (isJava) {
            // Java: public class Xxx, public interface Xxx, public enum Xxx
            Regex("(?:public\\s+)?(?:abstract\\s+)?(?:class|interface|enum)\\s+(\\w+)")
        } else {
            // Kotlin: class Xxx, object Xxx, interface Xxx
            Regex("(?:class|object|interface)\\s+(\\w+)")
        }
        val classMatch = classPattern.find(content) ?: return null
        val className = classMatch.groupValues[1]
        val qualifiedName = if (packageName.isNotEmpty()) {
            "$packageName.$className"
        } else {
            className
        }

        // 推断表名
        val tableName = inferTableName(className, content)
        val hasTableAnnotation = hasTableAnnotation(content)

        // 提取字段信息
        val fields = extractFields(content)
        val primaryKey = fields.find { it.isPrimaryKey }?.columnName

        // 计算阶段1置信度
        val stage1Confidence = calculateStage1Confidence(
            fields.size,
            0,
            primaryKey != null,
            hasTableAnnotation
        )

        return DbEntity(
            className = className,
            qualifiedName = qualifiedName,
            packageName = packageName,
            tableName = tableName,
            hasTableAnnotation = hasTableAnnotation,
            fields = fields,
            primaryKey = primaryKey,
            relations = emptyList(),
            stage1Confidence = stage1Confidence
        )
    }

    /**
     * 判断是否为实体类（支持 Java 和 Kotlin）
     */
    private fun isEntityClass(content: String, isJava: Boolean): Boolean {
        // 检查 @Entity 注解
        val hasEntityAnnotation = content.contains("@Entity") ||
                content.contains("javax.persistence.Entity") ||
                content.contains("jakarta.persistence.Entity")

        // 检查是否在 entity 包中
        val hasEntityPackage = content.contains(".entity.") ||
                content.contains(".model.entity.") ||
                content.contains("/entity/") ||
                content.contains("/model/entity/")

        // 提取类名
        val classPattern = if (isJava) {
            Regex("(?:public\\s+)?(?:abstract\\s+)?(?:class|interface|enum)\\s+(\\w+)")
        } else {
            Regex("(?:class|object|interface)\\s+(\\w+)")
        }
        val classMatch = classPattern.find(content)
        val classNameEndsWithEntity = classMatch?.groupValues?.get(1)?.endsWith("Entity") == true

        return hasEntityAnnotation || classNameEndsWithEntity || hasEntityPackage
    }

    /**
     * 推断表名
     */
    fun inferTableName(className: String, content: String): String {
        // 优先从 @Table 注解提取
        val tablePattern = Regex("@Table\\s*\\(\\s*name\\s*=\\s*[\"']([^\"']+)[\"']")
        val tableMatch = tablePattern.find(content)
        tableMatch?.let { return it.groupValues[1] }

        // 降级：从类名推断
        val simpleName = if (className.endsWith("Entity")) {
            className.removeSuffix("Entity")
        } else {
            className
        }

        // 驼峰转下划线
        val tableName = simpleName.replace(Regex("([a-z])([A-Z])"), "$1_$1").lowercase()

        return "t_$tableName"
    }

    /**
     * 检查是否有 @Table 注解
     */
    private fun hasTableAnnotation(content: String): Boolean {
        return content.contains("@Table") ||
                content.contains("javax.persistence.Table") ||
                content.contains("jakarta.persistence.Table")
    }

    /**
     * 提取字段信息（支持 Java 和 Kotlin）
     */
    fun extractFields(content: String): List<DbField> {
        val fields = mutableListOf<DbField>()
        val isJava = content.contains(";") && !content.contains("val ") && !content.contains("var ")

        if (isJava) {
            return extractJavaFields(content)
        } else {
            return extractKotlinFields(content)
        }
    }

    /**
     * 提取 Kotlin 字段
     */
    private fun extractKotlinFields(content: String): List<DbField> {
        val fields = mutableListOf<DbField>()

        // 匹配 val/var 字段
        val fieldPattern = Regex(
            "(?:val|var)\\s+(\\w+)\\s*(?::\\s*(\\w+))?" +
            "(?:\\s*=\\s*[^\\n]+)?" +
            "(?:\\s*([^\\n]*))?"
        )

        fieldPattern.findAll(content).forEach { match ->
            val fieldName = match.groupValues[1]
            val fieldType = match.groupValues[2].ifEmpty { "String" }
            val restOfLine = match.groupValues[3]

            // 提取列名
            val columnPattern = Regex("@Column\\s*\\(\\s*name\\s*=\\s*[\"']([^\"']+)[\"']")
            val columnMatch = columnPattern.find(restOfLine)
            val columnName = if (columnMatch != null && columnMatch.groupValues.isNotEmpty()) {
                columnMatch.groupValues[1]
            } else {
                fieldName.replace(Regex("([a-z])([A-Z])"), "$1_$1").lowercase()
            }

            // 检查是否主键
            val isPrimaryKey = restOfLine.contains("@Id") ||
                    restOfLine.contains("javax.persistence.Id") ||
                    restOfLine.contains("jakarta.persistence.Id")

            // 检查是否有 @Column 注解
            val hasColumnAnnotation = restOfLine.contains("@Column") ||
                    restOfLine.contains("javax.persistence.Column") ||
                    restOfLine.contains("jakarta.persistence.Column")

            fields.add(DbField(
                fieldName = fieldName,
                columnName = columnName,
                fieldType = fieldType,
                columnType = null,
                nullable = true,
                isPrimaryKey = isPrimaryKey,
                hasColumnAnnotation = hasColumnAnnotation
            ))
        }

        return fields
    }

    /**
     * 提取 Java 字段
     */
    private fun extractJavaFields(content: String): List<DbField> {
        val fields = mutableListOf<DbField>()

        // 匹配 Java 字段：private/public/protected Type name;
        val fieldPattern = Regex(
            "(?:private|public|protected)\\s+" +
            "(?:static\\s+)?" +
            "(?:final\\s+)?" +
            "(?:@[^\\s]+\\s+)+" +  // 注解
            "([\\w<>,\\s]+?)\\s+" +  // 类型（支持泛型）
            "(\\w+)" +               // 字段名
            "\\s*;"                  // 分号
        )

        fieldPattern.findAll(content).forEach { match ->
            val fieldType = match.groupValues[1].trim()
            val fieldName = match.groupValues[2]

            // 跳过一些非字段的情况
            if (fieldName in listOf("class", "interface", "enum", "if", "for", "while")) return@forEach

            // 简单的列名推断
            val columnName = fieldName.replace(Regex("([a-z])([A-Z])"), "$1_$1").lowercase()

            fields.add(DbField(
                fieldName = fieldName,
                columnName = columnName,
                fieldType = fieldType,
                columnType = null,
                nullable = true,
                isPrimaryKey = fieldName.equals("id", ignoreCase = true) ||
                              fieldName.endsWith("Id") ||
                              fieldName.endsWith("ID"),
                hasColumnAnnotation = false
            ))
        }

        return fields
    }

    /**
     * 计算阶段1置信度
     */
    fun calculateStage1Confidence(
        fieldCount: Int,
        relationCount: Int,
        hasPrimaryKey: Boolean,
        hasTableAnnotation: Boolean
    ): Double {
        var confidence = 0.3  // 基础分

        if (fieldCount > 10) confidence += 0.2
        if (relationCount > 2) confidence += 0.2
        if (hasPrimaryKey) confidence += 0.1
        if (hasTableAnnotation) confidence += 0.2

        return confidence.coerceAtMost(1.0)
    }
}
