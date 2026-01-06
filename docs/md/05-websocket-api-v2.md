# WebSocket API v2 协议规范

**版本**: 2.0
**更新日期**: 2026-01-05
**状态**: 正式发布

---

## 📋 概述

WebSocket API v2 是为支持 Claude Code 多轮对话和 Agent 模式而设计的新一代通信协议。

### 核心特性

- ✅ **多轮对话支持**: 通过 `sessionId` 维护会话上下文
- ✅ **Agent 模式**: 支持三阶段工作流 (Analyze → Plan → Execute)
- ✅ **双协议兼容**: 同时支持 v1 (ANALYZE/COMPLETE) 和 v2 (AGENT_CHAT/AGENT_RESPONSE)
- ✅ **工具调用**: 前端通过 `TOOL_CALL` 消息类型执行本地工具
- ✅ **降级支持**: 支持本地模式，不依赖 Claude Code CLI

### 协议版本对比

| 特性 | v1 协议 (ANALYZE) | v2 协议 (AGENT_CHAT) |
|------|------------------|---------------------|
| 消息类型 | ANALYZE, COMPLETE | AGENT_CHAT, AGENT_RESPONSE, TOOL_CALL |
| 会话管理 | 无状态 | 有状态 (sessionId) |
| 工具调用 | 后端执行 | 前端执行 |
| Claude Code | 不支持 | 原生支持 |
| 适用场景 | 单次分析 | 多轮对话 + Agent 模式 |

---

## 🔌 连接端点

### v2 协议端点

```
ws://localhost:8080/ws/agent/chat
```

**连接参数**:
- `sessionId` (必需): 会话 ID，格式为 UUID
- `projectKey` (必需): 项目唯一标识符
- `projectPath` (必需): 项目本地绝对路径
- `mode` (可选): Agent 模式，默认 `agent`
  - `ask`: 需求模式（只回答问题）
  - `plan`: 设计模式（生成方案）
  - `agent`: 开发模式（执行任务）

**示例连接 URL**:
```
ws://localhost:8080/ws/agent/chat?sessionId=550e8400-e29b-41d4-a716-446655440000&projectKey=bank-core&projectPath=/Users/user/projects/bank-core&mode=agent
```

### v1 协议端点（向后兼容）

```
ws://localhost:8080/ws/analyze
```

**参数**: `projectKey`, `requestId`, `mode`, `projectPath`

---

## 📨 消息格式

### 通用消息结构

所有消息都遵循以下 JSON 结构：

```json
{
  "type": "MESSAGE_TYPE",
  "data": {
    // 具体数据内容
  },
  "timestamp": 1704451200000
}
```

---

## 🚀 v2 协议消息类型

### 1. AGENT_CHAT - Agent 聊天请求

**方向**: 前端 → 后端

**用途**: 前端发送用户需求给后端，触发 Claude Code 分析

**消息格式**:

```json
{
  "type": "AGENT_CHAT",
  "data": {
    "sessionId": "550e8400-e29b-41d4-a716-446655440000",
    "projectKey": "bank-core",
    "projectPath": "/Users/user/projects/bank-core",
    "message": "请分析 BankService 类的所有方法调用关系",
    "mode": "agent"
  },
  "timestamp": 1704451200000
}
```

**字段说明**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| sessionId | String | ✅ | 会话 ID (UUID 格式)，用于多轮对话 |
| projectKey | String | ✅ | 项目唯一标识符，用于查找配置和调用链数据 |
| projectPath | String | ✅ | 项目本地绝对路径，用于前端工具执行 |
| message | String | ✅ | 用户需求（自然语言描述） |
| mode | String | ⚠️ | Agent 模式，默认 `agent` |

**后端处理流程**:

```
1. 验证 sessionId 和 projectKey 格式
2. 查询 projectKey → projectPath 映射（如果未提供 projectPath）
3. 构建发送给 Claude Code 的消息（包含 sessionId, projectKey, agentApiUrl）
4. 调用 ClaudeCodeProcessPool.createWorker(sessionId)
   - 首次请求: --session-id <sessionId>
   - 后续请求: --resume <sessionId>
5. Worker 调用 Claude Code CLI
6. Claude Code 通过 http_tool 调用后端工具 API
7. 后端返回响应给前端（流式）
```

---

### 2. AGENT_RESPONSE - Agent 响应

**方向**: 后端 → 前端

**用途**: 后端推送 Claude Code 的分析结果给前端

**消息格式**:

```json
{
  "type": "AGENT_RESPONSE",
  "data": {
    "sessionId": "550e8400-e29b-41d4-a716-446655440000",
    "content": "## 分析结果\n\n我已经分析了 BankService 类的调用关系...",
    "status": "success",
    "workerId": "worker-a1b2c3d4",
    "stage": "plan"
  },
  "timestamp": 1704451260000
}
```

**字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| sessionId | String | 会话 ID |
| content | String | Markdown 格式的响应内容 |
| status | String | 状态: `success` \| `error` \| `thinking` |
| workerId | String | Claude Code Worker 进程 ID |
| stage | String | 当前阶段: `analyze` \| `plan` \| `execute` |

**流式推送机制**:

```
1. 后端接收 Claude Code 的 stdout 输出
2. 实时解析输出内容（按行）
3. 检测到以下标记时推送消息：
   - 阶段标记: 【分析问题】【制定方案】【执行方案】
   - 工具调用: <tool_call>...</tool_call>
   - 结束标记: =====END_OF_RESPONSE=====
4. 将内容包装为 AGENT_RESPONSE 消息推送到前端
```

---

### 3. TOOL_CALL - 工具调用

**方向**: 后端 → 前端

**用途**: 后端请求前端执行本地工具（如 read_class, text_search）

**消息格式**:

```json
{
  "type": "TOOL_CALL",
  "data": {
    "toolName": "read_class",
    "toolCallId": "tc-550e8400",
    "params": {
      "className": "com.bank.service.BankService",
      "mode": "structure"
    },
    "projectPath": "/Users/user/projects/bank-core"
  },
  "timestamp": 1704451230000
}
```

**字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| toolName | String | 工具名称（见下方工具列表） |
| toolCallId | String | 工具调用 ID，用于关联结果 |
| params | Object | 工具参数（根据工具不同） |
| projectPath | String | 项目路径（前端用于定位文件） |

**可用工具列表**:

| 工具名 | 用途 | 参数 |
|--------|------|------|
| `read_class` | 读取类结构 | `className`, `mode` |
| `read_method` | 读取方法源码 | `className`, `methodName` |
| `text_search` | 文本搜索 | `query`, `filePattern`, `maxResults` |
| `list_dir` | 列出目录 | `path`, `depth` |
| `read_xml` | 读取 XML | `path`, `extractSql` |
| `read_file` | 读取文件 | `path`, `encoding` |
| `read_config` | 读取配置 | `path`, `type` |
| `call_chain` | 调用链分析 | `method`, `direction`, `depth` |
| `find_usages` | 查找引用 | `target`, `maxResults` |
| `write_file` | 写入文件 | `path`, `content` |
| `modify_file` | 修改文件 | `path`, `edits` |
| `apply_change` | 应用修改 | `relativePath`, `searchContent`, `replaceContent` |

详见: [前端工具清单文档](./06-frontend-tools.md)

---

### 4. TOOL_RESULT - 工具执行结果

**方向**: 前端 → 后端

**用途**: 前端返回工具执行结果给后端

**消息格式**:

```json
{
  "type": "TOOL_RESULT",
  "data": {
    "toolCallId": "tc-550e8400",
    "success": true,
    "result": "## 类结构\n\n- **类名**: `BankService`\n-...",
    "error": null,
    "executionTime": 125
  },
  "timestamp": 1704451230125
}
```

**字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| toolCallId | String | 工具调用 ID（对应 TOOL_CALL） |
| success | Boolean | 执行是否成功 |
| result | String | 执行结果（Markdown 或 JSON） |
| error | String | 错误信息（如果失败） |
| executionTime | Number | 执行耗时（毫秒） |

---

### 5. ERROR - 错误消息

**方向**: 后端 → 前端

**用途**: 后端报告错误给前端

**消息格式**:

```json
{
  "type": "ERROR",
  "data": {
    "sessionId": "550e8400-e29b-41d4-a716-446655440000",
    "errorCode": "WORKER_TIMEOUT",
    "errorMessage": "Claude Code Worker 进程响应超时（120秒）",
    "details": {
      "workerId": "worker-a1b2c3d4",
      "lastMessage": "正在分析调用链..."
    }
  },
  "timestamp": 1704451290000
}
```

**错误代码列表**:

| 错误代码 | 说明 |
|----------|------|
| `INVALID_SESSION` | sessionId 格式无效 |
| `PROJECT_NOT_FOUND` | projectKey 未找到映射 |
| `WORKER_TIMEOUT` | Claude Code Worker 超时 |
| `WORKER_START_FAILED` | Worker 进程启动失败 |
| `TOOL_EXECUTION_FAILED` | 前端工具执行失败 |
| `CLAUDE_CODE_ERROR` | Claude Code CLI 内部错误 |

---

## 🔄 完整通信流程

### 场景 1: 首次请求（新会话）

```
前端 → 后端: AGENT_CHAT {
  sessionId: "550e8400-...",
  message: "分析 BankService 调用关系"
}

后端: 检测到会话不存在
后端 → Claude Code CLI: --session-id 550e8400-... --print

后端 → 前端: AGENT_RESPONSE {
  content: "【分析问题】\n\n正在搜索相关代码...",
  stage: "analyze"
}

后端 → 前端: TOOL_CALL {
  toolName: "read_class",
  params: { className: "BankService" }
}

前端 → 后端: TOOL_RESULT {
  result: "## BankService 类结构\n..."
}

后端 → Claude Code: (将工具结果写入 stdin)

后端 → 前端: AGENT_RESPONSE {
  content: "【制定方案】\n\n我将按以下步骤分析...",
  stage: "plan"
}

后端 → 前端: AGENT_RESPONSE {
  content: "【执行方案】\n\n分析完成，调用关系如下...",
  stage: "execute"
}

后端 → 前端: AGENT_RESPONSE {
  content: "=====END_OF_RESPONSE=====",
  status: "success"
}
```

---

### 场景 2: 多轮对话（同一会话）

```
前端 → 后端: AGENT_CHAT {
  sessionId: "550e8400-...",  // 同一个 sessionId
  message: "请再分析一下 TransactionService"
}

后端: 检测到会话已存在（通过检查 ~/.claude/projects/*/550e8400-....jsonl）
后端 → Claude Code CLI: --resume 550e8400-... --print

后端 → 前端: AGENT_RESPONSE {
  content: "【分析问题】\n\n好的，我来分析 TransactionService...",
  stage: "analyze"
}

... (后续流程同上)
```

---

### 场景 3: 降级模式（本地模式）

当配置了 `agent.fallback.enabled=true` 且 Claude Code CLI 不可用时：

```
前端 → 后端: AGENT_CHAT {
  sessionId: "550e8400-...",
  message: "列出所有 Service 类"
}

后端: 检测到 Claude Code CLI 不可用
后端: 启用降级模式，直接调用后端工具

后端 → 前端: TOOL_CALL {
  toolName: "text_search",
  params: { query: "class *Service", filePattern: "*.java" }
}

前端 → 后端: TOOL_RESULT {
  result: "找到 15 个 Service 类:\n1. BankService\n..."
}

后端 → 前端: AGENT_RESPONSE {
  content: "## 降级模式响应\n\n找到以下 Service 类...",
  status: "success"
}
```

---

## 🔧 后端实现要点

### 1. WebSocket Handler 实现

**文件**: `agent/src/main/java/ai/smancode/sman/agent/websocket/AgentWebSocketHandler.java`

```java
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private ClaudeCodeProcessPool processPool;

    @Autowired
    private ProjectConfigService projectConfigService;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JSONObject payload = JSONObject.parseObject(message.getPayload());

        String messageType = payload.getString("type");
        JSONObject data = payload.getJSONObject("data");

        switch (messageType) {
            case "AGENT_CHAT":
                handleAgentChat(session, data);
                break;
            case "TOOL_RESULT":
                handleToolResult(session, data);
                break;
            default:
                sendError(session, "UNKNOWN_MESSAGE_TYPE",
                    "未知的消息类型: " + messageType);
        }
    }

    private void handleAgentChat(WebSocketSession session, JSONObject data) {
        String sessionId = data.getString("sessionId");
        String projectKey = data.getString("projectKey");
        String projectPath = data.getString("projectPath");
        String userMessage = data.getString("message");
        String mode = data.getString("mode");

        // 1. 验证参数
        if (!isValidUuid(sessionId)) {
            sendError(session, "INVALID_SESSION", "sessionId 格式无效");
            return;
        }

        // 2. 查询 projectPath 映射（如果未提供）
        if (projectPath == null || projectPath.isEmpty()) {
            projectPath = projectConfigService.getProjectPath(projectKey);
        }

        // 3. 构建 Claude Code 消息
        String claudeMessage = buildClaudeMessage(
            userMessage, projectKey, sessionId, projectPath, mode
        );

        // 4. 创建 Worker 进程
        try {
            processPool.acquireConcurrency();

            ClaudeCodeWorker worker = processPool.createWorker(sessionId);

            // 5. 异步执行并流式推送响应
            executeWorkerAsync(worker, claudeMessage, session);

        } catch (Exception e) {
            sendError(session, "WORKER_START_FAILED", e.getMessage());
            processPool.releaseConcurrency();
        }
    }

    private String buildClaudeMessage(String userMessage, String projectKey,
                                      String sessionId, String projectPath, String mode) {
        // 构建发送给 Claude Code 的消息（包含 sessionId, projectKey, projectPath）
        // 详见 QuickAnalysisController.buildClaudeMessage()
    }

    private void executeWorkerAsync(ClaudeCodeWorker worker, String message,
                                    WebSocketSession session) {
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 发送消息给 Claude Code
                String response = worker.sendAndReceive(message, 120);

                // 2. 流式推送响应
                streamResponse(session, response);

            } catch (TimeoutException e) {
                sendError(session, "WORKER_TIMEOUT", e.getMessage());
            } catch (Exception e) {
                sendError(session, "CLAUDE_CODE_ERROR", e.getMessage());
            } finally {
                processPool.releaseConcurrency();
                processPool.markWorkerCompleted(worker);
            }
        });
    }
}
```

---

### 2. Claude Code 消息构建

**关键点**: 必须传递 `sessionId`, `projectKey`, `projectPath` 给 Claude Code

```java
private String buildClaudeMessage(String userMessage, String projectKey,
                                  String sessionId, String projectPath, String mode) {
    String agentApiUrl = "http://localhost:" + serverPort + "/api/claude-code/tools/execute";

    StringBuilder sb = new StringBuilder();
    sb.append("## 用户需求\n\n");
    sb.append(userMessage);
    sb.append("\n\n");

    sb.append("## 项目信息\n\n");
    sb.append("- projectKey: ").append(projectKey).append("\n");
    sb.append("- sessionId: ").append(sessionId).append("\n");
    sb.append("- projectPath: ").append(projectPath).append("\n");
    sb.append("- agentApiUrl: ").append(agentApiUrl).append("\n");
    sb.append("- mode: ").append(mode).append("\n");
    sb.append("\n");

    sb.append("## 工具使用说明\n\n");
    sb.append("你需要使用以下工具来完成任务：\n\n");
    sb.append("1. **vector_search**: 向量搜索相关代码\n");
    sb.append("   调用: http_tool(\"vector_search\", {\"query\": \"xxx\", \"top_k\": 10})\n\n");

    sb.append("2. **read_class**: 读取 Java 类结构\n");
    sb.append("   调用: http_tool(\"read_class\", {\"className\": \"xxx\", \"mode\": \"structure\"})\n\n");

    sb.append("## 重要提示\n\n");
    sb.append("1. 你必须使用上述 HTTP API 来调用工具\n");
    sb.append("2. 禁止使用 Read、Edit、Bash、Write 等内置工具\n");
    sb.append("3. 所有工具调用都通过 curl 命令发送 HTTP 请求\n");
    sb.append("4. 分析结果后，给出清晰的结论和建议\n");

    return sb.toString();
}
```

---

### 3. projectKey → projectPath 映射配置

**配置文件**: `agent/src/main/resources/application.yml`

```yaml
agent:
  fallback:
    enabled: true
    auto-detect: true

  projects:
    bank-core:
      project-path: /Users/user/projects/bank-core
      description: "银行核心系统"
    payment-system:
      project-path: /Users/user/projects/payment-system
      description: "支付系统"
```

**配置服务**: `ProjectConfigService.java`

```java
@Service
public class ProjectConfigService {

    @Value("${agent.projects}")
    private Map<String, ProjectConfig> projectConfigs;

    public String getProjectPath(String projectKey) {
        ProjectConfig config = projectConfigs.get(projectKey);
        if (config == null) {
            throw new IllegalArgumentException("未找到 projectKey 映射: " + projectKey);
        }
        return config.getProjectPath();
    }

    public static class ProjectConfig {
        private String projectPath;
        private String description;

        // getters and setters
    }
}
```

---

## 📱 前端实现要点

### 1. WebSocket 连接管理

**文件**: `ide-plugin/src/main/kotlin/ai/smancode/sman/ide/service/AgentWebSocketClient.kt`

```kotlin
class AgentWebSocketClient(
    private val serverUrl: String,
    private val sessionId: String,
    private val projectKey: String,
    private val projectPath: String,
    private val messageHandler: (AgentResponse) -> Unit
) {
    private val wsUrl = "$serverUrl/ws/agent/chat?" +
        "sessionId=$sessionId&" +
        "projectKey=$projectKey&" +
        "projectPath=${URLEncoder.encode(projectPath, "UTF-8")}&" +
        "mode=agent"

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val webSocket: WebSocket

    init {
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                handleError(t)
            }
        })
    }

    fun sendMessage(message: String) {
        val payload = JSONObject().apply {
            put("type", "AGENT_CHAT")
            put("data", JSONObject().apply {
                put("sessionId", sessionId)
                put("projectKey", projectKey)
                put("projectPath", projectPath)
                put("message", message)
                put("mode", "agent")
            })
        }

        webSocket.send(payload.toString())
    }

    private fun handleMessage(text: String) {
        val payload = JSONObject.parseObject(text)
        val type = payload.getString("type")

        when (type) {
            "AGENT_RESPONSE" -> {
                val response = AgentResponse(
                    payload.getJSONObject("data")
                )
                messageHandler(response)
            }
            "TOOL_CALL" -> {
                val toolCall = ToolCall(payload.getJSONObject("data"))
                executeTool(toolCall)
            }
            "ERROR" -> {
                handleError(payload.getJSONObject("data"))
            }
        }
    }

    private fun executeTool(toolCall: ToolCall) {
        val result = LocalToolExecutor.execute(
            toolCall.toolName,
            toolCall.params,
            toolCall.projectPath
        )

        val response = JSONObject().apply {
            put("type", "TOOL_RESULT")
            put("data", JSONObject().apply {
                put("toolCallId", toolCall.toolCallId)
                put("success", result.success)
                put("result", result.result)
                put("error", result.error)
                put("executionTime", result.executionTime)
            })
        }

        webSocket.send(response.toString())
    }
}
```

---

### 2. 工具执行器

**文件**: `ide-plugin/src/main/kotlin/ai/smancode/sman/ide/service/LocalToolExecutor.kt`

详见: [前端工具清单文档](./06-frontend-tools.md)

---

## 🔍 调试与测试

### WebSocket 连接测试

使用 **wscat** 工具测试连接：

```bash
# 安装 wscat
npm install -g wscat

# 连接到 v2 端点
wscat -c "ws://localhost:8080/ws/agent/chat?sessionId=550e8400-e29b-41d4-a716-446655440000&projectKey=bank-core&projectPath=/Users/user/projects/bank-core&mode=agent"

# 发送 AGENT_CHAT 消息
> {"type":"AGENT_CHAT","data":{"sessionId":"550e8400-e29b-41d4-a716-446655440000","projectKey":"bank-core","projectPath":"/Users/user/projects/bank-core","message":"分析 BankService 类","mode":"agent"},"timestamp":1704451200000}

# 接收响应
< {"type":"AGENT_RESPONSE","data":{"content":"【分析问题】\n\n正在搜索...","stage":"analyze"},"timestamp":1704451260000}
```

---

### 会话文件验证

检查 Claude Code 会话文件是否正确创建：

```bash
# 会话文件路径
~/.claude/projects/-<encoded-project-path>/<sessionId>.jsonl

# 示例
~/.claude/projects/-Users-liuchao-projects-sman-data-claude-code-workspaces/550e8400-e29b-41d4-a716-446655440000.jsonl

# 查看会话内容
cat ~/.claude/projects/-*/550e8400-e29b-41d4-a716-446655440000.jsonl | jq .
```

---

### 日志调试

**后端日志级别配置** (`application.yml`):

```yaml
logging:
  level:
    ai.smancode.sman.agent.claude: DEBUG
    ai.smancode.sman.agent.websocket: DEBUG
```

**关键日志输出**:

```
📨 收到 Agent 聊天请求
  sessionId: 550e8400-e29b-41d4-a716-446655440000
  message: 分析 BankService 类

🔍 检查会话文件: ~/.claude/projects/-*/550e8400-....jsonl -> 不存在
🆕 新会话，使用 --session-id 参数 (sessionId=550e8400-...)

🚀 创建Worker进程: workerId=worker-a1b2c3d4, sessionId=550e8400-...
🔧 执行命令: claude-code --session-id 550e8400-... --print

✅ Worker worker-a1b2c3d4 启动成功 (活跃进程数=1)
📤 发送消息给 Claude Code...

🔵 Claude Code [worker-a1b2c3d4]: 【分析问题】
🔵 Claude Code [worker-a1b2c3d4]: 正在搜索相关代码...
🔵 Claude Code [worker-a1b2c3d4]: =====END_OF_RESPONSE=====

📥 Worker worker-a1b2c3d4 收到完整响应: 1234 字符
✅ 分析完成
```

---

## 📊 性能指标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| WebSocket 连接建立 | <1 秒 | 握手时间 |
| 首次响应时间 | <3 秒 | Worker 启动 + Claude Code 首次输出 |
| 工具调用往返 | <2 秒 | TOOL_CALL → TOOL_RESULT |
| 多轮对话响应 | <1 秒 | Worker 已启动的情况 |
| 并发连接数 | 50+ | 由 Netty 线程池决定 |
| 内存占用 | <500MB | 单个 WebSocket 连接 |

---

## 🔐 安全性考虑

### 1. sessionId 验证

```java
private boolean isValidUuid(String sessionId) {
    try {
        UUID.fromString(sessionId);
        return true;
    } catch (IllegalArgumentException e) {
        return false;
    }
}
```

### 2. projectKey 白名单

```java
private final Set<String> allowedProjectKeys = Set.of(
    "bank-core", "payment-system", "user-center"
);

if (!allowedProjectKeys.contains(projectKey)) {
    sendError(session, "PROJECT_NOT_ALLOWED", "projectKey 不在白名单中");
    return;
}
```

### 3. projectPath 路径遍历防护

```java
private String sanitizeProjectPath(String projectPath) {
    Path path = Paths.get(projectPath).normalize();
    if (!path.startsWith("/Users/user/projects/")) {
        throw new SecurityException("非法的 projectPath: " + projectPath);
    }
    return path.toString();
}
```

---

## 🚦 未来扩展

### 计划中的功能

1. **二进制协议**: 支持 Protocol Buffers 以提升性能
2. **消息压缩**: 启用 WebSocket Per-Message Deflate 压缩
3. **会话恢复**: 支持前端断线重连后恢复会话
4. **多租户**: 支持多用户隔离
5. **速率限制**: 防止滥用（单个 sessionId 每秒最多 N 个请求）

---

## 📚 相关文档

- [WebSocket API v1 文档](./02-websocket-api.md) - 旧版协议
- [前端工具清单](./06-frontend-tools.md) - 所有可用工具详解
- [降级策略](./07-fallback-strategy.md) - Claude Code 不可用时的处理
- [Claude Code 集成](./03-claude-code-integration.md) - HTTP Tool API 规范
- [多轮对话实现](./multi_turn.md) - --resume 参数详解

---

**文档版本历史**:

| 版本 | 日期 | 变更说明 |
|------|------|----------|
| 2.0 | 2026-01-05 | 初始版本，定义 AGENT_CHAT/AGENT_RESPONSE 协议 |
| 1.0 | 2025-12-20 | 初始版本 (ANALYZE/COMPLETE 协议) |
