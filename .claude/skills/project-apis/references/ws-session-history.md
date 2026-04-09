# WS session.history

## Signature
```
WS message: { type: "session.history", sessionId: string }
```

## Business Flow
Returns `{ type: "session.history", sessionId, messages: [...] }` containing the full message history for the session. Used when resuming a session.

## Called Services
`sessionManager.getHistory()` → `store.getMessages()`

## Source
`server/index.ts`
