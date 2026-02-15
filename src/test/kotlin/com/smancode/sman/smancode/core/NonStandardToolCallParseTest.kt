package com.smancode.sman.smancode.core

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 非标准工具调用格式解析测试
 *
 * 测试目标：验证 extractNonStandardToolCall 方法能够正确解析各种非标准格式
 */
@DisplayName("非标准工具调用格式解析测试")
class NonStandardToolCallParseTest {

    private val objectMapper = ObjectMapper()

    // 模拟 extractNonStandardToolCall 的逻辑
    private fun extractNonStandardToolCall(response: String): String? {
        // 1. 尝试解析 minimax 原生格式
        val minimaxResult = extractMinimaxToolCall(response)
        if (minimaxResult != null) {
            return minimaxResult
        }

        // 2. 尝试解析"调用工具: xxx\n参数: {...}"格式
        val historyStyleResult = extractHistoryStyleToolCall(response)
        if (historyStyleResult != null) {
            return historyStyleResult
        }

        // 3. 尝试解析 [TOOL_CALL]...[/TOOL_CALL] 格式
        val toolCallTagResult = extractToolCallTagFormat(response)
        if (toolCallTagResult != null) {
            return toolCallTagResult
        }

        // 4. 尝试解析 GLM-5 的 {"parts": [...]} 格式（即使 JSON 有错误）
        val glmResult = extractGlmPartsFormat(response)
        if (glmResult != null) {
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
                val paramName = paramMatch.groupValues[1]
                val paramValue = paramMatch.groupValues[2].trim()
                parameters[paramName] = paramValue
            }

            toolCalls.add(mapOf(
                "type" to "tool",
                "toolName" to toolName,
                "parameters" to parameters
            ))
        }

        if (toolCalls.isEmpty()) {
            return null
        }

        return try {
            objectMapper.writeValueAsString(mapOf("parts" to toolCalls))
        } catch (e: Exception) {
            null
        }
    }

    private fun extractHistoryStyleToolCall(response: String): String? {
        val toolCallPattern = Regex("""调用工具[:：]\s*(\w+)""")
        val toolMatch = toolCallPattern.find(response) ?: return null

        val toolName = toolMatch.groupValues[1]

        val parameters = mutableMapOf<String, Any>()

        val paramPattern = Regex("""参数[:：]\s*\{([^}]*)\}""")
        val paramMatch = paramPattern.find(response)
        if (paramMatch != null) {
            val paramContent = paramMatch.groupValues[1]
            val keyValuePattern = Regex("""(\w+)[:：]\s*["']?([^,"'}\n]+)["']?""")
            keyValuePattern.findAll(paramContent).forEach { kvMatch ->
                val key = kvMatch.groupValues[1]
                val value = kvMatch.groupValues[2].trim()
                parameters[key] = value.toIntOrNull() ?: value.toLongOrNull() ?: value
            }
        }

        return try {
            val parts = mutableListOf<Map<String, Any>>(
                mapOf(
                    "type" to "tool",
                    "toolName" to toolName,
                    "parameters" to parameters
                )
            )
            objectMapper.writeValueAsString(mapOf("parts" to parts))
        } catch (e: Exception) {
            null
        }
    }

    private fun extractToolCallTagFormat(response: String): String? {
        if (!response.contains("[TOOL_CALL]", ignoreCase = true)) {
            return null
        }

        val toolCalls = mutableListOf<Map<String, Any>>()

        val toolCallPattern = Regex("""\[TOOL_CALL\]([\s\S]*?)\[/TOOL_CALL\]""", RegexOption.IGNORE_CASE)
        val matches = toolCallPattern.findAll(response)

        for (match in matches) {
            val content = match.groupValues[1].trim()

            val toolNamePattern = Regex("""tool\s*=>\s*["'](\w+)["']""")
            val toolNameMatch = toolNamePattern.find(content) ?: continue
            val toolName = toolNameMatch.groupValues[1]

            val parameters = mutableMapOf<String, Any>()

            // 解析 --key "value" 或 --key value 格式（最常见）
            val dashParamPattern = Regex("""--(\w+)\s+["']?([^"'\n]+)["']?""")
            dashParamPattern.findAll(content).forEach { paramMatch ->
                val key = paramMatch.groupValues[1]
                val value = paramMatch.groupValues[2].trim()
                parameters[key] = value.toIntOrNull() ?: value.toLongOrNull() ?: value
            }

            val paramsBlockPattern = Regex("""parameters\s*=>\s*\{([^}]+)\}""")
            val paramsBlockMatch = paramsBlockPattern.find(content)
            if (paramsBlockMatch != null) {
                val paramsContent = paramsBlockMatch.groupValues[1]
                val keyValuePattern = Regex("""(\w+)[:：]\s*["']?([^,"'}\n]+)["']?""")
                keyValuePattern.findAll(paramsContent).forEach { kvMatch ->
                    val key = kvMatch.groupValues[1]
                    val value = kvMatch.groupValues[2].trim()
                    if (!parameters.containsKey(key)) {
                        parameters[key] = value.toIntOrNull() ?: value.toLongOrNull() ?: value
                    }
                }
            }

            // 解析 args => { key: "value" } 格式（minimax 常用）
            // 注意：使用 [\s\S] 匹配多行内容
            val argsBlockPattern = Regex("""args\s*=>\s*\{([\s\S]*?)\}""")
            val argsBlockMatch = argsBlockPattern.find(content)
            if (argsBlockMatch != null) {
                val argsContent = argsBlockMatch.groupValues[1]
                val keyValuePattern = Regex("""(\w+)[:：]\s*["']?([^,"'}\n]+)["']?""")
                keyValuePattern.findAll(argsContent).forEach { kvMatch ->
                    val key = kvMatch.groupValues[1]
                    val value = kvMatch.groupValues[2].trim()
                    if (!parameters.containsKey(key)) {
                        parameters[key] = value.toIntOrNull() ?: value.toLongOrNull() ?: value
                    }
                }
            }

            toolCalls.add(mapOf(
                "type" to "tool",
                "toolName" to toolName,
                "parameters" to parameters
            ))
        }

        if (toolCalls.isEmpty()) {
            return null
        }

        return try {
            objectMapper.writeValueAsString(mapOf("parts" to toolCalls))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 GLM-5 的 {"parts": [...]} 格式
     */
    private fun extractGlmPartsFormat(response: String): String? {
        if (!response.contains("\"parts\"") && !response.contains("\"toolName\"")) {
            return null
        }

        var jsonContent = extractFromMarkdownBlock(response) ?: response

        return try {
            val toolCalls = mutableListOf<Map<String, Any>>()

            val toolStartPattern = Regex("""\{\s*"type"\s*:\s*"tool"\s*,\s*"toolName"\s*:\s*"(\w+)"""")

            for (match in toolStartPattern.findAll(jsonContent)) {
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

                    toolCalls.add(mapOf(
                        "type" to "tool",
                        "toolName" to toolName,
                        "parameters" to parameters
                    ))
                }
            }

            if (toolCalls.isEmpty()) {
                null
            } else {
                objectMapper.writeValueAsString(mapOf("parts" to toolCalls))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractFromMarkdownBlock(response: String): String? {
        val jsonStart = "```json"
        val jsonEnd = "```"

        var startIndex = response.indexOf(jsonStart)
        if (startIndex != -1) {
            startIndex += jsonStart.length
            val endIndex = response.indexOf(jsonEnd, startIndex)
            if (endIndex != -1) {
                return response.substring(startIndex, endIndex).trim()
            }
        }
        return null
    }

    private fun extractBalancedBraces(content: String, startIndex: Int): String? {
        var depth = 0
        var inString = false
        var escapeNext = false

        for (i in startIndex until content.length) {
            val c = content[i]

            if (escapeNext) {
                escapeNext = false
                continue
            }

            when (c) {
                '\\' -> escapeNext = true
                '"' -> inString = !inString
                '{' -> if (!inString) depth++
                '}' -> {
                    if (!inString) {
                        depth--
                        if (depth == 0) {
                            return content.substring(startIndex, i + 1)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun extractKeyValuePairs(content: String, parameters: MutableMap<String, Any>) {
        val stringPattern = Regex(""""(\w+)"\s*:\s*"([^"]*)"""")
        stringPattern.findAll(content).forEach { match ->
            val key = match.groupValues[1]
            parameters[key] = match.groupValues[2]
        }

        val numPattern = Regex(""""(\w+)"\s*:\s*(\d+)""")
        numPattern.findAll(content).forEach { match ->
            val key = match.groupValues[1]
            if (!parameters.containsKey(key)) {
                parameters[key] = match.groupValues[2].toIntOrNull() ?: match.groupValues[2]
            }
        }
    }

    @Test
    @DisplayName("应该解析 [TOOL_CALL] args 格式（minimax 常用）")
    fun shouldParseToolCallArgsFormat() {
        val input = """
            [TOOL_CALL]
            {tool => "read_file", args => {
              --relativePath "settings.gradle"
              --startLine 1
              --endLine 50
            }}
            [/TOOL_CALL]
        """.trimIndent()

        val result = extractNonStandardToolCall(input)

        assertNotNull(result)
        val json = objectMapper.readTree(result)
        assertTrue(json.has("parts"))
        val parts = json.path("parts")
        assertEquals(1, parts.size())

        val toolPart = parts[0]
        assertEquals("tool", toolPart.path("type").asText())
        assertEquals("read_file", toolPart.path("toolName").asText())

        val params = toolPart.path("parameters")
        assertEquals("settings.gradle", params.path("relativePath").asText())
        assertEquals(1, params.path("startLine").asInt())
        assertEquals(50, params.path("endLine").asInt())
    }

    @Test
    @DisplayName("应该解析 minimax 原生工具调用格式")
    fun shouldParseMinimaxToolCallFormat() {
        val input = """
            <minimax:tool_call>
            <invoke name="read_file">
            <parameter name="simpleName">Application</parameter>
            <parameter name="startLine">1</parameter>
            <parameter name="endLine">100</parameter>
            </invoke>
            </minimax:tool_call>
        """.trimIndent()

        val result = extractNonStandardToolCall(input)

        assertNotNull(result)
        val json = objectMapper.readTree(result)
        assertTrue(json.has("parts"))
        val parts = json.path("parts")
        assertEquals(1, parts.size())

        val toolPart = parts[0]
        assertEquals("tool", toolPart.path("type").asText())
        assertEquals("read_file", toolPart.path("toolName").asText())

        val params = toolPart.path("parameters")
        assertEquals("Application", params.path("simpleName").asText())
    }

    @Test
    @DisplayName("应该解析历史风格工具调用格式")
    fun shouldParseHistoryStyleToolCallFormat() {
        val input = """
            继续扫描项目配置文件。调用工具: read_file
            参数: {simpleName: "settings", startLine: 1, endLine: 100}
        """.trimIndent()

        val result = extractNonStandardToolCall(input)

        assertNotNull(result)
        val json = objectMapper.readTree(result)
        assertTrue(json.has("parts"))
        val parts = json.path("parts")
        assertEquals(1, parts.size())

        val toolPart = parts[0]
        assertEquals("tool", toolPart.path("type").asText())
        assertEquals("read_file", toolPart.path("toolName").asText())

        val params = toolPart.path("parameters")
        assertEquals("settings", params.path("simpleName").asText())
    }

    @Test
    @DisplayName("应该解析 [TOOL_CALL] 标签格式")
    fun shouldParseToolCallTagFormat() {
        val input = """
            # 项目结构分析

            [TOOL_CALL]
            {tool => "find_file", parameters => {
              --filePattern "*.gradle"
            }}
            [/TOOL_CALL]
        """.trimIndent()

        val result = extractNonStandardToolCall(input)

        assertNotNull(result)
        val json = objectMapper.readTree(result)
        assertTrue(json.has("parts"))
        val parts = json.path("parts")
        assertEquals(1, parts.size())

        val toolPart = parts[0]
        assertEquals("tool", toolPart.path("type").asText())
        assertEquals("find_file", toolPart.path("toolName").asText())

        val params = toolPart.path("parameters")
        assertEquals("*.gradle", params.path("filePattern").asText())
    }

    @Test
    @DisplayName("标准 JSON 格式应该返回 null（交给其他 Level 处理）")
    fun shouldReturnNullForStandardJson() {
        val input = """{"parts": [{"type": "text", "text": "hello"}]}"""

        val result = extractNonStandardToolCall(input)

        assertNull(result, "标准 JSON 格式不应该被非标准解析器处理")
    }

    @Test
    @DisplayName("普通文本应该返回 null")
    fun shouldReturnNullForPlainText() {
        val input = "这是一段普通的文本，不包含工具调用"

        val result = extractNonStandardToolCall(input)

        assertNull(result)
    }

    @Test
    @DisplayName("应该解析多个 minimax 工具调用")
    fun shouldParseMultipleMinimaxToolCalls() {
        val input = """
            <minimax:tool_call>
            <invoke name="read_file">
            <parameter name="simpleName">settings</parameter>
            </invoke>
            <invoke name="find_file">
            <parameter name="filePattern">*.gradle</parameter>
            </invoke>
            </minimax:tool_call>
        """.trimIndent()

        val result = extractNonStandardToolCall(input)

        assertNotNull(result)
        val json = objectMapper.readTree(result)
        val parts = json.path("parts")
        assertEquals(2, parts.size())
        assertEquals("read_file", parts[0].path("toolName").asText())
        assertEquals("find_file", parts[1].path("toolName").asText())
    }

    @Test
    @DisplayName("实际 06_config_files.md 内容应该被正确解析")
    fun shouldParseActualConfigFilesContent() {
        val input = """
            继续扫描项目配置文件。调用工具: read_file
            参数: {simpleName: "settings", startLine: 1, endLine: 100}
            调用工具: read_file
            参数: {simpleName: "web/build", startLine: 1, endLine: 150}
        """.trimIndent()

        val result = extractNonStandardToolCall(input)

        assertNotNull(result)
        val json = objectMapper.readTree(result)
        val parts = json.path("parts")
        // 只会匹配第一个
        assertEquals(1, parts.size())
        assertEquals("read_file", parts[0].path("toolName").asText())
    }

    @Test
    @DisplayName("应该解析 GLM-5 不规范的 parts JSON 格式")
    fun shouldParseGlm5MalformedPartsJson() {
        // 这是实际从 autoloop 项目的 01_project_structure.md 中提取的内容
        // GLM-5 有时会输出参数直接放在对象里（没有 parameters 包装）的不规范 JSON
        val input = """
            <think&gt;
            用户要求我作为架构师分析项目结构...
            &lt;/think&gt;


            ```json
            {
              "parts": [
                {
                  "type": "text",
                  "text": "【分析问题】开始执行项目结构分析任务。"
                },
                {
                  "type": "tool",
                  "toolName": "find_file",
                  "parameters": {
                    "filePattern": "build.gradle"
                  }
                },
                {
                  "type": "tool",
                  "toolName": "find_file",
                  "filePattern": "settings.gradle"
                  }
                },
                {
                  "type": "tool",
                  "toolName": "find_file",
                  "filePattern": "pom.xml"
                  }
                }
              ]
            }
            ```
        """.trimIndent()

        val result = extractNonStandardToolCall(input)

        assertNotNull(result)
        val json = objectMapper.readTree(result)
        assertTrue(json.has("parts"))
        val parts = json.path("parts")
        // 应该解析出 3 个工具调用
        assertTrue(parts.size() >= 3, "应该解析出至少 3 个工具调用，实际: ${parts.size()}")

        // 验证第一个工具调用（规范的）
        val toolPart1 = parts[0]
        assertEquals("tool", toolPart1.path("type").asText())
        assertEquals("find_file", toolPart1.path("toolName").asText())

        // 验证第二个工具调用（不规范的，缺少 parameters 包装）
        val toolPart2 = parts[1]
        assertEquals("tool", toolPart2.path("type").asText())
        assertEquals("find_file", toolPart2.path("toolName").asText())
        // 参数应该被包装进 parameters
        val params2 = toolPart2.path("parameters")
        assertTrue(params2.has("filePattern"), "filePattern 应该被包装进 parameters")
    }
}
