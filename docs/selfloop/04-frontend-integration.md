# 04 - 前台集成与资源加载

## 1. 核心问题

**用户提需求时，如何加载后台自我迭代的学习成果？**

例如：用户说 "增加先本后息的还款方式"，前台需要能快速找到后台学到的关于"还款"的所有知识。

## 2. 资源加载架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    前台资源加载流程                              │
└─────────────────────────────────────────────────────────────────┘

用户请求: "增加先本后息的还款方式"
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 1: 意图分析 (LLM)                                          │
│                                                                 │
│   输入: "增加先本后息的还款方式"                                 │
│   输出:                                                         │
│   {                                                             │
│     "domain": ["还款", "还款方式"],                              │
│     "action": "ADD_FEATURE",                                    │
│   "keywords": ["先本后息", "还款方式", "计算逻辑"]               │
│   }                                                             │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 2: 多路召回                                                │
│                                                                 │
│   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐│
│   │  向量语义搜索    │  │  领域知识查询   │  │  关键词匹配     ││
│   │  (BGE-M3)       │  │  (精确查询)     │  │  (兜底)         ││
│   └────────┬────────┘  └────────┬────────┘  └────────┬────────┘│
│            │                    │                    │          │
│            │     ┌──────────────┴──────────────┐     │          │
│            │     │         合并去重             │     │          │
│            └────▶│                             │◀────┘          │
│                  └──────────────┬──────────────┘                │
│                                 │                               │
│                                 ▼                               │
│                        召回结果列表                              │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 3: 精排 (Reranker)                                         │
│                                                                 │
│   BGE-Reranker.rerank(                                          │
│     query: "增加先本后息的还款方式",                              │
│     candidates: 召回结果列表                                     │
│   )                                                             │
│                                                                 │
│   输出: 排序后的结果 (topK=10)                                   │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 4: 构建增强上下文                                          │
│                                                                 │
│   加载内容:                                                      │
│   • 相关学习记录 (5条)                                          │
│   • 相关代码片段 (10个)                                         │
│   • 领域知识概述                                                 │
│   • 项目技术栈信息                                               │
│                                                                 │
│   组装成 LLM 可用的上下文                                        │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 5: 注入 SmanLoop                                           │
│                                                                 │
│   将增强上下文注入到 System Prompt 或 User Prompt               │
│   SmanLoop 开始 ReAct 循环                                      │
└─────────────────────────────────────────────────────────────────┘
```

## 3. 多路召回策略

### 3.1 向量语义搜索 (主路)

```kotlin
/**
 * 向量语义搜索
 * 使用 BGE-M3 进行语义召回
 */
class VectorSemanticSearch(
    private val bgeM3Client: BgeM3Client,
    private val vectorStore: TieredVectorStore
) {
    /**
     * 语义搜索学习记录
     */
    suspend fun searchLearningRecords(
        query: String,
        projectKey: String,
        topK: Int = 10
    ): List<LearningRecordWithScore> {
        // 1. 向量化查询
        val queryVector = bgeM3Client.embed(query)

        // 2. 向量检索
        val results = vectorStore.search(
            vector = queryVector,
            topK = topK * 2,  // 召回 2 倍，后续 Rerank
            filter = mapOf("projectKey" to projectKey)
        )

        // 3. 加载完整记录
        return results.map { hit ->
            LearningRecordWithScore(
                record = loadRecord(hit.id),
                score = hit.score
            )
        }
    }

    /**
     * 语义搜索代码片段
     */
    suspend fun searchCodeFragments(
        query: String,
        projectKey: String,
        topK: Int = 10
    ): List<CodeFragmentWithScore> {
        val queryVector = bgeM3Client.embed(query)

        val results = vectorStore.searchCode(
            vector = queryVector,
            topK = topK,
            filter = mapOf("projectKey" to projectKey)
        )

        return results.map { hit ->
            CodeFragmentWithScore(
                fragment = loadCodeFragment(hit.id),
                score = hit.score
            )
        }
    }
}
```

### 3.2 领域知识查询 (精确路)

```kotlin
/**
 * 领域知识查询
 * 根据识别出的领域直接查询
 */
class DomainKnowledgeQuery(
    private val memoryManager: SharedMemoryManager
) {
    /**
     * 查询领域知识
     */
    suspend fun query(
        projectKey: String,
        domains: List<String>
    ): List<DomainKnowledge> {
        return domains.mapNotNull { domain ->
            memoryManager.getDomainKnowledge(projectKey, domain)
        }
    }
}
```

### 3.3 关键词匹配 (兜底路)

```kotlin
/**
 * 关键词匹配
 * 作为向量搜索的兜底
 */
class KeywordMatcher(
    private val db: H2Database
) {
    /**
     * 关键词搜索
     */
    suspend fun search(
        projectKey: String,
        keywords: List<String>,
        limit: Int = 10
    ): List<LearningRecord> {
        val sql = """
            SELECT * FROM learning_records
            WHERE project_key = ?
            AND (
                ${keywords.indices.joinToString(" OR ") { "question LIKE ? OR answer LIKE ?" }}
            )
            ORDER BY created_at DESC
            LIMIT ?
        """

        val params = mutableListOf<Any>(projectKey)
        keywords.forEach { kw ->
            params.add("%$kw%")
            params.add("%$kw%")
        }
        params.add(limit)

        return db.query(sql, params) { rs -> mapToRecord(rs) }
    }
}
```

### 3.4 多路合并

```kotlin
/**
 * 多路召回合并器
 */
class MultiPathRecaller(
    private val vectorSearch: VectorSemanticSearch,
    private val domainQuery: DomainKnowledgeQuery,
    private val keywordMatcher: KeywordMatcher,
    private val reranker: BgeReranker
) {
    /**
     * 多路召回并合并
     */
    suspend fun recall(
        projectKey: String,
        intent: UserIntent,
        topK: Int = 10
    ): RecallResult {
        // 并行执行三路召回
        val deferredVector = coroutineScope {
            async {
                vectorSearch.searchLearningRecords(
                    query = intent.originalQuery,
                    projectKey = projectKey,
                    topK = topK * 2
                )
            }
        }

        val deferredDomain = coroutineScope {
            async {
                domainQuery.query(projectKey, intent.domains)
            }
        }

        val deferredKeyword = coroutineScope {
            async {
                keywordMatcher.search(
                    projectKey = projectKey,
                    keywords = intent.keywords,
                    limit = topK
                )
            }
        }

        // 等待所有召回完成
        val vectorResults = deferredVector.await()
        val domainResults = deferredDomain.await()
        val keywordResults = deferredKeyword.await()

        // 合并去重
        val merged = mergeAndDeduplicate(
            vectorResults = vectorResults,
            keywordResults = keywordResults
        )

        // Rerank
        val reranked = reranker.rerank(
            query = intent.originalQuery,
            candidates = merged
        )

        return RecallResult(
            learningRecords = reranked.take(topK),
            domainKnowledge = domainResults,
            codeFragments = searchCodeFragments(projectKey, intent)
        )
    }

    /**
     * 合并去重
     */
    private fun mergeAndDeduplicate(
        vectorResults: List<LearningRecordWithScore>,
        keywordResults: List<LearningRecord>
    ): List<LearningRecordWithScore> {
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<LearningRecordWithScore>()

        // 向量结果优先
        vectorResults.forEach { item ->
            if (seen.add(item.record.id)) {
                merged.add(item)
            }
        }

        // 关键词结果补充
        keywordResults.forEach { record ->
            if (seen.add(record.id)) {
                merged.add(LearningRecordWithScore(record, 0.5))
            }
        }

        return merged
    }
}
```

## 4. 增强上下文构建

### 4.1 上下文结构

```kotlin
/**
 * 增强上下文
 * 注入到 SmanLoop 的 System Prompt
 */
data class EnhancedContext(
    // 项目基础信息
    val projectInfo: ProjectInfo,

    // 相关学习记录
    val learningRecords: List<LearningRecordWithScore>,

    // 领域知识
    val domainKnowledge: List<DomainKnowledge>,

    // 相关代码片段
    val codeFragments: List<CodeFragmentWithScore>,

    // 用户请求
    val userRequest: String
) {
    /**
     * 生成 System Prompt 补充内容
     */
    fun toSystemPromptSection(): String {
        val sb = StringBuilder()

        sb.appendLine("## 项目背景")
        sb.appendLine("- 技术栈: ${projectInfo.techStack}")
        sb.appendLine("- 项目路径: ${projectInfo.path}")
        sb.appendLine()

        if (domainKnowledge.isNotEmpty()) {
            sb.appendLine("## 相关领域知识")
            domainKnowledge.forEach { dk ->
                sb.appendLine("### ${dk.domain}")
                sb.appendLine(dk.summary)
                sb.appendLine("关键文件: ${dk.keyFiles.joinToString(", ")}")
                sb.appendLine()
            }
        }

        if (learningRecords.isNotEmpty()) {
            sb.appendLine("## 相关学习记录 (后台已学到的知识)")
            learningRecords.take(5).forEachIndexed { index, record ->
                sb.appendLine("### 知识 ${index + 1} (相关度: ${(record.score * 100).toInt()}%)")
                sb.appendLine("问题: ${record.record.question}")
                sb.appendLine("答案: ${record.record.answer}")
                sb.appendLine("涉及文件: ${record.record.sourceFiles.joinToString(", ")}")
                sb.appendLine()
            }
        }

        if (codeFragments.isNotEmpty()) {
            sb.appendLine("## 相关代码片段")
            codeFragments.take(3).forEach { fragment ->
                sb.appendLine("文件: ${fragment.fragment.filePath}")
                sb.appendLine("```")
                sb.appendLine(fragment.fragment.content.take(500))
                sb.appendLine("```")
                sb.appendLine()
            }
        }

        return sb.toString()
    }
}
```

### 4.2 上下文注入点

```kotlin
/**
 * 增强上下文注入器
 */
class EnhancedContextInjector(
    private val multiPathRecaller: MultiPathRecaller,
    private val intentAnalyzer: IntentAnalyzer
) {
    /**
     * 为用户请求构建增强上下文
     */
    suspend fun inject(
        session: Session,
        userRequest: String,
        projectKey: String
    ): EnhancedContext {
        // 1. 意图分析
        val intent = intentAnalyzer.analyze(userRequest)

        // 2. 多路召回
        val recallResult = multiPathRecaller.recall(
            projectKey = projectKey,
            intent = intent,
            topK = 10
        )

        // 3. 加载项目信息
        val projectInfo = loadProjectInfo(projectKey)

        // 4. 构建增强上下文
        return EnhancedContext(
            projectInfo = projectInfo,
            learningRecords = recallResult.learningRecords,
            domainKnowledge = recallResult.domainKnowledge,
            codeFragments = recallResult.codeFragments,
            userRequest = userRequest
        )
    }
}
```

### 4.3 与 SmanLoop 集成

```kotlin
/**
 * 扩展 PromptDispatcher
 */
class PromptDispatcherWithMemory(
    private val originalDispatcher: PromptDispatcher,
    private val contextInjector: EnhancedContextInjector
) {
    /**
     * 构建增强的 System Prompt
     */
    suspend fun buildEnhancedSystemPrompt(
        session: Session,
        projectKey: String,
        userRequest: String
    ): String {
        // 1. 获取原始 System Prompt
        val originalPrompt = originalDispatcher.buildSystemPrompt(session)

        // 2. 注入增强上下文
        val enhancedContext = contextInjector.inject(
            session = session,
            userRequest = userRequest,
            projectKey = projectKey
        )

        // 3. 组装
        return """
$originalPrompt

---

${enhancedContext.toSystemPromptSection()}
        """.trimIndent()
    }
}
```

## 5. 完整示例

### 5.1 用户请求处理流程

```
用户: "增加先本后息的还款方式"
                │
                ▼
┌─────────────────────────────────────────────────────────────────┐
│ SmanChatPanel.sendMessage()                                     │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 1: 意图分析                                                 │
│                                                                 │
│   IntentAnalyzer.analyze("增加先本后息的还款方式")               │
│                                                                 │
│   结果:                                                         │
│   {                                                             │
│     "domains": ["还款", "还款方式"],                             │
│     "action": "ADD_FEATURE",                                    │
│     "keywords": ["先本后息", "还款方式", "计算逻辑"]             │
│   }                                                             │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 2: 多路召回 (并行)                                          │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │ 向量语义搜索 (BGE-M3)                                    │  │
│   │                                                          │  │
│   │ 召回:                                                     │  │
│   │ 1. [0.92] "还款方式有哪些类型？"                         │  │
│   │ 2. [0.89] "还款计算的核心逻辑是什么？"                   │  │
│   │ 3. [0.86] "还款模块与其他模块的交互关系？"               │  │
│   │ 4. [0.83] "先息后本的实现方式？"                         │  │
│   └─────────────────────────────────────────────────────────┘  │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │ 领域知识查询                                             │  │
│   │                                                          │  │
│   │ 召回:                                                     │  │
│   │ - 还款领域知识概述                                        │  │
│   │ - 还款方式枚举定义                                        │  │
│   │ - 还款计算公式                                            │  │
│   └─────────────────────────────────────────────────────────┘  │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │ 代码片段搜索                                             │  │
│   │                                                          │  │
│   │ 召回:                                                     │  │
│   │ - RepaymentService.java                                  │  │
│   │ - RepaymentType.java                                     │  │
│   │ - RepaymentCalculator.java                               │  │
│   └─────────────────────────────────────────────────────────┘  │
│                                                                 │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 3: Rerank (BGE-Reranker)                                   │
│                                                                 │
│   重新排序，确保最相关的排在前面                                 │
│                                                                 │
│   最终排序:                                                      │
│   1. [0.95] "还款方式有哪些类型？"                              │
│   2. [0.93] "还款计算的核心逻辑是什么？"                        │
│   3. [0.90] "先息后本的实现方式？"                              │
│   ...                                                           │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 4: 构建增强上下文                                          │
│                                                                 │
│   System Prompt 补充:                                           │
│   ```                                                           │
│   ## 项目背景                                                    │
│   - 技术栈: Spring Boot, MyBatis, MySQL                         │
│                                                                 │
│   ## 相关领域知识                                                │
│   ### 还款                                                       │
│   还款模块处理所有还款相关业务，包括还款计划生成、还款执行、      │
│   还款状态跟踪等。                                               │
│   关键文件: RepaymentService.java, RepaymentCalculator.java     │
│                                                                 │
│   ## 相关学习记录                                                │
│   ### 知识 1 (相关度: 95%)                                       │
│   问题: 还款方式有哪些类型？                                     │
│   答案: 还款方式包括等额本息、等额本金、先息后本三种。           │
│         定义在 RepaymentType 枚举中。                            │
│   涉及文件: RepaymentType.java, RepaymentCalculator.java        │
│                                                                 │
│   ### 知识 2 (相关度: 93%)                                       │
│   问题: 还款计算的核心逻辑是什么？                               │
│   答案: 还款计算在 RepaymentCalculator 中实现，                  │
│         根据不同的还款方式调用不同的计算策略。                    │
│   涉及文件: RepaymentCalculator.java                            │
│   ```                                                           │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 5: SmanLoop ReAct 循环                                     │
│                                                                 │
│   Round 1: LLM 理解需求                                         │
│   - 已知: RepaymentType 枚举定义了三种还款方式                   │
│   - 已知: RepaymentCalculator 根据类型调用不同策略               │
│   - 任务: 增加新的 "先本后息" 枚举值和计算策略                   │
│                                                                 │
│   Round 2: 定位代码                                             │
│   - read_file(RepaymentType.java)                               │
│   - read_file(RepaymentCalculator.java)                         │
│                                                                 │
│   Round 3: 生成修改方案                                         │
│   - 在 RepaymentType 中增加 PRINCIPAL_FIRST 枚举值              │
│   - 在 RepaymentCalculator 中增加对应的计算方法                  │
│                                                                 │
│   Round 4: 应用修改                                             │
│   - apply_change(...)                                           │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 6: 返回结果给用户                                          │
│                                                                 │
│   "已为您添加先本后息还款方式：                                  │
│                                                                 │
│   1. 在 RepaymentType.java 中增加了 PRINCIPAL_FIRST 枚举值      │
│   2. 在 RepaymentCalculator.java 中增加了 calculatePrincipalFirst() │
│                                                                 │
│   建议测试：                                                     │
│   - 验证新的还款计算是否正确                                    │
│   - 检查数据库字段是否需要更新                                  │
│   "                                                             │
└─────────────────────────────────────────────────────────────────┘
```

## 6. 性能优化

### 6.1 缓存策略

```kotlin
/**
 * 召回结果缓存
 */
class RecallCache {
    private val cache = ConcurrentHashMap<String, CachedRecall>()

    /**
     * 获取缓存的召回结果
     */
    fun get(query: String, projectKey: String): RecallResult? {
        val key = "$projectKey:$query"
        val cached = cache[key] ?: return null

        // 5 分钟过期
        if (System.currentTimeMillis() - cached.timestamp > 300_000) {
            cache.remove(key)
            return null
        }

        return cached.result
    }

    /**
     * 缓存召回结果
     */
    fun put(query: String, projectKey: String, result: RecallResult) {
        val key = "$projectKey:$query"
        cache[key] = CachedRecall(result, System.currentTimeMillis())
    }
}
```

### 6.2 并行召回

```kotlin
/**
 * 并行执行三路召回
 */
suspend fun parallelRecall(
    projectKey: String,
    intent: UserIntent
): RecallResult = coroutineScope {
    val deferredVector = async { vectorSearch.search(...) }
    val deferredDomain = async { domainQuery.query(...) }
    val deferredKeyword = async { keywordMatcher.search(...) }

    // 等待所有完成
    val vectorResults = deferredVector.await()
    val domainResults = deferredDomain.await()
    val keywordResults = deferredKeyword.await()

    merge(vectorResults, domainResults, keywordResults)
}
```

### 6.3 Token 预算控制

```kotlin
/**
 * Token 预算控制器
 */
class TokenBudgetController(
    private val maxTokens: Int = 4000
) {
    /**
     * 裁剪上下文到预算内
     */
    fun trimToBudget(context: EnhancedContext): EnhancedContext {
        var currentTokens = estimateTokens(context.projectInfo.toString())
        var remainingTokens = maxTokens - currentTokens

        val trimmedRecords = mutableListOf<LearningRecordWithScore>()
        for (record in context.learningRecords) {
            val recordTokens = estimateTokens(record.record.answer)
            if (remainingTokens >= recordTokens) {
                trimmedRecords.add(record)
                remainingTokens -= recordTokens
            } else {
                break
            }
        }

        return context.copy(learningRecords = trimmedRecords)
    }
}
```

## 7. 设计要点总结

| 要点 | 说明 |
|------|------|
| **多路召回** | 向量 + 领域 + 关键词三路并行，确保召回完整 |
| **Rerank 精排** | 用 BGE-Reranker 提升相关性 |
| **上下文注入** | 通过 System Prompt 注入，不改变 SmanLoop 结构 |
| **Token 控制** | 预算内最大化信息量 |
| **缓存加速** | 热门查询缓存，减少重复计算 |
