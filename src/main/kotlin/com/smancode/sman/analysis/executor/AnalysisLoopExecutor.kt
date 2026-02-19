package com.smancode.sman.analysis.executor

import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.analysis.model.AnalysisTodo
import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.analysis.model.TodoStatus
import com.smancode.sman.evolution.guard.DoomLoopGuard
import com.smancode.sman.smancode.llm.LlmService
import com.smancode.sman.tools.ToolExecutor
import com.smancode.sman.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * 分析循环执行结果
 */
data class AnalysisLoopResult(
    val type: AnalysisType,
    val content: String,
    val completeness: Double,
    val missingSections: List<String>,
    val todos: List<AnalysisTodo>,
    val steps: Int,
    val toolCallHistory: String? = null
)

/**
 * 独立分析执行器
 *
 * 执行单个分析类型，复用工具调用能力，但不经过对话式 ReAct 循环。
 * 强制输出结构化 Markdown 报告，并进行完成度验证。
 *
 * 【核心修复】
 * 1. 加载提示词模板
 * 2. 注入项目文件内容
 * 3. 实现工具调用提取和执行
 */
class AnalysisLoopExecutor(
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val doomLoopGuard: DoomLoopGuard,
    private val llmService: LlmService,
    private val validator: AnalysisOutputValidator,
    private val maxSteps: Int = 15
) {
    private val logger = LoggerFactory.getLogger(AnalysisLoopExecutor::class.java)
    private val objectMapper = ObjectMapper()

    /**
     * 执行分析
     *
     * @param type 分析类型
     * @param projectKey 项目唯一标识
     * @param priorContext 前置上下文（之前分析的结果）
     * @param existingTodos 现有的 TODO 列表
     * @return 分析结果
     */
    suspend fun execute(
        type: AnalysisType,
        projectKey: String,
        priorContext: String = "",
        existingTodos: List<AnalysisTodo> = emptyList()
    ): AnalysisLoopResult = withContext(Dispatchers.IO) {
        logger.info("开始执行分析: type={}, projectKey={}", type, projectKey)

        val checkResult = doomLoopGuard.shouldSkipQuestion(projectKey)
        if (checkResult.shouldSkip) {
            logger.warn("分析被跳过: reason={}", checkResult.reason)
            return@withContext createEmptyResult(type, existingTodos)
        }

        // 加载提示词模板
        val promptTemplate = loadPromptTemplate(type)

        var step = 0
        var hasCompleteReport = false
        var currentContent = ""
        val toolResults = mutableListOf<String>()
        var finalCompleteness = 0.0
        var finalMissingSections = emptyList<String>()

        while (step < maxSteps && !hasCompleteReport) {
            step++
            logger.debug("分析步骤 {}/{}: type={}", step, maxSteps, type)

            val prompt = buildAnalysisPrompt(
                promptTemplate = promptTemplate,
                priorContext = priorContext,
                existingTodos = existingTodos,
                toolResults = toolResults,
                currentContent = currentContent
            )

            val response = callLlm(prompt)
            currentContent = response

            val cleanedContent = validator.cleanMarkdownContent(response)
            val toolCalls = extractToolCalls(response)

            if (toolCalls.isNotEmpty()) {
                logger.info("检测到 {} 个工具调用", toolCalls.size)
                executeToolCalls(toolCalls, toolResults, projectKey)
            } else {
                val validationResult = validator.validate(cleanedContent, type)
                finalCompleteness = validationResult.completeness
                finalMissingSections = validationResult.missingSections

                if (validationResult.isValid) {
                    hasCompleteReport = true
                    logger.info("分析完成: type={}, completeness={}", type, finalCompleteness)
                } else {
                    // 清空 toolResults，避免累积重复的补充请求
                    toolResults.clear()
                    val supplementRequest = buildSupplementRequest(finalMissingSections)
                    toolResults.add(supplementRequest)
                    logger.debug("报告不完整，继续补充: missing={}", finalMissingSections)
                }
            }
        }

        val finalTodos = if (finalMissingSections.isNotEmpty()) {
            validator.generateTodos(finalMissingSections, type)
        } else {
            existingTodos
        }

        val finalContent = validator.cleanMarkdownContent(currentContent)
        doomLoopGuard.recordSuccess(projectKey)

        logger.info("分析执行完成: type={}, steps={}, completeness={}", type, step, finalCompleteness)

        AnalysisLoopResult(
            type = type,
            content = finalContent,
            completeness = finalCompleteness,
            missingSections = finalMissingSections,
            todos = finalTodos,
            steps = step,
            toolCallHistory = if (toolResults.isNotEmpty()) toolResults.joinToString("\n") else null
        )
    }

    private fun createEmptyResult(type: AnalysisType, existingTodos: List<AnalysisTodo>): AnalysisLoopResult {
        return AnalysisLoopResult(
            type = type,
            content = "",
            completeness = 0.0,
            missingSections = validator.extractMissingSections("", type),
            todos = existingTodos,
            steps = 0
        )
    }

    /**
     * 加载分析提示词模板
     */
    private fun loadPromptTemplate(type: AnalysisType): String {
        return try {
            val promptPath = "prompts/${type.getPromptPath()}"
            val resource = javaClass.classLoader.getResourceAsStream(promptPath)
            if (resource != null) {
                String(resource.readAllBytes())
            } else {
                logger.warn("未找到提示词模板: $promptPath，使用默认模板")
                getDefaultPromptTemplate(type)
            }
        } catch (e: Exception) {
            logger.warn("加载提示词模板失败: ${type.key}", e)
            getDefaultPromptTemplate(type)
        }
    }

    /**
     * 获取默认提示词模板
     */
    private fun getDefaultPromptTemplate(type: AnalysisType): String {
        return """
            # 任务：${type.displayName}

            ## 分析要求
            请对项目进行${type.displayName}，生成结构化的 Markdown 报告。

            ## 必填章节
            ${getRequiredSectionsDescription(type)}

            ## 输出格式
            请直接输出 Markdown 格式的分析报告，不要包含任何对话式内容。
            不要使用 <thinking> 或 <thinkable> 标签。
        """.trimIndent()
    }

    /**
     * 构建分析提示词
     */
    private fun buildAnalysisPrompt(
        promptTemplate: String,
        priorContext: String,
        existingTodos: List<AnalysisTodo>,
        toolResults: List<String>,
        currentContent: String
    ): String {
        return buildString {
            // 使用提示词模板
            appendLine(promptTemplate)
            appendLine()

            // 已知上下文
            if (priorContext.isNotBlank()) {
                appendLine("## 已知上下文")
                appendLine(priorContext)
                appendLine()
            }

            // 待补充内容
            if (existingTodos.isNotEmpty()) {
                appendLine("## 待补充内容")
                existingTodos.forEach { appendLine("- ${it.content}") }
                appendLine()
            }

            // 工具调用结果
            if (toolResults.isNotEmpty()) {
                appendLine("## 工具调用结果")
                toolResults.forEach { appendLine(it) }
                appendLine()
            }

            // 当前分析内容（用于迭代改进）
            if (currentContent.isNotBlank()) {
                appendLine("## 当前分析内容")
                appendLine(currentContent)
                appendLine()
            }
        }
    }

    /**
     * 获取必填章节描述
     */
    private fun getRequiredSectionsDescription(type: AnalysisType): String {
        return when (type) {
            AnalysisType.PROJECT_STRUCTURE -> """
                - 项目概述：项目的基本介绍
                - 目录结构：项目的目录组织方式
                - 模块划分：各个模块的职责和关系
                - 依赖管理：项目使用的主要依赖
            """.trimIndent()

            AnalysisType.TECH_STACK -> """
                - 编程语言：使用的编程语言及版本
                - 构建工具：构建和打包工具
                - 框架：使用的框架
                - 数据存储：数据库和存储方案
            """.trimIndent()

            AnalysisType.API_ENTRIES -> """
                - 入口列表：所有 API 端点
                - 认证方式：API 的认证机制
                - 请求格式：请求的数据格式
                - 响应格式：响应的数据格式
            """.trimIndent()

            AnalysisType.DB_ENTITIES -> """
                - 实体列表：所有数据库实体
                - 表关系：实体之间的关系
                - 字段详情：重要字段的说明
            """.trimIndent()

            AnalysisType.ENUMS -> """
                - 枚举列表：所有枚举类型
                - 枚举用途：各枚举的用途说明
            """.trimIndent()

            AnalysisType.CONFIG_FILES -> """
                - 配置文件列表：所有配置文件
                - 环境配置：不同环境的配置差异
            """.trimIndent()
        }
    }

    /**
     * 调用 LLM
     */
    private suspend fun callLlm(prompt: String): String {
        return withContext(Dispatchers.IO) {
            try {
                llmService.simpleRequest(prompt)
            } catch (e: Exception) {
                logger.error("LLM 调用失败", e)
                ""
            }
        }
    }

    /**
     * 工具调用信息
     */
    data class ToolCallInfo(
        val name: String,
        val params: Map<String, Any>
    )

    /**
     * 从响应中提取工具调用
     *
     * 支持多种格式：
     * 1. 标准 JSON: {"parts": [{"type": "tool", "toolName": "xxx", "parameters": {...}}]}
     * 2. minimax 格式: <minimax:tool_call><invoke name="xxx">...</invoke></minimax:tool_call>
     * 3. [TOOL_CALL] 格式: [TOOL_CALL]{tool => "xxx", args => {...}}[/TOOL_CALL]
     * 4. <tool_code> 格式: <tool_code>{tool => 'xxx', args => '...'}</tool_code> (MiniMax-M2.5)
     * 5. 简单格式: 调用工具: xxx\n参数: {...}
     */
    private fun extractToolCalls(response: String): List<ToolCallInfo> {
        val toolCalls = mutableListOf<ToolCallInfo>()

        try {
            // 1. 尝试解析标准 JSON 格式
            val jsonToolCalls = extractJsonToolCalls(response)
            if (jsonToolCalls.isNotEmpty()) {
                logger.debug("使用 JSON 格式解析到 {} 个工具调用", jsonToolCalls.size)
                return jsonToolCalls
            }

            // 2. 尝试解析 minimax 格式
            val minimaxToolCalls = extractMinimaxToolCalls(response)
            if (minimaxToolCalls.isNotEmpty()) {
                logger.debug("使用 minimax 格式解析到 {} 个工具调用", minimaxToolCalls.size)
                return minimaxToolCalls
            }

            // 3. 尝试解析 <tool_code> 格式 (MiniMax-M2.5)
            val toolCodeCalls = extractToolCodeFormat(response)
            if (toolCodeCalls.isNotEmpty()) {
                logger.debug("使用 <tool_code> 格式解析到 {} 个工具调用", toolCodeCalls.size)
                return toolCodeCalls
            }

            // 4. 尝试解析 [TOOL_CALL] 格式
            val toolCallTagCalls = extractToolCallTagFormat(response)
            if (toolCallTagCalls.isNotEmpty()) {
                logger.debug("使用 [TOOL_CALL] 格式解析到 {} 个工具调用", toolCallTagCalls.size)
                return toolCallTagCalls
            }

            // 5. 尝试解析简单格式
            val simpleToolCalls = extractSimpleToolCalls(response)
            if (simpleToolCalls.isNotEmpty()) {
                logger.debug("使用简单格式解析到 {} 个工具调用", simpleToolCalls.size)
                return simpleToolCalls
            }
        } catch (e: Exception) {
            logger.warn("解析工具调用失败: ${e.message}")
        }

        return toolCalls
    }

    /**
     * 解析 <tool_code>{tool => 'xxx', args => '...'}</tool_code> 格式 (MiniMax-M2.5)
     */
    private fun extractToolCodeFormat(response: String): List<ToolCallInfo> {
        val toolCalls = mutableListOf<ToolCallInfo>()

        if (!response.contains("<tool_code>", ignoreCase = true)) {
            return toolCalls
        }

        // 匹配 <tool_code>...</tool_code> 内容
        val toolCodePattern = Regex("""<tool_code>([\s\S]*?)</tool_code>""", RegexOption.IGNORE_CASE)
        val matches = toolCodePattern.findAll(response)

        for (match in matches) {
            val content = match.groupValues[1].trim()

            // 提取工具名: tool => 'xxx' 或 tool => "xxx"
            val toolNamePattern = Regex("""tool\s*=>\s*['"](\w+)['"]""", RegexOption.IGNORE_CASE)
            val toolNameMatch = toolNamePattern.find(content) ?: continue
            val toolName = toolNameMatch.groupValues[1]

            val params = mutableMapOf<String, Any>()

            // 提取参数: args => '...' 或 args => "..." 或 args => {...}
            val argsPattern = Regex("""args\s*=>\s*['"]([\s\S]*?)['"]""", RegexOption.IGNORE_CASE)
            val argsMatch = argsPattern.find(content)
            if (argsMatch != null) {
                val argsContent = argsMatch.groupValues[1]

                // 解析 <key>value</key> 格式
                val xmlTagPattern = Regex("""<(\w+)>([^<]*)</\1>""", RegexOption.IGNORE_CASE)
                xmlTagPattern.findAll(argsContent).forEach { tagMatch ->
                    params[tagMatch.groupValues[1]] = tagMatch.groupValues[2]
                }

                // 也支持 JSON 格式参数
                if (params.isEmpty()) {
                    try {
                        val jsonParams = objectMapper.readTree(argsContent)
                        if (jsonParams.isObject) {
                            jsonParams.fields().forEach { entry ->
                                params[entry.key] = entry.value.asText()
                            }
                        }
                    } catch (e: Exception) {
                        // 不是 JSON，作为原始字符串
                        if (argsContent.isNotBlank()) {
                            params["args"] = argsContent
                        }
                    }
                }
            }

            // 工具名映射（MiniMax 可能使用不同的工具名）
            val mappedToolName = mapToolName(toolName)
            toolCalls.add(ToolCallInfo(mappedToolName, params))
        }

        return toolCalls
    }

    /**
     * 工具名映射
     *
     * MiniMax-M2.5 可能使用不同的工具名，需要映射到我们注册的工具名
     */
    private fun mapToolName(toolName: String): String {
        return when (toolName.lowercase()) {
            "bash", "shell", "cmd", "command" -> "run_shell"  // 我们有 run_shell_command
            "list_directory", "ls", "dir" -> "find_file"
            "read_multiple_files" -> "read_file"
            "glob", "find" -> "find_file"
            else -> toolName
        }
    }

    /**
     * 解析 [TOOL_CALL]{tool => "xxx", args => {...}}[/TOOL_CALL] 格式
     */
    private fun extractToolCallTagFormat(response: String): List<ToolCallInfo> {
        val toolCalls = mutableListOf<ToolCallInfo>()

        if (!response.contains("[TOOL_CALL]", ignoreCase = true)) {
            return toolCalls
        }

        // 匹配 [TOOL_CALL]...[/TOOL_CALL] 内容
        val toolCallPattern = Regex("""\[TOOL_CALL\]([\s\S]*?)\[/TOOL_CALL\]""", RegexOption.IGNORE_CASE)
        val matches = toolCallPattern.findAll(response)

        for (match in matches) {
            val content = match.groupValues[1].trim()

            // 提取工具名: tool => "xxx" 或 tool => "xxx"
            val toolNamePattern = Regex("""tool\s*=>\s*["']?(\w+)["']?""", RegexOption.IGNORE_CASE)
            val toolNameMatch = toolNamePattern.find(content) ?: continue
            val toolName = toolNameMatch.groupValues[1]

            val params = mutableMapOf<String, Any>()

            // 提取参数: args => {...} 或 parameters => {...}
            val argsPattern = Regex("""(?:args|parameters)\s*=>\s*\{([\s\S]*?)\}""", RegexOption.IGNORE_CASE)
            val argsMatch = argsPattern.find(content)
            if (argsMatch != null) {
                val argsContent = argsMatch.groupValues[1]
                // 解析 --key "value" 或 key: "value" 格式
                val keyValuePattern = Regex("""--?(\w+)\s+["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                keyValuePattern.findAll(argsContent).forEach { kvMatch ->
                    params[kvMatch.groupValues[1]] = kvMatch.groupValues[2]
                }
                // 也支持 key: "value" 格式
                val colonPattern = Regex("""(\w+)\s*[:：]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                colonPattern.findAll(argsContent).forEach { kvMatch ->
                    params[kvMatch.groupValues[1]] = kvMatch.groupValues[2]
                }
            }

            toolCalls.add(ToolCallInfo(toolName, params))
        }

        return toolCalls
    }

    private fun extractJsonToolCalls(response: String): List<ToolCallInfo> {
        val toolCalls = mutableListOf<ToolCallInfo>()

        // 查找 JSON 块
        val jsonPattern = Regex("""\{[\s\S]*"parts"[\s\S]*\}""")
        val jsonMatch = jsonPattern.find(response)

        if (jsonMatch != null) {
            try {
                val json = objectMapper.readTree(jsonMatch.value)
                val parts = json.get("parts")
                if (parts != null && parts.isArray) {
                    for (part in parts) {
                        if (part.get("type")?.asText() == "tool") {
                            val toolName = part.get("toolName")?.asText() ?: continue
                            val paramsNode = part.get("parameters") ?: part.get("args")
                            val params = mutableMapOf<String, Any>()

                            if (paramsNode != null && paramsNode.isObject) {
                                paramsNode.fields().forEach { entry ->
                                    params[entry.key] = entry.value.asText()
                                }
                            }

                            toolCalls.add(ToolCallInfo(toolName, params))
                        }
                    }
                }
            } catch (e: Exception) {
                logger.debug("JSON 解析失败，尝试其他格式")
            }
        }

        return toolCalls
    }

    private fun extractMinimaxToolCalls(response: String): List<ToolCallInfo> {
        val toolCalls = mutableListOf<ToolCallInfo>()

        if (!response.contains("<minimax:tool_call>", ignoreCase = true)) {
            return toolCalls
        }

        val invokePattern = Regex("""<invoke\s+name=["'](\w+)["']>([\s\S]*?)</invoke>""", RegexOption.IGNORE_CASE)
        val matches = invokePattern.findAll(response)

        for (match in matches) {
            val toolName = match.groupValues[1]
            val parameterContent = match.groupValues[2]

            val params = mutableMapOf<String, Any>()
            val paramPattern = Regex("""<parameter\s+name=["'](\w+)["']>([\s\S]*?)</parameter>""", RegexOption.IGNORE_CASE)
            paramPattern.findAll(parameterContent).forEach { paramMatch ->
                params[paramMatch.groupValues[1]] = paramMatch.groupValues[2].trim()
            }

            toolCalls.add(ToolCallInfo(toolName, params))
        }

        return toolCalls
    }

    private fun extractSimpleToolCalls(response: String): List<ToolCallInfo> {
        val toolCalls = mutableListOf<ToolCallInfo>()

        val toolCallPattern = Regex("""调用工具[:：]\s*(\w+)""")
        val toolMatch = toolCallPattern.find(response) ?: return toolCalls

        val toolName = toolMatch.groupValues[1]
        val params = mutableMapOf<String, Any>()

        val paramPattern = Regex("""参数[:：]\s*\{([^}]*)\}""")
        val paramMatch = paramPattern.find(response)
        if (paramMatch != null) {
            val paramContent = paramMatch.groupValues[1]
            val keyValuePattern = Regex("""(\w+)[:：]\s*["']?([^,"'}\n]+)["']?""")
            keyValuePattern.findAll(paramContent).forEach { kvMatch ->
                params[kvMatch.groupValues[1]] = kvMatch.groupValues[2].trim()
            }
        }

        toolCalls.add(ToolCallInfo(toolName, params))
        return toolCalls
    }

    /**
     * 执行工具调用
     */
    private suspend fun executeToolCalls(
        toolCalls: List<ToolCallInfo>,
        toolResults: MutableList<String>,
        projectKey: String
    ) {
        for (toolCall in toolCalls) {
            val toolCheck = doomLoopGuard.shouldSkipToolCall(toolCall.name, toolCall.params)
            if (toolCheck.shouldSkip) {
                if (toolCheck.cachedResult != null) {
                    toolResults.add("工具 ${toolCall.name} (缓存): ${toolCheck.cachedResult}")
                }
            } else {
                val result = executeToolCall(toolCall, projectKey)
                doomLoopGuard.recordToolCall(toolCall.name, toolCall.params, result)
                toolResults.add("工具 ${toolCall.name}: $result")
            }
        }
    }

    private suspend fun executeToolCall(toolCall: ToolCallInfo, projectKey: String): String {
        return try {
            // 【修复】实际执行工具
            val result = toolExecutor.execute(toolCall.name, projectKey, toolCall.params)
            result.data?.toString() ?: "工具执行成功，无输出"
        } catch (e: Exception) {
            logger.error("工具执行失败: name={}", toolCall.name, e)
            "工具执行失败: ${e.message}"
        }
    }

    /**
     * 构建补充请求
     */
    private fun buildSupplementRequest(missingSections: List<String>): String {
        return "请补充以下章节的分析内容：${missingSections.joinToString("、")}"
    }
}
