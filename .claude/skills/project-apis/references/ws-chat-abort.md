# WS chat.abort

## Signature
```
WS message: { type: "chat.abort", sessionId: string }
```

## Business Flow
Aborts the in-progress Claude SDK streaming response. Sends `chat.aborted` back to client.

## Called Services
`sessionManager.abort()`

## Source
`server/index.ts`
