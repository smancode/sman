package com.smancode.sman.analysis.util

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Markdown 解析器
 *
 * 负责解析 LLM 生成的 Markdown 文件，提取结构化内容
 */
object MdParser {
    private val logger = LoggerFactory.getLogger(MdParser::class.java)

    /**
     * Markdown 文档结构
     *
     * @property title 文档标题
     * @property sections 文档章节列表
     * @property metadata 元数据
     * @property rawContent 原始内容
     */
    data class ParsedDocument(
        val title: String,
        val sections: List<Section>,
        val metadata: Metadata,
        val rawContent: String
    ) {
        /**
         * 获取指定章节的内容
         */
        fun getSection(sectionName: String): Section? {
            return sections.firstOrNull { it.name.contains(sectionName, ignoreCase = true) }
        }

        /**
         * 获取指定元数据值
         */
        fun getMetadataValue(key: String): String? {
            return metadata.data[key]
        }
    }

    /**
     * 文档章节
     *
     * @property name 章节名称
     * @property level 章节级别（1-6）
     * @property content 章节内容
     * @property subsections 子章节
     */
    data class Section(
        val name: String,
        val level: Int,
        val content: String,
        val subsections: List<Section> = emptyList()
    )

    /**
     * 文档元数据
     *
     * @property data 元数据键值对
     */
    data class Metadata(
        val data: Map<String, String> = emptyMap()
    ) {
        companion object {
            /**
             * 从 Markdown 元数据块解析
             */
            fun fromMarkdownBlock(content: String): Metadata {
                val data = mutableMapOf<String, String>()

                val metadataRegex = """^-\s*([^\s:]+)\s*:\s*(.+)$""".toRegex(RegexOption.MULTILINE)
                metadataRegex.findAll(content).forEach { match ->
                    val key = match.groupValues[1].trim()
                    val value = match.groupValues[2].trim()
                    data[key] = value
                }

                return Metadata(data)
            }
        }

        /**
         * 获取分析时间
         */
        fun getAnalysisTime(): Long? {
            return data["分析时间"]?.toLongOrNull()
        }

        /**
         * 获取项目路径
         */
        fun getProjectPath(): String? {
            return data["项目路径"]
        }
    }

    /**
     * 解析 Markdown 文件
     *
     * @param filePath 文件路径
     * @return ParsedDocument 对象
     */
    fun parseFile(filePath: Path): ParsedDocument {
        return try {
            val content = Files.readString(filePath)
            parseContent(content)
        } catch (e: Exception) {
            logger.error("解析 Markdown 文件失败: {}", filePath, e)
            // 返回空文档
            ParsedDocument(
                title = "",
                sections = emptyList(),
                metadata = Metadata(),
                rawContent = ""
            )
        }
    }

    /**
     * 解析 Markdown 内容
     *
     * @param content Markdown 文本内容
     * @return ParsedDocument 对象
     */
    fun parseContent(content: String): ParsedDocument {
        val lines = content.lines()
        val sections = mutableListOf<Section>()
        val metadataLines = mutableListOf<String>()
        var inMetadata = false
        var currentSection: Section? = null
        var documentTitle = ""

        for (line in lines) {
            when {
                // 检测元数据块开始
                line.trim() == "---" -> {
                    inMetadata = !inMetadata
                    if (!inMetadata) {
                        // 元数据块结束，解析元数据
                        // 暂存元数据行，稍后解析
                    }
                }

                // 收集元数据
                inMetadata -> {
                    metadataLines.add(line)
                }

                // 检测标题
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    val title = line.drop(level).trim()

                    if (level == 1 && documentTitle.isEmpty()) {
                        // 一级标题作为文档标题
                        documentTitle = title
                    } else {
                        // 其他标题作为章节
                        val section = Section(
                            name = title,
                            level = level,
                            content = ""
                        )

                        // 处理层级关系
                        if (currentSection == null || level <= currentSection.level) {
                            sections.add(section)
                        } else {
                            // 作为子章节（简化处理，实际可能需要更复杂的逻辑）
                            sections.add(section)
                        }
                        currentSection = section
                    }
                }

                // 章节内容
                else -> {
                    currentSection?.let {
                        // 追加内容（简化处理）
                    }
                }
            }
        }

        val metadata = if (metadataLines.isNotEmpty()) {
            Metadata.fromMarkdownBlock(metadataLines.joinToString("\n"))
        } else {
            // 从文档末尾提取元数据
            extractMetadataFromDocument(content)
        }

        return ParsedDocument(
            title = documentTitle,
            sections = sections,
            metadata = metadata,
            rawContent = content
        )
    }

    /**
     * 从文档末尾提取元数据
     */
    private fun extractMetadataFromDocument(content: String): Metadata {
        val data = mutableMapOf<String, String>()

        // 查找 "## 元数据" 或 "## Metadata" 章节
        val metadataSectionRegex = """##\s+(元数据|Metadata)\s*\n((?:[^\n]+\n)*)""".toRegex(RegexOption.IGNORE_CASE)
        val match = metadataSectionRegex.find(content)

        if (match != null) {
            val metadataContent = match.groupValues[2]
            // 解析键值对
            val keyValueRegex = """-\s*([^:]+)\s*:\s*(.+)""".toRegex()
            keyValueRegex.findAll(metadataContent).forEach { kvMatch ->
                val key = kvMatch.groupValues[1].trim()
                val value = kvMatch.groupValues[2].trim()
                data[key] = value
            }
        }

        return Metadata(data)
    }

    /**
     * 提取 Markdown 中的表格
     *
     * @param content Markdown 内容
     * @return 表格列表
     */
    fun extractTables(content: String): List<Table> {
        val tables = mutableListOf<Table>()
        val lines = content.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // 检测表格（包含 | 的行）
            if (line.contains("|") && line.trim().startsWith("|")) {
                val tableRows = mutableListOf<List<String>>()
                val headers = parseTableRow(line)

                // 跳过分隔行
                i++
                if (i < lines.size && lines[i].contains("|") && lines[i].matches(Regex("""\|[\s\-:]+\|[\s\-:]+\|"""))) {
                    i++
                }

                // 读取表格行
                while (i < lines.size && lines[i].contains("|") && lines[i].trim().startsWith("|")) {
                    tableRows.add(parseTableRow(lines[i]))
                    i++
                }

                tables.add(Table(headers, tableRows))
                continue
            }

            i++
        }

        return tables
    }

    /**
     * 解析表格行
     */
    private fun parseTableRow(line: String): List<String> {
        return line.split("|")
            .drop(1)  // 去掉第一个空元素
            .dropLast(1)  // 去掉最后一个空元素
            .map { it.trim() }
    }

    /**
     * 表格数据结构
     */
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>
    ) {
        /**
         * 获取指定列的所有值
         */
        fun getColumn(columnName: String): List<String> {
            val columnIndex = headers.indexOf(columnName)
            if (columnIndex < 0) return emptyList()

            return rows.mapNotNull { it.getOrNull(columnIndex) }
        }
    }

    /**
     * 提取代码块
     *
     * @param content Markdown 内容
     * @return 代码块列表
     */
    fun extractCodeBlocks(content: String): List<CodeBlock> {
        val codeBlocks = mutableListOf<CodeBlock>()
        val regex = """```(\w*)\n([\s\S]+?)\n```""".toRegex()

        regex.findAll(content).forEach { match ->
            val language = match.groupValues[1]
            val code = match.groupValues[2]
            codeBlocks.add(CodeBlock(language, code))
        }

        return codeBlocks
    }

    /**
     * 代码块
     */
    data class CodeBlock(
        val language: String,
        val code: String
    )
}
