package com.smancode.sman.domain.react

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * 响应解析器
 *
 * 负责从 LLM 响应中提取和解析 JSON，支持多级递进式解析策略：
 * - Level 0: 解析非标准格式（minimax:tool_call、调用工具: xxx 等）
 * - Level 1: 直接解析
 * - Level 1.5: 修复常见 JSON 语法错误
 * - Level 2: 清理 markdown 代码块
 * - Level 3: 修复转义字符
 * - Level 4: 智能大括号提取
 * - Level 5: 正则提取尝试
 * - Level 6: 简单正则快速尝试
 * - Level 7: LLM 辅助提取（由 LlmJsonRepairer 处理）
 * - Level 8: 降级为纯文本
 */
class ResponseParser {
    private val logger = LoggerFactory.getLogger(ResponseParser::class.java)
    private val objectMapper = ObjectMapper()

    // LLM JSON 修复器（延迟初始化，避免循环依赖）
    private var llmRepairer: LlmJsonRepairer? = null

    /**
     * 从响应中提取 JSON（多级递进式解析策略）
     *
     * @param response LLM 返回的原始响应
     * @return 提取出的 JSON 字符串，如果所有策略都失败则返回 null
     */
    fun extractJsonFromResponse(response: String?): String? {
        if (response.isNullOrEmpty()) {
            return null
        }

        val trimmedResponse = response.trim()

        // Level 0: 解析非标准格式
        val level0Result = extractNonStandardToolCall(trimmedResponse)
        if (level0Result != null && tryParseJson(level0Result)) {
            logger.info("Level 0 成功: 解析非标准工具调用格式")
            return level0Result
        }

        // Level 1: 直接解析
        if (tryParseJson(trimmedResponse)) {
            logger.debug("Level 1 成功: 直接解析")
            return trimmedResponse
        }

        // Level 1.5: 修复常见 JSON 语法错误
        val level15Result = fixCommonJsonErrors(trimmedResponse)
        if (level15Result != null && tryParseJson(level15Result)) {
            logger.info("Level 1.5 成功: 修复 JSON 语法错误")
            return level15Result
        }

        // Level 2: 清理 markdown 代码块
        val level2Result = extractFromMarkdownBlock(trimmedResponse)
        if (level2Result != null && tryParseJson(level2Result)) {
            logger.debug("Level 2 成功: 清理 markdown 代码块")
            return level2Result
        }

        // Level 3: 修复转义字符
        val level3Result = fixAndParse(trimmedResponse)
        if (level3Result != null) {
            logger.debug("Level 3 成功: 修复转义字符")
            return level3Result
        }

        // Level 4: 智能大括号提取
        val level4Result = extractWithSmartBraceMatching(trimmedResponse)
        if (level4Result != null && tryParseJson(level4Result)) {
            logger.debug("Level 4 成功: 智能大括号提取")
            return level4Result
        }

        // Level 5: 正则提取尝试
        val level5Result = extractWithRegex(trimmedResponse)
        if (level5Result != null && tryParseJson(level5Result)) {
            logger.debug("Level 5 成功: 正则提取")
            return level5Result
        }

        // Level 6: 简单正则快速尝试
        val level6Result = extractWithSimpleRegex(trimmedResponse)
        if (level6Result != null && tryParseJson(level6Result)) {
            logger.debug("Level 6 成功: 简单正则提取")
            return level6Result
        }

        // Level 7: LLM 辅助提取
        if (llmRepairer == null) {
            llmRepairer = LlmJsonRepairer(this)
        }
        val level7Result = llmRepairer!!.extractWithLlmHelper(response)
        if (level7Result != null && tryParseJson(level7Result)) {
            logger.debug("Level 7 成功: LLM 辅助提取")
            return level7Result
        }

        // Level 8: 所有策略失败
        logger.warn("所有 JSON 提取策略失败，将降级为纯文本处理")
        return null
    }

    /**
     * 解析 JSON 字符串为 JsonNode
     */
    fun parseJson(jsonString: String): JsonNode? {
        return try {
            objectMapper.readTree(jsonString)
        } catch (e: Exception) {
            logger.error("JSON 解析失败: {}", e.message)
            null
        }
    }

    /**
     * 尝试解析 JSON
     */
    fun tryParseJson(str: String?): Boolean {
        if (str.isNullOrEmpty()) return false
        return try { objectMapper.readTree(str); true } catch (e: Exception) { false }
    }

    /**
     * 从 markdown 代码块中提取 JSON
     */
    fun extractFromMarkdownBlock(response: String): String? {
        val jsonStart = "```json"
        var startIndex = response.indexOf(jsonStart)
        if (startIndex != -1) {
            startIndex += jsonStart.length
            val endIndex = response.indexOf("```", startIndex)
            if (endIndex != -1) {
                return response.substring(startIndex, endIndex).trim()
            }
        }

        val codeStart = "```"
        val codeStartIndex = response.indexOf(codeStart)
        if (codeStartIndex != -1) {
            val afterStart = codeStartIndex + codeStart.length
            val firstBrace = response.indexOf('{', afterStart)
            if (firstBrace != -1) {
                val endIndex = response.indexOf(codeStart, firstBrace)
                if (endIndex != -1) {
                    return response.substring(firstBrace, endIndex).trim()
                }
            }
        }
        return null
    }

    // ==================== Level 0: 非标准格式解析 ====================

    private fun extractNonStandardToolCall(response: String): String? {
        // 1. minimax 原生格式
        val minimaxResult = extractMinimaxToolCall(response)
        if (minimaxResult != null) {
            logger.info("Level 0: 解析到 minimax 工具调用格式")
            return minimaxResult
        }

        // 2. "调用工具: xxx\n参数: {...}" 格式
        val historyStyleResult = extractHistoryStyleToolCall(response)
        if (historyStyleResult != null) {
            logger.info("Level 0: 解析到历史风格工具调用格式")
            return historyStyleResult
        }

        // 3. [TOOL_CALL]...[/TOOL_CALL] 格式
        val toolCallTagResult = extractToolCallTagFormat(response)
        if (toolCallTagResult != null) {
            logger.info("Level 0: 解析到 [TOOL_CALL] 标签格式")
            return toolCallTagResult
        }

        // 4. GLM-5 的 {"parts": [...]} 格式
        val glmResult = extractGlmPartsFormat(response)
        if (glmResult != null) {
            logger.info("Level 0: 解析到 GLM-5 parts 格式")
            return glmResult
        }

        return null
    }

    private fun extractMinimaxToolCall(response: String): String? {
        if (!response.contains("<minimax:tool_call>", ignoreCase = true)) {
            return null
        }

        val toolCalls = mutableListOf<Map<String, Any>>()
        val invokePattern = Regex("""<invoke\s+name=["'](\w+)["']>([\s\S]*?)</invoke>""", RegexOption.IGNORE_CASE)
        val matches = invokePattern.findAll(response)

        for (match in matches) {
            val toolName = match.groupValues[1]
            val parameterContent = match.groupValues[2]

            val parameters = mutableMapOf<String, Any>()
            val paramPattern = Regex("""<parameter\s+name=["'](\w+)["']>([\s\S]*?)</parameter>""", RegexOption.IGNORE_CASE)
            paramPattern.findAll(parameterContent).forEach { paramMatch ->
                parameters[paramMatch.groupValues[1]] = paramMatch.groupValues[2].trim()
            }

            toolCalls.add(mapOf(
                "type" to "tool",
                "toolName" to toolName,
                "parameters" to parameters
            ))
        }

        return if (toolCalls.isNotEmpty()) {
            objectMapper.writeValueAsString(mapOf("parts" to toolCalls))
        } else null
    }

    private fun extractHistoryStyleToolCall(response: String): String? {
        val toolCallPattern = Regex("""调用工具[:：]\s*(\w+)""")
        val toolMatch = toolCallPattern.find(response) ?: return null

        val toolName = toolMatch.groupValues[1]
        val parameters = mutableMapOf<String, Any>()

        val paramPattern = Regex("""参数[:：]\s*\{([^}]*)\}""")
        val paramMatch = paramPattern.find(response)
        if (paramMatch != null) {
            val keyValuePattern = Regex("""(\w+)[:：]\s*["']?([^,"'}\n]+)["']?""")
            keyValuePattern.findAll(paramMatch.groupValues[1]).forEach { kvMatch ->
                val value = kvMatch.groupValues[2].trim()
                parameters[kvMatch.groupValues[1]] = value.toIntOrNull() ?: value.toLongOrNull() ?: value
            }
        }

        return try {
            objectMapper.writeValueAsString(mapOf("parts" to listOf(
                mapOf("type" to "tool", "toolName" to toolName, "parameters" to parameters)
            )))
        } catch (e: Exception) {
            logger.warn("转换历史风格工具调用为 JSON 失败: {}", e.message)
            null
        }
    }

    private fun extractToolCallTagFormat(response: String): String? {
        if (!response.contains("[TOOL_CALL]", ignoreCase = true)) {
            return null
        }

        val toolCalls = mutableListOf<Map<String, Any>>()
        val toolCallPattern = Regex("""\[TOOL_CALL\]([\s\S]*?)\[/TOOL_CALL\]""", RegexOption.IGNORE_CASE)

        for (match in toolCallPattern.findAll(response)) {
            val content = match.groupValues[1].trim()
            val toolNamePattern = Regex("""tool\s*=>\s*["'](\w+)["']""")
            val toolNameMatch = toolNamePattern.find(content) ?: continue
            val toolName = toolNameMatch.groupValues[1]

            val parameters = mutableMapOf<String, Any>()

            // 解析 --key "value" 格式
            val dashParamPattern = Regex("""--(\w+)\s+["']?([^"'\n]+)["']?""")
            dashParamPattern.findAll(content).forEach { paramMatch ->
                val value = paramMatch.groupValues[2].trim()
                parameters[paramMatch.groupValues[1]] = value.toIntOrNull() ?: value.toLongOrNull() ?: value
            }

            // 解析 parameters => { ... } 格式
            val paramsBlockPattern = Regex("""parameters\s*=>\s*\{([^}]+)\}""")
            val paramsBlockMatch = paramsBlockPattern.find(content)
            if (paramsBlockMatch != null) {
                val keyValuePattern = Regex("""(\w+)[:：]\s*["']?([^,"'}\n]+)["']?""")
                keyValuePattern.findAll(paramsBlockMatch.groupValues[1]).forEach { kvMatch ->
                    val key = kvMatch.groupValues[1]
                    if (!parameters.containsKey(key)) {
                        val value = kvMatch.groupValues[2].trim()
                        parameters[key] = value.toIntOrNull() ?: value.toLongOrNull() ?: value
                    }
                }
            }

            toolCalls.add(mapOf("type" to "tool", "toolName" to toolName, "parameters" to parameters))
        }

        return if (toolCalls.isNotEmpty()) {
            objectMapper.writeValueAsString(mapOf("parts" to toolCalls))
        } else null
    }

    private fun extractGlmPartsFormat(response: String): String? {
        if (!response.contains("\"parts\"") && !response.contains("\"toolName\"")) {
            return null
        }

        var jsonContent = extractFromMarkdownBlock(response) ?: response

        return try {
            val toolCalls = mutableListOf<Map<String, Any>>()
            val toolStartPattern = Regex("""\{\s*"type"\s*:\s*"tool"\s*,\s*"toolName"\s*:\s*"(\w+)"""")
            val matches = toolStartPattern.findAll(jsonContent).toList()

            for (match in matches) {
                val startIndex = match.range.first
                val toolName = match.groupValues[1]
                val objectContent = extractBalancedBraces(jsonContent, startIndex)

                if (objectContent != null) {
                    val parameters = mutableMapOf<String, Any>()

                    val paramsMatch = Regex(""""parameters"\s*:\s*\{([^}]*)\}""").find(objectContent)
                    if (paramsMatch != null) {
                        extractKeyValuePairs(paramsMatch.groupValues[1], parameters)
                    }

                    extractKeyValuePairs(objectContent, parameters)
                    parameters.remove("type")
                    parameters.remove("toolName")
                    parameters.remove("parameters")

                    toolCalls.add(mapOf("type" to "tool", "toolName" to toolName, "parameters" to parameters))
                }
            }

            if (toolCalls.isNotEmpty()) {
                objectMapper.writeValueAsString(mapOf("parts" to toolCalls))
            } else null
        } catch (e: Exception) {
            logger.warn("解析 GLM-5 parts 格式失败: {}", e.message)
            null
        }
    }

    private fun extractBalancedBraces(content: String, startIndex: Int): String? {
        var depth = 0
        var inString = false
        var escapeNext = false

        for (i in startIndex until content.length) {
            val c = content[i]
            if (escapeNext) { escapeNext = false; continue }
            when (c) {
                '\\' -> escapeNext = true
                '"' -> inString = !inString
                '{' -> if (!inString) depth++
                '}' -> if (!inString) {
                    depth--
                    if (depth == 0) return content.substring(startIndex, i + 1)
                }
            }
        }
        return null
    }

    private fun extractKeyValuePairs(content: String, parameters: MutableMap<String, Any>) {
        val stringPattern = Regex(""""(\w+)"\s*:\s*"([^"]*)"""")
        stringPattern.findAll(content).forEach { parameters[it.groupValues[1]] = it.groupValues[2] }

        val numPattern = Regex(""""(\w+)"\s*:\s*(\d+)""")
        numPattern.findAll(content).forEach { match ->
            val key = match.groupValues[1]
            if (!parameters.containsKey(key)) {
                parameters[key] = match.groupValues[2].toIntOrNull() ?: match.groupValues[2]
            }
        }
    }

    // ==================== Level 1.5-3: 错误修复 ====================

    private fun fixCommonJsonErrors(response: String): String? {
        if (!response.contains("{") || !response.contains("}")) return null
        val jsonStart = response.indexOf("{")
        if (jsonStart < 0) return null

        var fixed = if (jsonStart > 0) response.substring(jsonStart) else response
        fixed = fixed.replace(Regex("""\}\s*,\s*"\s*\{"""), """},{""")
        fixed = fixed.replace(Regex("""\}\s*"\s*\{"""), """},{""")
        fixed = fixed.replace(Regex("""\}\s*\{"type"""), """},{"type""")
        fixed = fixed.replace(Regex("""\}\s+\{"""), """},{""")
        return fixed
    }

    private fun fixAndParse(response: String): String? {
        val extracted = extractFromMarkdownBlock(response)
        val toFix = extracted ?: response

        val fixedVersions = listOf(
            fixStringNewlines(toFix),
            fixUnescapedQuotes(toFix),
            fixUnescapedBackslashes(toFix),
            fixAllCommonIssues(toFix)
        )

        for (fixed in fixedVersions) {
            if (tryParseJson(fixed)) return fixed
        }
        return null
    }

    private fun fixStringNewlines(json: String): String = json.replace("\\\n", "\\\\n")
    private fun fixUnescapedQuotes(json: String): String = json
    private fun fixUnescapedBackslashes(json: String): String = json
    private fun fixAllCommonIssues(json: String): String {
        var result = json
        result = fixStringNewlines(result)
        result = fixUnescapedQuotes(result)
        result = fixUnescapedBackslashes(result)
        return result
    }

    // ==================== Level 4-6: 大括号和正则提取 ====================

    private fun extractWithSmartBraceMatching(response: String): String? {
        val braceStart = response.indexOf('{')
        if (braceStart < 0) return null

        var depth = 0
        var inString = false
        var escape = false

        for (i in braceStart until response.length) {
            val c = response[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"' && !escape) { inString = !inString; continue }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return response.substring(braceStart, i + 1)
                }
            }
        }
        return null
    }

    private fun extractWithRegex(response: String): String? {
        val patterns = listOf(
            Regex("""```json\s*([\s\S]*?)\s*```"""),
            Regex("""\{[\s\S]*\}"""),
            Regex("""\{(?:[^{}]|\{[^{}]*\})*\}""")
        )

        for (pattern in patterns) {
            val matcher = pattern.find(response)
            if (matcher != null) {
                val match = matcher.groupValues.getOrNull(1) ?: matcher.value
                if (match.trim().isNotEmpty()) return match.trim()
            }
        }
        return null
    }

    private fun extractWithSimpleRegex(response: String): String? {
        val firstBrace = response.indexOf('{')
        val lastBrace = response.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return response.substring(firstBrace, lastBrace + 1)
        }
        return null
    }
}
