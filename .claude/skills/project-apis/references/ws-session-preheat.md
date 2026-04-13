# WS session.preheat

Preheat (pre-create) a V2 session process without sending a message.

**Signature:** `session.preheat` → `{ sessionId: string }` (fire-and-forget)

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionId` | string | Yes | Session UUID |

## Business Flow

Called when user starts typing to pre-warm the Claude V2 process. Reduces perceived latency when the message is actually sent. Errors are swallowed.

## Source

`server/index.ts` — `case 'session.preheat'`
Calls: `sessionManager.preheatSession()` in `server/claude-session.ts`
