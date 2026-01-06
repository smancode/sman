# SiliconMan API 快速参考表

## WebSocket v2 协议核心消息

### IDE Plugin 发送的消息

| 消息类型 | 触发时机 | 关键字段 |
|---------|---------|---------|
| `AGENT_CHAT` | 用户输入需求 | `message`, `sessionId`, `projectKey` |
| `TOOL_RESULT` | 工具执行完成 | `callId`, `success`, `result` |
| `STOP` | 用户中断执行 | (无额外字段) |

### Agent 发送的消息

| 消息类型 | 触发时机 | 关键字段 |
|---------|---------|---------|
| `AGENT_RESPONSE` | 状态更新 | `status`, `message`, `sessionId` |
| `TOOL_CALL` | 需要执行工具 | `callId`, `toolName`, `parameters` |
| `CODE_EDIT` | 代码编辑指令 | `edits[]` |
| `STOPPED` | 响应STOP请求 | `sessionId`, `message` |
| `PONG` | 响应PING | `timestamp` |
| `ERROR` | 错误信息 | `errorCode`, `errorMessage` |

---

## 重要说明

**已废弃的消息类型** (v2.0起):
- ~~`CLARIFICATION`~~: Claude Code的澄清问题通过普通`AGENT_RESPONSE`消息返回
- ~~`ANSWER`~~: 用户回答通过`AGENT_CHAT`消息发送，无需特殊类型
- ~~`TODO_UPDATE`~~: TODO列表通过Markdown在普通消息中展示

**推荐做法**:
- 所有交互都通过`AGENT_CHAT`和`AGENT_RESPONSE`完成
- Claude Code的输出(包括澄清问题、TODO列表)都作为`AGENT_RESPONSE`的`message`字段返回
- 前端直接渲染Markdown内容即可

---

## AGENT_RESPONSE 状态值

```
PROCESSING      → 显示 "thinking" 消息
WAITING_CONFIRM → 显示确认对话框
COMPLETED       → 显示最终结果，关闭连接
SUCCESS         → 同 COMPLETED
FAILED          → 显示错误，关闭连接
ERROR           → 显示错误，关闭连接  
CANCELLED       → 显示取消提示，关闭连接
```

---

## TOOL_CALL 工具列表

```
read_class    → 读取类结构 (className, mode)
read_method   → 读取方法 (className, methodName)
text_search   → 文本搜索 (keyword, maxResults)
call_chain    → 调用链 (method, direction, depth)
find_usages   → 查找引用 (target, maxResults)
list_dir      → 列出目录 (relativePath)
read_xml      → 读取 XML (relativePath)
read_file     → 读取文件 (relativePath)
read_config   → 读取配置 (relativePath, type)
write_file    → 写入文件 (relativePath, content)
modify_file   → 修改文件 (relativePath, operation)
apply_change  → SEARCH/REPLACE (relativePath, searchContent, replaceContent)
```

---

## REST API 端点

```
GET  /api/test/health                    → 健康检查
GET  /api/claude-code/pool/status       → 进程池状态
GET  /api/fallback/status               → 降级模式状态
POST /api/fallback/enable               → 启用降级模式
POST /api/fallback/disable              → 禁用降级模式
GET  /api/config/projects               → 获取项目配置
POST /api/config/projects               → 添加项目配置
```

---

## 完整对话流程（示例）

```
IDE Plugin                    Agent Backend
    |                               |
    |-- AGENT_CHAT (sessionId="")-->|
    |                               |-- 创建 sessionId="abc-123"
    |                               |
    |<--AGENT_RESPONSE(PROCESSING)--|
    |                               |
    |<--TOOL_CALL(read_class)--------|
    |                               |
    |--TOOL_RESULT(success)--------->|
    |                               |
    |<--AGENT_RESPONSE(COMPLETED)---|
    |                               |
    X (连接关闭)                      X
```

---

## 关键注意事项

✅ **必填字段检查清单**:
- AGENT_CHAT: `message`, `sessionId`, `projectKey`, `projectPath`
- TOOL_RESULT: `callId`, `success`
- TOOL_CALL: `callId`, `toolName`, `parameters`

⚠️ **常见错误**:
1. `sessionId` 首次请求应为空字符串 `""`，不是 `null`
2. `callId` 必须在 TOOL_CALL 和 TOOL_RESULT 之间保持一致
3. `projectKey` 必须在 `application.yml` 中预先配置

🔧 **调试技巧**:
- 查看后端日志: `agent/logs/sman-agent.log`
- 检查 WebSocket 连接: 使用 wscat 工具
- 验证工具执行: 查看 IDE Plugin 的工具日志

