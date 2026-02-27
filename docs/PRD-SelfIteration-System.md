# PRD: 自迭代知识进化系统

> 版本: v1.0
> 日期: 2026-02-27
> 状态: 已实现

---

## 一、系统概述

### 1.1 核心理念

**项目理解 = 拼图游戏**

SmanCode 的自迭代系统将项目理解看作拼图游戏，Agent 在后台持续填充拼图：

```
项目代码 → Agent 自主理解 → 生成项目专属知识 → 持续迭代完善
```

**核心差异化**：
- ❌ 不用 Skill 封装业务场景（需要人工编写）
- ✅ Agent 自己理解项目，生成项目专属知识（自动生成）

### 1.2 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    自迭代知识进化系统                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  【主动发现】              【被动响应】                          │
│                                                                 │
│  GapDetector              KnowledgeEvolutionLoop                │
│       │                         │                               │
│       ▼                         ▼                               │
│  发现空白 ──────────────────► 触发进化                           │
│       │                         │                               │
│       ▼                         ▼                               │
│  PuzzleCoordinator ◄─────── 执行分析                            │
│       │                         │                               │
│       ▼                         ▼                               │
│  TaskQueue → TaskExecutor → 生成 Puzzle                         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 二、核心组件

### 2.1 KnowledgeEvolutionLoop（知识进化循环）

**职责**：执行完整的知识进化循环

**流程**：
```
1. 观察 (Observe) → 加载现有知识，分析触发原因
2. 假设 (Hypothesize) → 生成分析假设
3. 计划 (Plan) → 拆解为具体任务
4. 执行 (Execute) → 调用 LLM 分析
5. 评估 (Evaluate) → 验证结果质量
6. 合并 (Integrate) → 更新 Puzzle 知识库
```

**核心代码位置**：`domain/puzzle/KnowledgeEvolutionLoop.kt`

**触发方式**：
```kotlin
sealed class Trigger {
    data class UserQuery(val query: String) : Trigger()    // 用户提问
    data class FileChange(val files: List<String>) : Trigger()  // 文件变更
    data class Scheduled(val reason: String) : Trigger()   // 定时触发
    data class Manual(val reason: String) : Trigger()      // 手动触发
}
```

### 2.2 GapDetector（空白检测器）

**职责**：发现 Puzzle 知识库中的空白

**检测方式**：
| 方式 | 描述 |
|------|------|
| 低完成度检测 | completeness < 0.5 的 Puzzle |
| 低置信度检测 | confidence < 0.6 的 Puzzle |
| 文件变更触发 | 相关文件变更后需要更新 |
| 用户查询触发 | 用户查询涉及但缺失的知识 |

**核心代码位置**：`domain/puzzle/GapDetector.kt`

### 2.3 PuzzleCoordinator（协调器）

**职责**：协调自迭代系统的各个组件

**功能**：
- 启动时恢复中断任务
- 检测知识空白
- 调度任务优先级
- 执行分析任务

**核心代码位置**：`domain/puzzle/PuzzleCoordinator.kt`

### 2.4 TaskExecutor（任务执行器）

**职责**：执行具体的分析任务

**特性**：
- 幂等性：重复执行不会产生副作用
- 超时控制：单个任务超时自动终止
- Token 预算：控制单次执行成本

**核心代码位置**：`domain/puzzle/TaskExecutor.kt`

---

## 三、数据模型

### 3.1 Puzzle（拼图块）

```kotlin
data class Puzzle(
    val id: String,                    // 唯一标识
    val type: PuzzleType,              // 拼图类型
    val status: PuzzleStatus,          // 状态
    val content: String,               // Markdown 内容
    val completeness: Double,          // 完成度 0-1
    val confidence: Double,            // 置信度 0-1
    val lastUpdated: Instant,          // 最后更新时间
    val filePath: String               // 存储路径
)
```

### 3.2 PuzzleType（拼图类型）

| 类型 | 描述 | 输出文件 |
|------|------|----------|
| STRUCTURE | 项目结构、模块划分 | `PUZZLE_STRUCTURE.md` |
| TECH_STACK | 技术栈、依赖关系 | `PUZZLE_TECH.md` |
| API | API 入口、调用入口 | `PUZZLE_API.md` |
| DATA | 数据模型、表关系 | `PUZZLE_DATA.md` |
| FLOW | 业务流程、调用链 | `PUZZLE_FLOW_*.md` |
| RULE | 业务规则、约束条件 | `PUZZLE_RULES.md` |

### 3.3 Gap（知识空白）

```kotlin
data class Gap(
    val type: GapType,                 // 空白类型
    val puzzleType: PuzzleType,        // 关联的拼图类型
    val description: String,           // 描述
    val priority: Double,              // 优先级 0-1
    val relatedFiles: List<String>,    // 相关文件
    val detectedAt: Instant            // 发现时间
)
```

### 3.4 Iteration（迭代轮次）

```kotlin
data class Iteration(
    val id: String,                    // 迭代 ID
    val trigger: Trigger,              // 触发原因
    val hypothesis: String,            // 本轮假设
    val tasks: List<IterationTask>,    // 拆解的任务
    val results: List<TaskResult>,     // 执行结果
    val evaluation: Evaluation,        // 评估结果
    val status: IterationStatus        // 状态
)
```

---

## 四、健壮性设计

### 4.1 中断恢复

```
1. 启动时检查 queue/pending.json
2. 找到 status=RUNNING 且超时的任务
3. retryCount < MAX_RETRY → 重置为 PENDING
4. retryCount >= MAX_RETRY → 标记 FAILED
```

### 4.2 死循环防护

| 防护措施 | 实现 |
|---------|------|
| 任务去重 | 同一 target + type，24小时内不重复 |
| 执行次数限制 | 每个任务最多重试 3 次 |
| 超时熔断 | 单个任务超过 60 秒自动终止 |
| 收敛控制 | 连续低质量迭代达到阈值时终止 |

### 4.3 幂等执行

```
执行前检查：
1. 检查任务状态，非 PENDING 则跳过
2. 计算 target 文件的 checksum
3. 如果 checksum 未变化且 Puzzle 已存在，跳过
```

---

## 五、存储结构

```
{projectPath}/.sman/
├── MEMORY.md                    # 项目记忆（用户偏好）
├── puzzles/                     # 拼图块存储
│   ├── status.json              # 拼图状态汇总
│   ├── PUZZLE_*.md              # 各类型拼图
│   └── flow/                    # 流程拼图详情
├── queue/                       # 任务队列
│   └── pending.json             # 待执行任务
└── cache/                       # 缓存（不入 Git）
    ├── md5.json                 # MD5 缓存
    └── vectors/                 # 向量索引
```

---

## 六、评估体系

### 6.1 评估维度

| 维度 | 计算方式 |
|------|---------|
| 假设验证率 | 验证成功的假设 / 总假设数 |
| 新知识获取率 | 每轮获取的新 Puzzle 数 |
| 知识一致性 | 本轮发现的冲突数 |
| 质量评分 | LLM 自评 0-1 |

### 6.2 收敛判断

```kotlin
// 连续低质量迭代达到阈值时强制终止
if (evaluation.qualityScore < LOW_QUALITY_THRESHOLD) {
    consecutiveLowQualityCount++
    if (consecutiveLowQualityCount >= MAX_CONSECUTIVE_LOW_QUALITY) {
        // 终止迭代
    }
} else {
    consecutiveLowQualityCount = 0
}
```

---

## 七、测试验证

### 7.1 已通过的测试

| 测试类别 | 测试数量 | 状态 |
|---------|---------|------|
| 单元测试 | 50+ | ✅ 通过 |
| 集成测试 | 10+ | ✅ 通过 |
| 极限评估 | 14 | ✅ 通过 |
| 系统化对比 | 6 | ✅ 通过 |

### 7.2 评估结论

经过 10+ 轮真实 LLM 调用测试，系统已验证：
- ✅ 能基于现有知识进行深度推理
- ✅ 能识别 Saga、状态机、策略模式等
- ✅ 能进行技术+业务综合分析
- ✅ 质量评分稳定

**结论：系统价值已确认，达到可用状态。**

---

## 八、模块清单

| 模块 | 文件 | 行数 | 职责 |
|------|------|------|------|
| KnowledgeEvolutionLoop | `domain/puzzle/KnowledgeEvolutionLoop.kt` | ~350 | 核心进化循环 |
| GapDetector | `domain/puzzle/GapDetector.kt` | ~225 | 空白检测 |
| PuzzleCoordinator | `domain/puzzle/PuzzleCoordinator.kt` | ~200 | 协调器 |
| TaskExecutor | `domain/puzzle/TaskExecutor.kt` | ~150 | 任务执行 |
| EvolutionPromptBuilder | `domain/puzzle/EvolutionPromptBuilder.kt` | ~102 | Prompt 构建 |
| EvolutionResponseParser | `domain/puzzle/EvolutionResponseParser.kt` | ~108 | 响应解析 |
| TaskGapMapper | `domain/puzzle/TaskGapMapper.kt` | ~67 | Gap/Task 映射 |
| DoomLoopGuard | `domain/puzzle/DoomLoopGuard.kt` | ~195 | 死循环防护 |
| RecoveryService | `domain/puzzle/RecoveryService.kt` | ~80 | 中断恢复 |

---

## 九、变更历史

| 版本 | 日期 | 变更内容 |
|------|------|---------|
| v1.0 | 2026-02-27 | 初始版本，合并 PRD-Knowledge-Evolution-Loop、PRD-PuzzleCoordinator、PRD-TaskExecutor-Refactor |
