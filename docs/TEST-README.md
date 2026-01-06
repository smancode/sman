# SiliconMan 端到端测试指南

## 📋 测试说明

这个测试套件验证 SiliconMan 系统的完整功能，包括：
- WebSocket v2 协议通信
- 工具调用流程（vector_search, read_class, call_chain 等）
- Agent Backend 和 IDE Plugin 的交互

## 🚀 快速开始

### 1. 安装依赖

```bash
pip install -r test-requirements.txt
```

或手动安装：
```bash
pip install websockets aiohttp
```

### 2. 启动 Agent Backend

确保 Agent Backend 已启动（默认端口 8080）：

```bash
cd agent
./gradlew bootRun
```

验证服务运行：
```bash
curl http://localhost:8080/api/test/health
```

应该返回：`OK`

### 3. 运行测试

```bash
python test-javafileservice.py
```

## 📊 测试模式

### 模式 1: WebSocket 端到端测试（推荐）

**测试场景**: 询问 "JavaFileService是做啥的"

**完整流程**:
1. IDE Plugin → Agent Backend: 发送 AGENT_CHAT
2. Agent Backend → IDE Plugin: 返回 AGENT_RESPONSE (PROCESSING)
3. Agent Backend → IDE Plugin: 发送 TOOL_CALL (read_class)
4. IDE Plugin → Agent Backend: 返回 TOOL_RESULT
5. Agent Backend → IDE Plugin: 发送 TOOL_CALL (call_chain)
6. IDE Plugin → Agent Backend: 返回 TOOL_RESULT
7. Agent Backend → IDE Plugin: 返回 AGENT_RESPONSE (COMPLETED)

**特点**:
- ✅ 完整的 WebSocket 通信
- ✅ 多轮工具调用
- ✅ 实时状态更新
- ✅ 模拟 IDE Plugin 工具执行

### 模式 2: HTTP Tool API 测试

**测试工具**:
1. `vector_search` - 语义搜索
2. `read_class` - 读取类结构
3. `call_chain` - 调用链分析

**特点**:
- ✅ 直接测试 HTTP API
- ✅ 验证 projectKey/sessionId 参数
- ✅ 测试响应时间

### 模式 3: 全部测试

依次运行模式 1 和模式 2。

## 📝 预期输出

### 成功的测试输出示例

```
============================================================
🚀 SiliconMan Agent Backend 端到端测试
============================================================
WebSocket URL: ws://localhost:8080/ws/agent/chat
Project: autoloop
Project Path: /Users/liuchao/projects/autoloop
============================================================

📤 发送 AGENT_CHAT #1
   消息: JavaFileService是做啥的
   sessionId:

📥 收到消息: AGENT_RESPONSE
   状态: PROCESSING
   💭 🔍 正在搜索 JavaFileService 类...
✅ 更新 sessionId: session-abc123

📥 收到消息: TOOL_CALL
   callId: call-001
   toolName: vector_search

🔧 执行工具: vector_search
   参数: {
     "query": "JavaFileService",
     "top_k": 5
   }

📤 发送 TOOL_RESULT
   callId: call-001
   success: True

📥 收到消息: AGENT_RESPONSE
   状态: PROCESSING
   💭 正在读取类结构...

...（更多工具调用）...

📥 收到消息: AGENT_RESPONSE
   状态: COMPLETED

============================================================
✅ 分析完成!
============================================================

## JavaFileService 类分析

`JavaFileService` 是 AutoLoop 项目中的 **Java 文件服务类**...
...

============================================================

✅ 测试完成!
```

## ❌ 故障排查

### 错误 1: 无法连接到 WebSocket 服务器

**原因**: Agent Backend 未启动

**解决**:
```bash
cd agent
./gradlew bootRun
```

### 错误 2: 工具执行失败

**原因**: projectKey 未配置

**解决**:
检查 `application.yml` 中是否配置了 `autoloop` 项目：
```yaml
sman:
  projects:
    autoloop:
      project-path: /Users/liuchao/projects/autoloop
```

### 错误 3: JSON 解析失败

**原因**: 后端返回了非 JSON 格式的错误

**解决**:
检查 Agent Backend 日志：
```bash
tail -f agent/logs/sman-agent.log
```

## 🔍 测试覆盖范围

### ✅ 已测试功能

- [x] WebSocket 连接建立
- [x] AGENT_CHAT 消息发送
- [x] AGENT_RESPONSE 状态更新
- [x] TOOL_CALL 工具调用
- [x] TOOL_RESULT 结果返回
- [x] sessionId 管理（新会话创建）
- [x] projectKey 参数传递
- [x] vector_search 工具
- [x] read_class 工具
- [x] call_chain 工具
- [x] grep_file 工具（新增）
- [x] read_file 工具（新增）

### 🚧 待测试功能

- [ ] 多轮对话（使用已有 sessionId）
- [ ] STO P 消息（中断执行）
- [ ] 降级模式
- [ ] 错误处理（工具执行失败）
- [ ] 超大文件分段读取

## 📚 相关文档

- [完整 API 文档](docs/md/08-complete-api-reference.md)
- [架构设计](docs/md/01-architecture.md)
- [Claude Code 集成](docs/md/03-claude-code-integration.md)

## 🤝 贡献

如果测试失败，请：
1. 保存完整输出日志
2. 检查 Agent Backend 日志
3. 提交 Issue 到 GitHub

---

**最后更新**: 2026-01-05
**维护者**: SiliconMan Team
