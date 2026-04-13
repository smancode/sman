# WS session.updateLabel

Update the display label of a session.

**Signature:** `session.updateLabel` → `{ sessionId: string, label: string }` → broadcast `session.labelUpdated`

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionId` | string | Yes | Session UUID |
| `label` | string | Yes | New display label |

## Response

Broadcasts `session.labelUpdated` to all clients (not just the sender).

## Business Flow

Updates SQLite `label` column and broadcasts change to all connected clients for live UI sync.

## Source

`server/index.ts` — `case 'session.updateLabel'`
Calls: `store.updateLabel()` in `server/session-store.ts`
