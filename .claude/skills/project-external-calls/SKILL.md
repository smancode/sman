---
id: project-external-calls
name: Sman 外部依赖扫描
description: Sman 外部依赖扫描 - HTTP 服务、数据库、消息队列、CDP 协议等外部服务调用
category: integration
_scanned:
  commitHash: "70d53baa472e0b2f87d9b0080e3239118c1f1ec7"
  scannedAt: "2026-05-22T00:00:00.000Z"
  branch: "master"
---

# Sman 外部依赖

本文档记录 Sman 项目调用的所有外部服务和 API。

## 外部服务清单

| 服务 | 类型 | 用途 | 参考文档 |
|------|------|------|---------|
| **Anthropic Claude API** | LLM REST API | AI 对话、工具调用、流式响应 | [references/llm.md](references/llm.md) |
| **SQLite (better-sqlite3)** | 嵌入式数据库 | 会话/消息/Cron/Batch/Chatbot/Stardom/Hub/Group/Achievement/IM 持久化 | [references/sqlite.md](references/sqlite.md) |
| **Git CLI** | Shell 命令 | 版本控制操作（status, diff, commit, push, log） | [references/git.md](references/git.md) |
| **企业微信 Bot** | WebSocket (wss://) | 企业微信消息推送、流式回复、媒体文件 | [references/wecom-bot.md](references/wecom-bot.md) |
| **飞书 Bot** | SDK (@larksuiteoapi/node-sdk) | 飞书事件监听、消息发送、文件下载 | [references/feishu-bot.md](references/feishu-bot.md) |
| **微信 Bot** | HTTPS API (ilinkai.weixin.qq.com) | 微信 QR 登录、长轮询监听、消息发送 | [references/weixin-bot.md](references/weixin-bot.md) |
| **星域服务器** | WebSocket (ws://) | 多 Agent 协作、任务分发、声望系统 | [references/stardom-server.md](references/stardom-server.md) |
| **Hub 协作服务器** | HTTP + WebSocket | 企业级协作、任务分发、技能自动更新、广播消息、成就排行榜、IM 消息同步 | [references/hub.md](references/hub.md) |
| **Hub 反馈/错误上报** | HTTPS POST | 用户反馈提交、错误日志上报（PSK 加密） | [references/hub-feedback-api.md](references/hub-feedback-api.md) |
| **成就系统 (SQLite)** | Embedded DB | 成就进度、统计、连续天数、排行榜缓存、Smart Path 积分 | [references/achievement-system.md](references/achievement-system.md) |
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
8. **IM 解耦**: IM 系统通过 Hub 广播消息实现跨设备同步，本地存储通过 SQLite 持久化

## Recent Changes (since 3539892)

- **🆕 IM System**: 2 表（im_messages/im_rooms）+ Hub 消息同步 + Agent @mention 激活
- **🔄 SmartPath**: 新增 smartpath_run_log 表追踪执行状态，成就计分改为 completed=2/failed=0.5
- **🔄 Achievement**: 移除 filesystem backfill，改为从 DB 直接统计 smartpath_run_log
- **🔄 Cron**: 支持手动触发（跳过空闲窗口检查），init 触发任务不写入 cron_runs 表
- **🔄 Hub**: 新增 IM 消息转发（`im.*` 类型消息通过 Hub 广播）
