# WS session.delete

Delete a session and abort any active query.

**Signature:** `session.delete` → `{ sessionId: string }` → `session.deleted`

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionId` | string | Yes | Session UUID |

## Business Flow

Aborts any active V2 session stream, then deletes the session record from SQLite. The in-memory `ActiveSession` is also cleared.

## Source

`server/index.ts` — `case 'session.delete'`
Calls: `sessionManager.abort()`, `store.deleteSession()` in `server/session-store.ts`
