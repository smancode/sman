# server/ — Backend (Node.js, TypeScript, ESM)

## Purpose
Express HTTP + WebSocket server; handles all backend logic: Claude Agent SDK sessions, chatbot Bot connections, cron/batch task scheduling, settings management, and SQLite persistence.

## Key Files

| File | Purpose |
|---|---|
| `server/index.ts` | Entry point: HTTP server, WebSocket upgrade, route/handler registration |
| `server/claude-session.ts` | SDK V2 session lifecycle (create, resume, idle cleanup, streaming) |
| `server/session-store.ts` | SQLite: sessions + messages CRUD |
| `server/settings-manager.ts` | `~/.sman/config.json` read/write (LLM, WebSearch, Chatbot, Auth) |
| `server/skills-registry.ts` | Skills load from global + project dirs, dispatch by name |
| `server/mcp-config.ts` | Web Search MCP auto-config (brave/tavily/bing/builtin) |
| `server/types.ts` | Shared TypeScript types (SmanConfig, CronTask, BatchTask, etc.) |
| `server/user-profile.ts` | User profile management |
| `server/model-capabilities.ts` | Model capability registry |

### Chatbot submodule (`server/chatbot/`)
| File | Purpose |
|---|---|
| `chatbot-session-manager.ts` | Bot message routing, command parsing, Claude query dispatch |
| `chatbot-store.ts` | SQLite: per-bot user state (current workspace, etc.) |
| `chat-command-parser.ts` | Parse `//cd`, `//pwd`, `//help` etc. |
| `wecom-bot-connection.ts` | WeCom Bot WebSocket (heartbeat, reconnect, stream push) |
| `feishu-bot-connection.ts` | 飞书 Bot SDK event listener |
| `weixin-bot-connection.ts` | Weixin Bot connection |
| `weixin-api.ts` | Weixin API helpers |
| `wecom-media.ts` | WeCom media upload/download |
| `types.ts` | Chatbot shared types |

### Web Access submodule (`server/web-access/`) — see references/web-access.md

### Cron submodule (in server root)
| File | Purpose |
|---|---|
| `cron-scheduler.ts` | `node-cron` + `cron-parser`; schedules tasks |
| `cron-executor.ts` | Runs cron task via Claude session (SDK V2) |
| `cron-task-store.ts` | SQLite: cron tasks CRUD |
| `batch-engine.ts` | Batch task executor (semaphore concurrency) |
| `batch-store.ts` | SQLite: batch tasks CRUD |
| `batch-utils.ts` | Batch task utility functions |
| `semaphore.ts` | Concurrency control: pause/resume/cancel |

## Dependencies
- `server/` imports `server/chatbot/`, `server/web-access/`, `server/utils/`
- Uses `better-sqlite3` for persistence
- Uses `@anthropic-ai/claude-agent-sdk` for AI sessions
- Express + `ws` for HTTP/WebSocket server
