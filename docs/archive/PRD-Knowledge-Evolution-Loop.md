# PRD: 自迭代能力 - 知识进化循环

## 一、核心概念

### 1.1 知识进化循环

每轮迭代都遵循：

```
┌─────────────────────────────────────────────────────────────────┐
│  1. 查看已有知识 (Observe)                                      │
│     → 加载现有 Puzzles                                         │
│     → 分析用户 Query                                            │
│                                                               │
│  2. 提出假设 (Hypothesize)                                      │
│     → 生成分析假设                                              │
│     → "这个模块可能是认证模块"                                   │
│     → "这个调用可能是支付流程"                                   │
│                                                               │
│  3. 自动审查 (Review)                                          │
│     → 验证假设的可行性                                          │
│     → 识别风险和冲突                                            │
│                                                               │
│  4. 创建任务 (Plan)                                            │
│     → 拆解为具体步骤                                            │
│     → 确定优先级                                                │
│                                                               │
│  5. 执行 (Execute)                                             │
│     → 认领任务                                                  │
│     → 调用 LLM 分析                                             │
│                                                               │
│  6. 评估 (Evaluate)                                           │
│     → 验证结果质量                                              │
│     → 检查与现有知识的一致性                                     │
│                                                               │
│  7. 合并 (Integrate)                                           │
│     → 更新或创建 Puzzle                                         │
│     → 记录学习到的模式                                          │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 核心实体

```kotlin
/**
 * 迭代轮次
 * 代表一次完整的知识进化循环
 */
data class Iteration(
    val id: String,
    val trigger: Trigger,           // 触发原因
    val hypothesis: String,          // 本轮假设/目标
    val tasks: List<IterationTask>,  // 拆解的任务
    val results: List<TaskResult>,   // 执行结果
    val evaluation: Evaluation,       // 评估结果
    val status: IterationStatus,     // 状态
    val createdAt: Instant,
    val completedAt: Instant?
)

/**
 * 迭代任务
 * 具体的分析任务
 */
data class IterationTask(
    val id: String,
    val description: String,        // 任务描述
    val target: String,             // 分析目标
    val priority: Double,
    val status: TaskStatus,
    val assignee: String?,          // 认领者（可空 = 系统自动执行）
    val result: TaskResult?         // 执行结果
)

/**
 * 评估结果
 */
data class Evaluation(
    val hypothesisConfirmed: Boolean,   // 假设是否被证实
    val newKnowledgeGained: Int,       // 获取的新知识数
    val conflictsFound: List<Conflict>, // 发现的知识冲突
    val qualityScore: Double,          // 质量评分 0-1
    val lessonsLearned: List<String>    // 学到的教训
)

/**
 * 触发原因
 */
sealed class Trigger {
    data class UserQuery(val query: String) : Trigger()
    data class FileChange(val files: List<String>) : Trigger()
    data class Scheduled(val reason: String) : Trigger()  // 定时触发
    data class Manual(val reason: String) : Trigger()    // 手动触发
}

/**
 * 迭代状态
 */
enum class IterationStatus {
    OBSERVING,     // 观察阶段
    HYPOTHESIZING, // 假设阶段
    REVIEWING,     // 审查阶段
    PLANNING,     // 计划阶段
    EXECUTING,    // 执行阶段
    EVALUATING,   // 评估阶段
    COMPLETED,    // 完成
    FAILED        // 失败
}
```

## 二、迭代流程详解

### 2.1 阶段一：观察 (Observe)

```kotlin
suspend fun observe(trigger: Trigger): ObservationResult {
    // 1. 加载现有知识
    val existingPuzzles = puzzleStore.loadAll()

    // 2. 分析触发原因
    val context = when (trigger) {
        is Trigger.UserQuery -> {
            // 用户提问：分析问题涉及的知识领域
            analyzeQueryContext(trigger.query, existingPuzzles)
        }
        is Trigger.FileChange -> {
            // 文件变更：找出受影响的拼图
            analyzeFileChanges(trigger.files, existingPuzzles)
        }
        is Trigger.Scheduled -> {
            // 定时触发：检查过时知识
            checkStaleKnowledge(existingPuzzles)
        }
    }

    // 3. 生成观察报告
    return ObservationResult(
        existingKnowledge = context.relevantPuzzles,
        gaps = context.gaps,
        opportunities = context.opportunities
    )
}
```

### 2.2 阶段二：假设 (Hypothesize)

```kotlin
suspend fun hypothesize(observation: ObservationResult): Hypothesis {
    // 构建假设 Prompt
    val prompt = """
        基于以下观察，请提出分析假设：

        现有知识:
        ${observation.existingPuzzles.map { it.summary }.joinToString("\n")}

        知识空白:
        ${observation.gaps.joinToString("\n")}

        请用 1-2 句话描述本轮的分析目标。
        例如："验证用户模块是否使用了 Spring Security"
    """.trimIndent()

    val llmResponse = llmService.simpleRequest(prompt)

    return Hypothesis(
        statement = llmResponse,
        confidence = 0.5,  // 初始置信度
        evidence = observation.existingPuzzles.map { it.id }
    )
}
```

### 2.3 阶段三：审查 (Review)

```kotlin
suspend fun review(hypothesis: Hypothesis): ReviewResult {
    // 1. 可行性检查
    val feasibility = checkFeasibility(hypothesis)

    // 2. 风险评估
    val risks = assessRisks(hypothesis)

    // 3. 冲突检测
    val conflicts = detectConflicts(hypothesis)

    return ReviewResult(
        approved = feasibility.score > 0.5 && risks.isEmpty(),
        risks = risks,
        conflicts = conflicts,
        suggestions = generateSuggestions(feasibility, risks)
    )
}
```

### 2.4 阶段四：计划 (Plan)

```kotlin
fun plan(review: ReviewResult, hypothesis: Hypothesis): List<IterationTask> {
    // 将假设拆解为具体任务
    val tasks = mutableListOf<IterationTask>()

    // 示例拆解
    if (hypothesis.statement.contains("认证")) {
        tasks.add(IterationTask(
            id = "task-1",
            description = "扫描安全相关注解",
            target = "**/*Security*.kt",
            priority = 0.9,
            status = TaskStatus.PENDING,
            assignee = null,
            result = null
        ))
    }

    // 排序并返回
    return tasks.sortedByDescending { it.priority }
}
```

### 2.5 阶段五：执行 (Execute)

```kotlin
suspend fun execute(tasks: List<IterationTask>): List<TaskResult> {
    return tasks.map { task ->
        // 1. 认领任务
        val assignee = task.assignee ?: "system"

        // 2. 执行分析
        val result = llmAnalyzer.analyze(
            target = task.target,
            context = loadContext(task.target)
        )

        // 3. 返回结果
        TaskResult(
            taskId = task.id,
            assignee = assignee,
            output = result.content,
            tags = result.tags,
            confidence = result.confidence,
            filesAnalyzed = result.sourceFiles
        )
    }
}
```

### 2.6 阶段六：评估 (Evaluate)

```kotlin
suspend fun evaluate(
    hypothesis: Hypothesis,
    results: List<TaskResult>
): Evaluation {
    // 1. 假设验证
    val hypothesisConfirmed = results.any {
        it.confidence > 0.7 &&
        it.tags.any { tag -> hypothesis.statement.contains(tag) }
    }

    // 2. 新知识计数
    val newKnowledgeGained = results.count {
        isNewKnowledge(it)
    }

    // 3. 冲突检测
    val conflicts = detectConflictsBetween(results)

    // 4. 质量评分
    val qualityScore = calculateQualityScore(
        hypothesisConfirmed = hypothesisConfirmed,
        results = results,
        conflicts = conflicts
    )

    return Evaluation(
        hypothesisConfirmed = hypothesisConfirmed,
        newKnowledgeGained = newKnowledgeGained,
        conflictsFound = conflicts,
        qualityScore = qualityScore,
        lessonsLearned = extractLessons(results)
    )
}
```

### 2.7 阶段七：合并 (Integrate)

```kotlin
suspend fun integrate(
    results: List<TaskResult>,
    evaluation: Evaluation
): List<Puzzle> {
    val puzzles = mutableListOf<Puzzle>()

    results.forEach { result ->
        if (result.confidence > 0.6) {
            // 创建或更新 Puzzle
            val puzzle = buildPuzzle(result, evaluation)
            puzzleStore.save(puzzle)
            puzzles.add(puzzle)
        }
    }

    // 记录本轮学到的东西
    if (evaluation.lessonsLearned.isNotEmpty()) {
        recordLessons(evaluation.lessonsLearned)
    }

    return puzzles
}
```

## 三、主控制器

```kotlin
class KnowledgeEvolutionLoop(
    private val puzzleStore: PuzzleStore,
    private val llmService: LlmService,
    private val llmAnalyzer: LlmAnalyzer,
    private val taskQueueStore: TaskQueueStore
) {
    /**
     * 执行一轮知识进化
     */
    suspend fun evolve(trigger: Trigger): EvolutionResult {
        // 1. 观察
        val observation = observe(trigger)

        // 2. 假设
        val hypothesis = hypothesize(observation)

        // 3. 审查
        val review = review(hypothesis)
        if (!review.approved) {
            return EvolutionResult(
                status = IterationStatus.FAILED,
                reason = "假设未通过审查: ${review.suggestions}"
            )
        }

        // 4. 计划
        val tasks = plan(review, hypothesis)

        // 5. 执行
        val results = execute(tasks)

        // 6. 评估
        val evaluation = evaluate(hypothesis, results)

        // 7. 合并
        val puzzles = integrate(results, evaluation)

        return EvolutionResult(
            status = IterationStatus.COMPLETED,
            hypothesis = hypothesis.statement,
            evaluation = evaluation,
            puzzles = puzzles,
            tasks = tasks.map { it.id }
        )
    }
}
```

## 四、评估维度

### 4.1 假设验证率

```
本轮验证成功的假设 / 总假设数

高验证率 = 分析方向正确
```

### 4.2 新知识获取率

```
每轮获取的新 Puzzle 数

持续有新知识 = 系统在进步
```

### 4.3 知识一致性

```
本轮发现的知识冲突数

冲突 = 需要重新审视
```

### 4.4 执行效率

```
本轮耗时 / 任务数

效率低 = 需要优化
```

## 五、存储设计

```json
// .sman/evolutions/
evolution-{timestamp}.json

{
  "id": "evolution-2024-01-15-001",
  "trigger": {
    "type": "user_query",
    "query": "这个项目的认证是怎么实现的？"
  },
  "hypothesis": "项目使用了 Spring Security 进行认证",
  "tasks": [...],
  "results": [...],
  "evaluation": {
    "hypothesisConfirmed": true,
    "newKnowledgeGained": 3,
    "conflictsFound": [],
    "qualityScore": 0.85
  },
  "completedAt": "2024-01-15T10:30:00Z"
}
```

## 六、验收标准

1. 每轮迭代都完整执行 7 个阶段
2. 假设必须有明确的验证结果
3. 新知识必须被记录到 Puzzle
4. 失败/冲突必须被记录和复盘
5. 支持手动和自动触发
