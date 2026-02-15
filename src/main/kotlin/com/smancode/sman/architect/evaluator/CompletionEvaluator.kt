package com.smancode.sman.architect.evaluator

import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.architect.model.ArchitectGoal
import com.smancode.sman.architect.model.EvaluationResult
import com.smancode.sman.architect.model.TodoItem
import com.smancode.sman.architect.model.TodoPriority
import com.smancode.sman.config.SmanConfig
import com.smancode.sman.model.message.Message
import com.smancode.sman.model.part.TextPart
import com.smancode.sman.model.part.ToolPart
import com.smancode.sman.smancode.llm.LlmService
import org.slf4j.LoggerFactory

/**
 * 完成度评估器
 *
 * 调用 LLM 评估分析结果的阶段性完成度
 */
class CompletionEvaluator(
    private val llmService: LlmService
) {
    private val logger = LoggerFactory.getLogger(CompletionEvaluator::class.java)
    private val objectMapper = ObjectMapper()

    /**
     * 评估分析结果
     *
     * @param goal 分析目标
     * @param response SmanLoop 的响应消息
     * @param threshold 完成度阈值
     * @return 评估结果
     */
    fun evaluate(
        goal: ArchitectGoal,
        response: Message,
        threshold: Double = SmanConfig.getArchitectCompletionThreshold()
    ): EvaluationResult {
        return try {
            // 1. 提取响应内容
            val responseContent = extractResponseContent(response)

            if (responseContent.isBlank()) {
                logger.warn("响应内容为空，返回失败评估")
                return EvaluationResult.failure("响应内容为空")
            }

            // 2. 【防呆】检测内容质量
            val qualityCheck = checkContentQuality(responseContent, goal)
            if (!qualityCheck.isAcceptable) {
                logger.warn("内容质量检测失败: {}, 追问: {}", qualityCheck.reason, qualityCheck.followUpQuestion)
                return EvaluationResult(
                    completeness = 0.0,
                    isComplete = false,
                    summary = qualityCheck.reason,
                    todos = emptyList(),
                    followUpQuestions = listOf(qualityCheck.followUpQuestion),
                    confidence = 0.3
                )
            }

            // 3. 构建评估提示词
            val systemPrompt = buildSystemPrompt()
            val userPrompt = buildUserPrompt(goal, responseContent, threshold)

            // 4. 调用 LLM 评估
            logger.info("开始评估分析结果: goal={}, threshold={}", goal.type.key, threshold)
            val llmResponse = llmService.simpleRequest(systemPrompt, userPrompt)

            if (llmResponse.isNullOrBlank()) {
                logger.warn("LLM 评估响应为空")
                return EvaluationResult.failure("LLM 评估响应为空")
            }

            // 5. 解析评估结果
            parseEvaluationResult(llmResponse, threshold)

        } catch (e: Exception) {
            logger.error("评估失败", e)
            EvaluationResult.failure("评估异常: ${e.message}")
        }
    }

    /**
     * 内容质量检测结果
     */
    private data class QualityCheckResult(
        val isAcceptable: Boolean,
        val reason: String = "",
        val followUpQuestion: String = ""
    )

    /**
     * 【防呆】检测内容质量
     *
     * 检测 LLM 返回的内容是否是有效的分析结果，而不是：
     * - 只有思考块没有实际内容
     * - 等待用户输入的问候语
     * - 空洞的占位内容
     */
    private fun checkContentQuality(content: String, goal: ArchitectGoal): QualityCheckResult {
        val cleanContent = content.trim()

        // 1. 检测是否只有思考块
        val withoutThink = cleanContent
            .replace(Regex("<think[^>]*>[\\s\\S]*?</think&gt;", RegexOption.IGNORE_CASE), "")
            .replace(Regex("&lt;think[^&]*&gt;[\\s\\S]*?&lt;/think&gt;"), "")
            .trim()

        if (withoutThink.length < 100) {
            return QualityCheckResult(
                isAcceptable = false,
                reason = "内容过短或只有思考块",
                followUpQuestion = "请直接执行${goal.type.displayName}任务，使用工具扫描项目代码后输出分析报告。不要等待用户输入，不要只输出思考过程。"
            )
        }

        // 2. 检测是否是等待用户输入的问候语
        // 【增强】覆盖更多变体：请问你想...、请问您想...、请告诉我...
        val greetingPatterns = listOf(
            "请告诉我你的需求",
            "请描述你的需求",
            "我可以帮你",
            "请问有什么可以帮您",
            "请先配置",
            "你好！我是",
            "请问您想做什么",      // 新增：01_project_structure.md 的问题
            "请问你想做什么",      // 新增：变体
            "请问你想让我做什么",  // 新增：02_tech_stack.md 的问题
            "请问您想让我做什么",  // 新增：变体
            "请问你想了解",        // 新增：03_api_entries.md 的问题
            "还是有其他需求"       // 新增：变体
        )

        for (pattern in greetingPatterns) {
            if (cleanContent.contains(pattern)) {
                return QualityCheckResult(
                    isAcceptable = false,
                    reason = "内容是问候语而非分析结果",
                    followUpQuestion = "你是架构师，需要执行${goal.type.displayName}任务。请使用 read_file、find_file、grep_file 等工具扫描项目，然后输出 Markdown 格式的分析报告。"
                )
            }
        }

        // 3. 检测是否有实际的分析内容（至少要有标题或表格）
        val hasStructure = cleanContent.contains("#") ||
                          cleanContent.contains("|") ||
                          cleanContent.contains("- ") ||
                          cleanContent.contains("* ")

        if (!hasStructure && withoutThink.length < 300) {
            return QualityCheckResult(
                isAcceptable = false,
                reason = "内容缺乏结构化格式",
                followUpQuestion = "分析结果应该使用 Markdown 格式，包含标题（#）、列表（-）或表格（|）。请重新执行${goal.type.displayName}任务。"
            )
        }

        // 4. 检测是否有工具调用记录（好的分析应该有）
        val hasToolCalls = cleanContent.contains("工具调用") ||
                          cleanContent.contains("read_file") ||
                          cleanContent.contains("find_file") ||
                          cleanContent.contains("grep_file")

        if (!hasToolCalls && !hasStructure) {
            return QualityCheckResult(
                isAcceptable = false,
                reason = "没有工具调用记录且没有结构化内容",
                followUpQuestion = "请使用 read_file、find_file、grep_file 等工具扫描项目代码，然后输出${goal.type.displayName}的分析报告。"
            )
        }

        return QualityCheckResult(isAcceptable = true)
    }

    /**
     * 提取响应内容
     */
    private fun extractResponseContent(response: Message): String {
        val textParts = response.parts.filterIsInstance<TextPart>()
        val toolParts = response.parts.filterIsInstance<ToolPart>()

        val sb = StringBuilder()

        // 添加文本内容
        textParts.forEach { part ->
            part.text?.let { sb.append(it).append("\n\n") }
        }

        // 添加工具调用摘要
        if (toolParts.isNotEmpty()) {
            sb.append("## 工具调用记录\n\n")
            toolParts.forEach { part ->
                sb.append("- **${part.toolName}**: ${part.summary ?: "执行完成"}\n")
            }
        }

        return sb.toString().trim()
    }

    /**
     * 构建系统提示词
     */
    private fun buildSystemPrompt(): String {
        return """
<system_config>
    <language_rule>
        <input_processing>English (For logic & reasoning)</input_processing>
        <final_output>Simplified Chinese (For user readability)</final_output>
    </language_rule>
</system_config>

<context>
    <role>资深架构师 & 业务分析师</role>
    <task>评估项目分析结果的完成度和质量</task>
</context>

<evaluation_criteria>
1. **完整性** - 分析是否覆盖了目标定义的所有方面
2. **准确性** - 分析结果是否准确反映了项目实际情况
3. **深度** - 分析是否深入到关键细节
4. **可操作性** - 分析结果是否具有实用价值
</evaluation_criteria>

<output_format>
STRICTLY return valid JSON in this format:
```json
{
    "completeness": 0.85,
    "isComplete": true,
    "summary": "本次分析的总结（中文）",
    "todos": [
        {"content": "待完成事项（中文）", "priority": "HIGH"}
    ],
    "followUpQuestions": [
        "需要进一步探索的问题（中文）"
    ],
    "confidence": 0.9
}
```
</output_format>

<anti_hallucination_rules>
1. **Strict Grounding**: Base evaluation ONLY on provided content
2. **No Invention**: Do NOT invent items not present in the analysis
3. **Valid JSON**: Return ONLY valid JSON, no extra text
</anti_hallucination_rules>
        """.trimIndent()
    }

    /**
     * 构建用户提示词
     */
    private fun buildUserPrompt(
        goal: ArchitectGoal,
        responseContent: String,
        threshold: Double
    ): String {
        val previousTodos = if (goal.followUpQuestions.isNotEmpty()) {
            "\n<previous_todos>\nThese were the TODOs from last iteration. Check if they are completed:\n" +
            goal.followUpQuestions.mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n") +
            "\n</previous_todos>\n"
        } else ""

        return """
<task>
Evaluate the following analysis result for completeness and quality.
</task>

<goal>
Type: ${goal.type.displayName}
Description: ${goal.type.key}
Target Completeness Threshold: ${(threshold * 100).toInt()}%
</goal>

$previousTodos

<analysis_result>
$responseContent
</analysis_result>

<instructions>
1. Analyze the completeness (0.0 - 1.0) based on evaluation criteria
2. Determine if it meets the threshold ($threshold)
3. **Update TODO list dynamically**:
   - REMOVE completed items from previous TODOs
   - ADD newly discovered items that need analysis
   - KEEP items that are still pending
   - Return EMPTY array if everything is complete
4. If more exploration needed, list follow-up questions
5. Provide a brief summary in Chinese

Return ONLY the JSON result:
        """.trimIndent()
    }

    /**
     * 解析评估结果
     */
    private fun parseEvaluationResult(response: String, threshold: Double): EvaluationResult {
        return try {
            // 提取 JSON
            val jsonString = extractJson(response)
            if (jsonString == null) {
                logger.warn("无法从响应中提取 JSON: ${response.take(200)}")
                return EvaluationResult.failure("无法解析 LLM 响应")
            }

            // 解析 JSON
            val node = objectMapper.readTree(jsonString)

            val completeness = node.path("completeness").asDouble(0.0)
            val isComplete = completeness >= threshold
            val summary = node.path("summary").asText("无总结")
            val confidence = node.path("confidence").asDouble(0.8)

            // 解析 TODOs
            val todos = mutableListOf<TodoItem>()
            val todosNode = node.path("todos")
            if (todosNode.isArray) {
                for (todoNode in todosNode) {
                    val content = todoNode.path("content").asText()
                    val priorityStr = todoNode.path("priority").asText("MEDIUM")
                    if (content.isNotEmpty()) {
                        todos.add(TodoItem(
                            content = content,
                            priority = TodoPriority.fromString(priorityStr)
                        ))
                    }
                }
            }

            // 解析追问
            val followUpQuestions = mutableListOf<String>()
            val questionsNode = node.path("followUpQuestions")
            if (questionsNode.isArray) {
                for (qNode in questionsNode) {
                    val question = qNode.asText()
                    if (question.isNotEmpty()) {
                        followUpQuestions.add(question)
                    }
                }
            }

            logger.info("评估完成: completeness={}, isComplete={}, todos={}, followUps={}",
                completeness, isComplete, todos.size, followUpQuestions.size)

            EvaluationResult(
                completeness = completeness,
                isComplete = isComplete,
                summary = summary,
                todos = todos,
                followUpQuestions = followUpQuestions,
                confidence = confidence
            )

        } catch (e: Exception) {
            logger.error("解析评估结果失败", e)
            EvaluationResult.failure("解析失败: ${e.message}")
        }
    }

    /**
     * 从响应中提取 JSON
     */
    private fun extractJson(response: String): String? {
        val trimmed = response.trim()

        // 尝试直接解析
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        // 尝试从 markdown 代码块提取
        val jsonBlockPattern = Regex("""```(?:json)?\s*([\s\S]*?)```""")
        val match = jsonBlockPattern.find(trimmed)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        // 尝试找第一个 { 和最后一个 }
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1)
        }

        return null
    }
}
