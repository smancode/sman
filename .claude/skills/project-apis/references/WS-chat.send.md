# `chat.send` WebSocket Endpoint

## Signature
```
Client → Server: { type: 'chat.send', sessionId: string, content?: string, media?: MediaAttachment[], autoConfirm?: boolean }
Server → Client: chat.start, chat.delta, chat.done/aborted/error
```

## Request Parameters
- `sessionId` (string, required): Session identifier
- `content` (string, optional): User message text
- `media` (MediaAttachment[], optional): Image/media attachments
- `autoConfirm` (boolean, optional): Enable AUTO mode for tool confirmations

## Business Flow
1. Client sends message with content/media
2. Server validates session exists
3. SessionManager queues message (executes serially per session)
4. Sends `chat.start` to all subscribed clients
5. Streams response via `chat.delta` (text/thinking/tool_use)
6. Sends `chat.done` with token usage/cost or `chat.aborted`/`chat.error`

## Called Services
- `ClaudeSessionManager.sendMessage()`: Core message processing
- `SessionStore`: Token usage tracking
- `KnowledgeExtractor`: Async knowledge extraction
- `UserProfileManager`: Async user behavior learning

## Source File
`server/index.ts:862-892`
