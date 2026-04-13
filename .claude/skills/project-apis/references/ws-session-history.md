# WS session.history

Retrieve full message history for a session.

**Signature:** `session.history` → `{ sessionId: string }` → `session.history` with messages

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionId` | string | Yes | Session UUID |

## Response

```json
{
  "sessionId": "uuid",
  "messages": [
    { "role": "user", "content": "...", "createdAt": "ISO8601", "contentBlocks": [...] }
  ]
}
```

## Business Flow

Loads messages from SQLite for display in the chat UI. Messages include content blocks for thinking/tool_use blocks.

## Source

`server/index.ts` — `case 'session.history'`
Calls: `sessionManager.getHistory()` in `server/claude-session.ts`
