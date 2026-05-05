---
name: project-external-calls
description: "smanbase external dependencies: HTTP services, databases, message queues. Consult this when modifying integrations, debugging connectivity, or adding new external calls."
_scanned:
  commitHash: "35f8e752359eff2474610cf31f0beaaa40ccbca9"
  scannedAt: "2026-05-05T00:00:00.000Z"
  branch: "master"
---

# External Dependencies

Reference files in `references/` for each service.

## External Service Table

| Service | Type | Purpose | Reference |
|---------|------|---------|-----------|
| **LLM (Anthropic-compatible)** | HTTP | AI conversation via Claude Agent SDK; user profile analysis; capability detection; batch task execution | references/llm.md |
| **SQLite (local)** | DB | Session store, cron/batch tasks, chatbot state, Stardom registry, knowledge extraction progress | references/sqlite.md |
| **WeCom Bot** | HTTP/WS | WeCom enterprise chat bot via WebSocket long-polling (`wss://openws.work.weixin.qq.com`) | references/wecom-bot.md |
| **Feishu Bot** | HTTP SDK | Feishu bot via `@larksuiteoapi/node-sdk` | references/feishu-bot.md |
| **Weixin Bot** | HTTP API | Weixin bot via custom HTTP API (`weixin-api.ts`) | references/weixin-bot.md |
| **Stardom Server** | WS | Collaboration network via WebSocket (connect, register, heartbeat, task exchange) | references/stardom-server.md |
| **Web Search APIs** | HTTP | Brave/Tavily/Bing search (auto-configured as MCP servers) | references/web-search.md |
| **Chrome DevTools Protocol** | WS | Browser automation via CDP (Web Access feature) | references/cdp.md |

## Notes
- **Config source**: All external service configs stored in `~/.sman/config.json` (auth tokens, API keys, endpoints)
- **Environment isolation**: Uses `getCleanEnv()` to clear env vars, relies on config file instead
- **Retry & timeout**: Most external calls have configurable timeout (default 120s for batch tasks)
- **Connection pooling**: SQLite uses connection pool (better-sqlite3), WebSocket connections are persistent
- **Error handling**: All external calls wrapped in try-catch with user-friendly error messages
