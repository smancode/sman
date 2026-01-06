# WebSocket API 规范（IDE Plugin ↔ Agent 后端）

**文档版本**: v1.0
**创建日期**: 2025-01-05
**协议**: WebSocket
**端口**: 8080
**路径**: /ws/analyze

---

## ⚠️ 版本提示

本文档描述的是 **WebSocket API v1 协议**（`ANALYZE/COMPLETE` 消息类型）。

如果您需要使用以下新特性，请查看 **[WebSocket API v2 文档](./05-websocket-api-v2.md)**：

- ✅ **多轮对话支持**（通过 `sessionId` 维护会话上下文）
- ✅ **Agent 模式**（三阶段工作流：Analyze → Plan → Execute）
- ✅ **Claude Code 原生集成**（支持 `--resume` 参数）
- ✅ **降级模式**（Claude Code 不可用时自动切换）

### 协议版本对比

| 特性 | v1 (本文档) | v2 (新版本) |
|------|------------|-------------|
| 消息类型 | `ANALYZE`, `COMPLETE` | `AGENT_CHAT`, `AGENT_RESPONSE` |
| 会话管理 | `requestId` (有限支持) | `sessionId` (完整支持) |
| Claude Code | ❌ 不支持 | ✅ 原生支持 |
| 多轮对话 | ⚠️ 基础支持 | ✅ 完整支持（--resume） |
| 工具调用 | 前端工具 | 前端工具 + 后端工具 |
| 降级模式 | ❌ 不支持 | ✅ 自动降级 |

**推荐**:
- **新项目**: 使用 v2 协议
- **现有项目**: 可继续使用 v1 协议（向后兼容）

---

---

## 1. 连接建立

### 1.1 握手请求

**URL**: `ws://localhost:8080/ws/analyze`

**连接参数（Query String）**：

| 参数 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| `sessionId` | string | 是 | WebSocket 会话 ID（前端生成） | `ws-20250105-150000-xxx` |
| `projectKey` | string | 是 | 项目标识 | `autoloop` |
| `projectPath` | string | 是 | 项目根路径（本地绝对路径） | `/Users/xxx/project` |
| `requestId` | string | 否 | 多轮对话会话 ID（首次为空） | `req-123` |
| `mode` | string | 否 | 模式选择（claude-code 或 legacy） | `claude-code` |

**示例**：

```javascript
// JavaScript
const ws = new WebSocket(
  'ws://localhost:8080/ws/analyze?' +
  'sessionId=ws-20250105-150000-xxx&' +
  'projectKey=autoloop&' +
  'projectPath=/Users/xxx/project&' +
  'mode=claude-code'
);
```

---

### 1.2 握手响应

**格式**: JSON

```json
{
  "type": "CONNECTED",
  "data": {
    "message": "WebSocket connected successfully",
    "requestId": "req-123",  // 后端生成的 requestId（首次连接）
    "serverTime": 1704438400000
  }
}
```

---

## 2. 消息格式

### 2.1 通用消息结构

```json
{
  "type": "MESSAGE_TYPE",  // 消息类型
  "data": {               // 消息数据（根据类型不同）
    ...
  }
}
```

---

### 2.2 消息类型总览

| 类型 | 方向 | 说明 |
|------|------|------|
| `ANALYZE` | C → S | IDE Plugin 发送分析请求 |
| `COMPLETE` | S → C | 分析完成，返回结果 |
| `THINKING` | S → C | 分析进度（思考过程）
| `TOOL_CALL` | S → C | 后端请求 IDE Plugin 执行工具 |
| `TOOL_RESULT` | C → S | IDE Plugin 返回工具执行结果 |
| `ERROR` | S → C | 错误信息 |
| `PING` | C → S | 心跳检测 |
| `PONG` | S → C | 心跳响应 |

---

## 3. 消息类型详解

### 3.1 ANALYZE（分析请求）

**方向**: Client → Server

**用途**: IDE Plugin 发送分析请求

**示例**：

```json
{
  "type": "ANALYZE",
  "data": {
    "requestId": "req-123",        // 多轮对话会话 ID
    "message": "分析文件过滤的代码", // 用户输入
    "projectKey": "autoloop",      // 项目标识
    "projectPath": "/Users/xxx/project",  // 项目根路径
    "mode": "claude-code"          // 模式
  }
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `requestId` | string | 是 | 多轮对话会话 ID（首次可为空，后端生成） |
| `message` | string | 是 | 用户输入的消息 |
| `projectKey` | string | 是 | 项目标识 |
| `projectPath` | string | 是 | 项目根路径（本地绝对路径） |
| `mode` | string | 否 | 模式（claude-code 或 legacy，默认 claude-code） |

---

### 3.2 COMPLETE（分析完成）

**方向**: Server → Client

**用途**: 分析完成，返回最终结果

**示例**：

```json
{
  "type": "COMPLETE",
  "data": {
    "requestId": "req-123",
    "result": "# 文件过滤分析\n\n找到以下相关代码：\n\n## 1. FileFilter.java\n\n...",  // Markdown 格式
    "process": "分析过程摘要（可选）"
  }
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `requestId` | string | 是 | 多轮对话会话 ID |
| `result` | string | 是 | 分析结果（Markdown 格式） |
| `process` | string | 否 | 分析过程摘要 |

---

### 3.3 THINKING（分析进度）

**方向**: Server → Client

**用途**: 实时推送分析进度（思考过程）

**示例**：

```json
{
  "type": "THINKING",
  "data": {
    "requestId": "req-123",
    "thinking": "正在向量搜索相关代码...",
    "stage": "analyzing",  // analyzing, planning, executing
    "percent": 30,         // 进度百分比（可选）
    "round": 1             // 轮次（可选）
  }
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `requestId` | string | 是 | 多轮对话会话 ID |
| `thinking` | string | 是 | 思考内容 |
| `stage` | string | 否 | 阶段（analyzing, planning, executing） |
| `percent` | number | 否 | 进度百分比（0-100） |
| `round` | number | 否 | 轮次 |

---

### 3.4 TOOL_CALL（工具调用）

**方向**: Server → Client

**用途**: 后端请求 IDE Plugin 执行工具（需要访问本地文件）

**示例**：

```json
{
  "type": "TOOL_CALL",
  "data": {
    "callId": "call-456",         // 调用 ID
    "toolName": "read_class",     // 工具名称
    "params": {                   // 工具参数
      "className": "FileFilter",
      "mode": "structure"
    },
    "projectPath": "/Users/xxx/project"
  }
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `callId` | string | 是 | 调用 ID（用于关联 TOOL_RESULT） |
| `toolName` | string | 是 | 工具名称（见工具列表） |
| `params` | object | 是 | 工具参数（根据工具不同） |
| `projectPath` | string | 是 | 项目根路径 |

**支持的工具列表**：

| 工具名称 | 说明 | 参数 |
|---------|------|------|
| `read_class` | 读取类结构 | `className`, `mode` |
| `read_method` | 读取方法源码 | `className`, `methodName` |
| `read_file` | 读取文件内容 | `relativePath` |
| `apply_change` | 应用代码修改 | `relativePath`, `searchContent`, `replaceContent` |

---

### 3.5 TOOL_RESULT（工具结果）

**方向**: Client → Server

**用途**: IDE Plugin 返回工具执行结果

**示例**（成功）：

```json
{
  "type": "TOOL_RESULT",
  "data": {
    "callId": "call-456",
    "success": true,
    "result": "## FileFilter.java\n\n- **类名**: `FileFilter`\n- **路径**: `core/src/...`\n\n..."
  }
}
```

**示例**（失败）：

```json
{
  "type": "TOOL_RESULT",
  "data": {
    "callId": "call-456",
    "success": false,
    "error": "类未找到: FileFilter"
  }
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `callId` | string | 是 | 调用 ID（与 TOOL_CALL 对应） |
| `success` | boolean | 是 | 是否成功 |
| `result` | string | 条件 | 执行结果（成功时） |
| `error` | string | 条件 | 错误信息（失败时） |

---

### 3.6 ERROR（错误信息）

**方向**: Server → Client

**用途**: 服务器端错误通知

**示例**：

```json
{
  "type": "ERROR",
  "data": {
    "requestId": "req-123",
    "message": "分析失败：项目路径不存在",
    "code": "PROJECT_NOT_FOUND"
  }
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `requestId` | string | 是 | 多轮对话会话 ID |
| `message` | string | 是 | 错误信息 |
| `code` | string | 否 | 错误码 |

---

### 3.7 PING / PONG（心跳）

**方向**: Client ↔ Server

**用途**: 心跳检测，保持连接活跃

**PING 请求**（Client → Server）：

```json
{
  "type": "PING",
  "data": {
    "timestamp": 1704438400000
  }
}
```

**PONG 响应**（Server → Client）：

```json
{
  "type": "PONG",
  "data": {
    "timestamp": 1704438400000
  }
}
```

---

## 4. 错误处理

### 4.1 错误码列表

| 错误码 | 说明 | HTTP 状态码 |
|--------|------|-------------|
| `PROJECT_NOT_FOUND` | 项目路径不存在 | 404 |
| `INVALID_REQUEST` | 请求参数无效 | 400 |
| `ANALYSIS_FAILED` | 分析失败 | 500 |
| `TOOL_EXECUTION_FAILED` | 工具执行失败 | 500 |

---

### 4.2 错误响应格式

```json
{
  "type": "ERROR",
  "data": {
    "code": "PROJECT_NOT_FOUND",
    "message": "项目路径不存在: /Users/xxx/project",
    "details": {}
  }
}
```

---

## 5. 完整流程示例

### 5.1 单次分析流程

```
1. Client → Server: ANALYZE
{
  "type": "ANALYZE",
  "data": {
    "message": "分析文件过滤的代码"
  }
}

2. Server → Client: THINKING
{
  "type": "THINKING",
  "data": {
    "thinking": "正在向量搜索相关代码..."
  }
}

3. Server → Client: TOOL_CALL
{
  "type": "TOOL_CALL",
  "data": {
    "callId": "call-1",
    "toolName": "read_class",
    "params": {"className": "FileFilter"}
  }
}

4. Client → Server: TOOL_RESULT
{
  "type": "TOOL_RESULT",
  "data": {
    "callId": "call-1",
    "success": true,
    "result": "..."
  }
}

5. Server → Client: COMPLETE
{
  "type": "COMPLETE",
  "data": {
    "result": "# 分析结果\n\n..."
  }
}
```

---

### 5.2 多轮对话流程

```
第1轮：
1. Client → Server: ANALYZE (requestId=null)
2. Server → Client: COMPLETE (requestId=req-123, result=...)

第2轮：
3. Client → Server: ANALYZE (requestId=req-123, message="application.yml 怎么配置？")
4. Server → Client: COMPLETE (result=...)
```

---

## 6. 限制和约束

### 6.1 消息大小限制

- 单个消息最大大小：10MB
- 超过限制将被拒绝，返回 `MESSAGE_TOO_LARGE` 错误

---

### 6.2 并发限制

- 单个连接同时只能有一个活跃的分析请求
- 新请求会覆盖旧请求（返回 `REQUEST_OVERRIDDEN` 错误）

---

### 6.3 超时设置

- 心跳间隔：30 秒
- 连接超时：60 秒（无心跳则断开）
- 分析超时：5 分钟（超时返回 `ANALYSIS_TIMEOUT`）

---

## 7. 安全性

### 7.1 认证

**当前版本**：无认证（本地开发环境）

**未来版本**：支持 Token 认证

```json
{
  "type": "CONNECT",
  "data": {
    "token": "your-api-token"
  }
}
```

---

### 7.2 数据传输

- 所有数据通过 WebSocket 传输（支持 `wss://` 加密）
- 敏感信息（项目路径）建议使用加密连接

---

## 8. 版本历史

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| v1.0 | 2025-01-05 | 初始版本（ANALYZE/COMPLETE 协议） |
| v2.0 | 2026-01-05 | 新版本（AGENT_CHAT/AGENT_RESPONSE 协议，支持 Claude Code 和降级模式）详见：[WebSocket API v2 文档](./05-websocket-api-v2.md) |

---

**文档结束**

