# WS chat.send

## Signature
```
WS message: { type: "chat.send", sessionId: string, content: string, media?: MediaAttachment[] }
```

## Request Parameters
| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionId` | string | Yes | Target session |
| `content` | string | Yes | Message text |
| `media` | array | No | Media attachments (image, audio, video) |

## Business Flow
Sends a user message to the Claude session. Streams response back via `chat.start`, `chat.delta`, `chat.done`, `chat.error`. Supports media attachments for multimodal models.

## Called Services
`sessionManager.sendMessage()` → Claude Agent SDK → streams via WebSocket `wsSend`

## Source
`server/index.ts` — `chat.send` handler.
