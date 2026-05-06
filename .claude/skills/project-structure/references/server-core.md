# Server Core (server/index.ts)

Main backend entry point for Smanbase. Initializes HTTP server, WebSocket server, and all service handlers.

## Responsibilities

- HTTP server on port 5880 (Express-like routing)
- WebSocket server for real-time communication
- Session management via ClaudeSessionManager
- Route handlers for all WebSocket messages
- Static file serving (dev mode only)

## Key Routes (WebSocket)

**Session Management**: `session.create`, `session.list`, `session.delete`, `session.history`, `session.updateLabel`
**Chat**: `chat.send`, `chat.abort`, `chat.start`, `chat.delta`, `chat.tool_start`, `chat.done`, `chat.error`
**Settings**: `settings.get`, `settings.update`, `settings.updateLlm`, `skills.list`
**Cron**: `cron.create`, `cron.list`, `cron.delete`, `cron.update`
**Batch**: `batch.create`, `batch.list`, `batch.delete`, `batch.update`
**Smart Paths**: `smartpath.create`, `smartpath.list`, `smartpath.run`, `smartpath.delete`
**Code Viewer**: `code.listDir`, `code.readFile`, `code.searchSymbols`, `code.saveFile`, `code.searchFiles`
**Git**: `git.status`, `git.diff`, `git.commit`, `git.push`, `git.log`, `git.checkout`, `git.fetch`
**Stardom**: `stardom.connect`, `stardom.disconnect`, `stardom.status`, `stardom.searchAgents`
**Chatbot**: `chatbot.message` (incoming WeCom/Feishu/Weixin messages)

## Project Root Resolution

Resolves project root dynamically for plugin loading:
- Dev mode: `__dirname = server/` → parent has `plugins/`
- Prod mode: `__dirname = dist/server/server/` → great-grandparent
- Electron ASAR: redirects to `app.asar.unpacked/` for plugins

## Important

All WebSocket handlers use `await streamDone` to ensure message ordering (SDK doesn't support concurrent turns).
