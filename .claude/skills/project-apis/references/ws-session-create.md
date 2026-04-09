# WS session.create

## Signature
```
WS message: { type: "session.create", workspace: string }
```

## Request Parameters
| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `workspace` | string | Yes | Absolute path to workspace directory |
| `sessionId` | string | — | Returned in response |

## Business Flow
Creates a new Claude session linked to `workspace`. Persists to SQLite via `SessionStore`. Triggers an async knowledge scan if needed. Returns `{ type: "session.created", sessionId, workspace }`.

## Called Services
`sessionManager.createSession()` → `store.createSession()` (SQLite)

## Source
`server/index.ts` — WebSocket `session.create` handler.
