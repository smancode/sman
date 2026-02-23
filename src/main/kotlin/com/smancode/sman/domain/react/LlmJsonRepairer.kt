package com.smancode.sman.domain.react

import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.config.SmanConfig
import com.smancode.sman.util.StackTraceUtils
import org.slf4j.LoggerFactory

/**
 * LLM JSON 修复器
 *
 * 负责 Level 7：使用 LLM 辅助修复无法通过常规方法解析的 JSON
 *
 * 这是一个"大招"，因为：
 * 1. 它会消耗额外的 Token 和时间
 * 2. 但它是最智能的方式，可以处理各种复杂的字段值问题
 * 3. LLM 自己输出的内容，LLM 自己应该能理解并修复
 *
 * 常见问题：
 * - 字段值中包含未转义的换行符、引号、反斜杠
 * - 字段值中包含嵌套的代码块标记
 * - 字段值中包含特殊字符导致 JSON 结构破坏
 */
class LlmJsonRepairer(
    private val responseParser: ResponseParser
) {
    private val logger = LoggerFactory.getLogger(LlmJsonRepairer::class.java)
    private val objectMapper = ObjectMapper()

    /**
     * 使用 LLM 辅助修复 JSON
     *
     * 当所有常规方法都失败时，调用 LLM 让它帮我们修复 JSON 中的问题字段值
     *
     * @param response LLM 返回的原始响应
     * @return 修复后的 JSON 字符串，如果失败则返回 null
     */
    fun extractWithLlmHelper(response: String): String? {
        if (response.isNullOrEmpty()) return null

        return try {
            logger.info("启动 Level 7 终极大招: 使用 LLM 辅助修复 JSON 字段值")

            // 先尝试用智能大括号提取找出 JSON 结构
            val candidateJson = extractSmartBraceMatching(response)
            if (candidateJson == null) {
                logger.warn("LLM 辅助修复: 无法提取 JSON 结构，跳过")
                return null
            }

            // 分析 JSON 结构，找出问题字段
            val problematicField = extractProblematicField(candidateJson)
            if (problematicField == null) {
                // 如果找不到具体问题字段，尝试让 LLM 直接修复整个 JSON
                logger.warn("LLM 辅助修复: 无法识别具体问题字段，尝试 LLM 整体修复")
                val fixedJson = fixEntireJsonWithLlm(candidateJson)
                if (fixedJson != null && responseParser.tryParseJson(fixedJson)) {
                    logger.info("LLM 辅助修复: 整体修复成功")
                    return fixedJson
                }
                return null
            }

            logger.info("LLM 辅助修复: 识别到问题字段，开始修复")
            fixProblematicFieldWithLlm(candidateJson, problematicField)

        } catch (e: Exception) {
            logger.error("LLM 辅助修复异常: {}", StackTraceUtils.formatStackTrace(e))
            null
        }
    }

    /**
     * 智能大括号匹配提取
     */
    private fun extractSmartBraceMatching(response: String): String? {
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

    /**
     * 使用 LLM 整体修复 JSON
     */
    private fun fixEntireJsonWithLlm(brokenJson: String): String? {
        return try {
            val MAX_JSON_LENGTH = 3000
            val truncatedJson = if (brokenJson.length > MAX_JSON_LENGTH) {
                brokenJson.take(MAX_JSON_LENGTH) + "..."
            } else brokenJson

            val systemPrompt = """
                <system_config>
                    <language_rule>
                        <input_processing>English (For logic & reasoning)</input_processing>
                        <final_output>Valid JSON only</final_output>
                    </language_rule>
                </system_config>

                <context>
                    <role>JSON Repair Expert</role>
                    <task>Fix malformed JSON to valid JSON</task>
                    <requirement>Preserve all original content, only fix format issues</requirement>
                </context>

                <interaction_protocol>
                1. **Analyze**: In <thinking> tags, identify what's broken
                2. **Fix**: Escape newlines as \n, quotes as \", backslashes as \\
                3. **Output**: Return ONLY the fixed JSON, no markdown blocks
                </interaction_protocol>

                <anti_hallucination_rules>
                1. **Strict Grounding**: Use ONLY the provided JSON, do NOT invent content
                2. **No Markdown**: Do NOT wrap output in ```json``` blocks
                3. **No Explanation**: Output ONLY the JSON object
                </anti_hallucination_rules>
            """.trimIndent()

            val userPrompt = """
                <task>
                Fix this broken JSON to be valid JSON.
                </task>

                <broken_json>
                $truncatedJson
                </broken_json>

                Output the fixed JSON (no markdown, no explanation):
            """.trimIndent()

            val llmService = SmanConfig.createLlmService()
            val llmResponse = llmService.simpleRequest(systemPrompt, userPrompt)
            if (llmResponse.isNullOrEmpty()) return null

            cleanLlmResponse(llmResponse)

        } catch (e: Exception) {
            logger.error("LLM 整体修复 JSON 异常: {}", e.message)
            null
        }
    }

    /**
     * 从 JSON 中提取出可能有问题的字段定义
     */
    private fun extractProblematicField(json: String): String? {
        val fieldNames = listOf(
            "text", "reasoning", "summary", "content", "description",
            "result", "error", "message", "response", "output",
            "query", "code", "value", "data"
        )

        val MIN_LENGTH = 10

        for (fieldName in fieldNames) {
            val pattern = "\"$fieldName\"\\s*:\\s*\"(.{$MIN_LENGTH,})(?:\"|\\n|\$)"
            val p = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            val m = p.find(json)
            if (m != null) {
                return "\"$fieldName\": ${m.groupValues[1]}"
            }
        }

        return findFirstProblematicStringField(json)
    }

    /**
     * 尝试找到第一个看起来有问题的字符串字段
     */
    private fun findFirstProblematicStringField(json: String): String? {
        val fieldPattern = Regex("\"([a-zA-Z_][a-zA-Z0-9_]*)\"\\s*:\\s*\"")
        val matches = fieldPattern.findAll(json)

        for (match in matches) {
            val fieldName = match.groupValues[1]
            val valueStart = match.range.last + 1

            var inEscape = false
            for (i in valueStart until minOf(valueStart + 2000, json.length)) {
                val c = json[i]

                if (inEscape) { inEscape = false; continue }
                if (c == '\\') { inEscape = true; continue }
                if (c == '"') break

                if (c == '\n' || c == '\r') {
                    logger.debug("发现可能的问题字段: {} (包含未转义换行符)", fieldName)
                    return "\"$fieldName\": ${json.substring(valueStart, minOf(i + 100, json.length))}"
                }
            }
        }
        return null
    }

    /**
     * 调用 LLM 修复问题字段并重新组装 JSON
     */
    private fun fixProblematicFieldWithLlm(json: String, problematicField: String): String? {
        return try {
            val fieldParts = problematicField.split(":", limit = 2)
            val fieldName = fieldParts[0].trim().replace("\"", "")
            val rawValue = fieldParts.getOrNull(1)?.trim() ?: return null

            val MAX_VALUE_LENGTH = 2000
            val FALLBACK_PREFIX_LENGTH = 100
            val truncatedValue = if (rawValue.length > MAX_VALUE_LENGTH) {
                rawValue.take(MAX_VALUE_LENGTH) + "..."
            } else rawValue

            val systemPrompt = """
                <system_config>
                    <language_rule>
                        <input_processing>English (For logic & reasoning)</input_processing>
                        <final_output>Valid JSON only</final_output>
                    </language_rule>
                </system_config>

                <context>
                    <role>JSON Field Value Repair Expert</role>
                    <task>Fix malformed field values to valid JSON string literals</task>
                    <requirement>Preserve original meaning, only fix format issues</requirement>
                </context>

                <interaction_protocol>
                1. **Think First**: In <thinking> tags, analyze the input value
                2. **Fix Issues**: Escape newlines as \n, quotes as \", backslashes as \\
                3. **Final Output**: Return {"fieldName": "fixed_value"} in valid JSON
                </interaction_protocol>

                <anti_hallucination_rules>
                1. **Strict Grounding**: Use ONLY the provided input value
                2. **No Markdown**: Do NOT wrap output in ```json``` blocks
                3. **No Explanation**: Output ONLY the JSON object
                4. **Terminology**: Keep text in original language
                </anti_hallucination_rules>

                <output_format>
                STRICTLY follow this template:

                {"%s": "fixed_value_here"}
                </output_format>
            """.trimIndent()

            val userPrompt = """
                <task>
                Fix the following field value to be a valid JSON string literal.
                </task>

                <input>
                <field_name>%s</field_name>
                <raw_value>
                %s
                </raw_value>
                </input>

                Output the fixed JSON (format: {"fieldName": "fixed_value"}):
            """.trimIndent()

            val formattedUserPrompt = String.format(userPrompt, fieldName, truncatedValue)

            val llmService = SmanConfig.createLlmService()
            val llmResponse = llmService.simpleRequest(systemPrompt, formattedUserPrompt)
            if (llmResponse.isNullOrEmpty()) return null

            val cleanedResponse = cleanLlmResponse(llmResponse)
            val fixedValue = extractFieldValueFromSimpleJson(cleanedResponse, fieldName) ?: cleanedResponse

            replaceFieldInJson(json, fieldName, fixedValue, rawValue, FALLBACK_PREFIX_LENGTH)

        } catch (e: Exception) {
            logger.error("LLM 修复字段值异常: {}", StackTraceUtils.formatStackTrace(e))
            null
        }
    }

    /**
     * 清理 LLM 响应，移除可能的 markdown 标记
     */
    private fun cleanLlmResponse(response: String): String {
        val cleaned = response.trim()
        if (cleaned.startsWith("```")) {
            val extracted = responseParser.extractFromMarkdownBlock(cleaned)
            return extracted ?: cleaned
        }
        return cleaned
    }

    /**
     * 在 JSON 中替换指定字段的值
     */
    private fun replaceFieldInJson(
        json: String,
        fieldName: String,
        newValue: String,
        originalValue: String,
        fallbackPrefixLength: Int
    ): String {
        logger.debug("replaceFieldInJson: fieldName={}", fieldName)

        val patternStr = "\"$fieldName\"\\s*:\\s*\".*?(?=\"|\\n)"
        var fixedJson = json.replace(Regex(patternStr), "\"$fieldName\": $newValue")

        if (fixedJson == json) {
            logger.debug("正则替换失败，回退到简单字符串替换")
            val prefix = if (originalValue.length > fallbackPrefixLength) {
                originalValue.take(fallbackPrefixLength)
            } else originalValue
            fixedJson = json.replace(prefix, newValue.replace("\"", ""))
        }

        logger.debug("最终 fixedJson 有效性: {}", responseParser.tryParseJson(fixedJson))

        return fixedJson
    }

    /**
     * 从简单 JSON 中提取字段值
     */
    private fun extractFieldValueFromSimpleJson(simpleJson: String, fieldName: String): String? {
        return try {
            val node = objectMapper.readTree(simpleJson)
            val valueNode = node.path(fieldName)
            if (!valueNode.isMissingNode) {
                "\"" + valueNode.asText()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t") + "\""
            } else null
        } catch (e: Exception) {
            logger.debug("解析简单 JSON 失败: {}", e.message)
            null
        }
    }
}
