package com.smancode.sman.evolution.generator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.evolution.loop.EvolutionConfig
import com.smancode.sman.evolution.model.QuestionType
import com.smancode.sman.smancode.llm.LlmService
import org.slf4j.LoggerFactory

/**
 * 生成的问题 - LLM 生成的好问题
 *
 * 由 QuestionGenerator 生成，包含问题的详细信息和建议的探索策略。
 *
 * @property question 问题内容
 * @property type 问题类型
 * @property priority 优先级 (1-10，10 最高)
 * @property reason 为什么这是个好问题
 * @property suggestedTools 建议使用的工具列表
 * @property expectedOutcome 预期学到的内容
 */
data class GeneratedQuestion(
    val question: String,
    val type: QuestionType,
    val priority: Int,
    val reason: String,
    val suggestedTools: List<String>,
    val expectedOutcome: String
) {
    companion object {
        /**
         * 从 JSON 节点解析 GeneratedQuestion
         */
        fun fromJson(node: JsonNode): GeneratedQuestion {
            val questionText = node.path("question").asText()
            val typeStr = node.path("type").asText()
            val priority = node.path("priority").asInt()
            val reason = node.path("reason").asText()
            val suggestedTools = node.path("suggestedTools")
                .map { it.asText() }
            val expectedOutcome = node.path("expectedOutcome").asText()

            // 解析问题类型
            val questionType = try {
                QuestionType.valueOf(typeStr)
            } catch (e: IllegalArgumentException) {
                // 默认使用 BUSINESS_LOGIC
                QuestionType.BUSINESS_LOGIC
            }

            return GeneratedQuestion(
                question = questionText,
                type = questionType,
                priority = priority.coerceIn(1, 10),
                reason = reason,
                suggestedTools = suggestedTools,
                expectedOutcome = expectedOutcome
            )
        }
    }
}

/**
 * 问题生成器 - 让 LLM 生成好问题
 *
 * 核心职责：
 * 1. 基于项目上下文构建问题生成 Prompt
 * 2. 调用 LLM 生成好问题
 * 3. 解析 LLM 返回的 JSON 响应
 *
 * 设计要点：
 * - 好问题关注"为什么"和"如何"，而不是"是什么"
 * - 好问题关注模块间的关联和依赖
 * - 好问题关注业务逻辑的流程
 * - 向量去重由 DoomLoopGuard 处理，不在此实现
 *
 * @property llmService LLM 调用服务
 * @property config 进化配置
 */
class QuestionGenerator(
    private val llmService: LlmService,
    private val config: EvolutionConfig
) {
    private val logger = LoggerFactory.getLogger(QuestionGenerator::class.java)
    private val objectMapper = ObjectMapper()

    /**
     * 问题生成请求参数
     *
     * @property projectKey 项目键
     * @property techStack 技术栈信息
     * @property domains 已知领域列表
     * @property recentQuestions 最近探索过的问题（用于避免重复）
     * @property knowledgeGaps 知识盲点描述
     * @property count 期望生成的问题数量
     */
    data class QuestionGenerationRequest(
        val projectKey: String,
        val techStack: String = "Unknown",
        val domains: List<String> = emptyList(),
        val recentQuestions: List<String> = emptyList(),
        val knowledgeGaps: List<String> = emptyList(),
        val count: Int = 3
    )

    /**
     * 生成好问题
     *
     * @param request 问题生成请求参数
     * @return 生成的问题列表（按优先级降序排列）
     */
    fun generate(request: QuestionGenerationRequest): List<GeneratedQuestion> {
        logger.info("开始生成问题: projectKey={}, count={}", request.projectKey, request.count)

        // 参数校验
        require(request.projectKey.isNotEmpty()) { "缺少 projectKey 参数" }
        require(request.count > 0) { "count 必须大于 0" }

        // 构建用户提示词
        val userPrompt = buildUserPrompt(request)

        try {
            // 调用 LLM（使用 jsonRequest 确保返回 JSON）
            val jsonResponse = llmService.jsonRequest(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = userPrompt
            )

            // 解析响应
            val questions = parseQuestions(jsonResponse)

            // 按优先级降序排序
            val sortedQuestions = questions.sortedByDescending { it.priority }

            logger.info("成功生成 {} 个问题", sortedQuestions.size)
            sortedQuestions.forEach { q ->
                logger.debug("生成问题: priority={}, type={}, question={}",
                    q.priority, q.type, q.question)
            }

            return sortedQuestions.take(request.count)

        } catch (e: Exception) {
            logger.error("问题生成失败: {}", e.message, e)
            throw RuntimeException("问题生成失败: ${e.message}", e)
        }
    }

    /**
     * 构建用户提示词
     */
    private fun buildUserPrompt(request: QuestionGenerationRequest): String {
        return """
<project_context>
    <tech_stack>${request.techStack}</tech_stack>
    <known_domains>
        ${if (request.domains.isEmpty()) "暂无" else request.domains.joinToString("\n        ") { "- $it" }}
    </known_domains>
</project_context>

<constraints>
    <count>${request.count}</count>
    <avoid_duplicates>
        ${if (request.recentQuestions.isEmpty()) "无" else request.recentQuestions.take(10).joinToString("\n        ") { "- $it" }}
    </avoid_duplicates>
</constraints>

<knowledge_gaps>
    ${if (request.knowledgeGaps.isEmpty()) "暂无明确盲点" else request.knowledgeGaps.take(10).joinToString("\n    ") { "- $it" }}
</knowledge_gaps>

<task>
Based on the above context, generate ${request.count} good questions for code exploration.

Good questions should:
1. Focus on "how" and "why", not just "what"
2. Explore relationships between modules
3. Reveal business logic flows
4. Discover design patterns and architecture decisions
5. Not duplicate questions in <avoid_duplicates>
</task>

<output_format>
Output MUST be valid JSON in this exact format:

```json
{
  "questions": [
    {
      "question": "问题内容 (中文)",
      "type": "BUSINESS_LOGIC",
      "priority": 8,
      "reason": "为什么这是个好问题 (中文)",
      "suggestedTools": ["expert_consult", "read_file"],
      "expectedOutcome": "预期学到的内容 (中文)"
    }
  ]
}
```

Valid type values: CODE_STRUCTURE, BUSINESS_LOGIC, DATA_FLOW, DEPENDENCY, CONFIGURATION, ERROR_ANALYSIS, BEST_PRACTICE, DOMAIN_KNOWLEDGE
</output_format>
        """.trimIndent()
    }

    /**
     * 解析 LLM 返回的问题列表
     */
    private fun parseQuestions(jsonResponse: JsonNode): List<GeneratedQuestion> {
        val questionsNode = jsonResponse.path("questions")

        if (!questionsNode.isArray || questionsNode.size() == 0) {
            logger.warn("LLM 返回的问题列表为空或格式错误")
            return emptyList()
        }

        return questionsNode.map { node ->
            try {
                GeneratedQuestion.fromJson(node)
            } catch (e: Exception) {
                logger.warn("解析问题失败: {}", e.message)
                null
            }
        }.filterNotNull()
    }

    companion object {
        /**
         * 问题生成系统提示词
         *
         * 设计要点：
         * - 使用英文指令确保逻辑清晰
         * - 强调生成"好问题"的标准
         * - 明确输出格式要求
         */
        private val SYSTEM_PROMPT = """
You are a senior code analyst specializing in understanding software projects through strategic questioning.

<role>
Your goal is to generate high-quality questions that drive meaningful code exploration and learning.
</role>

<good_question_criteria>
1. **Depth over breadth**: Prefer questions that reveal deep understanding over surface-level facts
2. **Relationships**: Focus on connections between components, not isolated elements
3. **Business value**: Prioritize questions that explain business logic and domain concepts
4. **Architecture insights**: Seek understanding of design decisions and patterns
5. **Practical knowledge**: Questions should lead to actionable understanding
</good_question_criteria>

<anti_patterns>
- Avoid simple "what is" questions that can be answered by reading code directly
- Avoid questions about obvious facts (class names, method signatures)
- Avoid repeating questions that were already explored
- Avoid questions that are too broad or too narrow
</anti_patterns>

<language_rule>
    <input_processing>English (For logic & reasoning)</input_processing>
    <final_output>JSON with Chinese content for question, reason, and expectedOutcome fields</final_output>
</language_rule>

<anti_hallucination_rules>
1. **Strict Grounding**: Base questions ONLY on the provided context
2. **No Invention**: Do NOT invent features or components not mentioned
3. **Realistic Scope**: Questions should be answerable through code exploration
</anti_hallucination_rules>
        """.trimIndent()
    }
}
