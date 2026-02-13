package com.smancode.sman.evolution.explorer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.evolution.generator.GeneratedQuestion
import com.smancode.sman.evolution.loop.EvolutionConfig
import com.smancode.sman.evolution.model.ToolCallStep
import com.smancode.sman.smancode.llm.LlmService
import com.smancode.sman.tools.ToolRegistry
import com.smancode.sman.tools.ToolResult
import org.slf4j.LoggerFactory

/**
 * 探索上下文 - 保存探索过程中的状态
 *
 * 跟踪已执行的步骤和累积的知识，用于 LLM 决策下一步行动。
 *
 * @property question 当前探索的问题
 * @property projectKey 项目键
 * @property previousSteps 已执行的工具调用步骤
 * @property accumulatedKnowledge 累积获得的知识
 */
data class ExplorationContext(
    val question: GeneratedQuestion,
    val projectKey: String,
    val previousSteps: List<ToolCallStep> = emptyList(),
    val accumulatedKnowledge: String = ""
)

/**
 * 探索结果 - 探索完成后的返回值
 *
 * 包含探索是否成功、执行的步骤、最终的上下文和错误信息。
 *
 * @property question 探索的问题
 * @property projectKey 项目键
 * @property steps 执行的工具调用步骤列表
 * @property success 是否成功
 * @property error 错误信息（失败时）
 * @property finalContext 最终的探索上下文（成功时）
 */
data class ExplorationResult(
    val question: GeneratedQuestion,
    val projectKey: String,
    val steps: List<ToolCallStep>,
    val success: Boolean,
    val error: String? = null,
    val finalContext: ExplorationContext? = null
)

/**
 * 下一步行动 - LLM 决策的结果
 *
 * 描述探索过程中的下一步行动，包括是否停止、使用的工具和参数。
 *
 * @property shouldStop 是否应该停止探索
 * @property toolName 工具名称（继续探索时）
 * @property parameters 工具参数（继续探索时）
 * @property reason 选择该行动的原因
 */
data class NextAction(
    val shouldStop: Boolean,
    val toolName: String? = null,
    val parameters: Map<String, Any> = emptyMap(),
    val reason: String? = null
)

/**
 * 工具探索器 - 执行工具调用来探索问题
 *
 * 核心职责：
 * 1. 调用 LLM 决定下一步行动
 * 2. 执行工具调用获取信息
 * 3. 更新探索上下文
 * 4. 判断探索是否足够
 *
 * 设计要点：
 * - LLM 驱动：每一步都由 LLM 决策
 * - 工具复用：复用现有工具集（expert_consult, read_file, grep_file, find_file, call_chain）
 * - 步数限制：最多探索 maxSteps 步
 * - 知识累积：每一步的结果累积到上下文中
 *
 * @property toolRegistry 工具注册中心
 * @property llmService LLM 调用服务
 * @property config 进化配置
 */
class ToolExplorer(
    private val toolRegistry: ToolRegistry,
    private val llmService: LlmService,
    private val config: EvolutionConfig
) {
    private val logger = LoggerFactory.getLogger(ToolExplorer::class.java)
    private val objectMapper = ObjectMapper()

    /**
     * 探索问题
     *
     * 通过 LLM 决策和工具调用，逐步探索问题，直到获得足够的知识或达到最大步数。
     *
     * @param question 要探索的问题
     * @param projectKey 项目键
     * @return 探索结果
     */
    fun explore(question: GeneratedQuestion, projectKey: String): ExplorationResult {
        logger.info("开始探索: question={}, projectKey={}", question.question.take(50), projectKey)

        // 参数校验
        require(projectKey.isNotEmpty()) { "缺少 projectKey 参数" }

        val steps = mutableListOf<ToolCallStep>()
        var currentContext = ExplorationContext(
            question = question,
            projectKey = projectKey
        )

        try {
            // 最多探索 maxExplorationSteps 步
            repeat(config.maxExplorationSteps) { stepIndex ->
                logger.debug("探索步骤 {}/{}", stepIndex + 1, config.maxExplorationSteps)

                // 1. 决定下一步行动 (LLM 决策)
                val nextAction = decideNextAction(currentContext)

                if (nextAction == null || nextAction.shouldStop) {
                    logger.info("探索完成: 共 {} 步, 原因: {}", stepIndex, nextAction?.reason ?: "LLM 决定停止")
                    return@repeat
                }

                // 2. 执行工具调用
                val toolResult = executeTool(nextAction, projectKey)

                // 3. 记录步骤
                val step = ToolCallStep(
                    toolName = nextAction.toolName ?: "unknown",
                    parameters = nextAction.parameters.mapValues { it.value.toString() },
                    resultSummary = extractSummary(toolResult),
                    timestamp = System.currentTimeMillis()
                )
                steps.add(step)

                // 4. 更新上下文
                currentContext = currentContext.copy(
                    previousSteps = currentContext.previousSteps + step,
                    accumulatedKnowledge = buildAccumulatedKnowledge(
                        currentContext.accumulatedKnowledge,
                        step
                    )
                )

                // 5. 检查是否足够
                if (isExplorationSufficient(currentContext)) {
                    logger.info("探索已足够: 累积知识长度={}", currentContext.accumulatedKnowledge.length)
                    return@repeat
                }
            }

            logger.info("探索结束: 共 {} 步, 成功", steps.size)

            return ExplorationResult(
                question = question,
                projectKey = projectKey,
                steps = steps,
                success = true,
                finalContext = currentContext
            )

        } catch (e: Exception) {
            logger.error("探索失败: {}", e.message, e)
            return ExplorationResult(
                question = question,
                projectKey = projectKey,
                steps = steps,
                success = false,
                error = e.message
            )
        }
    }

    /**
     * 决定下一步行动 (LLM 决策)
     *
     * 基于当前上下文，让 LLM 决定下一步应该调用什么工具，或者是否应该停止探索。
     *
     * @param context 当前探索上下文
     * @return 下一步行动，如果 LLM 决定停止则返回 shouldStop=true
     */
    private fun decideNextAction(context: ExplorationContext): NextAction? {
        val userPrompt = buildExplorerPrompt(context)

        try {
            // 调用 LLM
            val jsonResponse = llmService.jsonRequest(
                systemPrompt = EXPLORER_SYSTEM_PROMPT,
                userPrompt = userPrompt
            )

            // 解析响应
            return parseNextAction(jsonResponse)

        } catch (e: Exception) {
            logger.error("LLM 决策失败: {}", e.message)
            // 决策失败时停止探索
            return NextAction(
                shouldStop = true,
                reason = "LLM 决策失败: ${e.message}"
            )
        }
    }

    /**
     * 构建探索决策 Prompt
     */
    private fun buildExplorerPrompt(context: ExplorationContext): String {
        val stepsDescription = if (context.previousSteps.isEmpty()) {
            "尚未执行任何步骤"
        } else {
            context.previousSteps.mapIndexed { i, s ->
                "${i + 1}. ${s.toolName}: ${s.resultSummary.take(200)}"
            }.joinToString("\n")
        }

        return """
<current_question>
${context.question.question}
</current_question>

<question_context>
    <type>${context.question.type}</type>
    <expected_outcome>${context.question.expectedOutcome}</expected_outcome>
</question_context>

<previous_steps>
$stepsDescription
</previous_steps>

<accumulated_knowledge>
${context.accumulatedKnowledge.take(1000)}
</accumulated_knowledge>

<suggested_tools>
${context.question.suggestedTools.joinToString(", ")}
</suggested_tools>

<available_tools>
- expert_consult: 语义搜索相关代码和知识（参数: query, topK）
- read_file: 读取文件内容（参数: filePath）
- grep_file: 正则搜索文件内容（参数: pattern, path）
- find_file: 按文件名查找（参数: pattern）
- call_chain: 分析调用关系（参数: methodSignature）
</available_tools>

<task>
Based on the current context, decide the next action.

Decision criteria:
1. If accumulated knowledge is sufficient to answer the question, set shouldStop=true
2. If you need more information, select an appropriate tool from available_tools
3. Each step should have a clear purpose
4. Avoid repeating the same information gathering

Output MUST be valid JSON in this exact format:

```json
{
  "shouldStop": false,
  "toolName": "expert_consult",
  "parameters": {
    "query": "search query here",
    "topK": 5
  },
  "reason": "Why this tool and parameters were chosen (in Chinese)"
}
```

If shouldStop is true, the output should be:
```json
{
  "shouldStop": true,
  "reason": "Why exploration is complete (in Chinese)"
}
```
        """.trimIndent()
    }

    /**
     * 解析 LLM 返回的下一步行动
     */
    private fun parseNextAction(jsonResponse: JsonNode): NextAction {
        val shouldStop = jsonResponse.path("shouldStop").asBoolean(false)
        val reason = jsonResponse.path("reason").asText()

        if (shouldStop) {
            return NextAction(
                shouldStop = true,
                reason = reason
            )
        }

        val toolName = jsonResponse.path("toolName").asText()
        val parametersNode = jsonResponse.path("parameters")

        val parameters = if (parametersNode.isObject) {
            objectMapper.convertValue(parametersNode, Map::class.java)
                .filterKeys { it is String }
                .mapKeys { it.key.toString() }
                .mapValues { it.value ?: "" }
        } else {
            emptyMap()
        }

        return NextAction(
            shouldStop = false,
            toolName = toolName,
            parameters = parameters,
            reason = reason
        )
    }

    /**
     * 执行工具调用
     *
     * @param action 下一步行动
     * @param projectKey 项目键
     * @return 工具执行结果
     */
    private fun executeTool(action: NextAction, projectKey: String): ToolResult {
        val toolName = action.toolName

        if (toolName.isNullOrBlank()) {
            return ToolResult.failure("工具名称为空")
        }

        val tool = toolRegistry.getTool(toolName)
        if (tool == null) {
            logger.warn("工具不存在: {}", toolName)
            return ToolResult.failure("工具不存在: $toolName")
        }

        logger.info("执行工具: {}({})", toolName, action.parameters)

        return try {
            tool.execute(projectKey, action.parameters)
        } catch (e: Exception) {
            logger.error("工具执行失败: {} - {}", toolName, e.message)
            ToolResult.failure("工具执行失败: ${e.message}")
        }
    }

    /**
     * 从工具结果中提取摘要
     */
    private fun extractSummary(toolResult: ToolResult): String {
        val summary = toolResult.summary
        val displayContent = toolResult.displayContent

        return when {
            !toolResult.isSuccess -> "执行失败: ${toolResult.error}"
            !summary.isNullOrBlank() -> summary
            !displayContent.isNullOrBlank() -> displayContent.take(500)
            toolResult.data != null -> toolResult.data.toString().take(500)
            else -> "无返回内容"
        }
    }

    /**
     * 构建累积知识字符串
     */
    private fun buildAccumulatedKnowledge(
        currentKnowledge: String,
        newStep: ToolCallStep
    ): String {
        val newInfo = "[${newStep.toolName}] ${newStep.resultSummary}"
        return if (currentKnowledge.isEmpty()) {
            newInfo
        } else {
            "$currentKnowledge\n$newInfo"
        }
    }

    /**
     * 检查探索是否足够
     *
     * 简单判断：累积知识超过 2000 字符
     */
    private fun isExplorationSufficient(context: ExplorationContext): Boolean {
        return context.accumulatedKnowledge.length > KNOWLEDGE_THRESHOLD
    }

    companion object {
        /**
         * 知识阈值：累积知识超过此值认为探索足够
         */
        private const val KNOWLEDGE_THRESHOLD = 2000

        /**
         * 探索决策系统提示词
         *
         * 设计要点：
         * - 使用英文指令确保逻辑清晰
         * - 明确可用的工具和参数
         * - 强调每一步都要有明确目的
         */
        private val EXPLORER_SYSTEM_PROMPT = """
You are a code exploration expert specializing in gathering information through tool usage.

<role>
Your goal is to efficiently explore a code-related question by selecting and executing the most appropriate tools.
</role>

<principles>
1. **Purpose-driven**: Every tool call must have a clear, specific purpose
2. **No redundancy**: Do not gather the same information twice
3. **Efficiency**: Stop when sufficient information is collected
4. **Adaptive**: Adjust strategy based on what you discover
</principles>

<tool_selection_guide>
- **expert_consult**: Use when you need to find relevant code or knowledge through semantic search
- **read_file**: Use when you know the specific file path and need its content
- **grep_file**: Use when you need to search for patterns across files
- **find_file**: Use when you need to locate files by name pattern
- **call_chain**: Use when you need to understand method call relationships
</tool_selection_guide>

<stopping_criteria>
- The question has been fully answered
- Sufficient context has been gathered
- No more productive tools can be applied
- Reaching information that requires domain expertise not available in code
</stopping_criteria>

<language_rule>
    <input_processing>English (For logic & reasoning)</input_processing>
    <final_output>JSON with Chinese content for reason field</final_output>
</language_rule>

<anti_hallucination_rules>
1. **Strict Grounding**: Only use tools that are explicitly listed
2. **No Invention**: Do NOT invent tool names or parameters
3. **Parameter Validity**: Ensure parameters match the tool's expected format
</anti_hallucination_rules>
        """.trimIndent()
    }
}
