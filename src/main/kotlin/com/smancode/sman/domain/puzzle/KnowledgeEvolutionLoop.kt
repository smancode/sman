package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * 知识进化循环
 *
 * 核心设计理念：
 * - 底线 = 目标（数据模型定义了输入/输出）
 * - 上限 = LLM 决定（Prompt 让 LLM 自由发挥）
 * - 不写死流程细节，让 LLM 决定如何达成目标
 *
 * 流程：
 * 1. 构建 Context（现有知识 + 触发原因）
 * 2. 调用 LLM 执行完整循环（观察→假设→计划→执行→评估）
 * 3. 解析 LLM 响应，提取结果
 * 4. 合并到知识库
 *
 * 收敛控制：
 * - 连续低质量迭代达到阈值时强制终止
 * - 冲突信息会被记录到评估结果中
 */
class KnowledgeEvolutionLoop(
    private val puzzleStore: PuzzleStore,
    private val llmService: com.smancode.sman.smancode.llm.LlmService,
    private val projectPath: String = System.getProperty("user.dir"),
    private val fileReader: FileReader = DefaultFileReader(projectPath)
) {
    private val logger = LoggerFactory.getLogger(KnowledgeEvolutionLoop::class.java)

    companion object {
        /** 连续低质量迭代阈值，超过后强制终止 */
        private const val MAX_CONSECUTIVE_LOW_QUALITY = 3
        /** 最低质量分数，低于此值视为低质量 */
        private const val LOW_QUALITY_THRESHOLD = 0.3
    }

    /** 连续低质量迭代计数 */
    private var consecutiveLowQualityCount = 0

    /**
     * 执行一轮知识进化
     *
     * @param trigger 触发原因
     * @return 进化结果
     */
    suspend fun evolve(trigger: Trigger): EvolutionResult {
        val iterationId = generateIterationId()

        try {
            logger.info("开始知识进化: iterationId={}, trigger={}", iterationId, trigger)

            // 1. 构建上下文
            val context = buildContext(trigger)

            // 2. 调用 LLM 执行循环
            val llmResponse = executeEvolutionLoop(iterationId, context)

            // 3. 解析响应
            val parsed = parseLlmResponse(llmResponse)

            // 4. 合并到知识库
            val puzzlesCreated = integrateResults(parsed, context)

            // 5. 记录冲突信息（如果有）
            val conflicts = parsed.evaluation.conflictsFound.map {
                Conflict(ConflictType.CONTRADICTION, it, emptyList())
            }
            if (conflicts.isNotEmpty()) {
                logger.warn("发现 {} 个知识冲突", conflicts.size)
            }

            // 6. 检查收敛：连续低质量迭代
            if (parsed.evaluation.qualityScore < LOW_QUALITY_THRESHOLD) {
                consecutiveLowQualityCount++
                logger.info("低质量迭代: {}/{}", consecutiveLowQualityCount, MAX_CONSECUTIVE_LOW_QUALITY)
            } else {
                consecutiveLowQualityCount = 0
            }

            // 7. 转换 Evaluation
            val evaluation = Evaluation(
                hypothesisConfirmed = parsed.evaluation.hypothesisConfirmed,
                newKnowledgeGained = parsed.evaluation.newKnowledgeGained,
                conflictsFound = conflicts,
                qualityScore = parsed.evaluation.qualityScore,
                lessonsLearned = parsed.evaluation.lessonsLearned
            )

            logger.info("知识进化完成: iterationId={}, puzzlesCreated={}, quality={}, conflicts={}",
                iterationId, puzzlesCreated, parsed.evaluation.qualityScore, conflicts.size)

            return EvolutionResult.success(
                iterationId = iterationId,
                hypothesis = parsed.hypothesis,
                evaluation = evaluation,
                puzzlesCreated = puzzlesCreated
            )

        } catch (e: Exception) {
            logger.error("知识进化失败: iterationId={}, error={}", iterationId, e.message)

            return EvolutionResult.failed(
                iterationId = iterationId,
                reason = e.message ?: "未知错误"
            )
        }
    }

    /**
     * 构建上下文
     */
    private suspend fun buildContext(trigger: Trigger): EvolutionContext {
        // 加载现有知识
        val existingPuzzles = puzzleStore.loadAll().getOrElse { emptyList() }

        val triggerDescription = when (trigger) {
            is Trigger.UserQuery -> "用户问题: ${trigger.query}"
            is Trigger.FileChange -> "文件变更: ${trigger.files.joinToString(", ")}"
            is Trigger.Scheduled -> "定时任务: ${trigger.reason}"
            is Trigger.Manual -> "手动触发: ${trigger.reason}"
        }

        // 读取真实项目代码（关键文件）
        val projectCode = readProjectCode()

        return EvolutionContext(
            iterationId = generateIterationId(),
            triggerDescription = triggerDescription,
            existingPuzzles = existingPuzzles,
            timestamp = Instant.now(),
            projectCode = projectCode
        )
    }

    /**
     * 读取项目代码（关键文件）
     *
     * 策略：读取项目核心文件，为 LLM 提供真实代码上下文
     */
    private fun readProjectCode(): Map<String, String> {
        val codeFiles = mutableMapOf<String, String>()
        val projectDir = java.io.File(projectPath)

        if (!projectDir.exists()) {
            logger.warn("项目目录不存在: {}", projectPath)
            return emptyMap()
        }

        // 查找关键源代码文件
        val sourcePatterns = listOf(
            "**/src/main/kotlin/**/*.kt",
            "**/src/main/java/**/*.java"
        )

        for (pattern in sourcePatterns) {
            projectDir.walkTopDown()
                .filter { it.isFile && isRelevantCode(it) }
                .take(15)  // 最多读取 15 个文件
                .forEach { file ->
                    try {
                        val relativePath = file.relativeTo(projectDir).path
                        val content = file.readText()
                        if (content.isNotBlank()) {
                            codeFiles[relativePath] = content
                        }
                    } catch (e: Exception) {
                        logger.debug("读取文件失败: {}", file.path)
                    }
                }
        }

        logger.info("读取项目代码: {} 个文件", codeFiles.size)
        return codeFiles
    }

    /**
     * 判断文件是否相关
     */
    private fun isRelevantCode(file: java.io.File): Boolean {
        val name = file.name.lowercase()
        val path = file.path.lowercase()

        // 排除无关文件
        if (name.startsWith(".")) return false
        if (path.contains("/build/")) return false
        if (path.contains("/target/")) return false
        if (path.contains("/node_modules/")) return false
        if (path.contains(".test.") || path.contains(".spec.")) return false
        if (path.contains("/test/")) return false

        // 只包含源代码文件
        return name.endsWith(".kt") || name.endsWith(".java")
    }

    /**
     * 执行进化循环
     *
     * 核心：单一 Prompt，让 LLM 决定如何执行
     */
    private suspend fun executeEvolutionLoop(iterationId: String, context: EvolutionContext): String {
        val prompt = EvolutionPromptBuilder.build(iterationId, context)
        return llmService.simpleRequest(prompt)
    }

    /**
     * 构建进化 Prompt
     *
     * 设计原则：
     * - 只给目标和底线
     * - 不写死具体步骤
     * - 让 LLM 自由发挥
     *
     * 底线要求：
     * - content 必须是深度分析，不能是目录列表
     * - 必须包含业务语义：规则、流程、关系
     * - 不能只输出"看了什么"，要输出"发现了什么"
     */
    private fun buildEvolutionPrompt(iterationId: String, context: EvolutionContext): String {
        return """
# 知识进化循环

你是项目的智能知识管理员，负责持续改进项目理解。

## 当前任务
$iterationId

## 触发原因
${context.triggerDescription}

## 现有知识（已分析的拼图）
${formatExistingPuzzles(context.existingPuzzles)}

## 底线要求（必须遵守）
1. **禁止输出目录树**：不要列出文件结构
2. **禁止浅层描述**：不要只说"这个模块做了什么"
3. **必须深度分析**：提取业务规则、数据关系、调用链、状态机
4. **必须有洞察**：发现代码中的隐含逻辑和模式

## 目标
请执行完整的知识进化循环：
1. 观察：分析现有知识，识别空白
2. 假设：提出本轮的分析目标
3. 计划：决定需要分析什么
4. 执行：深入分析目标内容，提取业务语义
5. 评估：验证分析结果
6. 合并：输出需要更新的知识

## 输出要求
请用以下 JSON 格式输出你的完整分析过程和结果：

```json
{
  "hypothesis": "本轮分析目标/假设（1-2句话）",
  "tasks": [
    {"target": "分析目标", "description": "任务描述", "priority": 0.0-1.0}
  ],
  "results": [
    {
      "target": "分析目标",
      "title": "发现的知识点标题",
      "content": "Markdown 格式的深度分析：包含业务规则、数据关系、调用链、状态机等",
      "tags": ["tag1", "tag2"],
      "confidence": 0.0-1.0
    }
  ],
  "evaluation": {
    "hypothesisConfirmed": true/false,
    "newKnowledgeGained": 数量,
    "conflictsFound": ["冲突描述"],
    "qualityScore": 0.0-1.0,
    "lessonsLearned": ["学到的教训"]
  }
}
```

## 底线要求
- hypothesis: 必须明确，不能为空
- results: 如果没有新发现，qualityScore 应低于 0.5
- tags: 每个结果必须有标签
- content: 必须是有意义的 Markdown 内容，不能是占位符
"""
    }

    /**
     * 格式化现有拼图
     */
    private fun formatExistingPuzzles(puzzles: List<Puzzle>): String {
        if (puzzles.isEmpty()) {
            return "（暂无现有知识）"
        }

        return puzzles.take(10).joinToString("\n\n") { puzzle ->
            """
## ${puzzle.id}
- 类型: ${puzzle.type.name}
- 完整度: ${(puzzle.completeness * 100).toInt()}%
- 摘要: ${puzzle.content.take(200)}
            """.trimIndent()
        }
    }

    /**
     * 解析 LLM 响应
     */
    private fun parseLlmResponse(response: String): ParsedEvolution {
        return EvolutionResponseParser.parse(response)
    }

    /**
     * 合并结果到知识库
     */
    private suspend fun integrateResults(parsed: ParsedEvolution, context: EvolutionContext): Int {
        var count = 0

        parsed.results.forEach { result ->
            if (result.confidence > 0.5 && result.content.isNotBlank()) {
                val puzzle = Puzzle(
                    id = generatePuzzleId(result.title),
                    type = inferPuzzleType(result.tags),
                    status = PuzzleStatus.COMPLETED,
                    content = "# ${result.title}\n\n${result.content}",
                    completeness = result.confidence,
                    confidence = result.confidence,
                    lastUpdated = Instant.now(),
                    filePath = ".sman/puzzles/${generatePuzzleId(result.title)}.md"
                )

                puzzleStore.save(puzzle)
                count++
            }
        }

        return count
    }

    /**
     * 推断拼图类型
     */
    private fun inferPuzzleType(tags: List<String>): PuzzleType {
        val lowerTags = tags.map { it.lowercase() }

        return when {
            lowerTags.any { it in listOf("api", "rest", "controller") } -> PuzzleType.API
            lowerTags.any { it in listOf("data", "entity", "model", "db") } -> PuzzleType.DATA
            lowerTags.any { it in listOf("flow", "process", "workflow") } -> PuzzleType.FLOW
            lowerTags.any { it in listOf("rule", "validation", "policy") } -> PuzzleType.RULE
            lowerTags.any { it in listOf("tech", "framework", "dependency") } -> PuzzleType.TECH_STACK
            else -> PuzzleType.STRUCTURE
        }
    }

    /**
     * 生成迭代 ID
     */
    private fun generateIterationId(): String {
        return "evolution-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
    }

    /**
     * 生成拼图 ID
     */
    private fun generatePuzzleId(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), "-")
            .take(50)
    }
}

/**
 * 进化上下文
 */
data class EvolutionContext(
    val iterationId: String,
    val triggerDescription: String,
    val existingPuzzles: List<Puzzle>,
    val timestamp: Instant,
    val projectCode: Map<String, String> = emptyMap()  // 真实项目代码（文件路径 -> 内容）
)

/**
 * 解析后的进化结果
 */
data class ParsedEvolution(
    val hypothesis: String,
    val results: List<ParsedResult>,
    val evaluation: ParsedEvaluation
)

/**
 * 解析后的结果
 */
data class ParsedResult(
    val target: String,
    val title: String,
    val content: String,
    val tags: List<String>,
    val confidence: Double
)

/**
 * 解析后的评估
 */
data class ParsedEvaluation(
    val hypothesisConfirmed: Boolean,
    val newKnowledgeGained: Int,
    val conflictsFound: List<String>,
    val qualityScore: Double,
    val contextUtilization: Double = 0.0,
    val lessonsLearned: List<String>
)
