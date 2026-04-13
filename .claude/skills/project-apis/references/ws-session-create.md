# WS session.create

Create a new chat session bound to a workspace directory.

**Signature:** `session.create` → `{ sessionId: string, workspace: string }`

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `workspace` | string | Yes | Absolute path to project directory |

## Response

`session.created` — `{ sessionId: string, workspace: string }`

## Business Flow

Creates a SQLite session record and an in-memory `ActiveSession`. Triggers async knowledge scan of the workspace. Workspace must exist on disk.

## Source

`server/index.ts` — `case 'session.create'`
Called by: `ClaudeSessionManager.createSession()` in `server/claude-session.ts`
