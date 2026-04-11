---
name: project-external-calls
description: "smanbase external dependencies: HTTP services, databases, message queues. Consult this when modifying integrations, debugging connectivity, or adding new external calls."
_scanned:
  commitHash: "244ad7730e49cf49ebc53f3129b49b391c9c3999"
  scannedAt: "2026-04-11T08:29:50.864Z"
  branch: "base"
---

# External Dependencies

## External Service Table

| Service | Type | Purpose | Reference |
|---------|------|---------|-----------|
| Claude Agent SDK | HTTP (SDK) | AI chat, cron tasks, chatbot responses via Anthropic API | [claude-agent-sdk.md](references/claude-agent-sdk.md) |
| Web Search MCP (Brave/Tavily/Bing) | HTTP (MCP, npx) | Web search capability for Claude sessions | [web-search-mcp.md](references/web-search-mcp.md) |
| WeCom Bot | WebSocket (WS) | WeChat Work bot - receive messages, stream Claude replies | [wecom-bot.md](references/wecom-bot.md) |
| Feishu Bot | HTTP + WS (SDK) | Feishu/Lark bot - receive messages, stream Claude replies | [feishu-bot.md](references/feishu-bot.md) |
| WeChat Personal (iLink) | HTTP (fetch) | WeChat personal account bot via iLink API, long-polling | [wechat-personal.md](references/wechat-personal.md) |
| Chrome CDP | WebSocket (WS) | Browser automation - navigate, snapshot, screenshot, click | [chrome-cdp.md](references/chrome-cdp.md) |
| SQLite (better-sqlite3) | DB | Sessions, messages, cron/batch tasks, chatbot state | [sqlite.md](references/sqlite.md) |
| Bazaar Server | WebSocket (WS) | Multi-agent collaboration - agent register, task offers | [bazaar-server.md](references/bazaar-server.md) |

## Config Source

All config is in ~/.sman/config.json (env: SMANBASE_HOME). No .env files. No hard-coded credentials.
