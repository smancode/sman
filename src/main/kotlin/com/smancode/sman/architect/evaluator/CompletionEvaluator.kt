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

            // 2. 构建评估提示词
            val systemPrompt = buildSystemPrompt()
            val userPrompt = buildUserPrompt(goal, responseContent, threshold)

            // 3. 调用 LLM 评估
            logger.info("开始评估分析结果: goal={}, threshold={}", goal.type.key, threshold)
            val llmResponse = llmService.simpleRequest(systemPrompt, userPrompt)

            if (llmResponse.isNullOrBlank()) {
                logger.warn("LLM 评估响应为空")
                return EvaluationResult.failure("LLM 评估响应为空")
            }

            // 4. 解析评估结果
            parseEvaluationResult(llmResponse, threshold)

        } catch (e: Exception) {
            logger.error("评估失败", e)
            EvaluationResult.failure("评估异常: ${e.message}")
        }
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
        return """
<task>
Evaluate the following analysis result for completeness and quality.
</task>

<goal>
Type: ${goal.type.displayName}
Description: ${goal.type.key}
Target Completeness Threshold: ${(threshold * 100).toInt()}%
</goal>

${if (goal.followUpQuestions.isNotEmpty()) {
    "<follow_up_questions>\n" + goal.followUpQuestions.mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n") + "\n</follow_up_questions>\n"
} else ""}

<analysis_result>
$responseContent
</analysis_result>

<instructions>
1. Analyze the completeness (0.0 - 1.0) based on evaluation criteria
2. Determine if it meets the threshold ($threshold)
3. If incomplete, list remaining TODOs with priority
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
