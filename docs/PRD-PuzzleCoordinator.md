# PRD: PuzzleCoordinator 自迭代系统

> 版本: 1.0
> 日期: 2026-02-23
> 作者: Claude

---

## 一、背景与目标

### 1.1 背景

SmanCode 已完成核心数据层：
- `PuzzleStore` - Markdown 格式存储项目知识拼图
- `MemoryStore` - 存储用户偏好和业务规则
- `GapDetector` - 检测知识空白
- `TaskScheduler` - 优先级调度
- `FeedbackCollector` - 收集用户反馈

但这些组件尚未整合，缺乏自动化的"自迭代"机制。

### 1.2 目标

构建一个**健壮的自迭代系统**，能够：
1. 自动检测项目知识空白
2. 按优先级执行分析任务
3. 静默更新项目知识库
4. 支持中断恢复、死循环防护、幂等执行

---

## 二、核心需求

### 2.1 功能需求

| 需求 | 描述 | 优先级 |
|------|------|--------|
| F1 | 自动检测知识空白（基于 Git diff、文件变更、用户查询） | P0 |
| F2 | 按优先级执行分析任务，静默更新 Puzzle | P0 |
| F3 | 中断恢复：任务执行到一半停机，重启后继续 | P0 |
| F4 | 死循环防护：同一任务不重复执行 | P0 |
| F5 | 幂等执行：重复触发不会产生副作用 | P0 |
| F6 | 知识过期检测：基于 Git diff 判断是否需要更新 | P1 |
| F7 | Token 预算管理：控制单次执行成本 | P1 |
| F8 | 知识注入：将 Puzzle 注入 LLM 上下文 | P1 |

### 2.2 非功能需求

| 需求 | 描述 |
|------|------|
| NF1 | 后台执行不影响 IDE 性能 |
| NF2 | 所有操作记录日志，可追溯 |
| NF3 | 用户可通过 UI 查看任务状态 |
| NF4 | 单个任务执行时间 < 30 秒 |

---

## 三、健壮性设计（核心）

### 3.1 状态机模型

每个分析任务有明确的状态流转：

```
PENDING → RUNNING → COMPLETED
    ↓         ↓
 SKIPPED   FAILED → PENDING (重试)
```

### 3.2 任务持久化

所有任务状态持久化到 `queue/pending.json`：

```json
{
  "version": 1,
  "lastUpdated": "2026-02-23T10:00:00Z",
  "tasks": [
    {
      "id": "task-001",
      "type": "ANALYZE_API",
      "target": "UserController.kt",
      "status": "RUNNING",
      "startedAt": "2026-02-23T10:00:00Z",
      "retryCount": 0,
      "checksum": "abc123"
    }
  ]
}
```

### 3.3 中断恢复机制

```
┌─────────────────────────────────────────────────────────────┐
│                     中断恢复流程                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. 启动时检查 queue/pending.json                           │
│  2. 找到 status=RUNNING 且超过超时时间的任务                 │
│  3. 判断是否需要重试：                                       │
│     - retryCount < MAX_RETRY → 重置为 PENDING               │
│     - retryCount >= MAX_RETRY → 标记 FAILED                 │
│  4. 继续处理 PENDING 任务                                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 3.4 死循环防护

| 防护措施 | 实现 |
|---------|------|
| 任务去重 | 同一 target + type 的任务，24 小时内不重复创建 |
| 执行次数限制 | 每个任务最多重试 3 次 |
| 进度检测 | 如果所有 Puzzle 完成度 > 90%，暂停新任务 |
| 超时熔断 | 单个任务执行超过 60 秒自动终止 |

### 3.5 幂等执行

```
┌─────────────────────────────────────────────────────────────┐
│                     幂等性保证                               │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  执行前检查：                                                │
│  1. 检查任务状态，非 PENDING 则跳过                          │
│  2. 计算 target 文件的 checksum                             │
│  3. 如果 checksum 未变化且 Puzzle 已存在，跳过               │
│                                                             │
│  执行中：                                                    │
│  1. 先标记 status = RUNNING                                 │
│  2. 持久化到文件                                            │
│  3. 执行分析                                                │
│  4. 更新 Puzzle                                             │
│  5. 标记 status = COMPLETED                                 │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 3.6 知识过期检测

```
┌─────────────────────────────────────────────────────────────┐
│                   知识过期检测流程                           │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. 读取 Puzzle 的 lastUpdated 时间                         │
│  2. 获取 Puzzle 关联文件的最后 Git commit 时间               │
│  3. 比较：如果文件更新时间 > Puzzle 更新时间                 │
│     → 标记 Puzzle 为 OUTDATED                               │
│     → 创建更新任务                                          │
│                                                             │
│  优化：使用 checksum 而非时间比较                           │
│  - 存储 Puzzle 时记录相关文件的 checksum                    │
│  - 检测时重新计算 checksum，不一致则过期                     │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 四、数据结构设计

### 4.1 任务队列文件 (`queue/pending.json`)

```json
{
  "version": 1,
  "lastUpdated": "2026-02-23T10:00:00Z",
  "tasks": [
    {
      "id": "uuid-001",
      "type": "ANALYZE_API",
      "target": "src/main/kotlin/UserController.kt",
      "puzzleId": "api-user-controller",
      "status": "PENDING",
      "priority": 0.85,
      "checksum": "sha256:abc123...",
      "relatedFiles": ["UserService.kt", "UserRepository.kt"],
      "createdAt": "2026-02-23T10:00:00Z",
      "startedAt": null,
      "completedAt": null,
      "retryCount": 0,
      "errorMessage": null
    }
  ]
}
```

### 4.2 状态枚举

```kotlin
enum class TaskStatus {
    PENDING,      // 等待执行
    RUNNING,      // 执行中
    COMPLETED,    // 已完成
    FAILED,       // 失败
    SKIPPED       // 跳过（已存在或无需更新）
}

enum class TaskType {
    ANALYZE_STRUCTURE,   // 分析项目结构
    ANALYZE_API,         // 分析 API 入口
    ANALYZE_DATA,        // 分析数据模型
    ANALYZE_FLOW,        // 分析业务流程
    ANALYZE_RULE,        // 分析业务规则
    UPDATE_PUZZLE        // 更新过期知识
}
```

### 4.3 核心数据类

```kotlin
data class AnalysisTask(
    val id: String,
    val type: TaskType,
    val target: String,
    val puzzleId: String,
    val status: TaskStatus,
    val priority: Double,
    val checksum: String,
    val relatedFiles: List<String>,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val retryCount: Int,
    val errorMessage: String?
)

data class TaskQueue(
    val version: Int,
    val lastUpdated: Instant,
    val tasks: MutableList<AnalysisTask>
)
```

---

## 五、模块设计

### 5.1 核心模块

| 模块 | 职责 | 文件 |
|------|------|------|
| TaskQueueStore | 任务队列持久化 | `infra/storage/TaskQueueStore.kt` |
| ChecksumCalculator | 文件 checksum 计算 | `domain/puzzle/ChecksumCalculator.kt` |
| TaskExecutor | 任务执行器 | `domain/puzzle/TaskExecutor.kt` |
| RecoveryService | 中断恢复服务 | `domain/puzzle/RecoveryService.kt` |
| DoomLoopGuard | 死循环防护 | `domain/puzzle/DoomLoopGuard.kt` |
| PuzzleCoordinator | 主协调器 | `domain/puzzle/PuzzleCoordinator.kt` |
| PuzzleContextInjector | 知识注入 | `domain/puzzle/PuzzleContextInjector.kt` |

### 5.2 调用时序

```
┌──────────────────────────────────────────────────────────────────┐
│                      PuzzleCoordinator 启动流程                   │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. RecoveryService.recover()                                    │
│     └─ 检查中断的任务，恢复或标记失败                             │
│                                                                  │
│  2. GapDetector.detect(puzzles)                                  │
│     └─ 检测知识空白，生成 Gap 列表                                │
│                                                                  │
│  3. DoomLoopGuard.filter(gaps)                                   │
│     └─ 过滤重复任务，防止死循环                                   │
│                                                                  │
│  4. TaskScheduler.prioritize(gaps)                               │
│     └─ 按优先级排序                                              │
│                                                                  │
│  5. TaskQueueStore.enqueue(tasks)                                │
│     └─ 持久化任务队列                                            │
│                                                                  │
│  6. TaskExecutor.executeNext(budget)                             │
│     └─ 执行下一个任务，更新 Puzzle                                │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 六、接口定义

### 6.1 PuzzleCoordinator

```kotlin
class PuzzleCoordinator(
    private val puzzleStore: PuzzleStore,
    private val memoryStore: MemoryStore,
    private val taskQueueStore: TaskQueueStore,
    private val gapDetector: GapDetector,
    private val taskScheduler: TaskScheduler,
    private val taskExecutor: TaskExecutor,
    private val recoveryService: RecoveryService,
    private val doomLoopGuard: DoomLoopGuard
) {
    /**
     * 启动协调器（IDE 启动时调用）
     */
    fun start()

    /**
     * 触发一次分析（文件变更或用户查询时调用）
     */
    fun trigger(trigger: TriggerType)

    /**
     * 获取当前状态
     */
    fun getStatus(): CoordinatorStatus

    /**
     * 停止协调器
     */
    fun stop()
}

enum class TriggerType {
    FILE_CHANGE,      // 文件变更
    USER_QUERY,       // 用户查询
    SCHEDULED,        // 定时触发
    MANUAL            // 手动触发
}
```

### 6.2 TaskExecutor

```kotlin
class TaskExecutor(
    private val puzzleStore: PuzzleStore,
    private val taskQueueStore: TaskQueueStore,
    private val checksumCalculator: ChecksumCalculator
) {
    /**
     * 执行下一个任务
     * @return 执行结果
     */
    suspend fun executeNext(budget: TokenBudget): ExecutionResult

    /**
     * 执行指定任务
     */
    suspend fun execute(task: AnalysisTask): ExecutionResult
}

sealed class ExecutionResult {
    object Success : ExecutionResult()
    data class Skipped(val reason: String) : ExecutionResult()
    data class Failed(val error: Throwable) : ExecutionResult()
}
```

### 6.3 RecoveryService

```kotlin
class RecoveryService(
    private val taskQueueStore: TaskQueueStore
) {
    /**
     * 恢复中断的任务
     * @return 恢复的任务数量
     */
    fun recover(): Int

    /**
     * 检查是否有需要恢复的任务
     */
    fun needsRecovery(): Boolean
}
```

### 6.4 DoomLoopGuard

```kotlin
class DoomLoopGuard(
    private val taskQueueStore: TaskQueueStore
) {
    companion object {
        const val MAX_RETRY = 3
        const val DEDUP_HOURS = 24
        const val EXECUTION_TIMEOUT_SECONDS = 60
    }

    /**
     * 过滤掉可能导致死循环的任务
     */
    fun filter(tasks: List<AnalysisTask>): List<AnalysisTask>

    /**
     * 检查任务是否可以执行
     */
    fun canExecute(task: AnalysisTask): Boolean

    /**
     * 记录任务执行（用于去重）
     */
    fun recordExecution(task: AnalysisTask)
}
```

---

## 七、测试场景

### 7.1 单元测试

| 测试类 | 测试场景 |
|--------|---------|
| TaskQueueStoreTest | CRUD、持久化、恢复 |
| ChecksumCalculatorTest | 文件变更检测 |
| DoomLoopGuardTest | 去重、重试限制、超时检测 |
| RecoveryServiceTest | 中断恢复、失败处理 |
| TaskExecutorTest | 任务执行、幂等性 |
| PuzzleCoordinatorTest | 完整流程 |

### 7.2 健壮性测试

| 场景 | 验证点 |
|------|--------|
| 中断恢复 | 任务执行到一半停机，重启后继续 |
| 重复执行 | 同一文件多次变更，只执行一次 |
| 失败重试 | 任务失败后自动重试，超过次数标记失败 |
| 死循环防护 | 相同任务 24 小时内不重复执行 |
| 幂等性 | 重复调用 `execute()` 结果一致 |
| 超时熔断 | 执行超时自动终止 |
| 知识过期 | 文件变更后知识被标记为过期 |

---

## 八、实施计划

### Phase 4.1: 基础设施 (2 天)

| 任务 | 交付物 |
|------|--------|
| TaskQueueStore | 任务队列持久化 + 18 个测试 |
| ChecksumCalculator | checksum 计算 + 8 个测试 |

### Phase 4.2: 健壮性组件 (3 天)

| 任务 | 交付物 |
|------|--------|
| DoomLoopGuard | 死循环防护 + 12 个测试 |
| RecoveryService | 中断恢复 + 10 个测试 |

### Phase 4.3: 执行引擎 (2 天)

| 任务 | 交付物 |
|------|--------|
| TaskExecutor | 任务执行 + 15 个测试 |

### Phase 4.4: 主协调器 (2 天)

| 任务 | 交付物 |
|------|--------|
| PuzzleCoordinator | 主循环 + E2E 测试 |
| PuzzleContextInjector | 知识注入 + 8 个测试 |

---

## 九、验收标准

### 功能验收

- [ ] 能自动检测知识空白
- [ ] 能按优先级执行分析任务
- [ ] 执行结果持久化到 Markdown
- [ ] 中断后能恢复执行
- [ ] 不会进入死循环
- [ ] 知识注入到 LLM 上下文

### 质量验收

- [ ] 所有单元测试通过
- [ ] 所有健壮性测试通过
- [ ] E2E 测试通过
- [ ] 代码覆盖率 > 80%
- [ ] 无硬编码、无魔法数字

---

## 十、风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| LLM 调用失败 | 重试机制 + 降级策略 |
| 文件读写冲突 | 使用文件锁 + 原子写入 |
| 内存泄漏 | 定期清理已完成的任务 |
| 性能影响 | 后台低优先级执行 |
