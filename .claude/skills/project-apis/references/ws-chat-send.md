# WS chat.send

Send a user message and receive a streaming response.

**Signature:** `chat.send` → `{ sessionId: string, content: string, media?: MediaAttachment[] }`

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionId` | string | Yes | Session UUID |
| `content` | string | Yes | Message text |
| `media` | MediaAttachment[] | No | Optional image/audio attachments |

## Response (streamed)

| Event | Description |
|-------|-------------|
| `chat.start` | Response begins |
| `chat.delta` | Text or thinking delta |
| `chat.tool_start` | Tool call begins |
| `chat.tool_delta` | Tool input delta |
| `chat.tool_result` | Tool output |
| `chat.done` | Complete (includes cost, usage) |
| `chat.error` | Error occurred |
| `chat.aborted` | Aborted mid-stream |

## Business Flow

Stores user message in SQLite, creates/retrieves V2 SDK session, injects user profile context, streams events back via WebSocket. If client disconnects mid-stream, falls back to broadcast.

## Source

`server/index.ts` — `case 'chat.send'`
Calls: `sessionManager.sendMessage()` in `server/claude-session.ts`
