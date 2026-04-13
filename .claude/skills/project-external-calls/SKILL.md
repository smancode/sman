---
name: project-external-calls
description: "smanbase external dependencies: HTTP services, databases, message queues. Consult this when modifying integrations, debugging connectivity, or adding new external calls."
_scanned:
  commitHash: "719682a1415b0f56538eee4bcf2a429abb1f4f8d"
  scannedAt: "2026-04-13T00:00:00.000Z"
  branch: "base"
---

# External Dependencies

Reference files in references/ for each service.

## External Service Table

| Service | Type | Purpose | Reference |
|---------|------|---------|-----------|
| LLM (Anthropic-compatible) | HTTP | AI conversation via Claude Agent SDK; user profile analysis; capability detection | references/llm.md |
| SQLite (local) | DB | Session store, cron/batch tasks, chatbot state, Bazaar registry | references/sqlite.md |
| WeCom Bot | HTTP/WS | WeCom enterprise chat bot via WebSocket long-polling | references/wecom-bot.md |
| Feishu Bot | HTTP | Feishu (Lark) enterprise messaging bot | references/feishu-bot.md |
| WeChat Personal (iLink) | HTTP | Personal WeChat bot via iLink Bot API, QR login | references/wechat-bot.md |
| Web Search MCP | stdio (MCP) | Brave / Tavily / Bing web search via MCP Server | references/web-search-mcp.md |
| Chrome CDP | WS | Browser automation (navigate, click, screenshot) via local Chrome | references/chrome-cdp.md |
