# WS chat.abort

Abort the currently streaming query for a session.

**Signature:** `chat.abort` → `{ sessionId: string }` → `chat.aborted`

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionId` | string | Yes | Session UUID |

## Business Flow

Calls `AbortController.abort()` on the active stream and attempts `session.interrupt()`. Partial assistant content is saved before abort.

## Source

`server/index.ts` — `case 'chat.abort'`
Calls: `sessionManager.abort()` in `server/claude-session.ts`
