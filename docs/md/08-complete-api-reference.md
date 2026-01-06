# SiliconMan 完整通信接口文档

**版本**: v2.0  
**更新时间**: 2026-01-05  
**适用**: IDE Plugin ↔ Agent Backend

---

## 📋 目录

1. [WebSocket v2 协议](#websocket-v2-协议推荐) - AGENT_CHAT/AGENT_RESPONSE
2. [WebSocket v1 协议](#websocket-v1-协议向后兼容) - ANALYZE/COMPLETE
3. [HTTP Tool API](#http-tool-api) - Claude Code 调用后端工具
4. [REST API](#rest-api) - 配置和管理接口
5. [数据模型](#数据模型) - 完整的数据结构定义

---

## 1. WebSocket v2 协议（推荐）

### 连接端点

```
ws://localhost:8080/ws/agent/chat
```

### 核心消息类型

**IDE Plugin → Agent**:
- `AGENT_CHAT`: 发送用户消息
- `TOOL_RESULT`: 返回工具执行结果（当前未使用）
- `STOP`: 用户主动中断执行
- `PING`: 心跳检测

**Agent → IDE Plugin**:
- `AGENT_RESPONSE`: 状态更新和分析结果
- `TOOL_CALL`: 请求执行本地工具（当前未使用）
- `CODE_EDIT`: 代码编辑指令（未来功能）
- `STOPPED`: 响应STOP请求
- `ERROR`: 错误信息

**已废弃的消息类型** (v2.0起):
- ~~`CLARIFICATION`~~: Claude Code的澄清问题通过普通`AGENT_RESPONSE`消息返回
- ~~`ANSWER`~~: 用户回答通过`AGENT_CHAT`消息发送，无需特殊类型
- ~~`TODO_UPDATE`~~: TODO列表通过Markdown在普通消息中展示

**设计理念**: 所有交互都通过`AGENT_CHAT`和`AGENT_RESPONSE`完成。Claude Code的输出(包括澄清问题、TODO列表、代码修改建议)都作为`AGENT_RESPONSE`的`message`字段返回，前端直接渲染Markdown内容即可。

---

### 1.1 IDE Plugin → Agent: AGENT_CHAT

**用途**: 发送用户消息，启动或继续分析

**消息格式**:
```json
{
  "type": "AGENT_CHAT",
  "data": {
    "message": "用户需求文本",
    "sessionId": "后端会话ID（首次为空字符串）",
    "projectKey": "autoloop",
    "mode": "medium",
    "projectPath": "/Users/liuchao/projects/autoloop"
  }
}
```

**字段说明**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | String | ✅ | 固定值："AGENT_CHAT" |
| `data.message` | String | ✅ | 用户输入的需求文本 |
| `data.sessionId` | String | ✅ | 后端会话ID，首次请求为空字符串 `""` |
| `data.projectKey` | String | ✅ | 项目标识符，用于定位项目路径 |
| `data.mode` | String | ❌ | 分析模式：`full`/`medium`/`lite`，默认 `medium` |
| `data.projectPath` | String | ✅ | 项目绝对路径 |

**示例**:
```json
{
  "type": "AGENT_CHAT",
  "data": {
    "message": "分析 FileFilter 类的结构",
    "sessionId": "",
    "projectKey": "autoloop",
    "mode": "medium",
    "projectPath": "/Users/liuchao/projects/autoloop"
  }
}
```

---

### 1.2 Agent → IDE Plugin: AGENT_RESPONSE

**用途**: 返回分析进度、结果或状态更新

**消息格式**:
```json
{
  "type": "AGENT_RESPONSE",
  "data": {
    "status": "PROCESSING",
    "message": "正在分析代码...",
    "stage": "Analyze",
    "sessionId": "abc-123",
    "result": null,
    "error": null
  }
}
```

**字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | 固定值："AGENT_RESPONSE" |
| `data.status` | String | 状态值（见下表） |
| `data.message` | String | 状态描述或结果内容 |
| `data.stage` | String | 当前阶段（可选）：`Analyze`/`Plan`/`Execute` |
| `data.sessionId` | String | 后端会话ID |
| `data.result` | String | 最终结果（仅在 `COMPLETED` 状态） |
| `data.error` | String | 错误信息（仅在 `ERROR` 状态） |

**状态值**:

| Status | 说明 | IDE 处理 |
|--------|------|----------|
| `PROCESSING` | 处理中 | 显示 thinking 消息 |
| `WAITING_CONFIRM` | 等待用户确认 | 显示确认提示 |
| `COMPLETED` | 完成 | 显示最终结果，关闭连接 |
| `SUCCESS` | 成功 | 同 COMPLETED |
| `FAILED` | 失败 | 显示错误，关闭连接 |
| `ERROR` | 错误 | 显示错误，关闭连接 |
| `CANCELLED` | 已取消 | 显示取消提示，关闭连接 |

**示例 1 - 处理中**:
```json
{
  "type": "AGENT_RESPONSE",
  "data": {
    "status": "PROCESSING",
    "message": "🔍 正在搜索 FileFilter 类...",
    "stage": "Analyze",
    "sessionId": "abc-123"
  }
}
```

**示例 2 - 完成**:
```json
{
  "type": "AGENT_RESPONSE",
  "data": {
    "status": "COMPLETED",
    "message": "分析完成！",
    "stage": "Execute",
    "sessionId": "abc-123",
    "result": "## FileFilter 类分析\n\n类名：`FileFilter`\n路径：`core/src/...`"
  }
}
```

---

### 1.3 Agent → IDE Plugin: TOOL_CALL

**用途**: 后端请求 IDE Plugin 执行本地工具

**消息格式**:
```json
{
  "type": "TOOL_CALL",
  "data": {
    "callId": "uuid-12345",
    "toolName": "read_class",
    "projectPath": "/Users/liuchao/projects/autoloop",
    "parameters": {
      "className": "FileFilter",
      "mode": "structure"
    }
  }
}
```

**字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| `callId` | String | 调用ID，用于关联请求和响应 |
| `toolName` | String | 工具名称（见下方工具列表） |
| `projectPath` | String | 项目绝对路径 |
| `parameters` | Object | 工具参数（键值对） |

**可用工具**:

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `read_class` | `className`, `mode`, `start_line`, `end_line`, `search_keyword`, `context_lines` | 读取类结构（支持按行号读取和关键词搜索） |
| `read_method` | `className`, `methodName` | 读取方法源码 |
| `text_search` | `keyword`/`query`/`text`, `limit`, `file_type`, `regex`, `include_context`, `context_lines` | 文本搜索（支持正则表达式） |
| `grep_file` | `relativePath`, `pattern`, `case_sensitive`, `context_lines`, `max_results` | 单文件内正则搜索（返回行号和上下文） |
| `read_file` | `relativePath`, `start_line`, `end_line`, `line`, `context_lines` | 读取文件（支持按行号范围读取） |
| `list_dir` | `relativePath` | 列出目录 |
| `read_xml` | `relativePath`, `namespace`, `method`, `line`, `context_lines` | 读取 XML（支持MyBatis Mapper提取SQL） |
| `read_config` | `relativePath`, `line`, `start_line`, `end_line`, `context_lines` | 读取配置文件（支持按行号读取） |
| `call_chain` | `method`, `direction`, `depth`, `include_source` | 调用链分析 |
| `find_usages` | `target`, `include_context`, `context_lines`, `max_results` | 查找引用 |
| `write_file` | `relativePath`, `content`, `package_name`, `class_name`, `overwrite` | 写入文件 |
| `modify_file` | `relativePath`, `operation`, `old_content`, `new_content`, `replace_all` | 修改文件（支持replace/insert/delete/add_import） |
| `apply_change` | `relativePath`, `searchContent`, `replaceContent`, `description` | SEARCH/REPLACE + 自动格式化 |

**工具参数详细说明**:

#### grep_file (文件内正则搜索)
- `relativePath` (必需): 文件相对路径
- `pattern` (必需): 正则表达式
- `case_sensitive` (可选): 是否大小写敏感，默认false
- `context_lines` (可选): 上下文行数，默认5
- `max_results` (可选): 最大结果数，默认50

#### read_file (读取文件)
- `relativePath` (必需): 文件相对路径
- `start_line` (可选): 起始行号（1-based）
- `end_line` (可选): 结束行号（1-based）
- `line` (可选): 中心行号（返回前后各context_lines行）
- `context_lines` (可选): 当使用line参数时的上下文行数，默认20

#### text_search (文本搜索)
- `keyword` (必需): 搜索关键词或正则表达式
- `regex` (可选): 是否使用正则表达式匹配，默认false
- `limit` (可选): 最大结果数，默认20
- `file_type` (可选): 文件类型过滤，可选值: `java`/`config`/`all`，默认`all`
- `include_context` (可选): 是否包含上下文，默认true
- `context_lines` (可选): 上下文行数，默认10

#### read_class (读取类)
- `className` (可选): 类名
- `relativePath` (可选): 文件相对路径（优先于className）
- `mode` (可选): 读取模式，可选值: `structure`/`full`/`imports_fields`，默认`structure`
- `start_line` (可选): 起始行号（1-based）
- `end_line` (可选): 结束行号（1-based）
- `search_keyword` (可选): 类内搜索关键词
- `context_lines` (可选): 搜索结果上下文行数，默认10

#### read_config (读取配置文件)
- `relativePath` (必需): 配置文件相对路径
- `line` (可选): 中心行号（返回该行前后各context_lines行）
- `start_line` (可选): 起始行号（与end_line配合使用）
- `end_line` (可选): 结束行号
- `context_lines` (可选): 当使用line参数时的上下文行数，默认20

---

### 1.4 IDE Plugin → Agent: TOOL_RESULT

**用途**: 返回工具执行结果

**消息格式**:
```json
{
  "type": "TOOL_RESULT",
  "data": {
    "callId": "uuid-12345",
    "success": true,
    "result": "类结构信息...",
    "executionTime": 150,
    "errorMessage": null
  }
}
```

**字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| `callId` | String | 调用ID（必须与 TOOL_CALL 一致） |
| `success` | Boolean | 执行是否成功 |
| `result` | String | 执行结果（成功时） |
| `executionTime` | Long | 执行耗时（毫秒） |
| `errorMessage` | String | 错误信息（失败时） |

---

### 1.5 Agent → IDE Plugin: CODE_EDIT

**用途**: 后端发送代码编辑指令

**消息格式**:
```json
{
  "type": "CODE_EDIT",
  "data": {
    "edits": [
      {
        "relativePath": "core/src/main/java/FileFilter.java",
        "searchContent": "public boolean accept",
        "replaceContent": "public boolean accept",
        "operation": "replace"
      }
    ],
    "autoFormat": true,
    "addImports": [
      "java.io.File",
      "java.nio.file.Path"
    ]
  }
}
```

---

## 2. WebSocket v1 协议（向后兼容）

### 连接端点

```
ws://localhost:8080/ws/analyze
```

### 2.1 IDE Plugin → Agent: ANALYZE

```json
{
  "type": "ANALYZE",
  "data": {
    "requirementText": "分析 FileFilter 类",
    "projectKey": "autoloop",
    "requestId": "abc-123",
    "mode": "medium",
    "projectPath": "/Users/liuchao/projects/autoloop"
  }
}
```

### 2.2 Agent → IDE Plugin: PROGRESS

```json
{
  "type": "PROGRESS",
  "data": {
    "thinking": "正在搜索类...",
    "round": 1,
    "todoItems": []
  }
}
```

### 2.3 Agent → IDE Plugin: COMPLETE

```json
{
  "type": "COMPLETE",
  "data": {
    "analysisResult": "分析结果...",
    "requestId": "abc-123",
    "process": "分析过程..."
  }
}
```

---

## 3. HTTP Tool API

**用途**: Claude Code CLI 调用后端工具

**Base URL**: `http://localhost:8080`

### 3.1 执行工具

**端点**: `POST /api/claude-code/tools/execute`

**请求格式**:
```json
{
  "tool": "vector_search",
  "params": {
    "query": "文件过滤",
    "top_k": 10
  },
  "sessionId": "abc-123",
  "projectKey": "autoloop"
}
```

**响应格式**:
```json
{
  "success": true,
  "result": "搜索结果...",
  "executionTime": 50,
  "errorMessage": null
}
```

**可用工具**:

| 工具名 | 端点 | 参数 | 说明 |
|--------|------|------|------|
| `vector_search` | `/api/vector/search` | `query`, `top_k` | 向量搜索 |
| `read_class` | `/api/ast/class` | `className`, `mode` | 读取类结构 |
| `call_chain` | `/api/callchain/analyze` | `method`, `direction`, `depth` | 调用链分析 |

---

### 3.2 向量搜索

**端点**: `POST /api/vector/search`

**请求**:
```json
{
  "query": "文件过滤功能",
  "top_k": 10,
  "sessionId": "abc-123"
}
```

**响应**:
```json
{
  "results": [
    {
      "className": "FileFilter",
      "relativePath": "core/src/main/java/FileFilter.java",
      "score": 0.89,
      "summary": "文件过滤器类..."
    }
  ]
}
```

---

### 3.3 读取类结构

**端点**: `POST /api/ast/class`

**请求**:
```json
{
  "className": "FileFilter",
  "mode": "structure",
  "projectKey": "autoloop"
}
```

**响应**:
```json
{
  "className": "FileFilter",
  "superClass": "Object",
  "interfaces": [],
  "fields": [
    {
      "name": "pattern",
      "type": "String",
      "modifiers": "private"
    }
  ],
  "methods": [
    {
      "name": "accept",
      "returnType": "boolean",
      "parameters": [
        {
          "name": "file",
          "type": "File"
        }
      ]
    }
  ]
}
```

---

### 3.4 调用链分析

**端点**: `POST /api/callchain/analyze`

**请求**:
```json
{
  "method": "FileFilter.accept",
  "direction": "both",
  "depth": 2,
  "projectKey": "autoloop"
}
```

**响应**:
```json
{
  "callers": [
    {
      "className": "FileManager",
      "methodName": "listFiles",
      "lineNumber": 45
    }
  ],
  "callees": [
    {
      "className": "String",
      "methodName": "endsWith",
      "lineNumber": 12
    }
  ]
}
```

---

## 4. REST API

### 4.1 健康检查

**端点**: `GET /api/test/health`

**响应**:
```json
{
  "status": "UP",
  "timestamp": 1704451200000
}
```

---

### 4.2 进程池状态

**端点**: `GET /api/claude-code/pool/status`

**响应**:
```json
{
  "totalProcesses": 15,
  "activeProcesses": 3,
  "idleProcesses": 12,
  "queueSize": 0,
  "warmupInProgress": false
}
```

---

### 4.3 降级模式控制

**端点**: `GET /api/fallback/status`

**响应**:
```json
{
  "enabled": true,
  "inFallbackMode": false,
  "reason": null,
  "lastCheckTime": 1704451200000
}
```

**端点**: `POST /api/fallback/enable`

**请求**:
```json
{
  "reason": "手动启用降级模式"
}
```

**端点**: `POST /api/fallback/disable`

---

### 4.4 项目配置管理

**端点**: `GET /api/config/projects`

**响应**:
```json
{
  "projects": {
    "autoloop": {
      "projectPath": "/Users/liuchao/projects/autoloop",
      "description": "AutoLoop 项目",
      "language": "java",
      "version": "1.0.0"
    }
  }
}
```

**端点**: `POST /api/config/projects`

**请求**:
```json
{
  "projectKey": "new-project",
  "config": {
    "projectPath": "/path/to/project",
    "description": "新项目",
    "language": "java",
    "version": "1.0.0"
  }
}
```

---

## 5. 数据模型

### 5.1 WebSocketMessage

```json
{
  "type": "MESSAGE_TYPE",
  "data": { /* 任意数据 */ }
}
```

### 5.2 ToolCall

```json
{
  "callId": "uuid",
  "toolName": "tool_name",
  "projectPath": "/path/to/project",
  "parameters": {}
}
```

### 5.3 ToolResult

```json
{
  "callId": "uuid",
  "success": true,
  "result": "结果",
  "executionTime": 100,
  "errorMessage": null
}
```

---

## 6. 完整通信流程示例

### 6.1 首次对话（无 sessionId）

```
1. IDE Plugin → Agent: AGENT_CHAT (sessionId="")
   {
     "message": "分析 FileFilter 类",
     "sessionId": "",
     "projectKey": "autoloop"
   }

2. Agent → IDE Plugin: AGENT_RESPONSE (PROCESSING)
   {
     "status": "PROCESSING",
     "message": "🔍 正在搜索类...",
     "sessionId": "abc-123"
   }

3. Agent → IDE Plugin: TOOL_CALL
   {
     "callId": "call-1",
     "toolName": "read_class",
     "parameters": {"className": "FileFilter", "mode": "structure"}
   }

4. IDE Plugin → Agent: TOOL_RESULT
   {
     "callId": "call-1",
     "success": true,
     "result": "类结构..."
   }

5. Agent → IDE Plugin: AGENT_RESPONSE (COMPLETED)
   {
     "status": "COMPLETED",
     "result": "## 分析结果\n...",
     "sessionId": "abc-123"
   }

6. Agent 关闭连接
```

---

### 6.2 多轮对话（有 sessionId）

```
1. IDE Plugin → Agent: AGENT_CHAT (sessionId="abc-123")
   {
     "message": "这个类的父类是谁？",
     "sessionId": "abc-123",
     "projectKey": "autoloop"
   }

2. Agent → IDE Plugin: AGENT_RESPONSE (PROCESSING)
   {
     "status": "PROCESSING",
     "message": "正在读取父类...",
     "sessionId": "abc-123"
   }

3. Agent → IDE Plugin: AGENT_RESPONSE (COMPLETED)
   {
     "status": "COMPLETED",
     "result": "父类是 Object",
     "sessionId": "abc-123"
   }
```

---

### 6.3 降级模式流程

```
1. IDE Plugin → Agent: AGENT_CHAT
   {
     "message": "搜索 FileFilter 类"
   }

2. Agent 检测到 Claude Code 不可用

3. Agent → IDE Plugin: AGENT_RESPONSE (降级)
   {
     "status": "COMPLETED",
     "result": "## 降级模式\n\n搜索结果：...",
     "sessionId": null
   }
```

---

## 7. 错误处理

### 7.1 错误码

| 错误码 | 说明 |
|--------|------|
| `INVALID_MESSAGE_TYPE` | 不支持的消息类型 |
| `MISSING_PARAMETER` | 缺少必填参数 |
| `PROJECT_NOT_FOUND` | 项目配置不存在 |
| `TOOL_EXECUTION_FAILED` | 工具执行失败 |
| `CLAUDE_CODE_UNAVAILABLE` | Claude Code 不可用 |

### 7.2 错误响应格式

```json
{
  "type": "ERROR",
  "data": {
    "code": "PROJECT_NOT_FOUND",
    "message": "未找到 projectKey: unknown-project",
    "details": {}
  }
}
```

---

## 8. 性能指标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| WebSocket 连接建立 | <100ms | 不包含握手 |
| TOOL_CALL 响应时间 | <2秒 | 本地工具执行 |
| AGENT_CHAT 首次响应 | <500ms | 返回 PROCESSING |
| AGENT_CHAT 完整响应 | <10秒 | 取决于任务复杂度 |

---

**文档结束**

*最后更新: 2026-01-05*
*维护者: SiliconMan Team*
