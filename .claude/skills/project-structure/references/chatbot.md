# server/chatbot/ — Multi-Platform Chatbot Connections

## Purpose
Manages WeCom, 飞书, and Weixin bot connections. Handles message routing, command parsing (`//cd`, `//pwd`, etc.), multi-turn conversation context, and Claude query dispatch.

## Key Files

| File | Purpose |
|---|---|
| `chatbot-session-manager.ts` | Message routing, command parsing, Claude session dispatch |
| `chatbot-store.ts` | SQLite: per-bot user state (current workspace, etc.) |
| `chat-command-parser.ts` | Parse `//cd`, `//pwd`, `//help`, `//status`, `//wss` |
| `wecom-bot-connection.ts` | WeCom Bot WS connection (heartbeat, reconnect, stream push) |
| `feishu-bot-connection.ts` | 飞书 Bot SDK event listener |
| `weixin-bot-connection.ts` | Weixin Bot connection handler |
| `weixin-api.ts` | Weixin API helpers |
| `weixin-store.ts` | Weixin-specific SQLite state |
| `weixin-types.ts` | Weixin type definitions |
| `wecom-media.ts` | WeCom media upload/download |
| `types.ts` | Shared chatbot types |

## Command System
- `//cd <项目名或路径>` — switch workspace (supports `~`, numeric index)
- `//pwd` — show current workspace
- `//workspaces` / `//wss` — list open projects
- `//status` / `//sts` — connection status
- `//help` — help message

## Dependencies
- `server/claude-session.ts` for query dispatch
- `server/session-store.ts` for session persistence
- `@larksuiteoapi/node-sdk` for 飞书
- `ws` for WeCom WebSocket
- Stores state in `chatbot-store.ts` (SQLite)
