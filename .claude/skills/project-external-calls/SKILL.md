---
id: project-external-calls
name: Sman 外部依赖扫描
description: Sman 外部依赖扫描 - HTTP 服务、数据库、消息队列、CDP 协议等外部服务调用
category: integration
_scanned:
  commitHash: "1ddac60bf3f5dbec4ced87ea1a0b7b680267f41c"
  scannedAt: "2026-05-19T00:00:00.000Z"
  branch: "master"
---

# Sman 外部依赖

本文档记录 Sman 项目调用的所有外部服务和 API。

## 外部服务清单

| 服务 | 类型 | 用途 | 参考文档 |
|------|------|------|---------|
| **Anthropic Claude API** | LLM REST API | AI 对话、工具调用、流式响应 | [references/llm.md](references/llm.md) |
| **SQLite (better-sqlite3)** | 嵌入式数据库 | 会话/消息/Cron/Batch/Chatbot/Stardom/Hub 持久化 | [references/sqlite.md](references/sqlite.md) |
| **Git CLI** | Shell 命令 | 版本控制操作（status, diff, commit, push, log） | [references/git.md](references/git.md) |
| **企业微信 Bot** | WebSocket (wss://) | 企业微信消息推送、流式回复、媒体文件 | [references/wecom-bot.md](references/wecom-bot.md) |
| **飞书 Bot** | SDK (@larksuiteoapi/node-sdk) | 飞书事件监听、消息发送、文件下载 | [references/feishu-bot.md](references/feishu-bot.md) |
| **微信 Bot** | HTTPS API (ilinkai.weixin.qq.com) | 微信 QR 登录、长轮询监听、消息发送 | [references/weixin-bot.md](references/weixin-bot.md) |
| **星域服务器** | WebSocket (ws://) | 多 Agent 协作、任务分发、声望系统 | [references/stardom-server.md](references/stardom-server.md) |
| **Hub 协作服务器** | HTTP + WebSocket | 企业级协作、任务分发、技能自动更新、广播消息 | [references/hub.md](references/hub.md) |
| **Brave Search API** | MCP Server (stdio) | Web 搜索（需 Brave API Key） | [references/web-search-mcp.md](references/web-search-mcp.md) |
| **Tavily Search API** | MCP Server (stdio) | AI 原生搜索引擎（需 Tavily API Key） | [references/web-search-mcp.md](references/web-search-mcp.md) |
| **Baidu Search API** | MCP Server (stdio) | 中文搜索引擎（需 Baidu API Key） | [references/web-search-mcp.md](references/web-search-mcp.md) |
| **Chrome DevTools Protocol** | WebSocket (ws://) | 浏览器自动化、页面快照、DOM 操作 | [references/chrome-cdp.md](references/chrome-cdp.md) |

## 架构设计原则

1. **环境隔离**: 所有外部 API 调用通过 `~/.sman/config.json` 配置，避免硬编码
2. **自动重连**: WebSocket 连接（WeCom/Stardom/Feishu/Weixin/Hub）支持指数退避重连
3. **流式节流**: Chatbot 流式回复带节流（WeCom 1s, Feishu 3s），避免触发频率限制
4. **认证隔离**: 使用独立的 `CLAUDE_CONFIG_DIR`，防止全局 Claude 配置污染
5. **WAL 模式**: SQLite 启用 WAL 模式和外键约束，保证数据一致性
6. **PSK 加密**: Hub 通信使用预共享密钥加密，支持时间戳防重放
7. **异步优先**: Git 操作使用 `execFile` + Promise，支持并发调用和超时控制

## Recent Changes (since 57e98c3)

### 🔄 MODIFIED: Git Operations (Performance Improvement)

**Impact**: ⚡ **Major Performance Upgrade**

- **Before**: Synchronous `execSync()` calls (blocking event loop)
- **After**: Asynchronous `execFile()` with Promise wrappers
- **Benefits**:
  - Parallel Git operations (e.g., `git status` now runs branch + porcelain queries concurrently)
  - Non-blocking UI during Git operations
  - Better error handling with async/await
  - Configurable timeout per operation (10-60s)
- **Breaking Changes**: None - all handlers now return `Promise<T>` instead of `T`
- **Files Changed**: `server/git-handler.ts` (all 15+ handlers migrated), `server/index.ts` (WebSocket handlers updated)

### 🆕 NEW: Git CLI Reference

**Added**: `references/git.md` documenting all Git command patterns
- Commands: status, diff, commit, log, checkout, fetch, push, branch
- Security: Path validation, timeout protection, command injection prevention
- Performance: Parallel queries, maxBuffer 10MB, intelligent directory expansion

### 🔄 MODIFIED: Smart Path Engine

**Changes**:
- Added workspace skill injection (reads `.claude/skills/{skillId}/SKILL.md`)
- Script file whitelist enforcement (`.py`, `.sh`, `.js`, `.ts`, `.bat`, `.sql`, etc.)
- Reference file filtering (only scripts allowed, no data files like `.json`, `.csv`)

### 🆕 NEW: Hub Integration Reference

**Added**: `references/hub.md` documenting Hub server protocol
- HTTP heartbeat (15min interval) + WebSocket persistent connection
- PSK encryption with `~/.sman/hub.key`
- Multi-agent task distribution and broadcast messaging
- Graceful degradation (connection failure doesn't block local operations)
