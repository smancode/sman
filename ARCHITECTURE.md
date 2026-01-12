# 银行核心系统 AI 编码助手架构设计

> **核心理念**：LLM 是引擎，架构是底盘
>
> **架构职责**：提供清晰可靠的工具 + 维护灵活无歧义的数据结构 + 让 LLM 自主驱动流程
>
> **设计原则**：不预设流程，不限制能力，只提供工具和状态

---

## 目录

1. [架构本质](#架构本质)
2. [数据结构设计](#数据结构设计)
3. [工具系统设计](#工具系统设计)
4. [LLM 驱动机制](#llm-驱动机制)
5. [前后端分离](#前后端分离)
6. [实施计划](#实施计划)

---

## 架构本质

### 我们要解决的核心问题

```
┌─────────────────────────────────────────────────────────────┐
│  问题：如何让 LLM 作为引擎，自主驱动完成复杂任务？           │
└─────────────────────────────────────────────────────────────┘

❌ 传统思维（预设流程）：
用户输入 → 判断场景 → 选择路径 → 执行步骤 → 返回结果
（流程固定，无法适应未知场景）

✅ LLM 驱动思维（自主决策）：
用户输入 → LLM 理解 → LLM 规划 → LLM 执行 → LLM 评估 → 循环
（LLM 自主决策，适应各种场景）
```

### 架构的三项核心职责

| 职责 | 说明 | 不可接受的做法 |
|------|------|----------------|
| **提供工具** | 给 LLM 足够的能力去完成任务 | 工具不足、工具不可靠、工具文档不清楚 |
| **维护状态** | 记录做了什么、正在做什么、要达成什么 | 状态模糊、状态丢失、状态冲突 |
| **让出控制** | 让 LLM 自己判断怎么切分任务、怎么组织流程 | 预设流程、限制分支、硬编码规则 |

### 关键设计原则

```
1. 工具要清晰可靠
   - 每个工具的输入输出要明确定义
   - 工具的文档要清楚说明用途
   - 工具的执行要稳定可靠

2. 数据结构要无歧义
   - 记录了什么：清晰的日志
   - 现在在做什么：当前状态
   - 未来要达成什么：目标状态

3. 相信 LLM 的能力
   - 不预设任务切分方式
   - 不限制任务执行顺序
   - 不硬编码流程控制
   - 让 LLM 自己判断怎么完成任务
```

---

## 数据结构设计

### 核心抽象：Session 和 Message

```kotlin
// ========== 会话 ==========
data class Session(
    val id: String,
    val project: ProjectInfo,
    val messages: List<Message>,
    val currentGoal: Goal?,           // 当前目标
    val completedGoals: List<Goal>    // 已完成的目标
) {
    // 获取最近的上下文（用于传递给 LLM）
    fun getRecentContext(tokenLimit: Int = 8000): String {
        // 从最近的 message 开始往前累加，直到达到 token 限制
        val result = mutableListOf<String>()
        var currentTokens = 0

        for (message in messages.reversed()) {
            val content = message.toPrompt()
            val tokens = estimateTokens(content)

            if (currentTokens + tokens > tokenLimit) break

            result.add(0, content)
            currentTokens += tokens
        }

        return result.joinToString("\n\n")
    }

    // 获取已完成的工作（让 LLM 了解已经做了什么）
    fun getCompletedWork(): String {
        return completedGoals.joinToString("\n") { goal ->
            """
            ## ${goal.title}
            状态: ${goal.status}
            结论: ${goal.conclusion}
            """.trimIndent()
        }
    }
}

// ========== 消息 ==========
data class Message(
    val id: String,
    val role: Role,
    val timestamp: Long,
    val parts: List<Part>
) {
    fun toPrompt(): String {
        return """
        [${role.name}] ${timestamp.format()}
        ${parts.joinToString("\n") { it.toPrompt() }}
        """.trimIndent()
    }
}

enum class Role { USER, ASSISTANT, SYSTEM }

// ========== Part (内容片段) ==========
sealed class Part {
    abstract val id: String
    abstract val type: String
    abstract fun toPrompt(): String

    // 用户输入
    data class UserInput(
        override val id: String,
        val text: String
    ) : Part() {
        override val type = "user_input"
        override fun toPrompt() = text
    }

    // LLM 的思考过程
    data class Thought(
        override val id: String,
        val content: String
    ) : Part() {
        override val type = "thought"
        override fun toPrompt() = """[思考] $content"""
    }

    // 工具调用
    data class ToolCall(
        override val id: String,
        val toolName: String,
        val arguments: Map<String, Any>,
        val result: Part.ToolResult
    ) : Part() {
        override val type = "tool_call"
        override fun toPrompt() = """
            [调用工具] $toolName
            参数: ${arguments.toJson()}
            结果: ${result.summary}
            """.trimIndent()
    }

    // 工具执行结果
    data class ToolResult(
        override val id: String,
        val toolName: String,
        val success: Boolean,
        val summary: String,          // 简要总结（给 LLM 看）
        val details: String? = null,  // 详细内容（按需获取）
        val data: Map<String, Any> = emptyMap()
    ) : Part() {
        override val type = "tool_result"
        override fun toPrompt() = """
            [工具结果] $toolName
            ${if (success) "✓" else "✗"} $summary
            """.trimIndent()
    }

    // 分析结论
    data class Analysis(
        override val id: String,
        val title: String,
        val conclusion: String,       // 核心结论
        val details: String,          // 详细说明
        val confidence: Float? = null // 置信度（可选）
    ) : Part() {
        override val type = "analysis"
        override fun toPrompt() = """
            [分析] $title
            结论: $conclusion
            """.trimIndent()
    }

    // 目标
    data class Goal(
        override val id: String,
        val title: String,
        val description: String,
        val status: GoalStatus,
        val conclusion: String? = null  // 完成后的结论
    ) : Part() {
        override val type = "goal"
        override fun toPrompt() = """
            [目标${status.symbol}] $title
            $description
            """.trimIndent()
    }

    enum class GoalStatus(val symbol: String) {
        PENDING("⏳"), IN_PROGRESS("▶️"), COMPLETED("✅"), FAILED("❌")
    }
}

// ========== 项目信息 ==========
data class ProjectInfo(
    val name: String,
    val path: String,
    val gitBranch: String,
    val language: String
)
```

### 数据结构的核心思想

```
1. 记录做了什么（历史）
   - Message 列表按时间顺序记录所有交互
   - 每个 Part 都有时间戳
   - 工具调用和结果都有记录
   - 可以随时回溯历史

2. 记录正在做什么（当前状态）
   - currentGoal 指向当前目标
   - 最近的消息反映当前进度
   - LLM 可以通过 getRecentContext() 了解状态

3. 记录要达成什么（目标）
   - Goal 清晰定义要达成什么
   - completedGoals 记录已完成的工作
   - 每个 Goal 都有状态和结论

4. 无歧义性
   - 所有数据结构都有明确的类型
   - 所有字段都有明确的含义
   - 所有关系都有明确的记录
```

---

## 工具系统设计

### 工具定义

```kotlin
// ========== 工具接口 ==========
interface Tool {
    val name: String
    val description: String           // 工具的用途（给 LLM 看）
    val parameters: Map<String, ParameterDef>  // 参数定义
    val returns: String               // 返回值说明

    suspend fun execute(args: Map<String, Any>): ToolResult

    data class ParameterDef(
        val name: String,
        val type: String,
        val description: String,
        val required: Boolean,
        val defaultValue: Any? = null
    )
}

// ========== 工具实现示例 ==========
class SemanticSearchTool(
    private val backendService: BackendService
) : Tool {
    override val name = "semantic_search"
    override val description = """
        基于向量相似度搜索代码片段。当你需要查找某个功能或概念的实现代码时使用。
        例如：查找"还款计划生成"的实现代码。
    """.trimIndent()

    override val parameters = mapOf(
        "query" to Tool.ParameterDef(
            name = "query",
            type = "string",
            description = "搜索查询，描述要查找的功能或概念",
            required = true
        ),
        "topK" to Tool.ParameterDef(
            name = "topK",
            type = "integer",
            description = "返回结果数量",
            required = false,
            defaultValue = 10
        )
    )

    override val returns = """
        返回最相关的代码片段列表，每个结果包含：
        - filePath: 文件路径
        - content: 代码内容
        - score: 相似度分数
        - lineNumbers: 行号范围
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val query = args["query"] as String
        val topK = (args["topK"] as? Number)?.toInt() ?: 10

        val result = backendService.semanticSearch(query, topK)

        return ToolResult(
            id = generateULID(),
            toolName = name,
            success = true,
            summary = "找到 ${result.results.size} 个相关代码片段",
            details = result.results.joinToString("\n") {
                "- ${it.filePath}:${it.lineNumbers.first()} (score: ${it.score})"
            },
            data = mapOf(
                "results" to result.results
            )
        )
    }
}

class GraphQueryTool(
    private val backendService: BackendService
) : Tool {
    override val name = "graph_query"
    override val description = """
        查询业务图谱，获取实体之间的关系。当你需要了解业务概念之间的关联时使用。
        例如：查询"还款计划"的调用关系、依赖关系等。
    """.trimIndent()

    override val parameters = mapOf(
        "entity" to Tool.ParameterDef(
            name = "entity",
            type = "string",
            description = "要查询的业务实体名称",
            required = true
        ),
        "relation" to Tool.ParameterDef(
            name = "relation",
            type = "string",
            description = "要查询的关系类型（如：调用、依赖、包含等）",
            required = false
        )
    )

    override val returns = """
        返回图谱查询结果，包含：
        - nodes: 相关节点列表
        - edges: 关系边列表
        - paths: 路径列表
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val entity = args["entity"] as String
        val relation = args["relation"] as? String

        val result = backendService.graphQuery(entity, relation)

        return ToolResult(
            id = generateULID(),
            toolName = name,
            success = true,
            summary = "找到 ${result.nodes.size} 个相关节点，${result.edges.size} 条关系",
            details = result.nodes.joinToString(", ") { it.name },
            data = mapOf(
                "nodes" to result.nodes,
                "edges" to result.edges,
                "paths" to result.paths
            )
        )
    }
}

class ReadFileTool : Tool {
    override val name = "read_file"
    override val description = """
        读取文件内容。当你需要查看具体代码实现时使用。
        例如：查看 PaymentService.java 的第 50-100 行。
    """.trimIndent()

    override val parameters = mapOf(
        "path" to Tool.ParameterDef(
            name = "path",
            type = "string",
            description = "文件路径（相对于项目根目录）",
            required = true
        ),
        "startLine" to Tool.ParameterDef(
            name = "startLine",
            type = "integer",
            description = "起始行号（从 1 开始）",
            required = false,
            defaultValue = 1
        ),
        "endLine" to Tool.ParameterDef(
            name = "endLine",
            type = "integer",
            description = "结束行号（包含），如果不指定则读到文件末尾",
            required = false
        )
    )

    override val returns = """
        返回指定行号范围内的文件内容。
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val path = args["path"] as String
        val startLine = (args["startLine"] as? Number)?.toInt() ?: 1
        val endLine = args["endLine"] as? Int?

        val content = readFileFromIDE(path, startLine, endLine)

        return ToolResult(
            id = generateULID(),
            toolName = name,
            success = true,
            summary = "读取 ${path}:${startLine}-${endLine ?: "末尾"}",
            details = content,
            data = mapOf("content" to content)
        )
    }
}

class FindCallersTool : Tool {
    override val name = "find_callers"
    override val description = """
        查找调用指定方法的所有位置。当你需要了解某个方法被哪里使用时使用。
        例如：查找 PaymentService.execute 方法的所有调用者。
    """.trimIndent()

    override val parameters = mapOf(
        "className" to Tool.ParameterDef(
            name = "className",
            type = "string",
            description = "类名（完整路径）",
            required = true
        ),
        "methodName" to Tool.ParameterDef(
            name = "methodName",
            type = "string",
            description = "方法名",
            required = true
        )
    )

    override val returns = """
        返回调用位置列表，每个位置包含文件路径和行号。
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val className = args["className"] as String
        val methodName = args["methodName"] as String

        val callers = findCallersInIDE(className, methodName)

        return ToolResult(
            id = generateULID(),
            toolName = name,
            success = true,
            summary = "找到 ${callers.size} 个调用位置",
            details = callers.joinToString("\n") { "${it.file}:${it.line}" },
            data = mapOf("callers" to callers)
        )
    }
}
```

### 工具注册和调用

```kotlin
// ========== 工具注册表 ==========
class ToolRegistry {
    private val tools = mapOf<String, Tool>(
        "semantic_search" to SemanticSearchTool(backendService),
        "graph_query" to GraphQueryTool(backendService),
        "find_rules" to FindRulesTool(backendService),
        "find_case" to FindCaseTool(backendService),
        "read_file" to ReadFileTool(),
        "find_callers" to FindCallersTool(),
        "find_callees" to FindCalleesTool(),
        "navigate" to NavigateTool()
    )

    fun getTool(name: String): Tool? = tools[name]

    fun getAllTools(): List<Tool> = tools.values.toList()

    // 生成工具列表描述（用于提示词）
    fun getToolsDescription(): String {
        return tools.values.joinToString("\n\n") { tool ->
            """
            ## ${tool.name}
            用途: ${tool.description}

            参数:
            ${tool.parameters.values.joinToString("\n") { param ->
                "- ${param.name} (${param.type}): ${param.description}" +
                if (param.required) " [必填]" else " [可选，默认: ${param.defaultValue}]"
            }}

            返回: ${tool.returns}
            """.trimIndent()
        }
    }
}

// ========== 工具执行器 ==========
class ToolExecutor(
    private val registry: ToolRegistry
) {
    suspend fun execute(toolCall: ToolCall): Part.ToolResult {
        val tool = registry.getTool(toolCall.toolName)
            ?: throw IllegalArgumentException("未知工具: ${toolCall.toolName}")

        // 参数校验
        for ((name, param) in tool.parameters) {
            if (param.required && !toolCall.arguments.containsKey(name)) {
                throw IllegalArgumentException("缺少必填参数: $name")
            }
        }

        // 执行工具
        return tool.execute(toolCall.arguments)
    }
}
```

---

## LLM 驱动机制

### 核心思想：LLM 是驱动引擎

```
┌─────────────────────────────────────────────────────────────┐
│                     LLM 驱动循环                              │
└─────────────────────────────────────────────────────────────┘

用户输入
    ↓
┌─────────────────────────────────────────────────────────────┐
│  LLM 思考                                                     │
│  - 理解用户意图                                               │
│  - 分析当前状态（通过 getRecentContext()）                    │
│  - 规划任务（自己决定怎么切分）                                │
│  - 决定下一步行动                                            │
└─────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────┐
│  LLM 决策                                                     │
│  - 需要更多信息？→ 调用工具                                  │
│  - 需要进一步分析？→ 创建子目标                               │
│  - 已经有答案？→ 输出结论                                    │
│  - 任务太复杂？→ 切分成多个子任务                             │
└─────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────┐
│  执行行动                                                     │
│  - 调用工具（获取信息）                                      │
│  - 输出分析（记录思考）                                      │
│  - 创建目标（记录规划）                                      │
│  - 更新状态（更新 Session）                                  │
└─────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────┐
│  评估进度                                                     │
│  - 当前目标完成了吗？                                        │
│  - 是否需要更多信息？                                        │
│  - 是否需要调整方向？                                        │
│  - 是否已经满足用户需求？                                    │
└─────────────────────────────────────────────────────────────┘
    ↓
   循环，直到满足用户需求
```

### 系统提示词设计

```kotlin
fun buildSystemPrompt(toolsDescription: String): String {
    return """
    你是银行核心系统的分析专家。

    ## 你的能力

    你可以使用以下工具来完成用户的需求：

    ${toolsDescription}

    ## 你的工作方式

    1. **理解需求**
       - 仔细理解用户想要什么
       - 分析当前上下文（历史消息）
       - 明确目标

    2. **规划任务**
       - 自己决定如何切分任务
       - 自己决定执行顺序
       - 自己决定何时需要更多信息

    3. **执行任务**
       - 使用工具获取信息
       - 基于信息进行分析
       - 记录你的思考过程

    4. **评估进度**
       - 判断是否已经满足用户需求
       - 判断是否需要进一步分析
       - 决定下一步做什么

    ## 重要原则

    - **自主决策**：你是最了解情况的，自己决定怎么做
    - **上下文隔离**：每个子任务独立分析，只传递结论
    - **清晰记录**：清楚地记录你的思考和结论
    - **适时停止**：满足需求后就停止，不要过度分析

    ## 输出格式

    使用以下格式来组织你的回复：

    ### [思考]
    （你的思考过程）

    ### [目标]
    （你要达成的目标）

    ### [行动]
    （你采取的行动，如调用工具）

    ### [结论]
    （你的结论）

    你可以多次循环：思考 → 行动 → 结论，直到满足用户需求。
    """.trimIndent()
}
```

### 主循环实现

```kotlin
class LLMDriver(
    private val llmClient: LLMClient,
    private val toolExecutor: ToolExecutor,
    private val toolRegistry: ToolRegistry
) {
    suspend fun process(session: Session, userMessage: String): Session {
        var currentSession = session.copy(
            messages = session.messages + Message(
                id = generateULID(),
                role = Role.USER,
                timestamp = System.currentTimeMillis(),
                parts = listOf(Part.UserInput(generateULID(), userMessage))
            )
        )

        val maxIterations = 20  // 防止无限循环
        var iteration = 0

        while (iteration < maxIterations) {
            iteration++

            // 1. 组装提示词
            val prompt = buildPrompt(currentSession)

            // 2. 调用 LLM
            val llmResponse = llmClient.complete(prompt)

            // 3. 解析 LLM 回复
            val parsed = parseLLMResponse(llmResponse)

            // 4. 记录 LLM 的回复
            currentSession = currentSession.copy(
                messages = currentSession.messages + Message(
                    id = generateULID(),
                    role = Role.ASSISTANT,
                    timestamp = System.currentTimeMillis(),
                    parts = parsed.parts
                )
            )

            // 5. 执行工具调用
            for (toolCall in parsed.toolCalls) {
                val result = toolExecutor.execute(toolCall)
                currentSession = currentSession.copy(
                    messages = currentSession.messages + Message(
                        id = generateULID(),
                        role = Role.ASSISTANT,
                        timestamp = System.currentTimeMillis(),
                        parts = listOf(Part.ToolCall(
                            id = generateULID(),
                            toolName = toolCall.toolName,
                            arguments = toolCall.arguments,
                            result = result
                        ))
                    )
                )
            }

            // 6. 检查是否完成
            if (parsed.isComplete) {
                return currentSession
            }

            // 7. 如果 LLM 判断需要继续，循环回去
        }

        return currentSession
    }

    fun buildPrompt(session: Session): String {
        return """
        ${buildSystemPrompt(toolRegistry.getToolsDescription())}

        ## 当前项目
        ${session.project.name} (${session.project.language})
        路径: ${session.project.path}
        分支: ${session.project.gitBranch}

        ## 已完成的工作
        ${session.getCompletedWork()}

        ## 最近的消息
        ${session.getRecentContext()}

        ## 当前目标
        ${session.currentGoal?.let { """
        ${it.title}
        ${it.description}
        """.trimIndent() } ?: "（无）"}

        ---
        请继续处理用户的请求。记住，你是自主决策的，自己决定怎么做。
        """.trimIndent()
    }
}
```

---

## 用户体验设计

### 核心问题：避免"光秃秃"的等待

```
❌ 冷淡的体验：
用户输入 → [空白等待 30 秒] → 结果弹出
（用户不知道在做什么，感到焦虑）

✅ 温暖的体验：
用户输入 → "正在理解您的需求..."
          → "正在搜索相关代码..."
          → "正在分析调用链..."
          → "正在生成方案..."
          → 结果
（用户感受到 AI 在努力工作）
```

### 实时反馈机制

```kotlin
// ========== 事件流 ==========
sealed interface UIEvent {
    // 开始处理
    data class ProcessingStarted(
        val message: String = "正在处理您的请求..."
    ) : UIEvent

    // 思考中
    data class Thinking(
        val content: String  // LLM 的思考内容（实时流式输出）
    ) : UIEvent

    // 调用工具
    data class ToolInvoked(
        val toolName: String,
        val description: String  // "正在搜索相关代码..."
    ) : UIEvent

    // 工具结果
    data class ToolCompleted(
        val toolName: String,
        val summary: String  // "找到 15 个相关代码片段"
    ) : UIEvent

    // 创建目标
    data class GoalCreated(
        val title: String,
        val description: String
    ) : UIEvent

    // 分析进度
    data class AnalysisProgress(
        val current: String,  // "正在分析 PaymentService..."
        val total: String? = null  // "分析 3/5"
    ) : UIEvent

    // 完成
    data class ProcessingCompleted(
        val summary: String
    ) : UIEvent

    // 错误
    data class Error(
        val message: String,
        val recoverable: Boolean
    ) : UIEvent
}

// ========== 事件总线 ==========
class EventBus {
    private val listeners = mutableListOf<(UIEvent) -> Unit>()

    fun subscribe(listener: (UIEvent) -> Unit) {
        listeners.add(listener)
    }

    fun emit(event: UIEvent) {
        listeners.forEach { it(event) }
    }
}
```

### LLM 驱动器支持流式输出

**核心思路：LLM 推理时间最长（10-30秒），必须流式输出思考过程**

```kotlin
// ========== 流式 LLM 客户端 ==========
interface LLMClient {
    // 流式完成：实时返回每个 token/chunk
    suspend fun streamComplete(
        prompt: String,
        onChunk: (chunk: String) -> Unit  // 每收到一个 chunk 就回调
    ): String

    // 非流式（兼容不支持流式的 API）
    suspend fun complete(prompt: String): String
}

// ========== DeepSeek 流式实现 ==========
class DeepSeekStreamClient(
    private val apiKey: String,
    private val baseURL: String = "https://api.deepseek.com"
) : LLMClient {

    override suspend fun streamComplete(
        prompt: String,
        onChunk: (chunk: String) -> Unit
    ): String {
        val client = HttpClient()
        val fullResponse = StringBuilder()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseURL/v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(JsonObject.mapOf(
                "model" to "deepseek-chat",
                "messages" to JsonArray.of(JsonObject.mapOf(
                    "role" to "user",
                    "content" to prompt
                )),
                "stream" to true  // 关键：开启流式
            ).toString()))
            .build()

        // 发送请求
        val response = client.send(request, BodyHandlers.ofLines())

        // 逐行解析 SSE (Server-Sent Events)
        response.body().forEach { line ->
            if (line.startsWith("data: ")) {
                val data = line.substring(6)
                if (data == "[DONE]") return@forEach

                try {
                    val json = JsonParser.parseString(data)
                    val content = json.getAsJsonObject("choices")
                        .getAsJsonArray(0)
                        .getAsJsonObject("delta")
                        .get("content")?.asString

                    if (content != null) {
                        fullResponse.append(content)
                        // 实时回调，让 UI 更新
                        onChunk(content)
                    }
                } catch (e: Exception) {
                    // 忽略解析错误（可能是心跳包）
                }
            }
        }

        return fullResponse.toString()
    }

    override suspend fun complete(prompt: String): String {
        // 流式的也可以用作非流式
        return streamComplete(prompt) {}
    }
}

// ========== Ollama 流式实现 ==========
class OllamaStreamClient(
    private val endpoint: String = "http://localhost:11434"
) : LLMClient {

    override suspend fun streamComplete(
        prompt: String,
        onChunk: (chunk: String) -> Unit
    ): String {
        val client = HttpClient()
        val fullResponse = StringBuilder()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$endpoint/api/generate"))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(JsonObject.mapOf(
                "model" to "deepseek-coder:33b",
                "prompt" to prompt,
                "stream" to true  // 开启流式
            ).toString()))
            .build()

        val response = client.send(request, BodyHandlers.ofLines())

        // Ollama 每行是一个 JSON 对象
        response.body().forEach { line ->
            try {
                val json = JsonParser.parseString(line)
                val content = json.getAsJsonObject("response")?.asString

                if (content != null && content.isNotEmpty()) {
                    fullResponse.append(content)
                    onChunk(content)
                }

                // 检查是否完成
                val done = json.get("done")?.asBoolean ?: false
                if (done) return@forEach
            } catch (e: Exception) {
                // 忽略解析错误
            }
        }

        return fullResponse.toString()
    }

    override suspend fun complete(prompt: String): String {
        return streamComplete(prompt) {}
    }
}

// ========== LLM 驱动器（使用流式客户端）==========
class LLMDriver(
    private val llmClient: LLMClient,
    private val toolExecutor: ToolExecutor,
    private val toolRegistry: ToolRegistry,
    private val eventBus: EventBus
) {
    suspend fun process(
        session: Session,
        userMessage: String,
        onEvent: (UIEvent) -> Unit
    ): Session {
        // 1. 通知开始
        onEvent(UIEvent.ProcessingStarted("正在处理您的请求..."))

        var currentSession = session.copy(
            messages = session.messages + Message(
                id = generateULID(),
                role = Role.USER,
                timestamp = System.currentTimeMillis(),
                parts = listOf(Part.UserInput(generateULID(), userMessage))
            )
        )

        val maxIterations = 20
        var iteration = 0

        while (iteration < maxIterations) {
            iteration++

            // 2. 开始思考（显示状态）
            onEvent(UIEvent.Thinking("\n🤔 "))

            // 3. 创建一个临时的"思考中" Part
            val thinkingContent = StringBuilder()
            var thinkingPartId = generateULID()

            // 4. 流式调用 LLM（关键！）
            val llmResponse = llmClient.streamComplete(
                prompt = buildPrompt(currentSession),
                onChunk = { chunk ->
                    // 实时更新 UI
                    thinkingContent.append(chunk)
                    onEvent(UIEvent.Thinking(chunk))
                }
            )

            // 5. 记录完整的思考过程
            val thoughtPart = Part.Thought(
                id = thinkingPartId,
                content = thinkingContent.toString()
            )

            currentSession = currentSession.copy(
                messages = currentSession.messages + Message(
                    id = generateULID(),
                    role = Role.ASSISTANT,
                    timestamp = System.currentTimeMillis(),
                    parts = listOf(thoughtPart)
                )
            )

            // 6. 解析回复（提取工具调用等）
            val parsed = parseLLMResponse(llmResponse)

            // 7. 执行工具调用（带进度提示）
            for (toolCall in parsed.toolCalls) {
                val tool = toolRegistry.getTool(toolCall.toolName)!!

                // 显示工具调用
                onEvent(UIEvent.ToolInvoked(
                    toolName = toolCall.toolName,
                    description = getToolDescription(tool, toolCall.arguments)
                ))

                val result = toolExecutor.execute(toolCall)

                // 显示结果摘要
                onEvent(UIEvent.ToolCompleted(
                    toolName = toolCall.toolName,
                    summary = result.summary
                ))

                currentSession = currentSession.copy(
                    messages = currentSession.messages + Message(
                        id = generateULID(),
                        role = Role.ASSISTANT,
                        timestamp = System.currentTimeMillis(),
                        parts = listOf(Part.ToolCall(
                            id = generateULID(),
                            toolName = toolCall.toolName,
                            arguments = toolCall.arguments,
                            result = result
                        ))
                    )
                )
            }

            // 8. 检查是否完成
            if (parsed.isComplete) {
                onEvent(UIEvent.ProcessingCompleted("处理完成"))
                return currentSession
            }
        }

        return currentSession
    }

    private fun getToolDescription(tool: Tool, args: Map<String, Any>): String {
        return when (tool.name) {
            "semantic_search" -> "正在搜索「${args["query"]}」的相关代码..."
            "graph_query" -> "正在查询「${args["entity"]}」的业务关系..."
            "read_file" -> "正在读取文件 ${args["path"]}..."
            "find_callers" -> "正在查找 ${args["className"]}.${args["methodName"]} 的调用者..."
            "find_callees" -> "正在查找 ${args["className"]}.${args["methodName"]} 的被调用者..."
            else -> "正在调用 ${tool.name}..."
        }
    }
}
```

### UI 更新逻辑（简化：终端式输出 + 颜色高亮）

```kotlin
class BankCoreAssistantPlugin : ProjectComponent {
    private lateinit var driver: LLMDriver
    private lateinit var console: ConsoleView

    // 定义颜色类型
    private val COLOR_USER_INPUT = ConsoleViewContentType.USER_INPUT
    private val COLOR_SYSTEM_OUTPUT = ConsoleViewContentType.SYSTEM_OUTPUT
    private val COLOR_NORMAL_OUTPUT = ConsoleViewContentType.NORMAL_OUTPUT
    private val COLOR_ERROR_OUTPUT = ConsoleViewContentType.ERROR_OUTPUT
    private val COLOR_LOG_INFO_OUTPUT = ConsoleViewContentType.LOG_INFO_OUTPUT
    private val COLOR_LOG_WARNING_OUTPUT = ConsoleViewContentType.LOG_WARNING_OUTPUT
    private val COLOR_LOG_ERROR_OUTPUT = ConsoleViewContentType.LOG_ERROR_OUTPUT

    // 自定义颜色（用于高亮关键字）
    private lateinit var COLOR_KEYWORD: ConsoleViewContentType
    private lateinit var COLOR_CODE: ConsoleViewContentType
    private lateinit var COLOR_SUCCESS: ConsoleViewContentType

    fun onProjectOpened(project: Project) {
        // 使用 IntelliJ 的 Console View（支持颜色）
        console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .getConsole()

        // 创建自定义颜色
        COLOR_KEYWORD = createConsoleViewContentType(project, Color(0, 120, 215))      // 蓝色
        COLOR_CODE = createConsoleViewContentType(project, Color(163, 21, 21))          // 深红
        COLOR_SUCCESS = createConsoleViewContentType(project, Color(0, 153, 0))         // 绿色

        // 显示在工具窗口
        val toolWindow = ToolWindowManager.getInstance(project)
            .registerToolWindow("Bank Core AI")
        val content = factory.createContent(
            console.component,
            "银行核心系统助手",
            false
        )
        toolWindow.contentManager.addContent(content)

        // 初始化驱动
        val toolRegistry = ToolRegistry()
        val toolExecutor = ToolExecutor(toolRegistry)
        val llmClient = createLLMClient()  // 流式客户端
        driver = LLMDriver(llmClient, toolExecutor, toolRegistry) { event ->
            handleEvent(event)
        }

        console.print("✓ 银行核心系统助手已就绪\n\n", COLOR_SUCCESS)
    }

    private fun handleEvent(event: UIEvent) {
        when (event) {
            is UIEvent.ProcessingStarted -> {
                console.print("\n${event.message}\n", COLOR_LOG_INFO_OUTPUT)
            }

            is UIEvent.Thinking -> {
                // 关键：流式输出，带简单高亮
                printWithHighlight(event.content)
            }

            is UIEvent.ToolInvoked -> {
                console.print("\n▶ ", COLOR_LOG_INFO_OUTPUT)
                console.print(event.description, COLOR_NORMAL_OUTPUT)
                console.print("\n", COLOR_NORMAL_OUTPUT)
            }

            is UIEvent.ToolCompleted -> {
                console.print("✓ ", COLOR_SUCCESS)
                console.print("${event.summary}\n", COLOR_NORMAL_OUTPUT)
            }

            is UIEvent.Error -> {
                console.print("\n✗ ", COLOR_ERROR_OUTPUT)
                console.print("${event.message}\n", COLOR_ERROR_OUTPUT)
            }

            else -> { /* 其他事件忽略或简单处理 */ }
        }
    }

    // 带高亮的输出（关键字、代码等）
    private fun printWithHighlight(text: String) {
        // 简单的关键字高亮
        var remaining = text
        val keywords = listOf(
            "结论", "分析", "发现", "注意", "警告", "错误",
            "步骤", "方法", "类", "函数", "接口"
        )

        for (keyword in keywords) {
            val pattern = Regex(Regex.escape(keyword))
            remaining = remaining.replace(pattern) { matchResult ->
                // 如果前面已经输出过这部分，先输出
                val before = remaining.substring(0, matchResult.range.first)
                console.print(before, COLOR_NORMAL_OUTPUT)

                // 输出高亮的关键字
                console.print(matchResult.value, COLOR_KEYWORD)

                // 返回剩余部分
                remaining.substring(matchResult.range.last + 1)
            }
        }

        // 输出剩余部分
        console.print(remaining, COLOR_NORMAL_OUTPUT)
    }

    fun onUserInput(text: String) {
        // 显示用户输入（高亮）
        console.print("\n➤ ", COLOR_USER_INPUT)
        console.print("$text\n", COLOR_USER_INPUT)

        GlobalScope.launch(Dispatchers.Default) {
            driver.process(session, text)
        }
    }
}

// 辅助函数：创建自定义颜色
fun createConsoleViewContentType(project: Project, color: Color): ConsoleViewContentType {
    return object : ConsoleViewContentType("custom_$color", {
        TextAttributes(color, null, null, null, Font.PLAIN)
    }) {}
}
```

### 更简单的方式：使用 ANSI 颜色

```kotlin
class BankCoreAssistantPlugin : ProjectComponent {
    private lateinit var console: ConsoleView
    private lateinit var project: Project

    fun onProjectOpened(project: Project) {
        this.project = project

        // 启用 ANSI 颜色支持
        console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .apply {
                setUsePredefinedMessageFilter(false)  // 允许 ANSI 颜色
            }
            .getConsole()

        // ... 其他初始化代码
    }

    private fun printWithAnsiColor(text: String) {
        // ANSI 颜色代码
        val RESET = "\u001B[0m"
        val BLUE = "\u001B[34m"
        val GREEN = "\u001B[32m"
        val YELLOW = "\u001B[33m"
        val RED = "\u001B[31m"
        val CYAN = "\u001B[36m"
        val BOLD = "\u001B[1m"

        // 简单替换关键字
        val colored = text
            .replace("结论", "$BOLD$BLUE结论$RESET")
            .replace("分析", "$BOLD$BLUE分析$RESET")
            .replace("警告", "$BOLD$YELLOW警告$RESET")
            .replace("错误", "$BOLD$RED错误$RESET")
            .replace(Regex("`([^`]+)`")) { match ->
                "$CYAN${match.groupValues[1]}$RESET"  // 代码用青色
            }

        console.print(colored, ConsoleViewContentType.NORMAL_OUTPUT)
    }
}
```

### 可点击的代码链接（核心功能！）

```kotlin
class BankCoreAssistantPlugin : ProjectComponent {
    private lateinit var console: ConsoleView
    private lateinit var project: Project
    private lateinit var hyperlinks: List<HyperlinkInfo>

    fun onProjectOpened(project: Project) {
        this.project = project
        this.hyperlinks = emptyList()

        // 使用支持超链接的 Console View
        console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .getConsole()

        // 启用超链接
        if (console is EditorTextConsole) {
            (console as EditorTextConsole).setEnableHyperlinks(true)
        }
    }

    private fun handleEvent(event: UIEvent) {
        when (event) {
            is UIEvent.Thinking -> {
                // 关键：解析文本中的代码引用，转换为可点击链接
                printWithClickableLinks(event.content)
            }
            // ... 其他事件处理
        }
    }

    // 带可点击链接的输出
    private fun printWithClickableLinks(text: String) {
        var remaining = text
        var lastPos = 0

        // 正则匹配各种代码引用
        val patterns = listOf(
            // 类名：com.example.PaymentService
            Regex("""([a-zA-Z_][a-zA-Z0-9_.]*\.)*[A-Z][a-zA-Z0-9_]*""") to { match ->
                createFileHyperlink(match.value)
            },
            // 方法：PaymentService.generateRepaymentPlan()
            Regex("""[A-Z][a-zA-Z0-9_]*\.[a-z][a-zA-Z0-9_]*\(\)""") to { match ->
                createMethodHyperlink(match.value)
            },
            // 文件路径：PaymentService.java:150
            Regex("""([a-zA-Z0-9_]+\.(java|kt|xml)):(\d+)""") to { match ->
                val file = match.groupValues[1]
                val line = match.groupValues[3].toInt()
                createLineHyperlink(file, line)
            }
        )

        // 查找所有匹配项
        val matches = mutableListOf<MatchResult>()
        for ((pattern, _) in patterns) {
            pattern.findAll(remaining).forEach { matches.add(it) }
        }

        // 按位置排序
        matches.sortBy { it.range.first }

        // 输出文本，插入超链接
        for (match in matches) {
            // 输出匹配前的普通文本
            if (match.range.first > lastPos) {
                val normalText = remaining.substring(lastPos, match.range.first)
                console.print(normalText, ConsoleViewContentType.NORMAL_OUTPUT)
            }

            // 输出超链接
            val linkText = match.value
            val hyperlink = createHyperlinkForText(linkText)
            console.printHyperlink(linkText) {
                hyperlink.onClick()
            }

            lastPos = match.range.last + 1
        }

        // 输出剩余的普通文本
        if (lastPos < remaining.length) {
            console.print(remaining.substring(lastPos), ConsoleViewContentType.NORMAL_OUTPUT)
        }
    }

    // 为文本创建超链接
    private fun createHyperlinkForText(text: String): HyperlinkInfo {
        return when {
            // 方法调用：PaymentService.generateRepaymentPlan()
            text.matches(Regex("""[A-Z][a-zA-Z0-9_]*\.[a-z][a-zA-Z0-9_]*\(\)""")) -> {
                val parts = text.split(".")
                val className = parts[0]
                val methodName = parts[1].removeSuffix("()")
                MethodHyperlink(className, methodName)
            }

            // 类名：com.example.PaymentService
            text.matches(Regex("""([a-zA-Z_][a-zA-Z0-9_.]*\.)*[A-Z][a-zA-Z0-9_]*""")) -> {
                ClassHyperlink(text)
            }

            // 文件:行号：PaymentService.java:150
            text.matches(Regex("""[a-zA-Z0-9_]+\.(java|kt|xml):\d+""")) -> {
                val parts = text.split(":")
                val file = parts[0]
                val line = parts[1].toInt()
                LineHyperlink(file, line)
            }

            else -> object : HyperlinkInfo {
                override fun onClick() {
                    // 默认不做任何事
                }
            }
        }
    }
}

// ========== 超链接类型 ==========
sealed class HyperlinkInfo {
    abstract fun onClick()
}

class ClassHyperlink(
    private val className: String
) : HyperlinkInfo() {
    override fun onClick() {
        GlobalScope.launch(Dispatchers.EDT) {
            // 搜索类定义
            val classes = PsiShortNamesCache.getInstance()
                .getClassesByName(className, GlobalSearchScope.projectScope(project))

            if (classes.isNotEmpty()) {
                // 跳转到第一个匹配的类
                NavigationUtil.navigateToPsiElement(classes[0])
            } else {
                // 如果找不到，尝试文件搜索
                val files = FilenameIndex.getFilesByName(
                    project,
                    "$className.java",
                    GlobalSearchScope.projectScope(project)
                )
                if (files.isNotEmpty()) {
                    NavigationUtil.navigateToPsiElement(files[0])
                }
            }
        }
    }
}

class MethodHyperlink(
    private val className: String,
    private val methodName: String
) : HyperlinkInfo() {
    override fun onClick() {
        GlobalScope.launch(Dispatchers.EDT) {
            // 搜索类
            val classes = PsiShortNamesCache.getInstance()
                .getClassesByName(className, GlobalSearchScope.projectScope(project))

            if (classes.isNotEmpty()) {
                val psiClass = classes[0]

                // 查找方法
                val methods = psiClass.findMethodsByName(methodName, true)
                if (methods.isNotEmpty()) {
                    // 跳转到方法定义
                    NavigationUtil.navigateToPsiElement(methods[0])
                }
            }
        }
    }
}

class LineHyperlink(
    private val fileName: String,
    private val lineNumber: Int
) : HyperlinkInfo() {
    override fun onClick() {
        GlobalScope.launch(Dispatchers.EDT) {
            // 搜索文件
            val files = FilenameIndex.getFilesByName(
                project,
                fileName,
                GlobalSearchScope.projectScope(project)
            )

            if (files.isNotEmpty()) {
                val file = files[0]
                val virtualFile = file.virtualFile

                if (virtualFile != null) {
                    // 打开文件并跳转到指定行
                    FileEditorManager.getInstance(project)
                        .openTextEditor(
                            OpenFileDescriptor(
                                project,
                                virtualFile,
                                lineNumber - 1,  // 转换为 0-based
                                0
                            ),
                            true
                        )
                }
            }
        }
    }
}
```

### 简化版本：使用 HyperlinkInfo

```kotlin
// 更简单的实现：直接使用 ConsoleView 的 printHyperlink
private fun printWithClickableLinks(text: String) {
    // 匹配类名、方法名、文件引用
    val classPattern = Regex("""\b([A-Z][a-zA-Z0-9_]*)\b""")
    val methodPattern = Regex("""\b([a-z][a-zA-Z0-9_]*)\(\)""")
    val filePattern = Regex("""([a-zA-Z0-9_]+\.(java|kt)):(\d+)""")

    var result = text
    var lastEnd = 0

    // 找出所有需要创建链接的位置
    val links = mutableListOf<Triple<Int, Int, () -> Unit>>()

    // 查找类名
    classPattern.findAll(text).forEach { match ->
        links.add(Triple(match.range.first, match.range.last + 1) {
            navigateToClass(match.groupValues[1])
        })
    }

    // 查找方法
    methodPattern.findAll(text).forEach { match ->
        links.add(Triple(match.range.first, match.range.last + 1) {
            navigateToMethod(match.groupValues[1])
        })
    }

    // 查找文件
    filePattern.findAll(text).forEach { match ->
        val file = match.groupValues[1]
        val line = match.groupValues[3].toInt()
        links.add(Triple(match.range.first, match.range.last + 1) {
            navigateToFile(file, line)
        })
    }

    // 按位置排序
    links.sortBy { it.first }

    // 输出带链接的文本
    links.forEach { (start, end, action) ->
        if (start > lastEnd) {
            console.print(text.substring(lastEnd, start), COLOR_NORMAL)
        }

        // 输出超链接
        console.printHyperlink(text.substring(start, end), action)
        lastEnd = end
    }

    // 输出剩余文本
    if (lastEnd < text.length) {
        console.print(text.substring(lastEnd), COLOR_NORMAL)
    }
}

private fun navigateToClass(className: String) {
    val classes = PsiShortNamesCache.getInstance()
        .getClassesByName(className, GlobalSearchScope.projectScope(project))
    if (classes.isNotEmpty()) {
        NavigationUtil.navigateToPsiElement(classes[0])
    }
}

private fun navigateToMethod(methodName: String) {
    // 类似实现...
}

private fun navigateToFile(fileName: String, line: Int) {
    val files = FilenameIndex.getFilesByName(
        project, fileName, GlobalSearchScope.projectScope(project)
    )
    if (files.isNotEmpty()) {
        val file = files[0]
        FileEditorManager.getInstance(project).openTextEditor(
            OpenFileDescriptor(project, file.virtualFile, line - 1, 0),
            true
        )
    }
}
```

### 为什么这样简单？

| 复杂方案（❌） | 简单方案（✅） |
|---------------|---------------|
| JTextPane + StyledDocument | ConsoleView（IntelliJ 内置） |
| Markdown 渲染 | 纯文本，不做渲染 |
| 颜色、字体、边框 | 终端风格，纯文本 |
| 多个组件协调 | 单一输出区域 |
| 自定义滚动 | 自动滚动（内置） |
| 复杂的事件处理 | 直接 print |

### UI 效果（终端风格 + 颜色高亮 + 可点击链接）

```
┌─────────────────────────────────────────────────────────────┐
│  银行核心系统助手                                            │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ✓ 银行核心系统助手已就绪                                    │
│                                                              │
│  ➤ 帮我理解还款计划的生成逻辑                               │
│                                                              │
│  ▶ 正在搜索「还款计划生成」的相关代码...                      │
│  ✓ 找到 15 个相关代码片段                                    │
│                                                              │
│  根据搜索结果，还款计划的生成主要在以下位置：                 │
│                                                              │
│  1. PaymentService.generateRepaymentPlan()                   │
│     ↑^^^^^^^^^^^^^^ ↑^^^^^^^^^^^^^^^^^^^                    │
│     (可点击的蓝色链接，点击跳转到代码)                        │
│                                                              │
│  2. RepaymentCalculator.calculate()                          │
│     ↑^^^^^^^^^^^^^^^^^^^^^^ ↑^^^^^^^^^^^                     │
│     (可点击的蓝色链接)                                        │
│                                                              │
│  3. PlanValidator.validate()                                │
│     ↑^^^^^^^^^^^^^^^ ↑^^^^^^^^^                              │
│     (可点击的蓝色链接)                                        │
│                                                              │
│  ▶ 正在读取文件 PaymentService.java...                       │
│  ✓ 已读取 150 行                                             │
│                                                              │
│  通过分析代码，我发现还款计划的生成流程如下：                 │
│  1. 首先获取贷款信息（本金、利率、期限）                     │
│  2. 计算每期还款金额（使用等额本息算法）                     │
│  3. 生成还款计划表（包含还款日期、本金、利息、余额）         │
│     实现在 PaymentService.java:150                          │
│                           ↑^^^^^^^^^^^^^^^^^^ ^^^^            │
│                           (可点击，直接跳转到文件第 150 行)   │
│  4. 验证计划的合法性（利率合规性、余额一致性）               │
│                                                              │
│  ▶ 正在查找 PaymentService.execute 的调用者...               │
│  ✓ 找到 3 个调用位置                                         │
│                                                              │
│  调用链分析：                                                 │
│  LoanController.createLoan()                                │
│      └─ PaymentService.execute()                            │
│          └─ generateRepaymentPlan()                         │
│      (点击任何类名或方法名都可以跳转)                        │
│                                                              │
│  结论：还款计划的生成涉及以下核心类：                        │
│  - PaymentService（核心服务）                                │
│  - RepaymentCalculator（计算器）                             │
│  - PlanValidator（验证器）                                   │
│  (所有类名都是蓝色可点击链接)                                │
│                                                              │
│  ➤ _                                                        │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 点击跳转效果

用户点击 `PaymentService`：
```
1. 自动在项目中搜索类定义
2. 在编辑器中打开 PaymentService.java
3. 光标定位到类定义处
```

用户点击 `PaymentService.java:150`：
```
1. 自动在项目中搜索文件
2. 在编辑器中打开文件
3. 跳转到第 150 行
```

用户点击 `generateRepaymentPlan()`：
```
1. 先找到 PaymentService 类
2. 在该类中查找 generateRepaymentPlan 方法
3. 跳转到方法定义
```

### 支持的链接类型

| 模式 | 示例 | 跳转目标 |
|------|------|---------|
| **类名** | `PaymentService` | 类定义 |
| **方法调用** | `PaymentService.execute()` | 方法定义 |
| **文件+行号** | `PaymentService.java:150` | 文件指定行 |
| **完整类名** | `com.example.PaymentService` | 类定义 |

### 技术要点

| 要点 | 说明 |
|------|------|
| **正则匹配** | 自动识别文本中的类名、方法名、文件引用 |
| **PsiShortNamesCache** | IntelliJ 的类搜索索引，快速定位 |
| **FilenameIndex** | IntelliJ 的文件搜索索引 |
| **NavigationUtil** | IntelliJ 的导航工具，自动跳转 |
| **ConsoleView.printHyperlink()** | 创建可点击的超链接 |

---

## 前后端分离

### 为什么分离？

```
前端（IntelliJ 插件）                    后端（知识服务）
├─ LLM 推理                            ├─ 语义搜索
├─ 对话管理                            ├─ 业务图谱
├─ 状态维护                            ├─ 规则查询
├─ 工具执行                            ├─ 案例库
└─ IDE 集成                            └─ 代码依赖

为什么这样分？
1. 业务知识是核心资产，独立存储和演进
2. LLM 推理在用户侧，数据不出域
3. 后端无状态，可水平扩展
4. 前端可换 LLM，可离线工作
```

### 后端 API（纯查询）

```kotlin
@RestController
@RequestMapping("/api/query")
class QueryController {
    @PostMapping("/search")
    suspend fun search(@RequestBody req: SearchRequest): SearchResult

    @PostMapping("/graph")
    suspend fun graph(@RequestBody req: GraphRequest): GraphResult

    @PostMapping("/rules")
    suspend fun rules(@RequestBody req: RuleRequest): RuleResult

    @PostMapping("/cases")
    suspend fun cases(@RequestBody req: CaseRequest): CaseResult

    @PostMapping("/dependencies")
    suspend fun dependencies(@RequestBody req: DependencyRequest): DependencyResult
}
```

### 前端插件

```kotlin
// 插件入口
class BankCoreAssistantPlugin : ProjectComponent {
    private lateinit var session: Session
    private lateinit var driver: LLMDriver

    fun onProjectOpened(project: Project) {
        // 初始化会话
        session = Session(
            id = generateULID(),
            project = ProjectInfo(
                name = project.name,
                path = project.basePath,
                gitBranch = GitHelper.getCurrentBranch(project),
                language = project.language
            ),
            messages = emptyList(),
            currentGoal = null,
            completedGoals = emptyList()
        )

        // 初始化驱动
        val toolRegistry = ToolRegistry()
        val toolExecutor = ToolExecutor(toolRegistry)
        val llmClient = createLLMClient()
        driver = LLMDriver(llmClient, toolExecutor, toolRegistry)

        // 显示工具窗口
        showToolWindow()
    }

    fun onUserInput(text: String) {
        GlobalScope.launch(Dispatchers.Default) {
            session = driver.process(session, text)

            // 更新 UI
            SwingUtilities.invokeLater {
                updateToolWindow()
            }
        }
    }
}
```

---

## 实施计划

### Phase 1: 数据结构和工具定义 (1 周)

- [ ] 定义 Session、Message、Part 数据结构
- [ ] 定义 Tool 接口
- [ ] 实现 ToolRegistry
- [ ] 实现 ToolExecutor

### Phase 2: 后端服务 (2 周)

- [ ] 搭建 Spring Boot 项目
- [ ] 实现语义搜索 API (BGE-M3)
- [ ] 实现业务图谱 API (Neo4j)
- [ ] 实现规则查询 API
- [ ] 实现案例查询 API

### Phase 3: 前端插件 - 基础 (2 周)

- [ ] 搭建 IntelliJ 插件项目
- [ ] 实现会话管理
- [ ] 实现工具窗口 UI
- [ ] 集成后端 API

### Phase 4: 前端插件 - LLM 集成 (2 周)

- [ ] 实现 LLM 客户端接口
- [ ] 实现 DeepSeek/Ollama 客户端
- [ ] 实现 LLMDriver
- [ ] 实现提示词组装

### Phase 5: 前端插件 - IDE 集成 (1 周)

- [ ] 实现 ReadFileTool
- [ ] 实现 FindCallersTool
- [ ] 实现 FindCalleesTool
- [ ] 实现 NavigateTool
- [ ] 集成 Git

### Phase 6: 测试和优化 (1 周)

- [ ] 端到端测试
- [ ] 性能优化
- [ ] 用户体验优化

**总计：9 周**

---

## 总结

### 核心设计原则

| 原则 | 说明 |
|------|------|
| **LLM 是引擎** | 让 LLM 自主决策，不要预设流程 |
| **架构是底盘** | 提供工具、维护状态、让出控制 |
| **工具清晰可靠** | 每个工具都有明确的定义和文档 |
| **状态无歧义** | 清晰记录做了什么、正在做什么、要达成什么 |
| **相信 LLM** | 让 LLM 自己判断怎么切分任务、怎么组织流程 |

### 与传统架构的区别

| 传统架构 | LLM 驱动架构 |
|---------|-------------|
| 预设流程 | 自主决策 |
| 状态机驱动 | LLM 驱动 |
| 硬编码规则 | LLM 判断 |
| 固定任务切分 | 自主任务切分 |
| 控制流程 | 提供工具 |

### 关键洞察

> **架构的职责不是控制流程，而是提供清晰可靠的工具和维护灵活无歧义的数据结构。让 LLM 作为引擎，自主驱动完成复杂任务。**

---

> **LLM 的能力越来越强，足以作为引擎驱动流程。架构设计应该相信 LLM，而不是限制 LLM。**
