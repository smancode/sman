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
            // 1. 【新增】检测是否有工具调用记录
            val toolParts = response.parts.filterIsInstance<ToolPart>()
            val hasToolCalls = toolParts.isNotEmpty()

            if (!hasToolCalls) {
                logger.warn("没有工具调用记录，拒绝分析结果")
                return EvaluationResult(
                    completeness = 0.0,
                    isComplete = false,
                    summary = "没有执行代码扫描",
                    todos = emptyList(),
                    followUpQuestions = listOf(
                        "你必须先使用 read_file、find_file、grep_file 等工具扫描项目代码，然后才能输出分析报告。不要直接输出文字，必须先调用工具。"
                    ),
                    confidence = 0.2
                )
            }

            // 2. 提取响应内容
            val responseContent = extractResponseContent(response)

            if (responseContent.isBlank()) {
                logger.warn("响应内容为空，返回失败评估")
                return EvaluationResult.failure("响应内容为空")
            }

            // 3. 【防呆】检测内容质量
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

            // 4. 构建评估提示词
            val systemPrompt = buildSystemPrompt()
            val userPrompt = buildUserPrompt(goal, responseContent, threshold)

            // 5. 调用 LLM 评估
            logger.info("开始评估分析结果: goal={}, threshold={}", goal.type.key, threshold)
            val llmResponse = llmService.simpleRequest(systemPrompt, userPrompt)

            if (llmResponse.isNullOrBlank()) {
                logger.warn("LLM 评估响应为空")
                return EvaluationResult.failure("LLM 评估响应为空")
            }

            // 6. 解析评估结果
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
     * - 只有"我将"没有实际结果
     *
     * 【增强】更严格的检测规则
     */
    private fun checkContentQuality(content: String, goal: ArchitectGoal): QualityCheckResult {
        val cleanContent = content.trim()

        // 0. 【新增】检测是否是纯对话式回复（最常见的问题）
        val conversationalPatterns = listOf(
            // 等待用户输入的问候语
            Regex("""请问[您你][想做想让我做了解]*.*[？?]",?"""),
            Regex("""请告诉我[你的]*需求"""),
            Regex("""我可以帮你"""),
            Regex("""请问有什么可以帮[您你]"""),
            Regex("""还是有其他需求"""),
            Regex("""你好[！!]我是"""),
            // 【关键】"我将/让我/我来"开头但没有实际内容
            Regex("""^[\s]*[让我我将我来][^。]*[。]?$""", RegexOption.MULTILINE),
            Regex("""我将按照.*执行"""),
            Regex("""让我[为帮]?[您你]?分析"""),
            Regex("""我来[为帮]?[您你]?.*分析""")
        )

        for (pattern in conversationalPatterns) {
            if (pattern.containsMatchIn(cleanContent)) {
                // 额外检查：如果内容主要是这种模式，拒绝
                val nonConversational = pattern.replace(cleanContent, "").trim()
                if (nonConversational.length < 200) {
                    return QualityCheckResult(
                        isAcceptable = false,
                        reason = "内容是对话式回复而非分析报告",
                        followUpQuestion = buildStrictFollowUp(goal)
                    )
                }
            }
        }

        // 【新增】检测是否是基于假设的编造内容
        val hallucinationPatterns = listOf(
            // 假设项目名称（常见的开源项目名）
            Regex("""RuoYi[\-\s]?Vue[\-\s]?Pro""", RegexOption.IGNORE_CASE),
            Regex("""若依"""),
            // 假设模块名（常见于模板内容）
            Regex("""ruoyi-admin|ruoyi-file|ruoyi-monitor|ruoyi-gateway"""),
            // 假设类名（常见于编造内容）
            Regex("""SysUserController|SysRoleController|SysMenuController|SysDeptController""")
        )

        val foundHallucinations = hallucinationPatterns.filter { it.containsMatchIn(cleanContent) }
        if (foundHallucinations.isNotEmpty()) {
            return QualityCheckResult(
                isAcceptable = false,
                reason = "内容包含假设/编造的元素（如假设项目名、假设类名等），必须基于实际扫描的代码生成报告",
                followUpQuestion = buildStrictFollowUp(goal) + "\n\n**重要**：你必须基于实际扫描到的文件和代码生成报告，禁止假设项目名、类名或技术栈。如果信息不足，请说明'信息不足'。"
            )
        }

        // 1. 检测是否只有思考块
        val withoutThink = cleanContent
            .replace(Regex("<think[^>]*>[\\s\\S]*?</think&gt;", RegexOption.IGNORE_CASE), "")
            .replace(Regex("&lt;think[^&]*&gt;[\\s\\S]*?&lt;/think&gt;"), "")
            .replace(Regex("<think[^>]*>[\\s\\S]*?</think[^>]*>", RegexOption.IGNORE_CASE), "")
            .trim()

        // 【增强】提高阈值：从 100 提高到 300
        if (withoutThink.length < 300) {
            return QualityCheckResult(
                isAcceptable = false,
                reason = "内容过短（<300字符）或只有思考块",
                followUpQuestion = buildStrictFollowUp(goal)
            )
        }

        // 2. 检测是否是等待用户输入的问候语（增强版）
        val greetingPatterns = listOf(
            "请告诉我你的需求",
            "请描述你的需求",
            "我可以帮你",
            "请问有什么可以帮您",
            "请先配置",
            "你好！我是",
            "请问您想做什么",
            "请问你想做什么",
            "请问你想让我做什么",
            "请问您想让我做什么",
            "请问你想了解",
            "还是有其他需求",
            "请问您需要我做什么",
            "请问有什么需要我帮助"
        )

        for (pattern in greetingPatterns) {
            if (cleanContent.contains(pattern)) {
                return QualityCheckResult(
                    isAcceptable = false,
                    reason = "内容是问候语而非分析结果",
                    followUpQuestion = buildStrictFollowUp(goal)
                )
            }
        }

        // 3. 【增强】检测是否有实际的分析内容
        // 必须有 Markdown 标题（## 或 ###）才算有效
        val hasMarkdownTitle = cleanContent.contains(Regex("""^#{1,3}\s+.+""", RegexOption.MULTILINE))
        val hasTable = cleanContent.contains("|") && cleanContent.contains("---")
        val hasList = cleanContent.contains(Regex("""^[\-\*]\s+.+""", RegexOption.MULTILINE))

        if (!hasMarkdownTitle && !hasTable && !hasList) {
            return QualityCheckResult(
                isAcceptable = false,
                reason = "内容缺乏 Markdown 结构化格式（没有标题、表格或列表）",
                followUpQuestion = buildStrictFollowUp(goal)
            )
        }

        // 4. 【新增】检测是否有报告的关键部分
        // 好的分析报告应该有"概述"、"总结"或类似结构
        val reportKeywords = listOf("概述", "总结", "分析", "模块", "结构", "配置", "入口", "实体", "枚举")
        val hasReportContent = reportKeywords.any { cleanContent.contains(it) }

        if (!hasReportContent && withoutThink.length < 500) {
            return QualityCheckResult(
                isAcceptable = false,
                reason = "内容不包含分析报告的关键字",
                followUpQuestion = buildStrictFollowUp(goal)
            )
        }

        // 5. 【新增】检测是否有"未完成"标记但内容很少（LLM 可能放弃分析）
        if (cleanContent.contains("TODO") || cleanContent.contains("待分析")) {
            // 如果有 TODO，检查实际内容是否足够
            val actualContent = cleanContent
                .replace(Regex("TODO[：:].*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("待分析[：:].*"), "")
                .trim()

            if (actualContent.length < 200) {
                return QualityCheckResult(
                    isAcceptable = false,
                    reason = "内容主要是 TODO 标记，缺乏实际分析",
                    followUpQuestion = buildStrictFollowUp(goal)
                )
            }
        }

        return QualityCheckResult(isAcceptable = true)
    }

    /**
     * 构建严格的追问（强制工具调用）
     */
    private fun buildStrictFollowUp(goal: ArchitectGoal): String {
        return """
你是自动化分析架构师，正在后台执行分析任务。**这是无人值守的自动化流程**。

## 强制规则（必须严格遵守）

1. **必须先调用工具**：在输出任何文字之前，必须先调用 `read_file`、`find_file`、`grep_file` 等工具扫描项目代码
2. **禁止对话式回复**：不要说"你好"、"请问"、"我可以帮你"等对话内容
3. **禁止等待用户**：不要说"请问你想了解什么"等等待用户输入的内容
4. **禁止思考陈述**：不要说"我将按照"、"让我来分析"等思考过程
5. **直接输出报告**：完成工具调用后，直接输出 Markdown 格式的分析报告

## 正确流程

```
步骤 1: 调用 find_file 查找相关文件
步骤 2: 调用 read_file 读取文件内容
步骤 3: 调用 grep_file 搜索关键模式（如需要）
步骤 4: 直接输出 Markdown 格式的分析报告
```

## 你的任务

执行 **${goal.type.displayName}** 分析任务。

如果你再次输出对话式内容而不是分析报告，任务将失败并重试。
        """.trimIndent()
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
