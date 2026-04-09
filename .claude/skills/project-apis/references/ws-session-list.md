# WS session.list

## Signature
```
WS message: { type: "session.list" }
```

## Business Flow
Returns `{ type: "session.list", sessions: [{ id, workspace, label, createdAt, lastActiveAt }] }`. Reads all sessions from SQLite, returns summary fields (no message history).

## Called Services
`sessionManager.listSessions()` → `store.listSessions()`

## Source
`server/index.ts`
