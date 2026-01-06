# WebSocket Tool Protocol API Specification

## Version: 1.0.0

## Message Types

### 1. TOOL_CALL (Backend → IDE Plugin)

后端请求 IDE Plugin 执行工具。

**⚠️ CRITICAL: 字段层级结构**
- **TOOL_CALL 消息**: 所有字段 (`toolCallId`, `toolName`, `params`) 都在**根级别**
- **TOOL_RESULT 消息**: 所有字段都在 **`data` 对象内**
- **IDE Plugin 解析**: 对于 TOOL_CALL，必须使用根 JSON 对象，不要使用 `data` 字段

**Message Structure**:
```json
{
  "type": "TOOL_CALL",
  "toolCallId": "string (required) - Unique tool call identifier",
  "toolName": "string (required) - Tool name (grep_file, read_file, call_chain, apply_change)",
  "params": "object (required) - Tool parameters",
  "webSocketSessionId": "string (required) - WebSocket session ID"
}
```

**Example**:
```json
{
  "type": "TOOL_CALL",
  "toolCallId": "grep_file-83742e07-1767686721056",
  "toolName": "grep_file",
  "params": {
    "pattern": "TODO",
    "projectKey": "autoloop",
    "webSocketSessionId": "83742e07-...",
    "relativePath": "core/src/File.java",
    "regex": false,
    "case_sensitive": false,
    "context_lines": 2
  },
  "webSocketSessionId": "83742e07-..."
}
```

---

### 2. TOOL_RESULT (IDE Plugin → Backend)

IDE Plugin 返回工具执行结果。

**⚠️ CRITICAL: 字段层级结构**
- 所有响应字段 (`toolCallId`, `success`, `result`, `error`, `executionTime`) 都在 **`data` 对象内**
- **后端解析**: 使用 `data.get("toolCallId")` 而不是根级别的 `toolCallId`

**Message Structure**:
```json
{
  "type": "TOOL_RESULT",
  "data": {
    "toolCallId": "string (required) - Must match the toolCallId from TOOL_CALL",
    "success": "boolean (required) - true if tool execution succeeded",
    "result": "string (optional) - Result content (JSON string or plain text)",
    "error": "string (optional) - Error message if success=false",
    "executionTime": "number (optional) - Execution time in milliseconds"
  }
}
```

**Success Example**:
```json
{
  "type": "TOOL_RESULT",
  "data": {
    "toolCallId": "grep_file-83742e07-1767686721056",
    "success": true,
    "result": "## 文件内容搜索: File.java\n\n**匹配数量**: 3\n...",
    "executionTime": 150
  }
}
```

**Failure Example**:
```json
{
  "type": "TOOL_RESULT",
  "data": {
    "toolCallId": "grep_file-83742e07-1767686721056",
    "success": false,
    "error": "文件不存在: core/src/File.java"
  }
}
```

---

## Tool Definitions

### grep_file
- **Purpose**: Search within files using regex or keyword matching
- **Parameters**:
  - `projectKey`: string (required)
  - `webSocketSessionId`: string (required)
  - `pattern`: string (required)
  - `relativePath`: string (optional)
  - `regex`: boolean (default: false)
  - `case_sensitive`: boolean (default: false)
  - `context_lines`: number (default: 0)
  - `limit`: number (default: 20)
  - `file_type`: string (default: "all")

### read_file
- **Purpose**: Read file content with optional line range filtering
- **Parameters**:
  - `projectKey`: string (required)
  - `webSocketSessionId`: string (required)
  - `relativePath`: string (required)
  - `start_line`: number (optional)
  - `end_line`: number (optional)
  - `line`: number (optional)
  - `context_lines`: number (default: 20)

### call_chain
- **Purpose**: Analyze method call relationships
- **Parameters**:
  - `projectKey`: string (required)
  - `webSocketSessionId`: string (required)
  - `method`: string (required) - format: ClassName.methodName
  - `direction`: string (default: "both") - "callers" | "callees" | "both"
  - `depth`: number (default: 1)
  - `includeSource`: boolean (default: false)

### apply_change
- **Purpose**: Apply code modifications (SEARCH/REPLACE + auto-format)
- **Parameters**:
  - `projectKey`: string (required)
  - `webSocketSessionId`: string (required)
  - `relativePath`: string (required)
  - `searchContent`: string (required for modify)
  - `replaceContent`: string (required)
  - `description`: string (optional)

---

## Error Handling

### toolCallId Mismatch
If `toolCallId` in TOOL_RESULT does not match any pending tool call:
- Backend logs: `⚠️ 未找到对应的工具调用: toolCallId={id}`
- Returns `false` from `handleToolResult()`

### Missing Required Fields
If required fields are missing:
- Backend rejects with error before forwarding
- IDE Plugin returns `success: false` with error message

---

## Testing

### Manual Test with WebSocket Client

```bash
# Connect
wscat -c ws://localhost:8080/ws/agent/chat?sessionId=test-123&projectKey=autoloop

# Send TOOL_CALL simulation (from backend)
{
  "type": "TOOL_CALL",
  "toolCallId": "test-001",
  "toolName": "grep_file",
  "params": {
    "pattern": "TODO",
    "projectKey": "autoloop"
  }
}

# Expected TOOL_RESULT response
{
  "type": "TOOL_RESULT",
  "data": {
    "toolCallId": "test-001",
    "success": true,
    "result": "..."
  }
}
```

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2026-01-06 | Initial version with toolCallId/params/error fields |
| 1.0.1 | 2026-01-06 | 修复字段层级结构说明：TOOL_CALL 根级别 vs TOOL_RESULT data 内部 |
