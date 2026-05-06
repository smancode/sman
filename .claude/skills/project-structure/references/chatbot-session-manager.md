# Chatbot Session Manager (server/chatbot/chatbot-session-manager.ts)

Routes incoming bot messages to appropriate SDK sessions.

## Supported Platforms

- **WeCom (企业微信)**: WebSocket connection, heartbeat, media download
- **Feishu (飞书)**: HTTP webhook, event verification
- **Weixin (微信)**: HTTP polling/message queue

## Message Flow

1. Bot receives message from platform
2. Parse command (e.g., `//cd`, `//pwd`, `//help`)
3. For commands: execute directly
4. For chat: find/create session for user
5. Send to SDK, stream response back
6. Post response to platform (handle markdown, images)

## Command Parser

Supported commands:
- `//cd <path>`: Change workspace
- `//pwd`: Show current workspace
- `//workspaces`: List available workspaces
- `//status`: Show connection status
- `//help`: Show help

## Session Mapping

Maps external user IDs to Sman sessions:
- WeCom: `wecom-{userId}`
- Feishu: `feishu-{userId}`
- Weixin: `weixin-{userId}`

## Important

Bot connections are stateful. Must handle reconnects, heartbeats, and error recovery.
