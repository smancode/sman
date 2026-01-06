# WebSocket Session ID 集成文档

## 概述

本文档描述了 `webSocketSessionId` 如何在完整链路中传递，实现 Agent Backend 与 IDE Plugin 之间的工具转发。

## 架构链路

```
IDE Plugin ←→ Agent Backend ←→ Claude Code
     ↓             ↓                  ↓
  WebSocket    HTTP Tool API     stdin/stdout
```

## 完整流程

### 1. IDE Plugin 连接 Agent Backend

```
IDE Plugin → Agent Backend (WebSocket)
URI: ws://localhost:8080/ws/agent/chat?sessionId=xxx&projectKey=autoloop

Agent Backend 生成:
- webSocketSessionId = session.getId()  (Spring WebSocket 自动生成)
- 保存到 SessionMetadata
```

### 2. IDE Plugin 发送分析请求

```json
{
  "type": "AGENT_CHAT",
  "data": {
    "sessionId": "8e0eeb6f-0307-ecc4-4750-c83636d82af9",
    "projectKey": "autoloop",
    "message": "分析文件过滤代码"
  }
}
```

### 3. Agent Backend 调用 Claude Code

```java
// AgentWebSocketHandler.java
ClaudeCodeWorker worker = processPool.createWorker(
    sessionId,          // = "8e0eeb6f-0307-ecc4-4750-c83636d82af9"
    projectKey,         // = "autoloop"
    projectPath,        // = "/Users/liuchao/projects/autoloop"
    logTag,
    execMode
);
```

### 4. Claude Code 调用工具

```json
POST http://localhost:8080/api/claude-code/tools/execute
{
  "tool": "read_class",
  "params": {
    "className": "FileFilter",
    "mode": "structure"
  },
  "projectKey": "autoloop",
  "sessionId": "8e0eeb6f-0307-ecc4-4750-c83636d82af9",
  "webSocketSessionId": "abc123-def456-..."  // 关键：带上这个 ID
}
```

### 5. Agent Backend 判断是否转发

```java
// HttpToolExecutor.java
if (toolForwardingService.shouldForwardToIde(toolName)) {
    return forwardToIdePlugin(toolName, params, webSocketSessionId);
}
```

**转发工具列表**:
- `read_class`
- `text_search`
- `read_file`
- `grep_file`
- `apply_change`

**本地处理工具**:
- `semantic_search` / `vector_search`
- `call_chain`

### 6. Agent Backend 转发给 IDE Plugin

```java
// ToolForwardingService.java
WebSocketSession session = sessionManager.getSession(webSocketSessionId);
ObjectNode message = objectMapper.createObjectNode();
message.put("type", "TOOL_CALL");
message.put("toolCallId", "read_class-abc123-1234567890");
message.put("toolName", "read_class");
message.set("params", objectMapper.valueToTree(params));
message.put("webSocketSessionId", webSocketSessionId);

session.sendMessage(new TextMessage(payload));
```

### 7. IDE Plugin 执行工具并返回结果

```json
{
  "type": "TOOL_RESULT",
  "toolCallId": "read_class-abc123-1234567890",
  "success": true,
  "result": {
    "className": "FileFilter",
    "methods": [...]
  }
}
```

### 8. Agent Backend 返回结果给 Claude Code

```json
{
  "success": true,
  "result": {
    "className": "FileFilter",
    "methods": [...]
  }
}
```

## 关键代码修改

### 1. ClaudeCodeToolModels.java

```java
public static class ToolExecutionRequest {
    private String tool;
    private Map<String, Object> params;
    private String workerId;
    private String sessionId;
    private String projectKey;
    private String webSocketSessionId;  // 新增

    // getter/setter...
}
```

### 2. HttpToolExecutor.java

```java
public ToolExecutionResponse execute(
    String toolName,
    Map<String, Object> params,
    String projectKey,
    String sessionId,
    String webSocketSessionId  // 新增参数
) {
    // 注入 webSocketSessionId 到 params
    if (webSocketSessionId != null) {
        params.put("webSocketSessionId", webSocketSessionId);
    }

    // 判断是否转发
    if (toolForwardingService.shouldForwardToIde(toolName)) {
        return forwardToIdePlugin(toolName, params, webSocketSessionId);
    }

    // 本地处理...
}
```

### 3. ToolForwardingService.java

```java
@Service
public class ToolForwardingService {
    public boolean shouldForwardToIde(String toolName) {
        return switch (toolName) {
            case "read_class", "text_search", "read_file", "grep_file", "apply_change" -> true;
            default -> false;
        };
    }

    public JsonNode forwardToolCall(
        String webSocketSessionId,
        String toolName,
        Map<String, Object> params,
        WebSocketSessionManager sessionManager
    ) throws Exception {
        String toolCallId = generateToolCallId(toolName, webSocketSessionId);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingToolCalls.put(toolCallId, future);

        WebSocketSession session = sessionManager.getSession(webSocketSessionId);
        // 发送 TOOL_CALL 消息...

        return future.get(30, TimeUnit.SECONDS);
    }

    public boolean handleToolResult(JsonNode data) {
        String toolCallId = data.get("toolCallId").asText();
        CompletableFuture<JsonNode> future = pendingToolCalls.remove(toolCallId);
        future.complete(data);
        return true;
    }
}
```

### 4. AgentWebSocketHandler.java

```java
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler
        implements HttpToolExecutor.WebSocketSessionManager {

    @Autowired
    private ToolForwardingService toolForwardingService;

    private void handleToolResult(WebSocketSession session, JsonNode data) {
        boolean handled = toolForwardingService.handleToolResult(data);
        // ...
    }

    @Override
    public WebSocketSession getSession(String webSocketSessionId) {
        return sessions.get(webSocketSessionId);
    }
}
```

## 测试方法

### 测试本地处理的工具

```bash
# semantic_search, vector_search, call_chain
curl -X POST 'http://localhost:8080/api/claude-code/tools/execute' \
  -H 'Content-Type: application/json' \
  -d '{
    "tool": "semantic_search",
    "params": {...},
    "projectKey": "autoloop",
    "sessionId": "test-001",
    "webSocketSessionId": "test-ws-001"
  }'
```

### 测试转发 IDE 的工具

```bash
# read_class, text_search, read_file, grep_file, apply_change
# ⚠️ 需要先建立 WebSocket 连接获取真实的 webSocketSessionId

# 1. IDE Plugin 连接 WebSocket
ws://localhost:8080/ws/agent/chat?sessionId=xxx&projectKey=autoloop

# 2. 使用返回的 webSocketSessionId
curl -X POST 'http://localhost:8080/api/claude-code/tools/execute' \
  -H 'Content-Type: application/json' \
  -d '{
    "tool": "read_class",
    "params": {
      "className": "FileFilter",
      "mode": "structure"
    },
    "projectKey": "autoloop",
    "sessionId": "test-001",
    "webSocketSessionId": "8e0eeb6f-0307-ecc4-4750-c83636d82af9"
  }'
```

## 注意事项

1. **webSocketSessionId 获取**: 必须从 IDE Plugin 建立 WebSocket 连接后获取
2. **超时处理**: 工具转发默认 30 秒超时
3. **会话管理**: Agent Backend 维护 `sessions` Map，存储所有活跃的 WebSocket 连接
4. **错误处理**: 如果 `webSocketSessionId` 无效，返回错误：`"WebSocket Session 未找到"`

## 总结

通过 `webSocketSessionId` 的传递，Agent Backend 可以：
1. 找到对应的 IDE Plugin WebSocket 连接
2. 转发工具调用给 IDE Plugin 执行
3. 接收 IDE Plugin 返回的执行结果
4. 将结果返回给 Claude Code

这样实现了完整的工具转发链路，充分利用了 IDE Plugin 的本地文件访问能力。
