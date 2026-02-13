# 06 - 实现计划与任务拆分

## 1. 实现优先级

```
Phase 1: 基础设施 (Week 1)
├── P0: 记忆系统扩展
│   ├── SharedMemoryManager 接口
│   ├── LearningRecord 数据结构
│   ├── H2 表结构
│   └── 向量化存储
│
└── P0: 死循环防护
    ├── QuestionDeduplicator
    ├── ToolCallDeduplicator
    └── ExponentialBackoff

Phase 2: 后台循环 (Week 2)
├── P1: SelfEvolutionLoop 主循环
│   ├── 启动/停止机制
│   ├── 循环主体
│   └── 状态管理
│
├── P1: QuestionGenerator
│   ├── Prompt 设计
│   ├── LLM 调用
│   └── 问题过滤
│
└── P1: ToolExplorer
    ├── 探索决策
    ├── 工具调用
    └── 结果收集

Phase 3: 前台集成 (Week 3)
├── P2: 资源加载
│   ├── 多路召回
│   ├── Rerank
│   └── 上下文注入
│
└── P2: SmanLoop 集成
    ├── PromptDispatcher 扩展
    └── expert_consult 增强

Phase 4: 测试与优化 (Week 4)
├── P3: 集成测试
│   ├── 后台循环测试
│   ├── 前台加载测试
│   └── 死循环防护测试
│
└── P3: 性能优化
    ├── 缓存优化
    ├── Token 优化
    └── 监控完善
```

## 2. 详细任务拆分

### Phase 1: 基础设施

#### Task 1.1: LearningRecord 数据结构

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/evolution/model/LearningRecord.kt

/**
 * 任务清单:
 * 1. 定义 LearningRecord 数据类
 * 2. 定义 QuestionType 枚举
 * 3. 定义 ToolCallStep 数据类
 * 4. 添加序列化注解
 * 5. 编写单元测试
 */

@Serializable
data class LearningRecord(
    val id: String,
    val projectKey: String,
    val createdAt: Long,
    val question: String,
    val questionType: QuestionType,
    val answer: String,
    val explorationPath: List<ToolCallStep>,
    val confidence: Double,
    val sourceFiles: List<String>,
    val questionVector: FloatArray? = null,
    val answerVector: FloatArray? = null,
    val tags: List<String> = emptyList(),
    val domain: String? = null
)

// 预计工作量: 2 小时
// 依赖: 无
```

#### Task 1.2: H2 表结构

```sql
-- 文件: src/main/resources/db/evolution-schema.sql

/**
 * 任务清单:
 * 1. 创建 learning_records 表
 * 2. 创建 learning_vectors 表
 * 3. 创建 failure_records 表
 * 4. 创建 domain_knowledge 表
 * 5. 添加索引
 * 6. 编写迁移脚本
 */

CREATE TABLE learning_records (
    id VARCHAR(64) PRIMARY KEY,
    project_key VARCHAR(128) NOT NULL,
    created_at BIGINT NOT NULL,
    question TEXT NOT NULL,
    question_type VARCHAR(32) NOT NULL,
    answer TEXT NOT NULL,
    exploration_path TEXT,
    confidence DOUBLE NOT NULL,
    source_files TEXT,
    tags TEXT,
    domain VARCHAR(64),
    INDEX idx_project_key (project_key),
    INDEX idx_domain (domain)
);

-- 预计工作量: 1 小时
-- 依赖: Task 1.1
```

#### Task 1.3: SharedMemoryManager 接口

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/evolution/memory/SharedMemoryManager.kt

/**
 * 任务清单:
 * 1. 定义 SharedMemoryManager 接口
 * 2. 实现 saveLearningRecord 方法
 * 3. 实现 searchLearningRecords 方法
 * 4. 实现 getProjectMemory 方法
 * 5. 实现失败记录相关方法
 * 6. 编写集成测试
 */

interface SharedMemoryManager {
    suspend fun saveLearningRecord(record: LearningRecord): LearningRecord
    suspend fun searchLearningRecords(query: String, projectKey: String, topK: Int): List<LearningRecordWithScore>
    suspend fun getProjectMemory(projectKey: String): ProjectMemory
    // ... 其他方法
}

// 预计工作量: 4 小时
// 依赖: Task 1.1, Task 1.2
```

#### Task 1.4: 向量化存储

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/evolution/memory/LearningVectorStore.kt

/**
 * 任务清单:
 * 1. 复用 BgeM3Client 进行向量化
 * 2. 复用 TieredVectorStore 进行存储
 * 3. 实现学习记录的向量搜索
 * 4. 实现 L1/L2/L3 缓存策略
 * 5. 编写性能测试
 */

class LearningVectorStore(
    private val bgeM3Client: BgeM3Client,
    private val vectorStore: TieredVectorStore
) {
    suspend fun vectorizeAndStore(record: LearningRecord): LearningRecord { ... }
    suspend fun search(query: String, topK: Int): List<SearchResult> { ... }
}

// 预计工作量: 3 小时
// 依赖: Task 1.3
```

#### Task 1.5: QuestionDeduplicator

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/evolution/guard/QuestionDeduplicator.kt

/**
 * 任务清单:
 * 1. 实现向量相似度计算
 * 2. 实现重复检测逻辑
 * 3. 设置相似度阈值 (0.9)
 * 4. 编写单元测试
 */

// 预计工作量: 2 小时
// 依赖: Task 1.4
```

#### Task 1.6: ToolCallDeduplicator

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/evolution/guard/ToolCallDeduplicator.kt

/**
 * 任务清单:
 * 1. 实现 LRU 缓存
 * 2. 实现调用签名计算
 * 3. 实现重复检测
 * 4. 实现缓存结果获取
 * 5. 编写单元测试
 */

// 预计工作量: 2 小时
// 依赖: 无
```

#### Task 1.7: ExponentialBackoff

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/evolution/guard/ExponentialBackoff.kt

/**
 * 任务清单:
 * 1. 实现退避时间计算
 * 2. 实现抖动 (jitter)
 * 3. 实现状态管理
 * 4. 编写单元测试
 */

// 预计工作量: 1 小时
// 依赖: 无
```

### Phase 2: 后台循环

#### Task 2.1: SelfEvolutionLoop 主循环

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/evolution/loop/SelfEvolutionLoop.kt

/**
 * 任务清单:
 * 1. 实现 start/stop 方法
 * 2. 实现主循环 while(enabled)
 * 3. 实现单次迭代逻辑
 * 4. 实现错误处理
 * 5. 实现状态管理
 * 6. 编写集成测试
 */

// 预计工作量: 4 小时
// 依赖: Task 1.3, Task 1.5, Task 1.6, Task 1.7
```

#### Task 2.2: QuestionGenerator

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/evolution/generator/QuestionGenerator.kt

/**
 * 任务清单:
 * 1. 设计问题生成 Prompt
 * 2. 实现 LLM 调用
 * 3. 实现 JSON 解析
 * 4. 实现问题过滤 (结合 QuestionDeduplicator)
 * 5. 编写单元测试
 */

// 预计工作量: 3 小时
// 依赖: Task 1.5
```

#### Task 2.3: ToolExplorer

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/evolution/explorer/ToolExplorer.kt

/**
 * 任务清单:
 * 1. 实现探索决策 (LLM)
 * 2. 实现工具调用 (复用现有工具)
 * 3. 实现结果收集
 * 4. 实现探索终止条件
 * 5. 编写集成测试
 */

// 预计工作量: 4 小时
// 依赖: Task 2.1
```

#### Task 2.4: LearningRecorder

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/evolution/recorder/LearningRecorder.kt

/**
 * 任务清单:
 * 1. 实现总结 Prompt 设计
 * 2. 实现 LLM 调用
 * 3. 实现 JSON 解析
 * 4. 实现向量化
 * 5. 实现持久化
 * 6. 编写集成测试
 */

// 预计工作量: 3 小时
// 依赖: Task 1.3, Task 1.4
```

### Phase 3: 前台集成

#### Task 3.1: MultiPathRecaller

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/evolution/recall/MultiPathRecaller.kt

/**
 * 任务清单:
 * 1. 实现向量语义搜索
 * 2. 实现领域知识查询
 * 3. 实现关键词匹配
 * 4. 实现并行召回
 * 5. 实现结果合并去重
 * 6. 编写性能测试
 */

// 预计工作量: 4 小时
// 依赖: Task 1.3, Task 1.4
```

#### Task 3.2: Reranker 集成

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/evolution/recall/Reranker.kt

/**
 * 任务清单:
 * 1. 复用 BgeReranker
 * 2. 实现学习记录 Rerank
 * 3. 实现 Token 预算控制
 * 4. 编写性能测试
 */

// 预计工作量: 2 小时
// 依赖: Task 3.1
```

#### Task 3.3: EnhancedContextInjector

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/evolution/context/EnhancedContextInjector.kt

/**
 * 任务清单:
 * 1. 实现意图分析 (LLM)
 * 2. 实现上下文构建
 * 3. 实现 Token 预算裁剪
 * 4. 实现 System Prompt 注入
 * 5. 编写集成测试
 */

// 预计工作量: 3 小时
// 依赖: Task 3.1, Task 3.2
```

#### Task 3.4: SmanLoop 集成

```kotlin
// 文件: src/main/kotlin/com/smancode/sman/smancode/prompt/PromptDispatcher.kt (扩展)

/**
 * 任务清单:
 * 1. 扩展 PromptDispatcher
 * 2. 在 buildSystemPrompt 中注入增强上下文
 * 3. 实现上下文缓存
 * 4. 编写集成测试
 */

// 预计工作量: 2 小时
// 依赖: Task 3.3
```

### Phase 4: 测试与优化

#### Task 4.1: 集成测试

```kotlin
// 文件: src/test/kotlin/com/smancode/sman/evolution/integration/...

/**
 * 任务清单:
 * 1. 后台循环集成测试
 * 2. 前台加载集成测试
 * 3. 死循环防护测试
 * 4. 端到端测试
 */

// 预计工作量: 4 小时
// 依赖: Phase 1-3
```

#### Task 4.2: 性能优化

```kotlin
/**
 * 任务清单:
 * 1. 缓存策略优化
 * 2. 并行召回优化
 * 3. Token 预算优化
 * 4. 监控指标完善
 */

// 预计工作量: 3 小时
// 依赖: Task 4.1
```

## 3. 文件清单

### 新增文件

```
src/main/kotlin/com/smancode/sman/evolution/
├── model/
│   ├── LearningRecord.kt           # 学习记录
│   ├── ProjectMemory.kt            # 项目记忆
│   ├── FailureRecord.kt            # 失败记录
│   └── EvolutionStatus.kt          # 进化状态
│
├── memory/
│   ├── SharedMemoryManager.kt      # 共享记忆管理器
│   ├── LearningVectorStore.kt      # 学习记录向量存储
│   └── MemoryStorage.kt            # 持久化存储
│
├── guard/
│   ├── DoomLoopGuard.kt            # 统一防护入口
│   ├── QuestionDeduplicator.kt     # 问题去重
│   ├── ToolCallDeduplicator.kt     # 工具调用去重
│   ├── FailureRecordService.kt     # 失败记录服务
│   ├── ExponentialBackoff.kt       # 指数退避
│   └── DailyQuotaManager.kt        # 每日配额
│
├── loop/
│   ├── SelfEvolutionLoop.kt        # 后台主循环
│   └── EvolutionConfig.kt          # 配置
│
├── generator/
│   └── QuestionGenerator.kt        # 问题生成器
│
├── explorer/
│   └── ToolExplorer.kt             # 工具探索器
│
├── recorder/
│   └── LearningRecorder.kt         # 学习记录器
│
├── recall/
│   ├── MultiPathRecaller.kt        # 多路召回
│   ├── VectorSemanticSearch.kt     # 向量语义搜索
│   ├── DomainKnowledgeQuery.kt     # 领域知识查询
│   └── KeywordMatcher.kt           # 关键词匹配
│
└── context/
    ├── EnhancedContext.kt          # 增强上下文
    ├── EnhancedContextInjector.kt  # 上下文注入器
    └── TokenBudgetController.kt    # Token 预算控制

src/main/resources/db/
└── evolution-schema.sql            # H2 表结构

src/test/kotlin/com/smancode/sman/evolution/
├── model/
│   └── LearningRecordTest.kt
├── memory/
│   └── SharedMemoryManagerTest.kt
├── guard/
│   └── DoomLoopGuardTest.kt
├── loop/
│   └── SelfEvolutionLoopTest.kt
├── recall/
│   └── MultiPathRecallerTest.kt
└── integration/
    └── EvolutionIntegrationTest.kt
```

### 修改文件

```
src/main/kotlin/com/smancode/sman/
├── analysis/
│   ├── model/ProjectMap.kt         # 扩展 ProjectEntry
│   └── scheduler/ProjectAnalysisScheduler.kt  # 集成 SelfEvolutionLoop
│
├── smancode/
│   └── prompt/PromptDispatcher.kt  # 扩展，注入增强上下文
│
└── tools/ide/
    └── LocalExpertConsultService.kt # 扩展，支持学习记录搜索
```

## 4. 里程碑

| 里程碑 | 目标 | 完成标准 |
|--------|------|---------|
| **M1** | 基础设施就绪 | 记忆系统可读写，死循环防护可用 |
| **M2** | 后台循环可运行 | SelfEvolutionLoop 能自动学习并记录 |
| **M3** | 前台可加载 | 用户请求能加载后台学习成果 |
| **M4** | 集成测试通过 | 端到端流程完整可用 |

## 5. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| LLM 调用成本高 | 中 | Token 预算控制 + 每日配额 |
| 向量存储性能 | 中 | L1/L2/L3 缓存 + 索引优化 |
| 死循环 | 高 | 五层防护机制 |
| 前后台资源竞争 | 中 | 独立协程 + 资源隔离 |
