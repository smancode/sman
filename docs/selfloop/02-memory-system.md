# 02 - 记忆系统设计

## 1. 记忆系统核心目标

**学习成果必须能被使用**：后台学到的每一条知识，都要能被前台用户请求高效检索和使用。

## 2. 三层记忆架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        三层记忆架构                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │                   GlobalMemory (全局)                    │  │
│   │                                                         │  │
│   │   • 跨项目通用知识                                       │  │
│   │   • 最佳实践                                             │  │
│   │   • 通用代码模式                                         │  │
│   │   • 生命周期: 永久                                       │  │
│   └─────────────────────────────────────────────────────────┘  │
│                              │                                  │
│                              │ 包含                             │
│                              ▼                                  │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │                  ProjectMemory (项目)                    │  │
│   │                                                         │  │
│   │   • 项目领域知识                                         │  │
│   │   • 学习记录索引                                         │  │
│   │   • 失败记录索引                                         │  │
│   │   • 代码模式                                             │  │
│   │   • 生命周期: 项目存在期间                               │  │
│   └─────────────────────────────────────────────────────────┘  │
│                              │                                  │
│                              │ 包含                             │
│                              ▼                                  │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │                  SessionMemory (会话)                    │  │
│   │                                                         │  │
│   │   • 当前对话历史                                         │  │
│   │   • 工具调用历史                                         │  │
│   │   • 临时上下文                                           │  │
│   │   • 生命周期: 会话期间                                   │  │
│   └─────────────────────────────────────────────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## 3. 数据结构设计

### 3.1 核心数据类

```kotlin
// ==================== 学习记录 (核心) ====================

/**
 * 学习记录 - 后台自问自答产生的知识
 * 这是最重要的数据结构，前台通过语义搜索找到它
 */
@Serializable
data class LearningRecord(
    // 基础信息
    val id: String,                    // 唯一标识
    val projectKey: String,            // 所属项目
    val createdAt: Long,               // 创建时间

    // 问题与答案
    val question: String,              // 好问题 (LLM 生成)
    val questionType: QuestionType,    // 问题类型
    val answer: String,                // 答案 (LLM 总结)

    // 探索过程
    val explorationPath: List<ToolCallStep>, // 探索路径

    // 元数据
    val confidence: Double,            // 置信度 (0.0 - 1.0)
    val sourceFiles: List<String>,     // 涉及的源文件
    val relatedRecords: List<String>,  // 关联的其他学习记录 ID

    // 向量 (用于语义搜索)
    val questionVector: FloatArray? = null,  // 问题向量
    val answerVector: FloatArray? = null,    // 答案向量

    // 标签和分类
    val tags: List<String> = emptyList(),    // 标签
    val domain: String? = null,              // 领域 (如: 还款、订单、支付)
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 问题类型枚举
 */
enum class QuestionType {
    CODE_STRUCTURE,      // 代码结构问题: "项目的分层架构是什么？"
    BUSINESS_LOGIC,      // 业务逻辑问题: "还款的核心流程是什么？"
    DATA_FLOW,           // 数据流问题: "订单状态如何流转？"
    DEPENDENCY,          // 依赖关系问题: "支付依赖哪些外部服务？"
    CONFIGURATION,       // 配置问题: "数据库连接池如何配置？"
    ERROR_ANALYSIS,      // 错误分析: "常见的异常有哪些？"
    BEST_PRACTICE,       // 最佳实践: "这个项目使用了什么设计模式？"
    DOMAIN_KNOWLEDGE     // 领域知识: "先息后本是什么意思？"
}

/**
 * 工具调用步骤
 */
@Serializable
data class ToolCallStep(
    val toolName: String,              // 工具名
    val parameters: Map<String, String>, // 参数
    val resultSummary: String,         // 结果摘要
    val timestamp: Long                // 时间戳
)

// ==================== 项目记忆 ====================

/**
 * 项目记忆 - 项目级的所有知识
 */
@Serializable
data class ProjectMemory(
    val projectKey: String,
    val projectPath: String,

    // 领域知识 (提炼后的结构化知识)
    val domainKnowledge: List<DomainKnowledge>,

    // 学习记录索引 (指向具体记录)
    val learningRecordIds: List<String>,

    // 失败记录索引
    val failureRecordIds: List<String>,

    // 代码模式
    val codePatterns: List<CodePattern>,

    // 进化状态
    val evolutionStatus: EvolutionStatus,

    // 时间戳
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 领域知识 - 提炼后的结构化知识
 */
@Serializable
data class DomainKnowledge(
    val id: String,
    val domain: String,              // 领域名 (如: 还款、订单)
    val summary: String,             // 概述
    val keyConcepts: List<Concept>,  // 关键概念
    val keyFiles: List<String>,      // 关键文件
    val sourceRecordIds: List<String> // 来源学习记录
)

/**
 * 概念
 */
@Serializable
data class Concept(
    val name: String,               // 概念名
    val description: String,        // 描述
    val relatedFiles: List<String>  // 相关文件
)

/**
 * 代码模式
 */
@Serializable
data class CodePattern(
    val patternType: String,        // 模式类型 (如: Strategy, Factory)
    val description: String,        // 描述
    val exampleFiles: List<String>, // 示例文件
    val usageScenarios: List<String> // 使用场景
)

/**
 * 进化状态
 */
@Serializable
data class EvolutionStatus(
    val isEnabled: Boolean = true,
    val currentPhase: EvolutionPhase = EvolutionPhase.IDLE,
    val consecutiveErrors: Int = 0,
    val lastError: String? = null,
    val backoffUntil: Long? = null,
    val questionsGeneratedToday: Int = 0,
    val lastResetDate: String = "",
    val totalQuestionsExplored: Int = 0
)

enum class EvolutionPhase {
    IDLE,               // 空闲
    GENERATING_QUESTION,// 生成问题中
    EXPLORING,          // 探索中
    SUMMARIZING,        // 总结中
    BACKING_OFF         // 退避中
}

// ==================== 失败记录 ====================

/**
 * 失败记录 - 用于死循环防护和学习
 */
@Serializable
data class FailureRecord(
    val id: String,
    val projectKey: String,
    val operationType: OperationType,
    val operation: String,           // 操作描述
    val error: String,               // 错误信息
    val context: Map<String, String>,// 上下文
    val timestamp: Long,
    val retryCount: Int = 0,
    val status: FailureStatus = FailureStatus.PENDING,
    val avoidStrategy: String? = null // 避免策略 (LLM 生成)
)

enum class OperationType {
    // 现有类型
    PROJECT_ANALYSIS,
    CODE_VECTORIZATION,

    // 新增类型
    SELF_EVOLUTION_QUESTION,     // 问题生成失败
    SELF_EVOLUTION_EXPLORATION,  // 工具探索失败
    SELF_EVOLUTION_SUMMARY       // 学习总结失败
}

enum class FailureStatus {
    PENDING,    // 待处理
    RETRYING,   // 重试中
    RECOVERED,  // 已恢复
    FAILED      // 彻底失败
}

// ==================== 全局记忆 ====================

/**
 * 全局记忆 - 跨项目的通用知识
 */
@Serializable
data class GlobalMemory(
    val bestPractices: List<BestPractice>,
    val crossProjectPatterns: List<CrossProjectPattern>,
    val version: String,
    val updatedAt: Long
)

/**
 * 最佳实践
 */
@Serializable
data class BestPractice(
    val id: String,
    val title: String,
    val description: String,
    val applicableScenarios: List<String>,
    val sourceProjects: List<String>
)

/**
 * 跨项目模式
 */
@Serializable
data class CrossProjectPattern(
    val patternName: String,
    val description: String,
    val occurrences: Int,            // 出现次数
    val exampleProjects: List<String>
)
```

### 3.2 project_map.json 扩展结构

```json
{
  "version": "2.0.0",
  "lastUpdate": 1739462400000,

  "projects": {
    "my-project": {
      "path": "/Users/liuchao/projects/my-project",
      "lastAnalyzed": 1739462300000,
      "projectMd5": "abc123...",

      "analysisStatus": {
        "project_structure": "COMPLETED",
        "tech_stack": "COMPLETED",
        "api_entries": "COMPLETED",
        "db_entities": "COMPLETED",
        "enums": "COMPLETED",
        "config_files": "COMPLETED"
      },

      "mdFiles": {
        "src/main/java/com/example/Handler.java": "def456..."
      },

      "evolutionStatus": {
        "isEnabled": true,
        "currentPhase": "IDLE",
        "consecutiveErrors": 0,
        "lastError": null,
        "backoffUntil": null,
        "questionsGeneratedToday": 5,
        "lastResetDate": "2026-02-13",
        "totalQuestionsExplored": 15
      },

      "learningRecordIndex": [
        "lr-20260213-001",
        "lr-20260213-002",
        "lr-20260213-003"
      ],

      "failureRecordIndex": [
        "fr-20260213-001"
      ],

      "domainKnowledgeIndex": [
        "dk-repayment",
        "dk-order",
        "dk-payment"
      ],

      "lastEvolutionTime": 1739462000000
    }
  }
}
```

## 4. 学习记录存储设计

### 4.1 H2 数据库表结构

```sql
-- 学习记录表
CREATE TABLE learning_records (
    id VARCHAR(64) PRIMARY KEY,
    project_key VARCHAR(128) NOT NULL,
    created_at BIGINT NOT NULL,

    -- 问题与答案
    question TEXT NOT NULL,
    question_type VARCHAR(32) NOT NULL,
    answer TEXT NOT NULL,

    -- 探索路径 (JSON)
    exploration_path TEXT,

    -- 元数据
    confidence DOUBLE NOT NULL,
    source_files TEXT,           -- JSON 数组
    related_records TEXT,        -- JSON 数组

    -- 标签
    tags TEXT,                   -- JSON 数组
    domain VARCHAR(64),
    metadata TEXT,               -- JSON 对象

    -- 索引字段
    INDEX idx_project_key (project_key),
    INDEX idx_domain (domain),
    INDEX idx_created_at (created_at)
);

-- 学习记录向量表
CREATE TABLE learning_vectors (
    id VARCHAR(64) PRIMARY KEY,
    record_id VARCHAR(64) NOT NULL,
    vector_type VARCHAR(16) NOT NULL,  -- 'question' or 'answer'
    vector BLOB NOT NULL,              -- 序列化的 float array

    FOREIGN KEY (record_id) REFERENCES learning_records(id) ON DELETE CASCADE,
    INDEX idx_record_id (record_id)
);

-- 失败记录表
CREATE TABLE failure_records (
    id VARCHAR(64) PRIMARY KEY,
    project_key VARCHAR(128) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    operation TEXT NOT NULL,
    error TEXT NOT NULL,
    context TEXT,                       -- JSON 对象
    timestamp BIGINT NOT NULL,
    retry_count INT DEFAULT 0,
    status VARCHAR(16) DEFAULT 'PENDING',
    avoid_strategy TEXT,

    INDEX idx_project_key (project_key),
    INDEX idx_status (status),
    INDEX idx_timestamp (timestamp)
);

-- 领域知识表
CREATE TABLE domain_knowledge (
    id VARCHAR(64) PRIMARY KEY,
    project_key VARCHAR(128) NOT NULL,
    domain VARCHAR(64) NOT NULL,
    summary TEXT NOT NULL,
    key_concepts TEXT,                  -- JSON 数组
    key_files TEXT,                     -- JSON 数组
    source_record_ids TEXT,             -- JSON 数组
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,

    INDEX idx_project_key (project_key),
    INDEX idx_domain (domain)
);
```

### 4.2 文件存储结构

```
{projectRoot}/.sman/
├── analysis.mv.db              # H2 数据库
├── project_map.json            # 项目映射 (扩展版)
│
├── memory/                     # 记忆存储目录 (新增)
│   ├── learning/               # 学习记录 (JSON 备份)
│   │   ├── lr-20260213-001.json
│   │   ├── lr-20260213-002.json
│   │   └── ...
│   │
│   ├── failures/               # 失败记录 (JSON 备份)
│   │   ├── fr-20260213-001.json
│   │   └── ...
│   │
│   ├── domain/                 # 领域知识 (JSON)
│   │   ├── repayment.json
│   │   ├── order.json
│   │   └── ...
│   │
│   └── global/                 # 全局记忆
│       ├── best_practices.json
│       └── patterns.json
│
├── md/                         # 现有 MD 文件
│   ├── reports/
│   └── classes/
│
└── cache/                      # 缓存目录
```

## 5. SharedMemoryManager 接口设计

```kotlin
/**
 * 共享记忆管理器 - 前后台统一的记忆访问入口
 */
interface SharedMemoryManager {

    // ==================== 学习记录操作 ====================

    /**
     * 保存学习记录 (后台写入)
     *
     * @param record 学习记录
     * @return 保存后的记录 (包含向量)
     */
    suspend fun saveLearningRecord(record: LearningRecord): LearningRecord

    /**
     * 语义搜索学习记录 (前台查询)
     *
     * @param query 查询文本
     * @param projectKey 项目键 (可选，null 表示全局搜索)
     * @param topK 返回数量
     * @return 相关的学习记录
     */
    suspend fun searchLearningRecords(
        query: String,
        projectKey: String? = null,
        topK: Int = 5
    ): List<LearningRecordWithScore>

    /**
     * 根据领域获取学习记录
     */
    suspend fun getLearningRecordsByDomain(
        projectKey: String,
        domain: String
    ): List<LearningRecord>

    /**
     * 获取最近的学习记录
     */
    suspend fun getRecentLearningRecords(
        projectKey: String,
        limit: Int = 10
    ): List<LearningRecord>

    // ==================== 项目记忆操作 ====================

    /**
     * 获取项目记忆
     */
    suspend fun getProjectMemory(projectKey: String): ProjectMemory

    /**
     * 更新项目记忆
     */
    suspend fun updateProjectMemory(projectKey: String, memory: ProjectMemory)

    /**
     * 获取项目的领域知识
     */
    suspend fun getDomainKnowledge(
        projectKey: String,
        domain: String
    ): DomainKnowledge?

    // ==================== 失败记录操作 ====================

    /**
     * 记录失败 (用于死循环防护)
     */
    suspend fun recordFailure(record: FailureRecord)

    /**
     * 检查操作是否已失败过 (用于去重)
     */
    suspend fun hasFailedBefore(
        projectKey: String,
        operationType: OperationType,
        operationSignature: String
    ): Boolean

    /**
     * 获取失败记录
     */
    suspend fun getFailureRecord(id: String): FailureRecord?

    /**
     * 更新失败记录状态
     */
    suspend fun updateFailureStatus(
        id: String,
        status: FailureStatus,
        avoidStrategy: String? = null
    )

    // ==================== 全局记忆操作 ====================

    /**
     * 获取全局记忆
     */
    suspend fun getGlobalMemory(): GlobalMemory

    /**
     * 添加最佳实践
     */
    suspend fun addBestPractice(practice: BestPractice)
}

/**
 * 带相似度分数的学习记录
 */
data class LearningRecordWithScore(
    val record: LearningRecord,
    val score: Double              // 相似度分数 (0.0 - 1.0)
)
```

## 6. 语义搜索流程

```
┌─────────────────────────────────────────────────────────────────┐
│              学习记录语义搜索流程                                │
└─────────────────────────────────────────────────────────────────┘

用户请求: "还款方式有哪些？"
              │
              ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 1: 向量化查询                                               │
│         BGE-M3.embed("还款方式有哪些？")                         │
│         → 1024 维向量                                           │
└─────────────────────────────┬───────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 2: 向量检索                                                 │
│         在 learning_vectors 表中搜索相似向量                     │
│         → 找到 topK=5 个最相似的学习记录 ID                      │
└─────────────────────────────┬───────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 3: 加载学习记录                                             │
│         从 learning_records 表加载完整记录                       │
│         → 5 条学习记录                                          │
└─────────────────────────────┬───────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 4: Rerank (可选)                                           │
│         BGE-Reranker.rerank(query, records)                     │
│         → 重新排序，提升相关性                                   │
└─────────────────────────────┬───────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 5: 返回结果                                                 │
│                                                                 │
│   1. [0.92] Q: "还款方式有哪些类型？"                           │
│      A: "还款方式包括等额本息、等额本金、先息后本..."            │
│      涉及文件: [RepaymentType.java, ...]                        │
│                                                                 │
│   2. [0.88] Q: "还款计算的核心逻辑是什么？"                     │
│      A: "还款计算在 RepaymentCalculator 中实现..."              │
│      涉及文件: [RepaymentCalculator.java, ...]                  │
│                                                                 │
│   3. [0.85] Q: "还款模块与其他模块的交互关系？"                  │
│      A: "还款模块与订单、支付、通知模块交互..."                  │
│      涉及文件: [...]                                            │
│                                                                 │
│   ...                                                           │
└─────────────────────────────────────────────────────────────────┘
```

## 7. 记忆加载策略

### 7.1 前台加载策略 (用户请求时)

```kotlin
/**
 * 前台记忆加载器
 */
class ForegroundMemoryLoader(
    private val memoryManager: SharedMemoryManager
) {
    /**
     * 根据用户请求加载相关记忆
     */
    suspend fun loadRelevantMemory(
        projectKey: String,
        userRequest: String
    ): LoadedMemory {
        // 1. 语义搜索学习记录
        val learningRecords = memoryManager.searchLearningRecords(
            query = userRequest,
            projectKey = projectKey,
            topK = 5
        )

        // 2. 获取项目基础记忆
        val projectMemory = memoryManager.getProjectMemory(projectKey)

        // 3. 根据学习记录涉及的领域，加载领域知识
        val domains = learningRecords
            .mapNotNull { it.record.domain }
            .distinct()

        val domainKnowledge = domains.mapNotNull { domain ->
            memoryManager.getDomainKnowledge(projectKey, domain)
        }

        // 4. 组装返回
        return LoadedMemory(
            learningRecords = learningRecords,
            projectMemory = projectMemory,
            domainKnowledge = domainKnowledge
        )
    }
}

/**
 * 加载的记忆
 */
data class LoadedMemory(
    val learningRecords: List<LearningRecordWithScore>,
    val projectMemory: ProjectMemory,
    val domainKnowledge: List<DomainKnowledge>
) {
    /**
     * 生成给 LLM 的上下文摘要
     */
    fun toContextSummary(): String {
        val sb = StringBuilder()

        sb.append("## 项目背景\n")
        sb.append("- 技术栈: ${projectMemory.techStack}\n")
        sb.append("- 领域: ${projectMemory.domainKnowledge.map { it.domain }}\n\n")

        sb.append("## 相关学习记录\n")
        learningRecords.forEachIndexed { index, recordWithScore ->
            sb.append("### 相关知识 ${index + 1} (相关度: ${(recordWithScore.score * 100).toInt()}%)\n")
            sb.append("问题: ${recordWithScore.record.question}\n")
            sb.append("答案: ${recordWithScore.record.answer}\n")
            sb.append("涉及文件: ${recordWithScore.record.sourceFiles}\n\n")
        }

        sb.append("## 领域知识\n")
        domainKnowledge.forEach { dk ->
            sb.append("### ${dk.domain}\n")
            sb.append("${dk.summary}\n")
            sb.append("关键概念: ${dk.keyConcepts.map { it.name }}\n\n")
        }

        return sb.toString()
    }
}
```

### 7.2 后台加载策略 (自问自答时)

```kotlin
/**
 * 后台记忆加载器
 */
class BackgroundMemoryLoader(
    private val memoryManager: SharedMemoryManager
) {
    /**
     * 加载项目记忆用于问题生成
     */
    suspend fun loadForQuestionGeneration(projectKey: String): ProjectContext {
        val projectMemory = memoryManager.getProjectMemory(projectKey)

        // 获取知识盲点
        val knowledgeGaps = identifyKnowledgeGaps(projectMemory)

        // 获取最近的学习记录 (避免重复)
        val recentRecords = memoryManager.getRecentLearningRecords(
            projectKey = projectKey,
            limit = 20
        )

        // 获取失败的探索 (用于避免)
        val failedExplorations = getRecentFailures(projectKey)

        return ProjectContext(
            projectMemory = projectMemory,
            knowledgeGaps = knowledgeGaps,
            recentLearning = recentRecords,
            failedExplorations = failedExplorations
        )
    }

    /**
     * 识别知识盲点
     */
    private fun identifyKnowledgeGaps(memory: ProjectMemory): List<KnowledgeGap> {
        val gaps = mutableListOf<KnowledgeGap>()

        // 基于代码结构找盲点
        // 例如: 某个目录下还没有学习记录

        // 基于领域找盲点
        // 例如: 某个领域的知识覆盖不完整

        return gaps
    }
}

/**
 * 知识盲点
 */
data class KnowledgeGap(
    val type: GapType,
    val description: String,
    val priority: Int,
    val suggestedQuestion: String
)

enum class GapType {
    UNEXPLORED_DIRECTORY,    // 未探索的目录
    INCOMPLETE_DOMAIN,       // 不完整的领域知识
    MISSING_DEPENDENCY,      // 缺失的依赖关系
    UNDOCUMENTED_PATTERN     // 未记录的代码模式
}
```

## 8. 设计要点总结

| 要点 | 说明 |
|------|------|
| **向量优先** | 通过 BGE-M3 向量化，支持语义搜索 |
| **分层存储** | JSON (人类可读) + H2 (高效查询) |
| **双向索引** | project_map.json 存索引，实际数据在 H2 |
| **增量学习** | 每条学习记录独立，不覆盖 |
| **关联关系** | 学习记录之间可以关联 |
| **前台优化** | 前台查询优先考虑速度，后台写入优先考虑完整性 |
