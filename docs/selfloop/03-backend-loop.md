# 03 - 后台自迭代循环

## 1. 核心目标

**让 AI 自己提出好问题，自己探索，自己学习，持续进化。**

## 2. 后台循环架构

```
┌─────────────────────────────────────────────────────────────────┐
│                   SelfEvolutionLoop 主循环                       │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│   while (enabled) {                                             │
│                                                                 │
│       // 0. 检查是否在退避期                                     │
│       if (isInBackoff()) {                                      │
│           delay(getRemainingBackoff())                          │
│           continue                                              │
│       }                                                         │
│                                                                 │
│       // 1. 加载项目记忆                                        │
│       val memory = loadProjectMemory()                          │
│                                                                 │
│       // 2. 检查每日配额                                        │
│       if (!checkDailyQuota()) {                                 │
│           delay(直到明天 00:00)                                  │
│           continue                                              │
│       }                                                         │
│                                                                 │
│       // 3. 生成好问题                                          │
│       val question = generateQuestion(memory)                   │
│         if (question == null) {                                 │
│           delay(intervalMs)                                     │
│           continue                                              │
│         }                                                       │
│                                                                 │
│       // 4. 死循环检测                                          │
│       if (detectDoomLoop(question)) {                           │
│           handleDoomLoop(question)                              │
│           continue                                              │
│       }                                                         │
│                                                                 │
│       // 5. 工具探索                                            │
│       val explorationResult = explore(question)                 │
│                                                                 │
│       // 6. LLM 总结学习成果                                    │
│       val learningRecord = summarize(question, explorationResult)│
│                                                                 │
│       // 7. 持久化学习记录                                      │
│       saveLearningRecord(learningRecord)                        │
│                                                                 │
│       // 8. 更新项目记忆                                        │
│       updateProjectMemory(learningRecord)                       │
│                                                                 │
│       // 9. 休眠后继续                                          │
│       delay(intervalMs)                                         │
│   }                                                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## 3. 组件详细设计

### 3.1 SelfEvolutionLoop 主循环

```kotlin
/**
 * 自进化循环 - 后台主循环
 */
class SelfEvolutionLoop(
    private val projectKey: String,
    private val projectPath: Path,
    private val memoryManager: SharedMemoryManager,
    private val questionGenerator: QuestionGenerator,
    private val toolExplorer: ToolExplorer,
    private val learningRecorder: LearningRecorder,
    private val doomLoopGuard: DoomLoopGuard,
    private val config: EvolutionConfig
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    @Volatile
    private var enabled = false

    @Volatile
    private var currentPhase = EvolutionPhase.IDLE

    /**
     * 启动后台循环
     */
    fun start() {
        if (enabled) return

        enabled = true
        currentJob = scope.launch {
            runEvolutionLoop()
        }

        logger.info("SelfEvolutionLoop 启动: projectKey={}", projectKey)
    }

    /**
     * 停止后台循环
     */
    fun stop() {
        enabled = false
        currentJob?.cancel()
        currentJob = null

        logger.info("SelfEvolutionLoop 停止: projectKey={}", projectKey)
    }

    /**
     * 主循环
     */
    private suspend fun runEvolutionLoop() {
        while (enabled) {
            try {
                runSingleIteration()
            } catch (e: CancellationException) {
                logger.info("SelfEvolutionLoop 被取消")
                break
            } catch (e: Exception) {
                logger.error("SelfEvolutionLoop 迭代失败", e)
                handleIterationError(e)
            }

            delay(config.intervalMs)
        }
    }

    /**
     * 单次迭代
     */
    private suspend fun runSingleIteration() {
        // 0. 检查退避
        if (isInBackoff()) {
            val remaining = getRemainingBackoff()
            logger.info("在退避期中，剩余 {} ms", remaining)
            delay(remaining)
            return
        }

        // 1. 加载项目记忆
        currentPhase = EvolutionPhase.LOADING_MEMORY
        val memory = memoryManager.getProjectMemory(projectKey)

        // 2. 检查每日配额
        if (!checkDailyQuota(memory)) {
            val nextReset = getNextDailyReset()
            logger.info("已达每日配额，等待至 {}", nextReset)
            delay(calculateDelayUntil(nextReset))
            return
        }

        // 3. 生成好问题
        currentPhase = EvolutionPhase.GENERATING_QUESTION
        val questions = questionGenerator.generate(memory, count = 3)

        if (questions.isEmpty()) {
            logger.info("没有生成新问题，休眠")
            return
        }

        // 选择最高优先级问题
        val selectedQuestion = questions.maxByOrNull { it.priority }!!

        // 4. 死循环检测
        if (doomLoopGuard.shouldSkip(selectedQuestion, memory)) {
            logger.warn("死循环检测: 跳过问题 {}", selectedQuestion.question)
            handleDoomLoop(selectedQuestion)
            return
        }

        // 5. 工具探索
        currentPhase = EvolutionPhase.EXPLORING
        val explorationResult = toolExplorer.explore(selectedQuestion)

        if (!explorationResult.success) {
            logger.error("探索失败: {}", explorationResult.error)
            handleExplorationFailure(selectedQuestion, explorationResult)
            return
        }

        // 6. LLM 总结学习成果
        currentPhase = EvolutionPhase.SUMMARIZING
        val learningRecord = learningRecorder.summarize(
            question = selectedQuestion,
            explorationResult = explorationResult
        )

        // 7. 持久化
        learningRecorder.save(learningRecord)

        // 8. 更新项目记忆
        memoryManager.updateProjectMemory(projectKey) { memory ->
            memory.copy(
                learningRecordIds = memory.learningRecordIds + learningRecord.id,
                evolutionStatus = memory.evolutionStatus.copy(
                    questionsGeneratedToday = memory.evolutionStatus.questionsGeneratedToday + 1,
                    totalQuestionsExplored = memory.evolutionStatus.totalQuestionsExplored + 1,
                    consecutiveErrors = 0
                )
            )
        }

        currentPhase = EvolutionPhase.IDLE
        logger.info("学习完成: {}", learningRecord.question)
    }

    // ... 其他辅助方法
}
```

### 3.2 QuestionGenerator - 问题生成器

```kotlin
/**
 * 问题生成器 - 让 LLM 生成好问题
 */
class QuestionGenerator(
    private val llmService: LlmService,
    private val bgeM3Client: BgeM3Client,
    private val vectorStore: TieredVectorStore
) {
    /**
     * 生成好问题
     */
    suspend fun generate(
        memory: ProjectMemory,
        count: Int = 3
    ): List<GeneratedQuestion> {
        // 1. 构建问题生成 Prompt
        val prompt = buildQuestionGenerationPrompt(memory)

        // 2. 调用 LLM
        val response = llmService.simpleRequest(
            systemPrompt = QUESTION_GENERATION_SYSTEM_PROMPT,
            userPrompt = prompt
        )

        // 3. 解析 LLM 响应
        val questions = parseQuestions(response)

        // 4. 过滤已探索过的问题 (向量相似度检测)
        val filteredQuestions = filterExploredQuestions(questions)

        return filteredQuestions.take(count)
    }

    /**
     * 构建问题生成 Prompt
     */
    private fun buildQuestionGenerationPrompt(memory: ProjectMemory): String {
        return """
你是一个代码分析专家，正在深入理解一个项目。

## 项目背景
- 技术栈: ${memory.techStack}
- 已知领域: ${memory.domainKnowledge.map { it.domain }}

## 已学到的知识
${memory.learningRecordIds.take(10).joinToString("\n") { "- $it" }}

## 知识盲点
${memory.knowledgeGaps.take(10).joinToString("\n") { "- ${it.description}" }}

## 任务
基于以上信息，生成 ${count} 个值得探索的好问题。

好问题的标准:
1. 能帮助理解业务逻辑
2. 能发现代码模式
3. 能建立模块间的关联
4. 之前没有探索过

## 输出格式 (JSON)
```json
{
  "questions": [
    {
      "question": "问题内容",
      "type": "BUSINESS_LOGIC|CODE_STRUCTURE|DATA_FLOW|DEPENDENCY|...",
      "priority": 1-10,
      "reason": "为什么这是个好问题",
      "suggestedTools": ["expert_consult", "read_file", ...],
      "expectedOutcome": "预期学到什么"
    }
  ]
}
```
        """.trimIndent()
    }

    /**
     * 过滤已探索过的问题
     */
    private suspend fun filterExploredQuestions(
        questions: List<GeneratedQuestion>
    ): List<GeneratedQuestion> {
        return questions.filter { q ->
            // 向量化问题
            val vector = bgeM3Client.embed(q.question)

            // 搜索相似问题
            val similar = vectorStore.search(
                vector = vector,
                topK = 3,
                filter = mapOf("type" to "learning_record")
            )

            // 如果相似度都低于 0.9，认为是新问题
            similar.none { it.score > 0.9 }
        }
    }

    companion object {
        private val QUESTION_GENERATION_SYSTEM_PROMPT = """
你是一个资深的代码分析师，擅长通过提问来深入理解项目。

你的目标是生成能够帮助理解项目的好问题，而不是简单的事实性问题。

好问题的特点:
1. 关注"为什么"和"如何"，而不是"是什么"
2. 关注模块间的关联和依赖
3. 关注业务逻辑的流程
4. 关注代码背后的设计思想
        """.trimIndent()
    }
}

/**
 * 生成的问题
 */
data class GeneratedQuestion(
    val question: String,
    val type: QuestionType,
    val priority: Int,
    val reason: String,
    val suggestedTools: List<String>,
    val expectedOutcome: String
)
```

### 3.3 ToolExplorer - 工具探索器

```kotlin
/**
 * 工具探索器 - 执行工具调用来探索问题
 */
class ToolExplorer(
    private val toolRegistry: ToolRegistry,
    private val subTaskExecutor: SubTaskExecutor,
    private val config: ExplorerConfig
) {
    /**
     * 探索问题
     */
    suspend fun explore(
        question: GeneratedQuestion
    ): ExplorationResult {
        val steps = mutableListOf<ToolCallStep>()
        var currentContext = ExplorationContext(question = question)

        try {
            // 最多探索 maxSteps 步
            repeat(config.maxSteps) { stepIndex ->
                // 1. 决定下一步行动 (LLM 决策)
                val nextAction = decideNextAction(currentContext)

                if (nextAction == null || nextAction.shouldStop) {
                    logger.info("探索完成: 共 {} 步", stepIndex)
                    return@repeat
                }

                // 2. 执行工具调用
                val toolResult = executeTool(nextAction)

                // 3. 记录步骤
                steps.add(ToolCallStep(
                    toolName = nextAction.toolName,
                    parameters = nextAction.parameters,
                    resultSummary = toolResult.summary,
                    timestamp = System.currentTimeMillis()
                ))

                // 4. 更新上下文
                currentContext = currentContext.copy(
                    previousSteps = currentContext.previousSteps + steps.last(),
                    accumulatedKnowledge = currentContext.accumulatedKnowledge + "\n" + toolResult.summary
                )

                // 5. 检查是否足够
                if (isExplorationSufficient(currentContext)) {
                    logger.info("探索已足够: {}", currentContext.accumulatedKnowledge.length)
                    return@repeat
                }
            }

            return ExplorationResult(
                question = question,
                steps = steps,
                success = true,
                finalContext = currentContext
            )

        } catch (e: Exception) {
            return ExplorationResult(
                question = question,
                steps = steps,
                success = false,
                error = e.message
            )
        }
    }

    /**
     * 决定下一步行动 (LLM 决策)
     */
    private suspend fun decideNextAction(
        context: ExplorationContext
    ): NextAction? {
        val prompt = """
你正在探索一个问题: "${context.question.question}"

已执行的步骤:
${context.previousSteps.mapIndexed { i, s -> "${i + 1}. ${s.toolName}: ${s.resultSummary}" }.joinToString("\n")}

已获得的信息:
${context.accumulatedKnowledge.take(1000)}

建议使用的工具: ${context.question.suggestedTools}

请决定下一步行动。如果已经足够回答问题，返回 shouldStop=true。

输出格式 (JSON):
```json
{
  "shouldStop": false,
  "toolName": "expert_consult|read_file|grep_file|call_chain|...",
  "parameters": { ... },
  "reason": "为什么选择这个工具"
}
```
        """.trimIndent()

        val response = llmService.simpleRequest(EXPLORER_SYSTEM_PROMPT, prompt)
        return parseNextAction(response)
    }

    /**
     * 执行工具
     */
    private suspend fun executeTool(action: NextAction): ToolResult {
        val tool = toolRegistry.getTool(action.toolName)
            ?: return ToolResult.error("工具不存在: ${action.toolName}")

        return tool.execute(action.parameters)
    }

    /**
     * 检查探索是否足够
     */
    private fun isExplorationSufficient(context: ExplorationContext): Boolean {
        // 简单判断: 累积知识超过 2000 字符
        return context.accumulatedKnowledge.length > 2000
    }

    companion object {
        private val EXPLORER_SYSTEM_PROMPT = """
你是一个代码探索专家，擅长使用工具来收集信息。

可用的工具:
- expert_consult: 语义搜索相关代码和知识
- read_file: 读取文件内容
- grep_file: 正则搜索文件内容
- find_file: 按文件名查找
- call_chain: 分析调用关系

原则:
1. 每一步都要有明确目的
2. 不要重复收集相同信息
3. 信息足够就停止
        """.trimIndent()
    }
}

/**
 * 探索上下文
 */
data class ExplorationContext(
    val question: GeneratedQuestion,
    val previousSteps: List<ToolCallStep> = emptyList(),
    val accumulatedKnowledge: String = ""
)

/**
 * 探索结果
 */
data class ExplorationResult(
    val question: GeneratedQuestion,
    val steps: List<ToolCallStep>,
    val success: Boolean,
    val error: String? = null,
    val finalContext: ExplorationContext? = null
)

/**
 * 下一步行动
 */
data class NextAction(
    val shouldStop: Boolean,
    val toolName: String? = null,
    val parameters: Map<String, Any> = emptyMap(),
    val reason: String? = null
)
```

### 3.4 LearningRecorder - 学习记录器

```kotlin
/**
 * 学习记录器 - 总结并持久化学习成果
 */
class LearningRecorder(
    private val llmService: LlmService,
    private val bgeM3Client: BgeM3Client,
    private val memoryManager: SharedMemoryManager
) {
    /**
     * 总结学习成果 (LLM)
     */
    suspend fun summarize(
        question: GeneratedQuestion,
        explorationResult: ExplorationResult
    ): LearningRecord {
        // 1. 构建 Prompt
        val prompt = buildSummaryPrompt(question, explorationResult)

        // 2. 调用 LLM 总结
        val response = llmService.simpleRequest(SUMMARY_SYSTEM_PROMPT, prompt)

        // 3. 解析总结结果
        val summary = parseSummary(response)

        // 4. 向量化问题和答案
        val questionVector = bgeM3Client.embed(question.question)
        val answerVector = bgeM3Client.embed(summary.answer)

        // 5. 构建学习记录
        return LearningRecord(
            id = generateId(),
            projectKey = memoryManager.getCurrentProjectKey(),
            createdAt = System.currentTimeMillis(),
            question = question.question,
            questionType = question.type,
            answer = summary.answer,
            explorationPath = explorationResult.steps,
            confidence = summary.confidence,
            sourceFiles = summary.sourceFiles,
            questionVector = questionVector,
            answerVector = answerVector,
            tags = summary.tags,
            domain = summary.domain
        )
    }

    /**
     * 持久化学习记录
     */
    suspend fun save(record: LearningRecord) {
        memoryManager.saveLearningRecord(record)
        logger.info("学习记录已保存: id={}, question={}", record.id, record.question)
    }

    /**
     * 构建总结 Prompt
     */
    private fun buildSummaryPrompt(
        question: GeneratedQuestion,
        result: ExplorationResult
    ): String {
        return """
你刚刚探索了一个问题，请总结你学到的内容。

## 问题
${question.question}

## 探索过程
${result.steps.mapIndexed { i, s ->
    "### 步骤 ${i + 1}: ${s.toolName}\n参数: ${s.parameters}\n结果: ${s.resultSummary}"
}.joinToString("\n\n")}

## 累积信息
${result.finalContext?.accumulatedKnowledge ?: "无"}

## 任务
总结你学到的内容，输出 JSON 格式:

```json
{
  "answer": "简洁的答案，用中文",
  "confidence": 0.0-1.0,
  "sourceFiles": ["涉及的文件列表"],
  "tags": ["标签1", "标签2"],
  "domain": "所属领域 (如: 还款、订单、支付)"
}
```
        """.trimIndent()
    }

    companion object {
        private val SUMMARY_SYSTEM_PROMPT = """
你是一个知识总结专家，擅长从探索过程中提炼关键信息。

要求:
1. 答案要简洁、准确
2. 置信度要保守估计
3. 标签要能帮助后续检索
4. 领域分类要准确
        """.trimIndent()
    }
}
```

## 4. 配置设计

```kotlin
/**
 * 进化配置
 */
data class EvolutionConfig(
    // 基础配置
    val enabled: Boolean = true,
    val intervalMs: Long = 60000,              // 每次迭代间隔 (默认 1 分钟)

    // 问题生成配置
    val questionsPerIteration: Int = 3,        // 每次生成的问题数
    val maxQuestionRetries: Int = 3,           // 问题生成最大重试次数

    // 探索配置
    val maxExplorationSteps: Int = 10,         // 每次探索最大步数
    val explorationTimeoutMs: Long = 120000,   // 探索超时时间 (2 分钟)

    // 每日配额
    val maxDailyQuestions: Int = 50,           // 每天最多生成 50 个问题

    // 退避配置
    val baseBackoffMs: Long = 1000,            // 基础退避时间 (1 秒)
    val maxBackoffMs: Long = 3600000,          // 最大退避时间 (1 小时)
    val maxConsecutiveErrors: Int = 5,         // 最大连续错误次数

    // Token 预算
    val maxTokensPerIteration: Int = 8000      // 每次迭代最大 Token 消耗
)
```

## 5. 与现有架构集成

### 5.1 集成到 ProjectAnalysisScheduler

```kotlin
/**
 * 扩展 ProjectAnalysisScheduler
 */
class ProjectAnalysisScheduler(
    private val project: Project,
    // ... 现有依赖

    // 新增: 自进化循环
    private val selfEvolutionLoop: SelfEvolutionLoop
) {
    fun start() {
        // 现有逻辑: 启动分析调度
        startAnalysisScheduler()

        // 新增: 启动自进化循环
        if (config.selfEvolutionEnabled) {
            selfEvolutionLoop.start()
        }
    }

    fun stop() {
        // 现有逻辑: 停止分析调度
        stopAnalysisScheduler()

        // 新增: 停止自进化循环
        selfEvolutionLoop.stop()
    }
}
```

### 5.2 复用现有工具

```kotlin
/**
 * 工具探索器复用现有工具
 */
class ToolExplorer(
    // 复用现有的工具注册表
    private val toolRegistry: ToolRegistry
) {
    // 可用工具:
    // - expert_consult (LocalExpertConsultService)
    // - read_file (LocalToolExecutor)
    // - grep_file (LocalToolExecutor)
    // - find_file (LocalToolExecutor)
    // - call_chain (LocalToolExecutor)
}
```

## 6. 监控与日志

```kotlin
/**
 * 进化统计
 */
data class EvolutionStats(
    val projectKey: String,
    val totalQuestions: Int,
    val todayQuestions: Int,
    val totalLearningRecords: Int,
    val consecutiveErrors: Int,
    val lastEvolutionTime: Long?,
    val currentPhase: EvolutionPhase
)

/**
 * 进化日志
 */
data class EvolutionLog(
    val timestamp: Long,
    val phase: EvolutionPhase,
    val question: String?,
    val success: Boolean,
    val error: String?,
    val durationMs: Long
)
```

## 7. 设计要点总结

| 要点 | 说明 |
|------|------|
| **LLM 驱动** | 问题生成、探索决策、学习总结都由 LLM 完成 |
| **工具复用** | 复用现有工具集，不重复造轮子 |
| **死循环防护** | 多层防护，避免无限循环 |
| **增量学习** | 每次学习都是增量，不覆盖历史 |
| **后台运行** | 不影响前台用户交互 |
| **配额控制** | 每日配额 + Token 预算，避免资源滥用 |
