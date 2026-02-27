# PRD: TaskExecutor 重构 - LLM 驱动的通用分析

## 一、背景

当前 TaskExecutor 存在严重问题：
1. **硬编码模板**：分析内容是写死的模板，没有真正分析
2. **过度预设**：预设了 API/STRUCTURE/DATA/FLOW/RULE 等类型
3. **没有真正调用 LLM**：performAnalysis 只是生成占位符文本

## 二、目标

将 TaskExecutor 重构为：
1. **通用分析器**：不预设分析内容，让 LLM 自由发现
2. **LLM 驱动**：真正调用 LLM 进行代码分析
3. **知识沉淀**：将 LLM 分析结果存储为 Puzzle

## 三、核心设计

### 3.1 分析流程

```
┌─────────────────────────────────────────────────────────────┐
│                     TaskExecutor                             │
│                                                             │
│  1. 获取任务                                                 │
│     ↓                                                       │
│  2. 准备上下文（读取相关文件）                                │
│     ↓                                                       │
│  3. 构建 Prompt（通用模板，不预设内容）                       │
│     ↓                                                       │
│  4. 调用 LLM                                                 │
│     ↓                                                       │
│  5. 解析响应，生成 Puzzle                                    │
│     ↓                                                       │
│  6. 存储 Puzzle                                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Prompt 设计原则

```
不预设：
❌ "请分析 API 接口"
❌ "请列出数据实体"
❌ "请画出流程图"

通用提示：
✅ "请分析以下代码，发现其中的重要知识和模式"
✅ "请用 Markdown 格式记录你的发现"
✅ "关注项目特有的设计决策和约定"
```

### 3.3 Puzzle 模型调整

保持现有 Puzzle 模型，但：
- `type` 改为 `GENERAL` 或从内容推断
- `content` 完全由 LLM 生成
- `tags` 从内容中提取（后续任务）

## 四、接口设计

### 4.1 新增接口

```kotlin
/**
 * LLM 分析器接口
 */
interface LlmAnalyzer {
    /**
     * 分析代码，生成知识内容
     *
     * @param target 分析目标（文件/目录路径）
     * @param context 上下文（相关文件内容）
     * @return 分析结果（Markdown 格式）
     */
    suspend fun analyze(target: String, context: AnalysisContext): AnalysisResult
}

/**
 * 分析上下文
 */
data class AnalysisContext(
    val relatedFiles: Map<String, String>,  // 文件路径 → 文件内容
    val existingPuzzles: List<Puzzle>,      // 已有的相关知识
    val userQuery: String? = null           // 用户查询（如果有）
)

/**
 * 分析结果
 */
data class AnalysisResult(
    val title: String,           // 标题
    val content: String,         // Markdown 内容
    val tags: List<String>,      // 提取的标签
    val confidence: Double,      // 置信度
    val sourceFiles: List<String> // 分析的源文件
)
```

### 4.2 TaskExecutor 改造

```kotlin
class TaskExecutor(
    private val taskQueueStore: TaskQueueStore,
    private val puzzleStore: PuzzleStore,
    private val checksumCalculator: ChecksumCalculator,
    private val llmAnalyzer: LlmAnalyzer,  // 新增
    private val fileReader: FileReader      // 新增：读取文件内容
) {
    suspend fun execute(task: AnalysisTask): ExecutionResult {
        // 1. 幂等性检查（保留）
        // 2. 准备上下文（新增）
        val context = prepareContext(task)
        // 3. 调用 LLM 分析（改造）
        val result = llmAnalyzer.analyze(task.target, context)
        // 4. 存储 Puzzle（保留）
        // ...
    }

    private suspend fun prepareContext(task: AnalysisTask): AnalysisContext {
        // 读取相关文件内容
        // 加载已有的相关 Puzzle
    }
}
```

## 五、任务拆分

### Phase 1: 基础接口（2 个任务）

| 任务 | 描述 | 产出 |
|------|------|------|
| 1.1 | 创建 AnalysisContext 和 AnalysisResult 模型 | 2 个 data class |
| 1.2 | 创建 LlmAnalyzer 接口 | 1 个 interface |

### Phase 2: LLM 分析器实现（3 个任务）

| 任务 | 描述 | 产出 |
|------|------|------|
| 2.1 | 创建通用分析 Prompt 模板 | Prompt 常量 |
| 2.2 | 实现 DefaultLlmAnalyzer | LLM 调用 + 响应解析 |
| 2.3 | 编写 LlmAnalyzer 测试 | 单元测试 |

### Phase 3: TaskExecutor 改造（3 个任务）

| 任务 | 描述 | 产出 |
|------|------|------|
| 3.1 | 添加 FileReader 依赖 | 文件读取工具 |
| 3.2 | 实现 prepareContext 方法 | 上下文准备逻辑 |
| 3.3 | 重构 execute 方法 | 集成 LlmAnalyzer |

### Phase 4: 测试与验证（2 个任务）

| 任务 | 描述 | 产出 |
|------|------|------|
| 4.1 | 更新 TaskExecutorTest | 适配新实现 |
| 4.2 | 编写 E2E 测试 | 集成测试 |

## 六、验收标准

1. TaskExecutor 调用真实的 LLM 进行分析
2. 分析内容由 LLM 生成，不再是硬编码模板
3. 所有测试通过
4. 可以生成有意义的 Puzzle 内容

## 七、风险与对策

| 风险 | 对策 |
|------|------|
| LLM 响应格式不稳定 | 设计容错的解析逻辑 |
| Token 消耗过大 | 限制上下文文件数量和大小 |
| 分析质量不稳定 | 后续可迭代优化 Prompt |
