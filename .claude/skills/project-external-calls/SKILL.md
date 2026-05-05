---
description: Sman 外部依赖扫描 - HTTP 服务、数据库、消息队列、CDP 协议等外部服务调用
_scanned:
  commitHash: "32289f752b24fd9424b2dd1c9e9e34938bf4a806"
  scannedAt: "2026-05-05T00:00:00.000Z"
  branch: "master"
---

# Sman 外部依赖

本文档记录 Sman 项目调用的所有外部服务和 API。

## 外部服务清单

| 服务 | 类型 | 用途 | 参考文档 |
|------|------|------|---------|
| **Anthropic Claude API** | LLM REST API | AI 对话、工具调用、流式响应 | [references/llm.md](references/llm.md) |
| **SQLite (better-sqlite3)** | 嵌入式数据库 | 会话/消息/Cron/Batch/Chatbot/Stardom 持久化 | [references/sqlite.md](references/sqlite.md) |
| **企业微信 Bot** | WebSocket (wss://) | 企业微信消息推送、流式回复、媒体文件 | [references/wecom-bot.md](references/wecom-bot.md) |
| **飞书 Bot** | SDK (@larksuiteoapi/node-sdk) | 飞书事件监听、消息发送、文件下载 | [references/feishu-bot.md](references/feishu-bot.md) |
| **微信 Bot** | HTTPS API (ilinkai.weixin.qq.com) | 微信 QR 登录、长轮询监听、消息发送 | [references/weixin-bot.md](references/weixin-bot.md) |
| **星域服务器** | WebSocket (ws://) | 多 Agent 协作、任务分发、声望系统 | [references/stardom-server.md](references/stardom-server.md) |
| **Brave Search API** | MCP Server (stdio) | Web 搜索（需 Brave API Key） | [references/web-search-mcp.md](references/web-search-mcp.md) |
| **Tavily Search API** | MCP Server (stdio) | AI 原生搜索引擎（需 Tavily API Key） | [references/web-search-mcp.md](references/web-search-mcp.md) |
| **Chrome DevTools Protocol** | WebSocket (ws://) | 浏览器自动化、页面快照、DOM 操作 | [references/chrome-cdp.md](references/chrome-cdp.md) |

## 架构设计原则

1. **环境隔离**: 所有外部 API 调用通过 `~/.sman/config.json` 配置，避免硬编码
2. **自动重连**: WebSocket 连接（WeCom/Stardom/Feishu/Weixin）支持指数退避重连
3. **流式节流**: Chatbot 流式回复带节流（WeCom 1s, Feishu 3s），避免触发频率限制
4. **认证隔离**: 使用独立的 `CLAUDE_CONFIG_DIR`，防止全局 Claude 配置污染
5. **WAL 模式**: SQLite 启用 WAL 模式和外键约束，保证数据一致性
