# WS session.delete

## Signature
```
WS message: { type: "session.delete", sessionId: string }
```

## Business Flow
Aborts any in-progress response for the session, then deletes it from SQLite. Returns `{ type: "session.deleted", sessionId }`.

## Called Services
`sessionManager.abort()` + `store.deleteSession()`

## Source
`server/index.ts`
