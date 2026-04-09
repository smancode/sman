# WS session.preheat

## Signature
```
WS message: { type: "session.preheat", sessionId: string }
```

## Business Flow
Fires a non-blocking pre-warming of the session (pre-creates the Claude SDK session so first real message is faster). No response sent to client.

## Called Services
`sessionManager.preheatSession()`

## Source
`server/index.ts`
