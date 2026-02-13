# 05 - 死循环防护机制

## 1. 核心原则

**底线思维**：假设一切可能出错的地方都会出错，为每种情况设计防护措施。

## 2. 五层防护架构

```
┌─────────────────────────────────────────────────────────────────┐
│                      五层死循环防护                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Layer 1: 问题去重 (Question Deduplication)              │   │
│  │                                                         │   │
│  │ 检测: 相似问题是否已探索过 (向量相似度 > 0.9)            │   │
│  │ 动作: 跳过该问题                                        │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Layer 2: 工具调用去重 (Tool Call Deduplication)         │   │
│  │                                                         │   │
│  │ 检测: 相同工具+相同参数是否已执行过                      │   │
│  │ 动作: 跳过该调用，使用缓存结果                          │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Layer 3: 失败记录 (Failure Recording)                   │   │
│  │                                                         │   │
│  │ 检测: 操作是否已失败过                                  │   │
│  │ 动作: 查看避免策略，决定是否重试                        │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Layer 4: 指数退避 (Exponential Backoff)                 │   │
│  │                                                         │   │
│  │ 检测: 连续错误次数                                      │   │
│  │ 动作: 计算退避时间，暂停执行                            │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Layer 5: 每日配额 (Daily Quota)                         │   │
│  │                                                         │   │
│  │ 检测: 今日已使用配额                                    │   │
│  │ 动作: 超过配额则暂停，明天重置                          │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## 3. 各层详细设计

### 3.1 Layer 1: 问题去重

```kotlin
/**
 * 问题去重器
 * 通过向量相似度检测重复问题
 */
class QuestionDeduplicator(
    private val bgeM3Client: BgeM3Client,
    private val vectorStore: TieredVectorStore
) {
    // 相似度阈值
    private val similarityThreshold = 0.9

    /**
     * 检查问题是否已探索过
     */
    suspend fun isDuplicate(question: String, projectKey: String): Boolean {
        // 1. 向量化问题
        val vector = bgeM3Client.embed(question)

        // 2. 搜索相似问题
        val similarQuestions = vectorStore.search(
            vector = vector,
            topK = 5,
            filter = mapOf(
                "type" to "learning_record",
                "projectKey" to projectKey
            )
        )

        // 3. 判断是否重复
        return similarQuestions.any { it.score >= similarityThreshold }
    }

    /**
     * 批量过滤重复问题
     */
    suspend fun filterDuplicates(
        questions: List<GeneratedQuestion>,
        projectKey: String
    ): List<GeneratedQuestion> {
        return questions.filter { !isDuplicate(it.question, projectKey) }
    }
}
```

### 3.2 Layer 2: 工具调用去重

```kotlin
/**
 * 工具调用去重器
 * 使用 LRU 缓存记录最近的工具调用
 */
class ToolCallDeduplicator(
    private val cacheSize: Int = 100
) {
    // 最近工具调用缓存 (LRU)
    private val recentCalls = object : LinkedHashMap<String, ToolCallCache>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ToolCallCache>?): Boolean {
            return size > cacheSize
        }
    }

    /**
     * 计算调用签名
     */
    private fun computeSignature(toolName: String, parameters: Map<String, Any>): String {
        val sortedParams = parameters.toSortedMap()
        return "$toolName:${sortedParams.toString()}"
    }

    /**
     * 检查是否重复调用
     */
    fun isDuplicate(toolName: String, parameters: Map<String, Any>): Boolean {
        val signature = computeSignature(toolName, parameters)
        return recentCalls.containsKey(signature)
    }

    /**
     * 记录工具调用
     */
    fun recordCall(toolName: String, parameters: Map<String, Any>, result: ToolResult) {
        val signature = computeSignature(toolName, parameters)
        recentCalls[signature] = ToolCallCache(
            toolName = toolName,
            parameters = parameters,
            resultSummary = result.summary,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * 获取缓存结果
     */
    fun getCachedResult(toolName: String, parameters: Map<String, Any>): ToolCallCache? {
        val signature = computeSignature(toolName, parameters)
        return recentCalls[signature]
    }
}

/**
 * 工具调用缓存
 */
data class ToolCallCache(
    val toolName: String,
    val parameters: Map<String, Any>,
    val resultSummary: String,
    val timestamp: Long
)
```

### 3.3 Layer 3: 失败记录

```kotlin
/**
 * 失败记录服务
 * 记录所有失败操作，用于去重和学习
 */
class FailureRecordService(
    private val db: H2Database,
    private val llmService: LlmService
) {
    /**
     * 记录失败
     */
    suspend fun recordFailure(
        projectKey: String,
        operationType: OperationType,
        operation: String,
        error: Exception,
        context: Map<String, Any>
    ): FailureRecord {
        // 1. 计算操作签名
        val signature = computeSignature(operationType, operation, context)

        // 2. 检查是否已记录过相同失败
        val existing = findExistingFailure(projectKey, signature)
        if (existing != null) {
            // 更新重试次数
            return updateRetryCount(existing)
        }

        // 3. 创建新的失败记录
        val record = FailureRecord(
            id = generateId(),
            projectKey = projectKey,
            operationType = operationType,
            operation = operation,
            error = error.message ?: "Unknown error",
            context = context.mapValues { it.value.toString() },
            timestamp = System.currentTimeMillis(),
            status = FailureStatus.PENDING
        )

        // 4. 持久化
        saveFailure(record)

        // 5. 尝试生成避免策略 (LLM)
        generateAvoidStrategy(record)

        return record
    }

    /**
     * 检查操作是否已失败过
     */
    suspend fun hasFailedBefore(
        projectKey: String,
        operationType: OperationType,
        operation: String,
        context: Map<String, Any>
    ): Boolean {
        val signature = computeSignature(operationType, operation, context)
        return findExistingFailure(projectKey, signature) != null
    }

    /**
     * 获取避免策略
     */
    suspend fun getAvoidStrategy(failureId: String): String? {
        val record = loadFailure(failureId) ?: return null
        return record.avoidStrategy
    }

    /**
     * 生成避免策略 (LLM)
     */
    private suspend fun generateAvoidStrategy(record: FailureRecord) {
        val prompt = """
一个操作失败了，请分析原因并给出避免策略。

操作类型: ${record.operationType}
操作内容: ${record.operation}
错误信息: ${record.error}
上下文: ${record.context}

请给出:
1. 失败原因分析
2. 如何避免类似失败
3. 如果重试，应该如何调整

输出格式 (JSON):
```json
{
  "cause": "失败原因",
  "avoidStrategy": "避免策略",
  "retrySuggestion": "重试建议"
}
```
        """.trimIndent()

        val response = llmService.simpleRequest(FAILURE_ANALYSIS_PROMPT, prompt)
        val strategy = parseStrategy(response)

        // 更新失败记录
        updateAvoidStrategy(record.id, strategy.avoidStrategy)
    }

    companion object {
        private val FAILURE_ANALYSIS_PROMPT = """
你是一个错误分析专家，擅长分析失败原因并给出避免策略。

要求:
1. 分析要深入，找到根本原因
2. 避免策略要具体可执行
3. 如果是临时性错误，建议重试
        """.trimIndent()
    }
}
```

### 3.4 Layer 4: 指数退避

```kotlin
/**
 * 指数退避策略
 */
class ExponentialBackoff(
    private val baseMs: Long = 1000,      // 基础退避时间 1 秒
    private val maxMs: Long = 3600000,    // 最大退避时间 1 小时
    private val multiplier: Double = 2.0   // 乘数
) {
    /**
     * 计算退避时间
     */
    fun calculateBackoff(consecutiveErrors: Int): Long {
        if (consecutiveErrors <= 0) return 0

        val backoff = baseMs * (multiplier.pow(consecutiveErrors - 1)).toLong()
        return minOf(backoff, maxMs)
    }

    /**
     * 计算带抖动的退避时间
     */
    fun calculateBackoffWithJitter(consecutiveErrors: Int): Long {
        val baseBackoff = calculateBackoff(consecutiveErrors)
        // 添加 0-20% 的随机抖动
        val jitter = baseBackoff * (0.1 + Math.random() * 0.1)
        return baseBackoff + jitter.toLong()
    }
}

/**
 * 退避状态管理
 */
class BackoffStateManager(
    private val backoff: ExponentialBackoff
) {
    // 项目 -> 退避状态
    private val states = ConcurrentHashMap<String, BackoffState>()

    /**
     * 记录错误
     */
    fun recordError(projectKey: String) {
        states.compute(projectKey) { _, state ->
            val current = state ?: BackoffState()
            current.copy(
                consecutiveErrors = current.consecutiveErrors + 1,
                lastErrorTime = System.currentTimeMillis(),
                backoffUntil = System.currentTimeMillis() + backoff.calculateBackoff(current.consecutiveErrors + 1)
            )
        }
    }

    /**
     * 记录成功
     */
    fun recordSuccess(projectKey: String) {
        states.compute(projectKey) { _, state ->
            state?.copy(
                consecutiveErrors = 0,
                backoffUntil = null
            ) ?: BackoffState()
        }
    }

    /**
     * 检查是否在退避期
     */
    fun isInBackoff(projectKey: String): Boolean {
        val state = states[projectKey] ?: return false
        return state.backoffUntil != null && System.currentTimeMillis() < state.backoffUntil!!
    }

    /**
     * 获取剩余退避时间
     */
    fun getRemainingBackoff(projectKey: String): Long {
        val state = states[projectKey] ?: return 0
        return if (state.backoffUntil != null) {
            maxOf(0, state.backoffUntil!! - System.currentTimeMillis())
        } else {
            0
        }
    }
}

/**
 * 退避状态
 */
data class BackoffState(
    val consecutiveErrors: Int = 0,
    val lastErrorTime: Long? = null,
    val backoffUntil: Long? = null
)
```

### 3.5 Layer 5: 每日配额

```kotlin
/**
 * 每日配额管理器
 */
class DailyQuotaManager(
    private val maxQuestionsPerDay: Int = 50,
    private val maxExplorationsPerDay: Int = 100
) {
    // 项目 -> 配额状态
    private val quotas = ConcurrentHashMap<String, DailyQuota>()

    /**
     * 检查问题配额
     */
    fun canGenerateQuestion(projectKey: String): Boolean {
        val quota = getOrCreateQuota(projectKey)
        checkAndResetIfNeeded(quota)
        return quota.questionsToday < maxQuestionsPerDay
    }

    /**
     * 检查探索配额
     */
    fun canExplore(projectKey: String): Boolean {
        val quota = getOrCreateQuota(projectKey)
        checkAndResetIfNeeded(quota)
        return quota.explorationsToday < maxExplorationsPerDay
    }

    /**
     * 记录问题生成
     */
    fun recordQuestionGenerated(projectKey: String) {
        quotas.compute(projectKey) { _, quota ->
            val current = quota ?: createNewQuota()
            current.copy(questionsToday = current.questionsToday + 1)
        }
    }

    /**
     * 记录探索
     */
    fun recordExploration(projectKey: String) {
        quotas.compute(projectKey) { _, quota ->
            val current = quota ?: createNewQuota()
            current.copy(explorationsToday = current.explorationsToday + 1)
        }
    }

    /**
     * 获取剩余配额
     */
    fun getRemainingQuota(projectKey: String): QuotaInfo {
        val quota = getOrCreateQuota(projectKey)
        checkAndResetIfNeeded(quota)
        return QuotaInfo(
            questionsRemaining = maxQuestionsPerDay - quota.questionsToday,
            explorationsRemaining = maxExplorationsPerDay - quota.explorationsToday,
            resetsAt = getNextResetTime()
        )
    }

    /**
     * 检查并重置配额 (每天 00:00)
     */
    private fun checkAndResetIfNeeded(quota: DailyQuota) {
        val today = LocalDate.now().toString()
        if (quota.lastResetDate != today) {
            quota.reset(today)
        }
    }

    private fun getOrCreateQuota(projectKey: String): DailyQuota {
        return quotas.computeIfAbsent(projectKey) { createNewQuota() }
    }

    private fun createNewQuota(): DailyQuota {
        return DailyQuota(
            questionsToday = 0,
            explorationsToday = 0,
            lastResetDate = LocalDate.now().toString()
        )
    }

    private fun getNextResetTime(): Long {
        val tomorrow = LocalDate.now().plusDays(1)
        return tomorrow.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

/**
 * 每日配额
 */
data class DailyQuota(
    var questionsToday: Int,
    var explorationsToday: Int,
    var lastResetDate: String
) {
    fun reset(date: String) {
        questionsToday = 0
        explorationsToday = 0
        lastResetDate = date
    }
}

/**
 * 配额信息
 */
data class QuotaInfo(
    val questionsRemaining: Int,
    val explorationsRemaining: Int,
    val resetsAt: Long
)
```

## 4. 统一防护入口

```kotlin
/**
 * 死循环防护器 - 统一入口
 */
class DoomLoopGuard(
    private val questionDeduplicator: QuestionDeduplicator,
    private val toolCallDeduplicator: ToolCallDeduplicator,
    private val failureRecordService: FailureRecordService,
    private val backoffManager: BackoffStateManager,
    private val quotaManager: DailyQuotaManager
) {
    /**
     * 检查是否应该跳过问题
     */
    suspend fun shouldSkipQuestion(
        projectKey: String,
        question: GeneratedQuestion
    ): DoomLoopCheckResult {
        // 1. 检查退避期
        if (backoffManager.isInBackoff(projectKey)) {
            return DoomLoopCheckResult(
                shouldSkip = true,
                reason = "在退避期中",
                remainingBackoff = backoffManager.getRemainingBackoff(projectKey)
            )
        }

        // 2. 检查配额
        if (!quotaManager.canGenerateQuestion(projectKey)) {
            return DoomLoopCheckResult(
                shouldSkip = true,
                reason = "已达每日配额"
            )
        }

        // 3. 检查问题去重
        if (questionDeduplicator.isDuplicate(question.question, projectKey)) {
            return DoomLoopCheckResult(
                shouldSkip = true,
                reason = "问题已探索过"
            )
        }

        return DoomLoopCheckResult(shouldSkip = false)
    }

    /**
     * 检查是否应该跳过工具调用
     */
    fun shouldSkipToolCall(
        toolName: String,
        parameters: Map<String, Any>
    ): DoomLoopCheckResult {
        if (toolCallDeduplicator.isDuplicate(toolName, parameters)) {
            return DoomLoopCheckResult(
                shouldSkip = true,
                reason = "工具调用已执行过",
                cachedResult = toolCallDeduplicator.getCachedResult(toolName, parameters)
            )
        }

        return DoomLoopCheckResult(shouldSkip = false)
    }

    /**
     * 记录成功
     */
    fun recordSuccess(projectKey: String) {
        backoffManager.recordSuccess(projectKey)
        quotaManager.recordQuestionGenerated(projectKey)
    }

    /**
     * 记录失败
     */
    suspend fun recordFailure(
        projectKey: String,
        operationType: OperationType,
        operation: String,
        error: Exception,
        context: Map<String, Any>
    ) {
        backoffManager.recordError(projectKey)
        failureRecordService.recordFailure(projectKey, operationType, operation, error, context)
    }
}

/**
 * 检查结果
 */
data class DoomLoopCheckResult(
    val shouldSkip: Boolean,
    val reason: String? = null,
    val remainingBackoff: Long? = null,
    val cachedResult: ToolCallCache? = null
)
```

## 5. 健康检查

```kotlin
/**
 * 进化健康检查器
 */
class EvolutionHealthChecker(
    private val config: HealthCheckConfig
) {
    // 上次进度检查时间
    private var lastProgressCheck: Long = System.currentTimeMillis()

    // 上次学习记录数
    private var lastRecordCount: Int = 0

    /**
     * 执行健康检查
     */
    fun checkHealth(stats: EvolutionStats): HealthCheckResult {
        val issues = mutableListOf<String>()

        // 1. 检查连续错误
        if (stats.consecutiveErrors >= config.maxConsecutiveErrors) {
            issues.add("连续错误次数过多: ${stats.consecutiveErrors}")
        }

        // 2. 检查进度停滞
        val timeSinceLastCheck = System.currentTimeMillis() - lastProgressCheck
        if (timeSinceLastCheck > config.progressCheckInterval) {
            if (stats.totalLearningRecords == lastRecordCount) {
                issues.add("进度停滞: 在 ${config.progressCheckInterval / 60000} 分钟内没有新的学习记录")
            }
            lastProgressCheck = System.currentTimeMillis()
            lastRecordCount = stats.totalLearningRecords
        }

        // 3. 检查是否长时间无活动
        stats.lastEvolutionTime?.let { lastTime ->
            val idleTime = System.currentTimeMillis() - lastTime
            if (idleTime > config.maxIdleTime) {
                issues.add("长时间无活动: ${idleTime / 60000} 分钟")
            }
        }

        return HealthCheckResult(
            isHealthy = issues.isEmpty(),
            issues = issues,
            suggestedAction = if (issues.isNotEmpty()) "建议重启自进化循环" else null
        )
    }
}

/**
 * 健康检查配置
 */
data class HealthCheckConfig(
    val maxConsecutiveErrors: Int = 5,
    val progressCheckInterval: Long = 600000,  // 10 分钟
    val maxIdleTime: Long = 1800000            // 30 分钟
)

/**
 * 健康检查结果
 */
data class HealthCheckResult(
    val isHealthy: Boolean,
    val issues: List<String>,
    val suggestedAction: String?
)
```

## 6. 设计要点总结

| 层级 | 检测内容 | 防护动作 |
|------|---------|---------|
| **Layer 1** | 问题相似度 | 跳过重复问题 |
| **Layer 2** | 工具调用签名 | 跳过重复调用，使用缓存 |
| **Layer 3** | 失败历史 | 查看避免策略，决定是否重试 |
| **Layer 4** | 连续错误次数 | 指数退避，暂停执行 |
| **Layer 5** | 每日配额 | 超过配额则暂停，明天重置 |

**核心原则**:
1. **宁可错过，不可死循环** - 有疑问就跳过
2. **记录一切** - 每次失败都记录，用于学习
3. **逐步退避** - 连续失败时逐步增加等待时间
4. **配额保护** - 限制每日最大操作数
5. **健康检查** - 定期检查是否有停滞
