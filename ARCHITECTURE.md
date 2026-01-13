# SmanAgent 架构设计文档

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
5. [前后端通信](#前后端通信)
6. [前端设计](#前端设计)

---

## 架构本质

### 核心问题

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

### 三项核心职责

| 职责 | 说明 | 不可接受的做法 |
|------|------|----------------|
| **提供工具** | 给 LLM 足够的能力去完成任务 | 工具不足、工具不可靠、工具文档不清楚 |
| **维护状态** | 记录做了什么、正在做什么、要达成什么 | 状态模糊、状态丢失、状态冲突 |
| **让出控制** | 让 LLM 自己判断怎么切分任务、怎么组织流程 | 预设流程、限制分支、硬编码规则 |

---

## 数据结构设计

### 核心抽象：Session 和 Message

```java
// ========== 会话 ==========
public class Session {
    private String id;                      // 会话 ID
    private ProjectInfo projectInfo;        // 项目信息
    private SessionStatus status;           // 状态：IDLE | BUSY | RETRY
    private List<Message> messages;         // 消息列表
    private Instant createdTime;            // 创建时间
    private Instant updatedTime;            // 更新时间

    // 极简设计：移除了 Goal 内部类，目标通过 GoalPart 表达
}

// ========== 会话状态 ==========
public enum SessionStatus {
    IDLE,   // 空闲
    BUSY,   // 忙碌（处理中）
    RETRY   // 重试中
}

// ========== 消息 ==========
public class Message {
    private String id;                      // 消息 ID
    private String sessionId;               // 所属会话
    private Role role;                      // 角色：USER | ASSISTANT | SYSTEM
    private List<Part> parts;               // Part 列表
    private Instant createdTime;            // 创建时间
}

// ========== 角色 ==========
public enum Role {
    USER,       // 用户
    ASSISTANT,  // AI 助手
    SYSTEM      // 系统
}
```

### Part 系统（统一内容抽象）

```java
// ========== Part 基类 ==========
public abstract class Part {
    private String id;                      // Part ID
    private String messageId;               // 所属消息
    private String sessionId;               // 所属会话
    private PartType type;                  // Part 类型
    private Instant createdTime;            // 创建时间
    private Instant updatedTime;            // 更新时间

    public void touch();                    // 更新时间戳
}

// ========== Part 类型 ==========
public enum PartType {
    TEXT,           // 文本内容
    REASONING,      // 思考过程
    TOOL,           // 工具调用（核心！）
    GOAL,           // 目标显示
    PROGRESS,       // 进度更新
    TODO            // TODO 列表
}

// ========== TextPart（文本） ==========
public class TextPart extends Part {
    private String text;                    // 文本内容
}

// ========== ReasoningPart（思考） ==========
public class ReasoningPart extends Part {
    private String text;                    // 思考内容
}

// ========== ToolPart（工具调用） ==========
public class ToolPart extends Part {
    private String toolName;                // 工具名称
    private Map<String, Object> parameters; // 参数
    private ToolResult result;              // 执行结果
    private ToolState state;                // 状态

    public enum ToolState {
        PENDING,    // 等待执行
        RUNNING,    // 执行中
        COMPLETED,  // 完成
        ERROR       // 错误
    }
}

// ========== GoalPart（目标） ==========
public class GoalPart extends Part {
    private String title;                   // 目标标题
    private String description;             // 目标描述
    private GoalStatus status;              // 目标状态

    public enum GoalStatus {
        PENDING,        // 待处理
        IN_PROGRESS,    // 进行中
        COMPLETED,      // 已完成
        CANCELLED       // 已取消
    }
}

// ========== ProgressPart（进度） ==========
public class ProgressPart extends Part {
    private String message;                 // 进度消息
    private int current;                    // 当前进度
    private int total;                      // 总进度
}

// ========== TodoPart（任务列表） ==========
public class TodoPart extends Part {
    private List<TodoItem> items;           // 任务项列表

    public static class TodoItem {
        private String id;                  // 任务 ID
        private String content;             // 任务内容
        private TodoStatus status;          // 任务状态

        public enum TodoStatus {
            PENDING,        // 待处理
            IN_PROGRESS,    // 进行中
            COMPLETED,      // 已完成
            CANCELLED       // 已取消
        }
    }
}
```

### ToolPart 状态机

```
PENDING → RUNNING → COMPLETED
                    ↓
                   ERROR
```

---

## 工具系统设计

### 工具接口

```java
// ========== 工具接口 ==========
public interface Tool {
    String getName();                       // 工具名称
    String getDescription();                // 工具描述（给 LLM 看）
    Map<String, ParameterDef> getParameters(); // 参数定义
    String getReturns();                    // 返回值说明
    ToolResult execute(Map<String, Object> args); // 执行工具
}

// ========== 参数定义 ==========
public class ParameterDef {
    private String name;                    // 参数名
    private String type;                    // 参数类型
    private String description;             // 参数描述
    private boolean required;               // 是否必填
    private Object defaultValue;            // 默认值
}

// ========== 工具结果 ==========
public class ToolResult {
    private boolean success;                // 是否成功
    private String summary;                 // 结果摘要（给 LLM 看）
    private String details;                 // 详细内容（按需获取）
    private Map<String, Object> data;       // 额外数据
}
```

### 已注册工具

| 工具名 | 用途 | 参数 |
|--------|------|------|
| `semantic_search` | 语义搜索代码片段 | `query`, `topK` |
| `read_file` | 读取文件内容 | `path`, `startLine`, `endLine` |
| `grep_file` | 正则搜索文件内容 | `path`, `pattern` |
| `find_file` | 按文件名搜索 | `pattern` |
| `call_chain` | 分析方法调用链 | `className`, `methodName` |
| `extract_xml` | 提取 XML 标签内容 | `text`, `tagName` |

### 工具注册表

```java
@Component
public class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();

    @Autowired
    public ToolRegistry(
        SemanticSearchTool semanticSearchTool,
        ReadFileTool readFileTool,
        GrepFileTool grepFileTool,
        FindFileTool findFileTool,
        CallChainTool callChainTool,
        ExtractXmlTool extractXmlTool
    ) {
        registerTool(semanticSearchTool);
        registerTool(readFileTool);
        registerTool(grepFileTool);
        registerTool(findFileTool);
        registerTool(callChainTool);
        registerTool(extractXmlTool);
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    // 生成工具列表描述（用于提示词）
    public String getToolsDescription() {
        // ... 生成 Markdown 格式的工具描述
    }
}
```

### 工具执行器

```java
@Component
public class ToolExecutor {
    @Autowired
    private ToolRegistry toolRegistry;

    public ToolResult execute(ToolPart toolPart) {
        String toolName = toolPart.getToolName();
        Tool tool = toolRegistry.getTool(toolName);

        if (tool == null) {
            return ToolResult.failure("未知工具: " + toolName);
        }

        // 参数校验
        Map<String, Object> params = toolPart.getParameters();
        for (ParameterDef param : tool.getParameters().values()) {
            if (param.isRequired() && !params.containsKey(param.getName())) {
                return ToolResult.failure("缺少必填参数: " + param.getName());
            }
        }

        // 执行工具
        try {
            return tool.execute(params);
        } catch (Exception e) {
            return ToolResult.failure("执行失败: " + e.getMessage());
        }
    }
}
```

---

## LLM 驱动机制

### 核心循环（SmanAgentLoop）

```java
@Service
public class SmanAgentLoop {

    /**
     * 处理用户消息（核心入口）
     */
    public Message process(Session session, String userInput, Consumer<Part> partPusher) {
        // 1. 创建用户消息
        Message userMessage = createUserMessage(session.getId(), userInput);
        session.addMessage(userMessage);
        partPusher.accept(userMessage.getParts().get(0));

        // 2. 主循环
        Message assistantMessage = createAssistantMessage(session.getId());

        // 流式处理 LLM 输出
        callLLMStream(session.getMessages(), (part) -> {
            assistantMessage.addPart(part);
            partPusher.accept(part);

            // 如果是 ToolPart，执行工具
            if (part instanceof ToolPart) {
                ToolPart toolPart = (ToolPart) part;
                executeToolInSubSession(toolPart, session, partPusher);
            }
        });

        session.addMessage(assistantMessage);
        return assistantMessage;
    }
}
```

### 上下文隔离（子会话执行工具）

```java
/**
 * 在独立子会话中执行工具调用（防止 Token 爆炸）
 */
private void executeToolInSubSession(ToolPart toolPart, Session parentSession, Consumer<Part> partPusher) {
    // 1. 更新状态为 RUNNING
    toolPart.setState(ToolState.RUNNING);
    partPusher.accept(toolPart);

    // 2. 创建子会话（只包含当前工具上下文）
    Session subSession = createSubSession(parentSession, toolPart);

    // 3. 在子会话中执行工具
    ToolResult result = toolExecutor.execute(toolPart);

    // 4. 格式化结果
    String formattedResult = toolResultFormatter.format(result);

    // 5. 推送结果
    toolPart.setState(ToolState.COMPLETED);
    toolPart.setResult(result);
    partPusher.accept(toolPart);

    // 6. 清理子会话
    subSession = null;
}
```

### 上下文压缩

```java
@Component
public class ContextCompactor {

    /**
     * 检查是否需要上下文压缩
     */
    public boolean needsCompaction(Session session) {
        int estimatedTokens = estimateTokens(session);
        return estimatedTokens > MAX_CONTEXT_TOKENS;
    }

    /**
     * 压缩上下文（保留摘要，清理完整输出）
     */
    public void compact(Session session, Consumer<Part> partPusher) {
        // 1. 生成摘要
        String summary = resultSummarizer.summarize(session);

        // 2. 发送压缩通知
        TextPart notification = new TextPart();
        notification.setText("🗑️ 上下文已压缩\n\n为避免 Token 超限，之前的对话历史已压缩为以下摘要：\n\n" + summary);
        partPusher.accept(notification);

        // 3. 清理旧消息
        session.clearOldMessages();
    }
}
```

### 提示词管理

```java
@Component
public class PromptDispatcher {

    /**
     * 构建系统提示词
     */
    public String buildSystemPrompt() {
        StringBuilder prompt = new StringBuilder();

        // 1. 基础系统提示
        prompt.append(loadPrompt("common/system-header.md"));

        // 2. 工具介绍
        prompt.append(loadPrompt("tools/tool-introduction.md"));

        // 3. 工具列表
        prompt.append(toolRegistry.getToolsDescription());

        return prompt.toString();
    }
}
```

---

## 前后端通信

### WebSocket 消息格式

```json
// 前端发送请求
{
  "type": "analyze",          // 或 "chat"
  "sessionId": "xxx",
  "projectKey": "xxx",
  "input": "用户输入"
}

// 后端推送 Part
{
  "type": "part",
  "sessionId": "xxx",
  "part": {
    "id": "xxx",
    "type": "TEXT",           // PartType
    "createdTime": "2026-01-13T...",
    "updatedTime": "2026-01-13T...",
    "text": "内容"            // 根据 type 不同，字段不同
  }
}

// 后端推送完成消息
{
  "type": "complete",
  "sessionId": "xxx"
}

// 后端推送错误
{
  "type": "error",
  "message": "错误信息"
}
```

### WebSocket 处理器

```java
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private SmanAgentLoop smanAgentLoop;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Map<String, Object> request = parseRequest(message);
        String type = (String) request.get("type");

        switch (type) {
            case "analyze" -> handleAnalyze(session, request);
            case "chat" -> handleChat(session, request);
            case "ping" -> handlePing(session);
        }
    }

    private void handleChat(WebSocketSession session, Map<String, Object> request) {
        String sessionId = (String) request.get("sessionId");
        String input = (String) request.get("input");

        Session s = sessionManager.getOrCreateSession(sessionId);

        // 推送 Part 到前端
        smanAgentLoop.process(s, input, part -> {
            pushPart(session, sessionId, part);
        });
    }
}
```

---

## 前端设计

### 组件架构

```
SmanAgentChatPanel (主面板)
├── ControlBar (控制栏)
│   ├── 新建会话按钮
│   ├── 历史记录按钮
│   └── 设置按钮
├── CenterPanel (中间内容区)
│   ├── WelcomePanel (欢迎面板)
│   └── OutputArea (消息输出区，JTextPane)
├── TaskProgressBar (任务进度栏，固定底部)
│   ├── 任务列表显示
│   └── 进度条
└── InputArea (输入框)
```

### 消息渲染（CLI 风格）

```kotlin
// ========== 消息渲染器 ==========
object StyledMessageRenderer {

    fun renderToDocument(part: PartData, doc: StyledDocument, colors: ColorPalette) {
        when (part.type) {
            PartType.TEXT -> renderTextPart(part, doc, colors)
            PartType.REASONING -> renderReasoningPart(part, doc, colors)
            PartType.TOOL -> renderToolPart(part, doc, colors)
            PartType.GOAL -> renderGoalPart(part, doc, colors)
            PartType.TODO -> renderTodoPart(part, doc, colors)
        }
    }

    private fun renderToolPart(part: PartData, doc: StyledDocument, colors: ColorPalette) {
        val toolName = part.data["toolName"] as? String ?: ""
        val state = part.data["state"] as? String ?: "PENDING"

        val text = when (state) {
            "PENDING" -> "▶ 调用工具: [$TOOL]$toolName[RESET]\n"
            "RUNNING" -> "⏳ 执行中: [$TOOL]$toolName[RESET]\n"
            "COMPLETED" -> "✓ 工具完成: [$TOOL]$toolName[RESET]\n"
            "ERROR" -> "✗ 工具失败: [$TOOL]$toolName[RESET]\n"
            else -> ""
        }

        // 带颜色的输出
        doc.insertString(doc.length, text, attributes)
    }
}
```

### 任务进度栏（固定底部）

```kotlin
class TaskProgressBar : JPanel(BorderLayout()) {

    private val tasksPanel: JPanel
    private val progressBar: JProgressBar
    private var currentItems: List<TodoItem> = emptyList()

    fun updateTasks(part: PartData) {
        val items = part.items
        currentItems = items

        if (items.isEmpty()) {
            isVisible = false
            return
        }

        isVisible = true

        // 更新任务列表
        tasksPanel.removeAll()
        for (item in items) {
            val taskLabel = JLabel(formatTaskItem(item))
            tasksPanel.add(taskLabel)
        }

        // 更新进度条
        val completed = items.count { it.status == "COMPLETED" }
        progressBar.value = (completed * 100) / items.size
        progressBar.string = "$completed/${items.size}"

        // 全部完成后自动隐藏
        if (completed == items.size) {
            Timer(2000) { isVisible = false }.start()
        }
    }
}
```

### WebSocket 客户端

```kotlin
class AgentWebSocketClient(
    private val serverUrl: String,
    private val onPartCallback: ((PartData) -> Unit)?
) {
    private var client: WebSocketClient? = null

    fun connect(): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()

        client = object : WebSocketClient(URI(serverUrl)) {
            override fun onOpen(handshake: ServerHandshake) {
                println("[SmanAgent] WebSocket 连接成功")
                future.complete(null)
            }

            override fun onMessage(message: String) {
                val data = objectMapper.readValue<Map<String, Any>>(message)
                val type = data["type"] as? String ?: ""

                when (type) {
                    "part" -> {
                        val part = parsePartData(data["part"] as? Map<String, Any>)
                        onPartCallback?.invoke(part)
                    }
                }
            }

            override fun onError(ex: Exception) {
                println("[SmanAgent] WebSocket 错误: ${ex.message}")
            }
        }

        client?.connect()
        return future
    }
}
```

---

## 关键设计原则

### 1. 极简状态管理

- **Session 只有 3 种状态**：IDLE, BUSY, RETRY
- **移除 Goal 内部类**：目标通过 GoalPart 表达
- **所有状态通过 Part 管理**：不引入额外的状态机

### 2. 上下文隔离

- **工具调用在子会话执行**：防止 Token 爆炸
- **只保留摘要**：清理完整的工具输出
- **自动压缩**：超过 Token 限制时自动压缩

### 3. 流式优先

- **所有 Part 支持流式更新**：实时推送到前端
- **WebSocket 实时通信**：不等待全部完成
- **进度可视化**：TaskProgressBar 固定在底部

### 4. LLM 自主决策

- **无硬编码意图识别**：完全由 LLM 决定行为
- **无预设流程**：LLM 自己决定怎么完成任务
- **提供清晰工具**：让 LLM 有足够能力

---

## 文件结构

### 后端 (agent/)

```
agent/src/main/java/com/smancode/smanagent/
├── model/
│   ├── message/
│   │   ├── Message.java          # 消息
│   │   ├── Role.java             # 角色
│   │   └── TokenUsage.java       # Token 使用
│   ├── part/
│   │   ├── Part.java             # Part 基类
│   │   ├── PartType.java         # Part 类型
│   │   ├── TextPart.java         # 文本 Part
│   │   ├── ReasoningPart.java    # 思考 Part
│   │   ├── ToolPart.java         # 工具 Part
│   │   ├── GoalPart.java         # 目标 Part
│   │   ├── ProgressPart.java     # 进度 Part
│   │   └── TodoPart.java         # TODO Part
│   └── session/
│       ├── Session.java          # 会话
│       ├── SessionStatus.java    # 会话状态
│       └── ProjectInfo.java      # 项目信息
├── tools/
│   ├── Tool.java                 # 工具接口
│   ├── ToolExecutor.java         # 工具执行器
│   ├── ToolRegistry.java         # 工具注册表
│   ├── ToolResult.java           # 工具结果
│   ├── search/                   # 搜索工具
│   ├── read/                     # 读取工具
│   └── analysis/                 # 分析工具
├── smancode/
│   ├── core/
│   │   ├── SmanAgentLoop.java    # 核心循环
│   │   ├── SubTaskExecutor.java  # 子任务执行器
│   │   ├── ContextCompactor.java # 上下文压缩器
│   │   ├── StreamingNotificationHandler.java
│   │   └── ...
│   ├── llm/
│   │   └── LlmService.java       # LLM 服务
│   └── prompt/
│       ├── PromptDispatcher.java # 提示词分发器
│       └── PromptLoaderService.java
├── websocket/
│   ├── AgentWebSocketHandler.java   # WebSocket 处理器
│   ├── WebSocketConfig.java         # WebSocket 配置
│   └── WebSocketSessionManager.java
├── controller/
│   └── AgentController.java     # HTTP 控制器
└── service/
    └── SessionFileService.java  # 会话持久化
```

### 前端 (ide-plugin/)

```
ide-plugin/src/main/kotlin/com/smancode/smanagent/ide/
├── components/
│   ├── TaskProgressBar.kt       # 任务进度栏
│   ├── CliControlBar.kt         # 控制栏
│   ├── CliInputArea.kt          # 输入框
│   └── WelcomePanel.kt          # 欢迎面板
├── model/
│   ├── PartModels.kt            # Part 数据模型
│   └── GraphModels.kt           # 图模型
├── renderer/
│   ├── StyledMessageRenderer.kt # 消息渲染器
│   └── CliMessageRenderer.kt    # CLI 渲染器
├── service/
│   ├── AgentWebSocketClient.kt  # WebSocket 客户端
│   └── StorageService.kt        # 存储服务
├── ui/
│   ├── SmanAgentChatPanel.kt    # 主面板
│   ├── SmanAgentToolWindowFactory.kt
│   └── SettingsDialog.kt        # 设置对话框
└── theme/
    ├── ThemeColors.kt           # 主题颜色
    └── ColorPalette.kt          # 颜色调色板
```

---

## 参考文档

- [OpenCode prompt.ts](https://github.com/openai/open-code) - LLM 驱动循环参考
- [Spring WebSocket](https://docs.spring.io/spring-framework/reference/web/websocket.html) - WebSocket 文档
- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html) - 插件开发文档

  二、前端显示逻辑
  ┌───────────┬─────────────────────────────┬───────────────────────────────────┐
  │ PartType  │          渲染方式           │            使用的颜色             │
  ├───────────┼─────────────────────────────┼───────────────────────────────────┤
  │ USER      │ StyledDocument 直接插入     │ textPrimary (内容), warning (>>>) │
  ├───────────┼─────────────────────────────┼───────────────────────────────────┤
  │ TEXT      │ Markdown → HTML → JTextPane │ CSS 样式                          │
  ├───────────┼─────────────────────────────┼───────────────────────────────────┤
  │ REASONING │ Markdown → HTML (蓝色斜体)  │ #61AFEF + italic                  │
  ├───────────┼─────────────────────────────┼───────────────────────────────────┤
  │ TOOL      │ 样式标记文本 → HTML span    │ warning/codeFunction 等           │
  ├───────────┼─────────────────────────────┼───────────────────────────────────┤
  │ GOAL      │ 样式标记文本 → HTML span    │ textPrimary/textSecondary         │
  ├───────────┼─────────────────────────────┼───────────────────────────────────┤
  │ PROGRESS  │ 样式标记文本 → HTML span    │ info                              │
  ├───────────┼─────────────────────────────┼───────────────────────────────────┤
  │ TODO      │ 样式标记文本 → HTML span    │ 多种颜色                          │
  └───────────┴─────────────────────────────┴───────────────────────────────────┘
  