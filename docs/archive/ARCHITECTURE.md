# SmanUnion 架构文档

## 项目概述

**SmanUnion** 是一个统一的 IntelliJ IDEA 插件项目，整合了 SmanAgent 的前端 UI 和后端 Agent 逻辑，提供 AI 驱动的代码分析和对话能力。

### 核心特性

- 🤖 **AI 驱动代码分析**：基于 ReAct 循环的智能代码分析
- 💬 **多轮对话支持**：完整的会话管理和上下文保持
- 🔧 **12+ 本地工具**：read_class, text_search, call_chain 等代码分析工具
- 📊 **流式输出**：实时推送分析进度和中间结果
- 🎯 **上下文隔离**：每个工具调用在独立子会话中执行，防止 Token 爆炸
- 🛡️ **降级模式**：LLM 调用失败时的智能降级处理

### 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.20 |
| 平台 | IntelliJ Platform SDK 2024.1 |
| HTTP 客户端 | OkHttp 4.12.0 |
| JSON 处理 | Jackson 2.16.0 |
| Markdown 渲染 | Flexmark 0.64.8 |
| 日志 | SLF4J + Logback |

---

## 架构演进

### 原始架构（SmanAgent）

```
┌─────────────────────────────────────────────────────────────┐
│                    IntelliJ IDEA Plugin                      │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              SmanAgent ide-plugin                   │   │
│  │  ┌─────────────┐         ┌─────────────────────┐   │   │
│  │  │ ChatPanel   │◄───────┤ AgentWebSocketClient │   │   │
│  │  │ (UI/Kotlin) │  WS     │  (Java)             │   │   │
│  │  └─────────────┘         └─────────────────────┘   │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ WebSocket (ws://localhost:8080/ws/agent)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                  SmanAgent Backend Agent                     │
│  ┌─────────────────────────────────────────────────────┐   │
│  │               SmanAgent agent                        │   │
│  │  ┌─────────────┐  ┌────────────┐  ┌──────────────┐  │   │
│  │  │SmanAgentLoop│  │ LlmService │  │ToolRegistry  │  │   │
│  │  │  (Java)     │  │  (Java)    │  │  (Java)      │  │   │
│  │  └─────────────┘  └────────────┘  └──────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
│                    Spring Boot Application                  │
└─────────────────────────────────────────────────────────────┘
```

### 当前架构（SmanUnion - 单模块）

```
┌─────────────────────────────────────────────────────────────┐
│              IntelliJ IDEA Plugin (SmanUnion)                │
│                                                              │
│  ┌────────────────────────────────────────────────────┐     │
│  │                    Frontend UI                     │     │
│  │  ┌─────────────┐  ┌──────────────┐  ┌───────────┐ │     │
│  │  │ ChatPanel   │  │ToolWindow    │  │ Settings  │ │     │
│  │  │ (Kotlin)    │  │ (Kotlin)     │  │ (Kotlin)  │ │     │
│  │  └─────────────┘  └──────────────┘  └───────────┘ │     │
│  └────────────────────────────────────────────────────┘     │
│                          │                                   │
│                          │ Direct Local Call                 │
│                          ▼                                   │
│  ┌────────────────────────────────────────────────────┐     │
│  │                   Backend Core                     │     │
│  │  ┌─────────────┐  ┌────────────┐  ┌──────────────┐│     │
│  │  │SmanAgentLoop│  │ LlmService │  │ToolRegistry  ││     │
│  │  │ (Kotlin)    │  │ (Kotlin)   │  │  (Kotlin)    ││     │
│  │  └─────────────┘  └────────────┘  └──────────────┘│     │
│  │                                                     │     │
│  │  ┌─────────────────┐  ┌──────────────────────────┐│     │
│  │  │SessionManager   │  │StreamingNotification     ││     │
│  │  │(Kotlin)         │  │Handler (Kotlin)          ││     │
│  │  └─────────────────┘  └──────────────────────────┘│     │
│  └────────────────────────────────────────────────────┘     │
│                                                              │
│  ┌────────────────────────────────────────────────────┐     │
│  │                      Models                        │     │
│  │  ┌────────┐  ┌────────┐  ┌────────┐  ┌─────────┐ │     │
│  │  │ Session│  │Message │  │  Part  │  │  Tool   │ │     │
│  │  │(Kotlin)│  │(Kotlin)│  │(Kotlin)│  │(Kotlin) │ │     │
│  │  └────────┘  └────────┘  └────────┘  └─────────┘ │     │
│  └────────────────────────────────────────────────────┘     │
│                                                              │
│  ┌────────────────────────────────────────────────────┐     │
│  │                     Tools                          │     │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ │     │
│  │  │read_file│ │grep_file│ │call_chain││...      │ │     │
│  │  │(Kotlin) │ │(Kotlin) │ │(Kotlin) │ │(Kotlin) │ │     │
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘ │     │
│  └────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ HTTPS
                              ▼
                    ┌─────────────────┐
                    │   LLM API       │
                    │  (GLM-4-Flash)  │
                    └─────────────────┘
```

### 主要变更

| 方面 | 原始架构 | 当前架构 |
|------|----------|----------|
| 模块结构 | 双模块 (ide-plugin + agent) | 单模块统一 |
| 通信方式 | WebSocket | 直接本地调用 |
| 后端语言 | Java + Spring Boot | Kotlin (无 Spring) |
| HTTP 客户端 | RestTemplate | OkHttp |
| 依赖注入 | @Autowired | 构造器注入 |
| 部署方式 | 需要独立启动后端 | 插件内运行 |

---

## 目录结构

```
src/main/kotlin/com/smancode/smanagent/
├── ide/                          # 前端 UI 层
│   ├── ui/                       # UI 组件
│   │   ├── SmanAgentChatPanel.kt         # 主聊天面板
│   │   ├── SmanAgentToolWindowFactory.kt # 工具窗口工厂
│   │   └── SettingsDialog.kt             # 设置对话框
│   ├── components/                # UI 子组件
│   │   ├── CliInputArea.kt               # 输入区域
│   │   ├── CliControlBar.kt              # 控制栏
│   │   ├── WelcomePanel.kt               # 欢迎面板
│   │   └── TaskProgressBar.kt            # 进度条
│   ├── renderer/                 # 消息渲染器
│   │   ├── CliMessageRenderer.kt         # CLI 消息渲染
│   │   ├── StyledMessageRenderer.kt      # 样式消息渲染
│   │   ├── MarkdownRenderer.kt           # Markdown 渲染
│   │   └── TodoRenderer.kt               # Todo 渲染
│   ├── service/                  # IDE 服务层
│   │   ├── StorageService.kt             # 存储服务
│   │   ├── CodeEditService.kt            # 代码编辑服务
│   │   ├── LocalToolExecutor.kt          # 本地工具执行器
│   │   ├── GitCommitHandler.kt           # Git 提交处理
│   │   └── AgentWebSocketClient.kt       # WebSocket 客户端（待移除）
│   ├── core/                     # IDE 核心
│   │   └── PsiNavigationHelper.kt        # PSI 导航辅助
│   ├── util/                     # 工具类
│   │   ├── SessionIdGenerator.kt         # Session ID 生成器
│   │   └── SystemInfoProvider.kt         # 系统信息提供者
│   ├── theme/                    # 主题配置
│   │   └── ThemeColors.kt                 # 颜色主题
│   └── model/                    # UI 模型
│       └── PartModels.kt                  # Part 视图模型
│
├── smancode/                     # 后端核心层（从 agent 转换）
│   ├── core/                     # 核心逻辑
│   │   ├── SmanAgentLoop.kt              # ReAct 循环核心
│   │   ├── SessionManager.kt             # 会话管理器
│   │   ├── SubTaskExecutor.kt            # 子任务执行器
│   │   ├── StreamingNotificationHandler.kt # 流式通知处理
│   │   ├── ContextCompactor.kt           # 上下文压缩器
│   │   ├── ResultSummarizer.kt           # 结果摘要生成器
│   │   ├── ToolResultFormatter.kt        # 工具结果格式化
│   │   ├── ParamsFormatter.kt            # 参数格式化
│   │   └── TokenEstimator.kt             # Token 估算器
│   ├── llm/                      # LLM 服务层
│   │   ├── LlmService.kt                 # LLM 服务实现
│   │   └── config/                       # LLM 配置
│   │       ├── LlmPoolConfig.kt          # 连接池配置
│   │       ├── LlmEndpoint.kt            # 端点配置
│   │       └── LlmRetryPolicy.kt         # 重试策略
│   ├── prompt/                   # Prompt 管理
│   │   ├── PromptDispatcher.kt           # Prompt 分发器
│   │   ├── PromptLoaderService.kt        # Prompt 加载服务
│   │   └── DynamicPromptInjector.kt      # 动态 Prompt 注入器
│   └── command/                  # 命令处理
│       └── SlashCommandHandler.kt        # 斜杠命令处理
│
├── tools/                       # 工具系统
│   ├── Tool.kt                           # 工具接口
│   ├── AbstractTool.kt                   # 抽象工具基类
│   ├── SessionAwareTool.kt               # 会话感知工具
│   ├── ToolRegistry.kt                   # 工具注册表
│   ├── ToolExecutor.kt                   # 工具执行器
│   ├── ToolResult.kt                     # 工具结果
│   ├── ParameterDef.kt                   # 参数定义
│   └── BatchSubResult.kt                 # 批量子任务结果
│
├── model/                       # 数据模型
│   ├── message/                  # 消息模型
│   │   ├── Message.kt                    # 消息基类
│   │   ├── Role.kt                       # 角色枚举
│   │   ├── TokenUsage.kt                 # Token 使用统计
│   │   └── MessageExtensions.kt          # 消息扩展
│   ├── part/                     # Part 模型
│   │   ├── Part.kt                       # Part 基类
│   │   ├── PartType.kt                   # Part 类型枚举
│   │   ├── TextPart.kt                   # 文本 Part
│   │   ├── ToolPart.kt                   # 工具 Part
│   │   ├── ReasoningPart.kt              # 推理 Part
│   │   ├── GoalPart.kt                   # 目标 Part
│   │   ├── ProgressPart.kt               # 进度 Part
│   │   ├── TodoPart.kt                   # 待办 Part
│   │   └── SubTaskPart.kt                # 子任务 Part
│   ├── session/                  # 会话模型
│   │   ├── Session.kt                    # 会话类
│   │   ├── SessionStatus.kt              # 会话状态枚举
│   │   └── ProjectInfo.kt                # 项目信息
│   ├── context/                  # 上下文模型
│   │   └── SharedContext.kt              # 共享上下文
│   ├── subtask/                  # 子任务模型
│   │   └── SubTaskConclusion.kt          # 子任务结论
│   ├── DomainKnowledge.kt                # 领域知识
│   ├── BusinessTerm.kt                   # 业务术语
│   ├── BusinessAnalysis.kt               # 业务分析
│   ├── CodeElement.kt                    # 代码元素
│   └── TermRelation.kt                   # 术语关系
│
├── util/                        # 工具类
│   └── StackTraceUtils.kt                # 堆栈跟踪工具
│
├── config/                      # 配置
│   └── SmanCodeProperties.kt             # SmanCode 配置属性
│
├── dto/                         # 数据传输对象
│   └── AgentMessageRequest.kt            # Agent 消息请求
│
├── websocket/                   # WebSocket 相关（待移除）
│   ├── AgentWebSocketHandler.kt          # WebSocket 处理器
│   └── WebSocketConfig.kt                # WebSocket 配置
│
└── SmanAgentPlugin.kt                   # 插件主入口
```

---

## 核心组件详解

### 1. 前端 UI 层 (ide/)

#### 1.1 SmanAgentChatPanel
主聊天面板，负责用户交互和消息显示。

**主要职责**：
- 渲染用户输入和 AI 响应
- 处理用户消息发送
- 显示工具调用进度
- 管理 Markdown 渲染
- 处理快捷键和命令

**关键方法**：
```kotlin
private fun sendMessage(text: String) {
    // TODO: 改为本地调用 SmanAgentLoop
    // 当前实现：显示占位消息
    outputArea.text = "⚠️ 本地调用模式正在开发中，请稍后...\n\n输入: $text"
}
```

**状态**：
- ✅ WebSocket 代码已移除
- ⏳ 本地服务集成待实现

#### 1.2 SmanAgentToolWindowFactory
工具窗口工厂，负责创建和注册工具窗口。

```kotlin
class SmanAgentToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatPanel = SmanAgentChatPanel(project)
        // ...
    }
}
```

#### 1.3 SettingsDialog
配置对话框，允许用户设置：
- 后端 URL（本地模式不再需要）
- API Key
- 模型选择
- 其他插件偏好

### 2. 后端核心层 (smancode/)

#### 2.1 SmanAgentLoop
ReAct 循环核心，负责协调 LLM 调用和工具执行。

**处理流程**：
```
1. 接收用户消息
   ↓
2. 检查会话状态和上下文压缩
   ↓
3. 推送确认消息（判断用户意图）
   ↓
4. ReAct 循环开始
   ├─→ 构建提示词
   ├─→ 调用 LLM
   ├─→ 解析响应（提取 JSON）
   ├─→ 处理 TextPart（直接显示）
   └─→ 处理 ToolPart（执行工具）
       ├─→ 子会话中执行工具
       ├─→ 推送工具执行进度
       ├─→ 推送工具完成通知
       ├─→ 推送阶段性结论
       └─→ 继续循环
   ↓
5. 推送最终总结
   ↓
6. 返回助手消息
```

**关键代码**：
```kotlin
class SmanAgentLoop(
    private val llmService: LlmService,
    private val promptDispatcher: PromptDispatcher,
    private val toolRegistry: ToolRegistry,
    private val subTaskExecutor: SubTaskExecutor,
    private val notificationHandler: StreamingNotificationHandler,
    private val contextCompactor: ContextCompactor,
    private val smanCodeProperties: SmanCodeProperties,
    private val dynamicPromptInjector: DynamicPromptInjector
) {
    fun process(session: Session, userInput: String, partPusher: Consumer<Part>): Message {
        // 核心处理逻辑
    }
}
```

#### 2.2 SessionManager
会话管理器，负责创建和管理会话。

**功能**：
- 创建根会话和子会话
- 会话状态管理
- 会话清理

#### 2.3 SubTaskExecutor
子任务执行器，实现工具调用的上下文隔离。

**关键特性**：
- 每个工具调用在独立子会话中执行
- 保留完整结果用于 LLM 生成摘要
- 生成前端显示摘要
- 防止 Token 爆炸

#### 2.4 StreamingNotificationHandler
流式通知处理器，负责生成和推送渐进式输出。

**通知类型**：
- 确认消息（Acknowledgment）
- 工具调用通知
- 工具进度通知
- 工具完成通知
- 工具错误通知
- 阶段性结论
- 最终总结

### 3. LLM 服务层 (smancode/llm/)

#### 3.1 LlmService
LLM 服务实现，使用 OkHttp 进行 HTTP 调用。

**主要方法**：
```kotlin
class LlmService(private val poolConfig: LlmPoolConfig) {
    private val client = OkHttpClient()

    // 简单文本请求（用于 ReAct 循环）
    fun simpleRequest(systemPrompt: String, userPrompt: String): String

    // JSON 请求（用于结构化输出）
    fun jsonRequest(vararg prompts: String): JsonNode
}
```

**配置**：
- 支持多端点负载均衡
- 自动重试机制
- 超时控制

#### 3.2 LlmPoolConfig
连接池配置，管理多个 LLM 端点。

```kotlin
class LlmPoolConfig(
    val endpoints: List<LlmEndpoint>,
    val retryPolicy: LlmRetryPolicy
) {
    fun getNextAvailableEndpoint(): LlmEndpoint?
}
```

### 4. Prompt 管理层 (smancode/prompt/)

#### 4.1 PromptDispatcher
Prompt 分发器，负责加载和分发系统提示词。

**Prompt 类型**：
- 系统提示词（System Prompt）
- 工具描述提示词
- 动态注入提示词

#### 4.2 DynamicPromptInjector
动态 Prompt 注入器，根据上下文动态注入相关内容。

### 5. 工具系统 (tools/)

#### 5.1 Tool 接口
所有工具的基类接口。

```kotlin
interface Tool {
    val name: String
    val description: String
    val parameters: List<ParameterDef>

    fun execute(params: Map<String, Any>): ToolResult
}
```

#### 5.2 ToolRegistry
工具注册表，管理所有可用工具。

```kotlin
class ToolRegistry {
    private val tools: Map<String, Tool>

    fun getTool(name: String): Tool?
    fun getAllTools(): List<Tool>
}
```

#### 5.3 ToolExecutor
工具执行器，负责执行工具调用。

**特性**：
- 支持会话感知工具
- 参数验证
- 错误处理

### 6. 数据模型 (model/)

#### 6.1 Message
消息类，表示对话中的一条消息。

```kotlin
class Message {
    var id: String?
    var sessionId: String?
    var role: Role?
    var parts: MutableList<Part>

    fun isUserMessage(): Boolean
    fun isAssistantMessage(): Boolean
    fun isSystemMessage(): Boolean
}
```

#### 6.2 Part
Part 基类，表示消息的一部分。

**Part 类型**：
- `TextPart`: 文本内容
- `ToolPart`: 工具调用
- `ReasoningPart`: 推理过程
- `GoalPart`: 目标描述
- `ProgressPart`: 进度更新
- `TodoPart`: 待办事项
- `SubTaskPart`: 子任务

#### 6.3 Session
会话类，表示一个完整的对话会话。

```kotlin
class Session {
    var id: String?
    var webSocketSessionId: String?
    var projectInfo: ProjectInfo?
    var status: SessionStatus
    var messages: MutableList<Message>

    val latestUserMessage: Message?
    val latestAssistantMessage: Message?

    fun markBusy()
    fun markIdle()
}
```

---

## 数据流

### 用户输入处理流程

```
用户输入文本
    ↓
ChatPanel.sendMessage()
    ↓
[TODO] 调用 SmanAgentLoop.process()
    ↓
┌─────────────────────────────────────┐
│         SmanAgentLoop               │
│                                     │
│  1. 检查会话状态                    │
│  2. 上下文压缩检查                  │
│  3. 推送确认消息                    │
│  4. ReAct 循环：                   │
│     ├─ 构建提示词                   │
│     ├─ 调用 LLM                    │
│     ├─ 解析响应                     │
│     ├─ 处理 TextPart → partPusher  │
│     └─ 处理 ToolPart → 执行工具    │
│  5. 推送最终总结                    │
└─────────────────────────────────────┘
    ↓
partPusher.accept(part)
    ↓
ChatPanel 显示 Part
```

### Part 推送机制

```kotlin
// partPusher 回调定义
val partPusher = Consumer<Part> { part ->
    // 在 EDT 线程中更新 UI
    ApplicationManager.getApplication().invokeLater {
        when (part.type) {
            PartType.TEXT -> appendTextPart(part as TextPart)
            PartType.TOOL -> appendToolPart(part as ToolPart)
            PartType.REASONING -> appendReasoningPart(part as ReasoningPart)
            // ... 其他类型
        }
    }
}

// 传递给 SmanAgentLoop
smanAgentLoop.process(session, userInput, partPusher)
```

---

## 集成点

### 1. ChatPanel → SmanAgentLoop

**当前状态**：
```kotlin
private fun sendMessage(text: String) {
    // TODO: 改为本地调用 SmanAgentLoop
    outputArea.text = "⚠️ 本地调用模式正在开发中，请稍后...\n\n输入: $text"
}
```

**目标实现**：
```kotlin
private fun sendMessage(text: String) {
    // 1. 获取或创建 Session
    val session = sessionManager.getOrCreateSession(project)

    // 2. 创建用户消息
    val userMessage = Message().apply {
        this.role = Role.USER
        this.sessionId = session.id
        this.addPart(TextPart().apply {
            this.text = text
        })
    }
    session.addMessage(userMessage)

    // 3. 创建 partPusher 回调
    val partPusher = Consumer<Part> { part ->
        ApplicationManager.getApplication().invokeLater {
            displayPart(part)
        }
    }

    // 4. 调用 SmanAgentLoop
    val backgroundTask = object : Task.Backgroundable(project, "AI 处理中...", true) {
        override fun run(indicator: ProgressIndicator) {
            val assistantMessage = smanAgentLoop.process(session, text, partPusher)
        }
    }
    BackgroundableUtil.run(backgroundTask)
}
```

### 2. SmanAgentLoop 依赖初始化

**需要初始化的组件**：

```kotlin
// 1. LlmService
val llmPoolConfig = LlmPoolConfig(
    endpoints = listOf(
        LlmEndpoint(
            baseUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            apiKey = settings.apiKey,
            modelName = "glm-4-flash"
        )
    ),
    retryPolicy = LlmRetryPolicy(maxRetries = 3)
)
val llmService = LlmService(llmPoolConfig)

// 2. ToolRegistry
val toolRegistry = ToolRegistry()
toolRegistry.register(ReadFileTool())
toolRegistry.register(GrepFileTool())
// ... 注册其他工具

// 3. ToolExecutor
val toolExecutor = ToolExecutor(toolRegistry)

// 4. SessionManager
val sessionManager = SessionManager()

// 5. SubTaskExecutor
val subTaskExecutor = SubTaskExecutor(
    sessionManager = sessionManager,
    toolExecutor = toolExecutor,
    resultSummarizer = ResultSummarizer(),
    llmService = llmService,
    notificationHandler = notificationHandler
)

// 6. StreamingNotificationHandler
val notificationHandler = StreamingNotificationHandler(llmService)

// 7. PromptDispatcher
val promptDispatcher = PromptDispatcher()

// 8. ContextCompactor
val contextCompactor = ContextCompactor(llmService)

// 9. DynamicPromptInjector
val dynamicPromptInjector = DynamicPromptInjector()

// 10. SmanAgentLoop
val smanAgentLoop = SmanAgentLoop(
    llmService = llmService,
    promptDispatcher = promptDispatcher,
    toolRegistry = toolRegistry,
    subTaskExecutor = subTaskExecutor,
    notificationHandler = notificationHandler,
    contextCompactor = contextCompactor,
    smanCodeProperties = SmanCodeProperties(),
    dynamicPromptInjector = dynamicPromptInjector
)
```

---

## 待完成工作

### 1. 本地服务初始化
- [ ] 在 ChatPanel 中初始化 SmanAgentLoop 及其依赖
- [ ] 创建服务管理类统一管理生命周期
- [ ] 处理异步调用和线程切换

### 2. sendMessage() 实现
- [ ] 实现本地调用逻辑
- [ ] 创建 partPusher 回调
- [ ] 处理 Part 显示

### 3. 工具集成
- [ ] 确保所有工具在本地环境中正常工作
- [ ] 处理工具执行的线程切换
- [ ] 实现工具结果的 UI 反馈

### 4. 错误处理
- [ ] LLM 调用失败处理
- [ ] 工具执行失败处理
- [ ] 网络错误处理

### 5. 测试
- [ ] 重写测试用例（修复包名问题）
- [ ] 添加集成测试
- [ ] 添加 UI 测试

### 6. 清理工作
- [ ] 移除 AgentWebSocketClient.kt
- [ ] 移除 WebSocket 相关配置
- [ ] 清理不再使用的依赖

---

## 构建和运行

### 构建插件
```bash
./gradlew buildPlugin
```

### 运行测试
```bash
./gradlew test
```

### 在 IDE 中运行
```bash
./gradlew runIde
```

### 发布插件
```bash
./gradlew publishPlugin
```

---

## 配置文件

### build.gradle.kts
主要配置：
- Kotlin 1.9.20
- IntelliJ Platform 2024.1
- 依赖：OkHttp, Jackson, Flexmark, Logback

### plugin.xml
插件清单：
- 插件名称：SmanUnion
- 版本：2.0.0
- 工具窗口：SmanAgent Chat
- 兼容性：2024.1 - 2025.3

---

## 相关资源

- **项目根目录**: `/Users/liuchao/projects/smanunion`
- **构建输出**: `build/distributions/smanunion-2.0.0.zip`
- **插件大小**: ~21MB
- **支持平台**: IntelliJ IDEA 2024.1+

---

## 版本历史

### 2.0.0 (当前版本)
- 🚀 重大整合：合并 SmanAgent ide-plugin 和 agent
- ✨ 单模块架构：移除 WebSocket，改为本地调用
- 🔧 Kotlin 转换：将 Java 后端代码转换为 Kotlin
- 📦 依赖优化：移除 Spring 依赖，使用 OkHttp
