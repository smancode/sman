package com.smancode.sman.evolution.recorder

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.analysis.database.TieredVectorStore
import com.smancode.sman.analysis.model.VectorFragment
import com.smancode.sman.analysis.vectorization.BgeM3Client
import com.smancode.sman.evolution.memory.LearningRecordRepository
import com.smancode.sman.evolution.model.LearningRecord
import com.smancode.sman.evolution.model.QuestionType
import com.smancode.sman.evolution.model.ToolCallStep
import com.smancode.sman.smancode.llm.LlmService
import org.slf4j.LoggerFactory
import java.util.*

// ==================== 输入模型 ====================

/**
 * 生成的问题 - 问题生成器产生的探索目标
 *
 * @property question 问题内容
 * @property type 问题类型
 * @property priority 优先级 (1-10)
 * @property reason 为什么这是个好问题
 * @property suggestedTools 建议使用的工具列表
 * @property expectedOutcome 预期学到的内容
 */
data class GeneratedQuestion(
    val question: String,
    val type: QuestionType,
    val priority: Int,
    val reason: String,
    val suggestedTools: List<String> = emptyList(),
    val expectedOutcome: String = ""
)

/**
 * 探索上下文 - 记录探索过程中的状态
 *
 * @property question 当前探索的问题
 * @property previousSteps 已执行的步骤
 * @property accumulatedKnowledge 累积的知识
 */
data class ExplorationContext(
    val question: GeneratedQuestion,
    val previousSteps: List<ToolCallStep> = emptyList(),
    val accumulatedKnowledge: String = ""
)

/**
 * 探索结果 - 工具探索器执行后的输出
 *
 * @property question 探索的问题
 * @property steps 探索步骤列表
 * @property success 是否成功
 * @property error 错误信息（如果失败）
 * @property finalContext 最终上下文
 */
data class ExplorationResult(
    val question: GeneratedQuestion,
    val steps: List<ToolCallStep>,
    val success: Boolean,
    val error: String? = null,
    val finalContext: ExplorationContext? = null
)

// ==================== 内部总结结果 ====================

/**
 * LLM 总结结果
 */
private data class SummaryResult(
    val answer: String,
    val confidence: Double,
    val sourceFiles: List<String>,
    val tags: List<String>,
    val domain: String?
)

// ==================== 学习记录器 ====================

/**
 * 学习记录器 - 总结并持久化学习成果
 *
 * 职责：
 * 1. 调用 LLM 总结探索过程中学到的内容
 * 2. 将学习记录持久化到数据库
 * 3. 将问题和答案向量化存储到 TieredVectorStore
 *
 * @property llmService LLM 调用服务
 * @property repository 学习记录仓储
 * @property bgeM3Client 向量化客户端
 * @property vectorStore 向量存储服务
 * @property projectKey 当前项目 key
 */
class LearningRecorder(
    private val llmService: LlmService,
    private val repository: LearningRecordRepository,
    private val bgeM3Client: BgeM3Client,
    private val vectorStore: TieredVectorStore,
    private val projectKey: String
) {
    private val logger = LoggerFactory.getLogger(LearningRecorder::class.java)
    private val objectMapper = ObjectMapper()

    /**
     * 总结学习成果
     *
     * 调用 LLM 将探索过程转化为结构化的知识记录。
     *
     * @param question 探索的问题
     * @param explorationResult 探索结果
     * @return 学习记录
     * @throws IllegalArgumentException 如果参数不合法
     * @throws RuntimeException 如果 LLM 调用失败或解析失败
     */
    fun summarize(
        question: GeneratedQuestion,
        explorationResult: ExplorationResult
    ): LearningRecord {
        // 参数校验
        require(question.question.isNotBlank()) { "问题内容不能为空" }
        require(explorationResult.success) { "探索结果必须是成功的才能总结" }
        require(explorationResult.steps.isNotEmpty()) { "探索步骤不能为空" }

        logger.info("开始总结学习成果: question={}", question.question)

        // 1. 构建总结 Prompt
        val prompt = buildSummaryPrompt(question, explorationResult)

        // 2. 调用 LLM 总结
        val response = llmService.jsonRequest(SUMMARY_SYSTEM_PROMPT, prompt)

        // 3. 解析总结结果
        val summary = parseSummary(response)

        // 4. 构建学习记录
        val record = LearningRecord(
            id = generateId(),
            projectKey = projectKey,
            createdAt = System.currentTimeMillis(),
            question = question.question,
            questionType = question.type,
            answer = summary.answer,
            explorationPath = explorationResult.steps,
            confidence = summary.confidence,
            sourceFiles = summary.sourceFiles,
            relatedRecords = emptyList(),
            tags = summary.tags,
            domain = summary.domain
        )

        logger.info("学习成果总结完成: id={}, confidence={}", record.id, record.confidence)
        return record
    }

    /**
     * 持久化学习记录
     *
     * 将学习记录保存到数据库，并将问题和答案向量化存储到 TieredVectorStore。
     *
     * @param record 学习记录
     * @throws IllegalArgumentException 如果 record 无效
     */
    suspend fun save(record: LearningRecord) {
        require(record.id.isNotBlank()) { "学习记录 id 不能为空" }
        require(record.projectKey.isNotBlank()) { "学习记录 projectKey 不能为空" }
        require(record.question.isNotBlank()) { "学习记录 question 不能为空" }
        require(record.answer.isNotBlank()) { "学习记录 answer 不能为空" }

        // 1. 保存到数据库
        repository.save(record)
        logger.info("学习记录已保存: id={}, question={}", record.id, record.question)

        // 2. 向量化并存储
        vectorizeAndStore(record)
    }

    /**
     * 向量化问题和答案，并存入 TieredVectorStore
     *
     * @param record 学习记录
     */
    private fun vectorizeAndStore(record: LearningRecord) {
        try {
            // 向量化问题
            val questionVector = bgeM3Client.embed(record.question, "learning-${record.id}-question")
            val questionFragment = VectorFragment(
                id = "learning:${record.id}:question",
                title = record.question.take(100),
                content = record.question,
                fullContent = record.question,
                tags = buildList {
                    add("learning")
                    add("question")
                    record.domain?.let { add(it) }
                    addAll(record.tags)
                },
                metadata = mapOf(
                    "recordId" to record.id,
                    "projectKey" to record.projectKey,
                    "type" to "question",
                    "questionType" to record.questionType.name,
                    "createdAt" to record.createdAt.toString()
                ),
                vector = questionVector
            )
            vectorStore.add(questionFragment)
            logger.debug("问题向量已存储: id={}", questionFragment.id)

            // 向量化答案
            val answerVector = bgeM3Client.embed(record.answer, "learning-${record.id}-answer")
            val answerFragment = VectorFragment(
                id = "learning:${record.id}:answer",
                title = record.answer.take(100),
                content = record.answer,
                fullContent = record.answer,
                tags = buildList {
                    add("learning")
                    add("answer")
                    record.domain?.let { add(it) }
                    addAll(record.tags)
                },
                metadata = mapOf(
                    "recordId" to record.id,
                    "projectKey" to record.projectKey,
                    "type" to "answer",
                    "questionType" to record.questionType.name,
                    "confidence" to record.confidence.toString(),
                    "createdAt" to record.createdAt.toString()
                ),
                vector = answerVector
            )
            vectorStore.add(answerFragment)
            logger.debug("答案向量已存储: id={}", answerFragment.id)

            logger.info("学习记录向量化完成: id={}, questionId={}, answerId={}",
                record.id, questionFragment.id, answerFragment.id)
        } catch (e: Exception) {
            logger.error("学习记录向量化失败: id={}, error={}", record.id, e.message, e)
            // 向量化失败不影响主流程，记录已保存到数据库
        }
    }

    /**
     * 总结并保存（便捷方法）
     *
     * @param question 探索的问题
     * @param explorationResult 探索结果
     * @return 保存后的学习记录
     */
    suspend fun summarizeAndSave(
        question: GeneratedQuestion,
        explorationResult: ExplorationResult
    ): LearningRecord {
        val record = summarize(question, explorationResult)
        save(record)
        return record
    }

    // ========== 私有方法 ==========

    /**
     * 构建总结 Prompt
     *
     * 使用混合架构 Prompt 设计：
     * - 英文指令 + 中文标题视觉锚定
     * - XML 结构化数据容器
     * - JSON Schema 类型提示
     */
    private fun buildSummaryPrompt(
        question: GeneratedQuestion,
        result: ExplorationResult
    ): String {
        val stepsDescription = result.steps.mapIndexed { i, s ->
            "### Step ${i + 1}: ${s.toolName}\nParameters: ${s.parameters}\nResult: ${s.resultSummary}"
        }.joinToString("\n\n")

        return """
# Configuration
<system_config>
    <language_rule>
        <input_processing>English (For logic & reasoning)</input_processing>
        <final_output>Simplified Chinese (For user readability)</final_output>
    </language_rule>
</system_config>

# Context
<exploration_context>
    <question>${escapeXml(question.question)}</question>
    <question_type>${question.type}</question_type>
    <reason>${escapeXml(question.reason)}</reason>
</exploration_context>

# Exploration Process
<exploration_steps>
$stepsDescription
</exploration_steps>

# Accumulated Knowledge
<accumulated_knowledge>
${result.finalContext?.accumulatedKnowledge ?: "无"}
</accumulated_knowledge>

# Task
Based on the exploration process above, summarize what you have learned.

## Output Format (Strict JSON)
```json
{
  "answer": "String (Write in Chinese: 简洁的答案，总结探索发现的核心内容)",
  "confidence": "Number (0.0-1.0: 保守估计置信度，基于探索的完整性和信息质量)",
  "sourceFiles": ["Array of Strings: 涉及的源文件路径列表"],
  "tags": ["Array of Strings: 用于后续检索的标签，如 '还款', '订单状态', '调用链'"],
  "domain": "String (所属业务领域，如: 还款、订单、支付、配置)"
}
```

# Rules
<anti_hallucination_rules>
1. **Strict Grounding**: You are FORBIDDEN from inventing information not found in the exploration steps.
2. **Language Decoupling**:
   - Content MUST be in Simplified Chinese.
   - **Exception**: Keep technical terms (e.g., "Service", "Repository", "DTO") in English.
3. **Conservative Confidence**: If information is incomplete, set confidence accordingly (not above 0.7).
</anti_hallucination_rules>

# Interaction Protocol
1. **Analyze First**: Inside <thinking> tags, analyze what was discovered in the exploration.
2. **Final Output**: After closing </thinking>, generate the JSON result.
        """.trimIndent()
    }

    /**
     * 解析 LLM 返回的 JSON 总结结果
     */
    private fun parseSummary(jsonNode: JsonNode): SummaryResult {
        val answer = jsonNode.path("answer").asText()
        if (answer.isNullOrBlank()) {
            throw RuntimeException("LLM 返回的总结缺少 answer 字段")
        }

        val confidence = jsonNode.path("confidence").asDouble(0.5).coerceIn(0.0, 1.0)

        val sourceFiles = jsonNode.path("sourceFiles")
            .takeIf { it.isArray }
            ?.map { it.asText() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val tags = jsonNode.path("tags")
            .takeIf { it.isArray }
            ?.map { it.asText() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val domain = jsonNode.path("domain").takeIf { !it.isMissingNode }?.asText()

        return SummaryResult(
            answer = answer,
            confidence = confidence,
            sourceFiles = sourceFiles,
            tags = tags,
            domain = domain
        )
    }

    /**
     * 生成唯一 ID
     */
    private fun generateId(): String {
        return "lr-${UUID.randomUUID()}"
    }

    /**
     * 转义 XML 特殊字符
     */
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    companion object {
        /**
         * 总结系统提示词
         *
         * 使用英文定义角色，确保逻辑理解准确
         */
        private val SUMMARY_SYSTEM_PROMPT = """
You are a knowledge summarization expert specializing in extracting key insights from code exploration processes.

Your core competencies:
1. Synthesizing information from multiple tool calls into coherent knowledge
2. Identifying patterns and relationships in code
3. Estimating confidence based on completeness and quality of exploration
4. Creating searchable tags for future retrieval

Guidelines:
1. Answers should be concise and accurate - aim for 2-4 sentences
2. Confidence should be conservative - incomplete information warrants lower scores
3. Tags should help future retrieval - think about what keywords someone would search
4. Domain classification should be accurate - when uncertain, use "通用" (general)
        """.trimIndent()
    }
}
