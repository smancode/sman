package com.smancode.sman.domain.react

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.model.part.*
import com.smancode.sman.tools.ToolRegistry
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Part 处理器
 *
 * 负责：
 * - Part 创建（TextPart, ToolPart, ReasoningPart, TodoPart 等）
 * - 摘要处理（查找无摘要工具、检查摘要）
 * - 结果格式化
 * - JSON 转换
 */
class PartHandler(
    private val toolRegistry: ToolRegistry
) {
    private val logger = LoggerFactory.getLogger(PartHandler::class.java)
    private val objectMapper = ObjectMapper()

    // ==================== Part 创建 ====================

    /**
     * 解析 Part（从 LLM JSON 响应）
     */
    fun parsePart(partJson: JsonNode, messageId: String, sessionId: String): Part? {
        val type = partJson.path("type").asText()

        if (type == "tool") {
            return createToolPart(partJson, messageId, sessionId)
        }

        // 兼容 LLM 直接用工具名作为 type 的情况
        if (isToolName(type)) {
            logger.info("检测到工具类型 Part: {}, 转换为标准 tool 格式", type)
            return createToolPartFromLegacyFormat(partJson, messageId, sessionId, type)
        }

        return when (type) {
            "text" -> createTextPart(partJson, messageId, sessionId)
            "reasoning" -> createReasoningPart(partJson, messageId, sessionId)
            "subtask" -> createSubtaskPart(partJson, messageId, sessionId)
            "todo" -> createTodoPart(partJson, messageId, sessionId)
            else -> {
                logger.warn("未知的 Part 类型: {}", type)
                null
            }
        }
    }

    fun createTextPart(partJson: JsonNode, messageId: String, sessionId: String): TextPart {
        val partId = UUID.randomUUID().toString()
        val part = TextPart(partId, messageId, sessionId)
        part.text = partJson.path("text").asText()
        part.touch()
        return part
    }

    fun createReasoningPart(partJson: JsonNode, messageId: String, sessionId: String): ReasoningPart {
        val partId = UUID.randomUUID().toString()
        val part = ReasoningPart(partId, messageId, sessionId)
        part.text = partJson.path("text").asText()
        part.touch()
        return part
    }

    fun createToolPart(partJson: JsonNode, messageId: String, sessionId: String): ToolPart {
        val partId = UUID.randomUUID().toString()
        val toolName = partJson.path("toolName").asText()
        val part = ToolPart(partId, messageId, sessionId, toolName)

        // 解析 parameters 对象
        var params = parseJsonNodeToMap(partJson.path("parameters"))

        // Fallback: 如果 parameters 为空，尝试从根级别提取参数
        if (params.isEmpty()) {
            logger.info("检测到扁平格式参数，尝试从根级别提取: toolName={}", toolName)
            val rootParams = mutableMapOf<String, Any>()
            val fields = partJson.fields()
            while (fields.hasNext()) {
                val entry = fields.next()
                if (entry.key !in listOf("type", "summary", "toolName")) {
                    convertJsonNodeToObject(entry.value)?.let { rootParams[entry.key] = it }
                }
            }
            if (rootParams.isNotEmpty()) {
                params = rootParams
                logger.info("从根级别提取参数成功: toolName={}, 参数={}", toolName, params.keys)
            }
        }

        part.parameters = params

        // 提取 LLM 生成的摘要
        val summary = partJson.path("summary").asText(null)
        if (!summary.isNullOrEmpty()) {
            part.summary = summary
            logger.info("提取到 LLM 生成的摘要: toolName={}, summary={}", part.toolName, summary)
        }

        part.touch()
        return part
    }

    /**
     * 从旧格式创建 ToolPart（兼容 LLM 直接使用工具名作为 type 的情况）
     */
    private fun createToolPartFromLegacyFormat(
        partJson: JsonNode,
        messageId: String,
        sessionId: String,
        toolName: String
    ): ToolPart {
        val partId = UUID.randomUUID().toString()
        val part = ToolPart(partId, messageId, sessionId, toolName)

        val params = mutableMapOf<String, Any>()
        val fields = partJson.fields()
        while (fields.hasNext()) {
            val entry = fields.next()
            if (entry.key !in listOf("type", "summary", "toolName")) {
                convertJsonNodeToObject(entry.value)?.let { params[entry.key] = it }
            }
        }

        part.parameters = params

        val summary = partJson.path("summary").asText(null)
        if (!summary.isNullOrEmpty()) {
            part.summary = summary
            logger.info("提取到 LLM 生成的摘要: toolName={}, summary={}", toolName, summary)
        }

        part.touch()
        return part
    }

    private fun createSubtaskPart(partJson: JsonNode, messageId: String, sessionId: String): TextPart {
        val partId = UUID.randomUUID().toString()
        val part = TextPart(partId, messageId, sessionId)
        part.text = partJson.path("text").asText("子任务列表")
        part.touch()
        return part
    }

    private fun createTodoPart(partJson: JsonNode, messageId: String, sessionId: String): TodoPart {
        val partId = UUID.randomUUID().toString()
        val part = TodoPart(partId, messageId, sessionId)

        val itemsJson = partJson.path("items")
        if (itemsJson.isArray) {
            val items = mutableListOf<TodoPart.TodoItem>()
            for (itemJson in itemsJson) {
                val item = TodoPart.TodoItem().apply {
                    id = itemJson.path("id").asText(UUID.randomUUID().toString())
                    content = itemJson.path("content").asText()

                    val statusStr = itemJson.path("status").asText("PENDING")
                    status = try {
                        TodoPart.TodoStatus.valueOf(statusStr.uppercase())
                    } catch (e: IllegalArgumentException) {
                        logger.warn("未知的 TodoStatus: {}, 使用默认值 PENDING", statusStr)
                        TodoPart.TodoStatus.PENDING
                    }
                }
                items.add(item)
            }
            part.items = items
        }

        part.touch()
        logger.info("创建 TodoPart: items={}", part.getTotalCount())
        return part
    }

    // ==================== 摘要处理 ====================

    /**
     * 查找最后一个无摘要的 ToolPart
     */
    fun findLastToolWithoutSummary(parts: List<Part>): ToolPart? {
        logger.info("【查找无摘要工具】开始查找，Part 总数={}", parts.size)

        for (i in parts.size - 1 downTo 0) {
            val part = parts[i]
            if (part is ToolPart) {
                val summary = part.summary
                val hasSummary = !summary.isNullOrEmpty()

                logger.info("【查找无摘要工具】  检查 ToolPart: toolName={}, hasSummary={}",
                    part.toolName, hasSummary)

                if (!hasSummary) {
                    logger.info("【查找无摘要工具】✅ 找到无摘要的工具: toolName={}", part.toolName)
                    return part
                }
            }
        }

        logger.info("【查找无摘要工具】❌ 没有找到无摘要的工具")
        return null
    }

    /**
     * 检查 ToolPart 是否有摘要
     */
    fun hasToolPartSummary(part: Part): Boolean {
        if (part !is ToolPart) return false

        val summary = part.summary
        val hasSummary = !summary.isNullOrEmpty()
        logger.info("【摘要处理】检查 ToolPart: toolName={}, hasSummary={}, summary={}",
            part.toolName, hasSummary,
            if (hasSummary) truncate(summary!!, 50) else "null")
        return hasSummary
    }

    /**
     * 截断字符串到指定长度
     */
    fun truncate(s: String, maxLength: Int): String = s.take(maxLength)

    // ==================== 格式化方法 ====================

    /**
     * 格式化参数简述
     */
    fun formatParamsBrief(params: Map<String, Any>?): String {
        if (params.isNullOrEmpty()) return ""
        val sb = StringBuilder()
        for ((key, value) in params) {
            sb.append(key).append("=").append(value).append(" ")
        }
        return sb.toString().trim()
    }

    // ==================== 工具方法 ====================

    private fun isToolName(type: String): Boolean = toolRegistry.hasTool(type)

    private fun parseJsonNodeToMap(node: JsonNode): Map<String, Any> {
        if (!node.isObject) return emptyMap()

        val result = mutableMapOf<String, Any>()
        val fields = node.fields()
        while (fields.hasNext()) {
            val entry = fields.next()
            convertJsonNodeToObject(entry.value)?.let { result[entry.key] = it }
        }
        return result
    }

    /**
     * 将 JsonNode 转换为 Java 对象
     */
    fun convertJsonNodeToObject(node: JsonNode): Any? {
        return when {
            node.isNull -> null
            node.isBoolean -> node.asBoolean()
            node.isNumber -> node.numberValue()
            node.isTextual -> node.asText()
            node.isArray -> node.map { convertJsonNodeToObject(it) }
            node.isObject -> {
                val map = mutableMapOf<String, Any>()
                val fields = node.fields()
                while (fields.hasNext()) {
                    val entry = fields.next()
                    convertJsonNodeToObject(entry.value)?.let { map[entry.key] = it }
                }
                map
            }
            else -> node.asText()
        }
    }

    /**
     * 判断 JSON 是否为单个 text part
     */
    fun isSingleTextPart(json: JsonNode): Boolean {
        return json.has("type") && json.path("type").asText() == "text"
    }

    /**
     * 判断 JSON 是否为单个 text part（字符串格式）
     */
    fun isSingleTextPart(jsonString: String): Boolean {
        return try {
            val json = objectMapper.readTree(jsonString)
            isSingleTextPart(json)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从混合格式中提取额外的 Parts
     *
     * 处理 LLM 返回混合格式的情况：前导文本 + XML 标签中的 JSON parts
     */
    fun extractAdditionalParts(responseText: String, firstJson: String, messageId: String, sessionId: String): List<Part> {
        val additionalParts = mutableListOf<Part>()

        try {
            val firstJsonEnd = responseText.indexOf(firstJson) + firstJson.length
            val remaining = responseText.substring(firstJsonEnd)

            val partPattern = Regex("<part>\\s*(\\{.*?\\})\\s*</part>", RegexOption.DOT_MATCHES_ALL)
            val matches = partPattern.findAll(remaining)

            for (match in matches) {
                val partJsonStr = match.groupValues[1]
                try {
                    val partNode = objectMapper.readTree(partJsonStr)
                    val part = parsePart(partNode, messageId, sessionId)
                    if (part != null) {
                        additionalParts.add(part)
                        logger.info("提取到额外 part: type={}", partNode.path("type").asText())
                    }
                } catch (e: Exception) {
                    logger.warn("解析额外 part 失败: {}", e.message)
                }
            }
        } catch (e: Exception) {
            logger.warn("提取额外 parts 过程出错: {}", e.message)
        }

        return additionalParts
    }
}
