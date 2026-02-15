package com.smancode.sman.architect.model

import com.smancode.sman.analysis.model.AnalysisType
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * MD 文件元信息
 *
 * 存储在 MD 文件头部注释中的元数据
 *
 * @property analysisType 分析类型
 * @property lastModified 最后修改时间
 * @property completeness 完成度
 * @property todos 未完成 TODO
 * @property iterationCount 迭代次数
 * @property version 版本号
 */
data class MdMetadata(
    val analysisType: AnalysisType,
    val lastModified: Instant,
    val completeness: Double = 0.0,
    val todos: List<TodoItem> = emptyList(),
    val iterationCount: Int = 0,
    val version: Int = 1
) {
    /**
     * 序列化为注释格式
     */
    fun toComment(): String {
        val todosStr = if (todos.isNotEmpty()) {
            todos.joinToString("\n") { "  - \"${it}\"" }
        } else {
            "  []"
        }

        return """
<!-- META
lastModified: ${DateTimeFormatter.ISO_INSTANT.format(lastModified)}
analysisType: ${analysisType.key}
completeness: $completeness
todos:
$todosStr
iterationCount: $iterationCount
version: $version
-->
        """.trimIndent()
    }

    /**
     * 是否需要继续分析
     */
    val needsMoreAnalysis: Boolean
        get() = completeness < COMPLETENESS_THRESHOLD

    companion object {
        private val logger = LoggerFactory.getLogger(MdMetadata::class.java)
        private const val COMPLETENESS_THRESHOLD = 0.8

        // 元信息区正则
        private val META_PATTERN = Regex(
            """<!--\s*META\s*([\s\S]*?)\s*-->""",
            RegexOption.MULTILINE
        )

        // 字段解析正则
        private val FIELD_PATTERN = Regex(
            """^(\w+):\s*(.+)$""",
            RegexOption.MULTILINE
        )

        /**
         * 从 MD 内容解析元信息
         */
        fun fromContent(content: String): MdMetadata? {
            val metaMatch = META_PATTERN.find(content) ?: return null
            val metaContent = metaMatch.groupValues[1]

            // 解析字段
            val fields = mutableMapOf<String, String>()
            val currentTodoList = mutableListOf<String>()
            var inTodos = false

            metaContent.lines().forEach { line ->
                val trimmedLine = line.trim()
                when {
                    trimmedLine.startsWith("todos:") -> inTodos = true
                    inTodos && trimmedLine.startsWith("-") -> {
                        val todoContent = trimmedLine
                            .removePrefix("-")
                            .trim()
                            .removeSurrounding("\"")
                        if (todoContent.isNotEmpty()) {
                            currentTodoList.add(todoContent)
                        }
                    }
                    else -> {
                        val fieldMatch = FIELD_PATTERN.find(trimmedLine)
                        if (fieldMatch != null) {
                            fields[fieldMatch.groupValues[1]] = fieldMatch.groupValues[2].trim()
                            if (fieldMatch.groupValues[1] != "todos") {
                                inTodos = false
                            }
                        }
                    }
                }
            }

            // 构建 MdMetadata
            val analysisTypeKey = fields["analysisType"] ?: return null
            val analysisType = AnalysisType.fromKey(analysisTypeKey) ?: return null

            val lastModified = try {
                Instant.parse(fields["lastModified"] ?: return null)
            } catch (e: Exception) {
                logger.warn("解析时间戳失败: ${fields["lastModified"]}")
                return null
            }

            val completeness = fields["completeness"]?.toDoubleOrNull() ?: 0.0
            val iterationCount = fields["iterationCount"]?.toIntOrNull() ?: 0
            val version = fields["version"]?.toIntOrNull() ?: 1

            // 解析 TODOs
            val todos = currentTodoList.mapNotNull { todoStr ->
                parseTodoItem(todoStr)
            }

            return MdMetadata(
                analysisType = analysisType,
                lastModified = lastModified,
                completeness = completeness,
                todos = todos,
                iterationCount = iterationCount,
                version = version
            )
        }

        /**
         * 解析 TODO 项
         */
        private fun parseTodoItem(todoStr: String): TodoItem? {
            if (todoStr.isBlank()) return null

            // 格式: [HIGH] 内容 或 [MEDIUM] 内容 或 [LOW] 内容
            val priorityMatch = Regex("""^\[(HIGH|MEDIUM|LOW)\]\s*(.+)$""").find(todoStr)

            return if (priorityMatch != null) {
                TodoItem(
                    priority = TodoPriority.fromString(priorityMatch.groupValues[1]),
                    content = priorityMatch.groupValues[2].trim()
                )
            } else {
                // 默认中等优先级
                TodoItem(
                    priority = TodoPriority.MEDIUM,
                    content = todoStr.trim()
                )
            }
        }

        /**
         * 创建默认元信息
         */
        fun createDefault(analysisType: AnalysisType): MdMetadata {
            return MdMetadata(
                analysisType = analysisType,
                lastModified = Instant.now(),
                completeness = 0.0,
                todos = emptyList(),
                iterationCount = 0,
                version = 1
            )
        }
    }
}
