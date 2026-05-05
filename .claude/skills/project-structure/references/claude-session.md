# Claude Session Management

**Purpose**: Claude Agent SDK V2 session lifecycle management (create, resume, idle cleanup, streaming, message queuing)

## Key Files
- `server/claude-session.ts` — Main session manager

## Responsibilities
1. **Session Lifecycle**
   - Create new V2 sessions (with SDK session ID persistence)
   - Resume existing sessions from SQLite (process restart support)
   - Idle timeout cleanup (default 30 min inactivity)

2. **Message Processing**
   - Send messages to Claude (with preheating/context preparation)
   - Stream responses (text/thinking/tool_use deltas)
   - Message queuing (await `streamDone` before next message to prevent interruption)

3. **State Management**
   - Session persistence to SQLite (`session-store.ts`)
   - Resume capability (SDK session_id stored in DB)
   - Turn isolation (each turn is independent)

4. **Capabilities Integration**
   - Auto-inject MCP servers (Web Search, Web Access, Gateway)
   - Auto-inject matched capabilities
   - Auto-inject user profile

## Dependencies
- `@anthropic-ai/claude-agent-sdk` v0.2
- `@anthropic-ai/claude-code` v2.1
- `session-store.ts` (SQLite)
- `settings-manager.ts` (LLM config)
- `skills-registry.ts` (skills loading)

## Key Methods
- `getOrCreateV2Session(workspace, sessionId)` — Get or create session
- `sendMessage(sessionId, content)` — Send message with streaming
- `resumeSession(sessionId)` — Resume from SDK session_id

## Notes
- SDK does NOT support interrupting turns, hence message queuing
- Session ID format: `{workspace UUID}-{session UUID}`
- Idle cleanup: 30 min default (configurable via `CLAUDE_SESSION_IDLE_TIMEOUT`)
