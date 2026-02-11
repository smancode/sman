package com.smancode.sman.analysis.xml

import com.smancode.sman.analysis.structure.ProjectSourceFinder
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * XML 代码扫描器
 *
 * 扫描 MyBatis Mapper XML、配置文件等，使用通用扁平化策略
 */
class XmlCodeScanner {

    private val logger = LoggerFactory.getLogger(XmlCodeScanner::class.java)

    /**
     * 扫描 XML 文件
     *
     * @param projectPath 项目路径
     * @return XML 文件信息列表
     */
    fun scan(projectPath: Path): List<XmlFileInfo> {
        val xmlFiles = mutableListOf<XmlFileInfo>()

        // 使用通用工具查找所有 XML 文件（包括子模块）
        val allXmlFiles = mutableListOf<Path>()

        // 查找所有源代码目录
        val sourceDirs = ProjectSourceFinder.findAllSourceDirectories(projectPath)

        // 收集所有 resources 目录下的 XML 文件
        for (srcDir in sourceDirs) {
            val modulePath = java.nio.file.Path.of(srcDir.modulePath)
            val resourcesDir = modulePath.resolve("src/main/resources")
            if (resourcesDir.toFile().exists()) {
                try {
                    java.nio.file.Files.walk(resourcesDir)
                        .filter { it.toFile().isFile }
                        .filter { it.toString().endsWith(".xml") }
                        .forEach { allXmlFiles.add(it) }
                } catch (e: Exception) {
                    logger.debug("扫描 XML 目录失败: $resourcesDir", e)
                }
            }
        }

        if (allXmlFiles.isEmpty()) {
            return xmlFiles
        }

        logger.info("发现 {} 个 XML 文件", allXmlFiles.size)

        try {
            allXmlFiles.forEach { file ->
                try {
                    val xmlInfo = parseXmlFile(projectPath, file)
                    xmlFiles.add(xmlInfo)
                } catch (e: Exception) {
                    logger.debug("Failed to parse XML file: $file", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to scan XML files", e)
        }

        return xmlFiles
    }

    /**
     * 解析 XML 文件（通用扁平化策略）
     */
    private fun parseXmlFile(projectPath: Path, file: Path): XmlFileInfo {
        val content = file.readText()

        // 提取根节点标签
        val rootTag = extractRootTag(content)

        // 推断 XML 类型
        val xmlType = inferXmlType(file, rootTag)

        // 提取元数据
        val metadata = extractMetadata(content)

        // 提取引用
        val references = extractReferences(content)

        // 提取表名（用于 Mapper XML）
        val tableNames = extractTableNames(content)

        val relativePath = try {
            projectPath.relativize(file).toString()
        } catch (e: Exception) {
            file.fileName.toString()
        }

        return XmlFileInfo(
            fileName = file.fileName.toString(),
            relativePath = relativePath,
            rootTag = rootTag,
            xmlType = xmlType,
            metadata = metadata,
            references = references,
            tableNames = tableNames,
            size = content.length
        )
    }

    /**
     * 提取根节点标签
     */
    private fun extractRootTag(content: String): String {
        val rootPattern = Regex("<([\\w:]+)[\\s>]")
        val match = rootPattern.find(content)
        return match?.groupValues?.get(1) ?: "unknown"
    }

    /**
     * 推断 XML 类型
     */
    private fun inferXmlType(file: Path, rootTag: String): XmlType {
        val fileName = file.fileName.toString()

        return when {
            fileName.contains("mapper") || rootTag.contains("mapper") -> XmlType.MYBATIS_MAPPER
            rootTag == "configuration" -> XmlType.SPRING_CONFIG
            rootTag == "beans" -> XmlType.SPRING_BEANS
            rootTag.contains("application") -> XmlType.SPRING_CONFIG
            fileName.contains("application") -> XmlType.SPRING_CONFIG
            fileName.contains("logback") || fileName.contains("log4j") -> XmlType.LOGGING_CONFIG
            else -> XmlType.UNKNOWN
        }
    }

    /**
     * 提取元数据（ID, NAME, DESC 等属性）
     */
    private fun extractMetadata(content: String): Map<String, String> {
        val metadata = mutableMapOf<String, String>()

        // 提取 ID 属性
        val idPattern = Regex("id\\s*=\\s*[\"']([^\"']+)[\"']")
        idPattern.findAll(content).forEach {
            metadata["id"] = it.groupValues[1]
        }

        // 提取 NAME 属性
        val namePattern = Regex("name\\s*=\\s*[\"']([^\"']+)[\"']")
        namePattern.findAll(content).forEach {
            metadata["name"] = it.groupValues[1]
        }

        // 提取 DESC 属性
        val descPattern = Regex("desc\\s*=\\s*[\"']([^\"']+)[\"']")
        descPattern.findAll(content).forEach {
            metadata["desc"] = it.groupValues[1]
        }

        return metadata
    }

    /**
     * 提取引用（class, script 等节点）
     */
    private fun extractReferences(content: String): List<String> {
        val references = mutableListOf<String>()

        // 提取 class 引用
        val classPattern = Regex("class\\s*=\\s*[\"']([\\w.]+)[\"']")
        classPattern.findAll(content).forEach {
            references.add(it.groupValues[1])
        }

        // 提取 script 引用
        val scriptPattern = Regex("script\\s*=\\s*[\"']([\\w.]+)[\"']")
        scriptPattern.findAll(content).forEach {
            references.add(it.groupValues[1])
        }

        // 提取 resultMap 引用
        val resultMapPattern = Regex("resultMap\\s*=\\s*[\"']([\\w.]+)[\"']")
        resultMapPattern.findAll(content).forEach {
            references.add(it.groupValues[1])
        }

        return references.distinct()
    }

    /**
     * 提取表名（用于 MyBatis Mapper）
     */
    private fun extractTableNames(content: String): List<String> {
        val tableNames = mutableSetOf<String>()

        // 从 SQL 语句中提取表名（忽略大小写）
        val sqlPatterns = listOf(
            Regex("FROM\\s+([\\w_]+)", RegexOption.IGNORE_CASE),
            Regex("JOIN\\s+([\\w_]+)", RegexOption.IGNORE_CASE),
            Regex("UPDATE\\s+([\\w_]+)", RegexOption.IGNORE_CASE),
            Regex("INSERT\\s+INTO\\s+([\\w_]+)", RegexOption.IGNORE_CASE),
            Regex("DELETE\\s+FROM\\s+([\\w_]+)", RegexOption.IGNORE_CASE)
        )

        sqlPatterns.forEach { pattern ->
            pattern.findAll(content).forEach {
                tableNames.add(it.groupValues[1].uppercase())
            }
        }

        return tableNames.toList()
    }
}

/**
 * XML 文件信息
 */
@Serializable
data class XmlFileInfo(
    val fileName: String,
    val relativePath: String,
    val rootTag: String,
    val xmlType: XmlType,
    val metadata: Map<String, String>,
    val references: List<String>,
    val tableNames: List<String>,
    val size: Int
)

/**
 * XML 类型
 */
@Serializable
enum class XmlType {
    MYBATIS_MAPPER,    // MyBatis Mapper 文件
    SPRING_CONFIG,     // Spring 配置文件
    SPRING_BEANS,      // Spring Bean 配置
    LOGGING_CONFIG,    // 日志配置文件
    UNKNOWN            // 未知类型
}
