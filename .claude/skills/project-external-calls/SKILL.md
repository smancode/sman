---
name: project-external-calls
description: "smanbase external dependencies: HTTP services, databases, message queues. Consult this when modifying integrations, debugging connectivity, or adding new external calls."
_scanned:
  commitHash: "74f4bbc6b4bfc811384eabcc73070c20f12be381"
  scannedAt: "2026-04-09T15:40:40.605Z"
  branch: "base"
---

# External Dependencies

## External Service Table

| Service | Type | Purpose | Reference |
|---------|------|---------|-----------|
| Anthropic API | HTTP | LLM inference via SDK + direct HTTP for model probing | `references/anthropic-api.md` |
| SQLite (better-sqlite3) | DB | All local persistence: sessions, cron tasks, batch tasks, chatbot state | `references/sqlite.md` |
| WeCom Bot | WS | Enterprise WeChat (WeCom) bot via WebSocket long-connection | `references/wecom-bot.md` |
| WeChat Personal Bot | HTTP | iLink Bot API (personal WeChat account) via HTTP long-polling | `references/wechat-personal-bot.md` |
| Feishu Bot | WS | 飞书 Bot via `@larksuiteoapi/node-sdk` WebSocket long-connection | `references/feishu-bot.md` |
| Brave Search | MCP | Web search via `@anthropic-ai/mcp-server-brave` (stdio) | `references/brave-search.md` |
| Tavily Search | MCP | Web search via `@anthropic-ai/mcp-server-tavily` (stdio) | `references/tavily-search.md` |
| Bing Search | MCP | Web search via `@anthropic-ai/mcp-server-bing` (stdio) | `references/bing-search.md` |
| Web Access (CDP) | CDP | Chrome DevTools Protocol — local browser automation | `references/web-access-cdp.md` |
| Capability Registry | HTTP | Internal capability registry (Anthropic API proxy) | `references/capability-registry.md` |

## Config Source

All credentials stored in `~/.sman/config.json` (env: `SMANBASE_HOME`). No `.env` files.

## Notes

- No email, Redis, S3, or other external services found.
- Claude SDK handles LLM API internally via env vars `ANTHROPIC_API_KEY` + `ANTHROPIC_BASE_URL`.
- Three chatbot platforms are mutually exclusive; only one active at a time.
