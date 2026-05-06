# Claude Session Manager (server/claude-session.ts)

Manages V2 SDK sessions with lifecycle control, idle timeout, and crash recovery.

## Core Features

**Session Reuse**: Keeps SDK process alive between messages (efficient)
**Idle Timeout**: 30min inactivity → auto-close process
**Crash Recovery**: Detects dead processes and recreates
**Resume Support**: Persists SDK session_id for post-restart recovery

## Key Functions

- `normalizeWorkspacePath(path)`: Resolves symlinks, gets OS-level canonical path
- `createSession()`: Creates new V2 session with MCP servers
- `getSession()`: Returns existing session (creates if needed)
- `closeSession()`: Gracefully closes SDK process
- `sendMessage()`: Sends user message, streams response
- `abortTurn()`: Interrupts in-progress turn

## MCP Servers Injected

- Web Search: Brave, Tavily, Baidu, Anthropic builtin
- Web Access: Chrome DevTools Protocol (9 tools)
- Capability Gateway: Dynamic capability loading
- Workspace: File system access within workspace

## Project Root Resolution

Handles 3 scenarios for plugin path resolution:
1. Dev mode (`tsx`): direct relative path
2. Prod mode (compiled): traverse up from `dist/server/server/`
3. Electron ASAR: redirect to `app.asar.unpacked/plugins/`

## Important

SDK sessions are keyed by `(workspacePath, profileId)` tuple. Different LLM profiles use separate sessions.
