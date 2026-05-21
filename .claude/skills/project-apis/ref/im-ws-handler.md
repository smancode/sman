# IM WebSocket Handler Reference

**File**: `server/im/im-ws-handler.ts`

## Architecture

```
Client WS → IMWsHandler.handleLocalMessage()
              ├─ im.send      → handleSend()
              ├─ im.history   → handleHistory()
              ├─ im.sync      → handleSync()
              └─ im.typing    → handleTyping()

Hub WS → IMWsHandler.handleHubMessage()
            └─ im.message → imStore.insertMessage()

IMWsHandler → IMAgentBridge.handleMention()
                  → createOrGetSession() → streamSessionMessage()
                  → broadcastToRoom() → im.message (WS)
```

## Message Types

### Client → Server

| Type | Params | Response | Description |
|------|--------|----------|-------------|
| `im.send` | roomId, content, mentionedAgents[]?, quoteId? | `im.sent` | Send message to room |
| `im.history` | roomId, before?, limit=50 | `im.history` | Get message history |
| `im.sync` | roomId, afterTimestamp | `im.sync` | Sync messages after timestamp |
| `im.typing` | roomId | `im.typing` (broadcast) | Typing indicator |

### Server → Client

| Type | Data | Description |
|------|------|-------------|
| `im.sent` | IMMessage | Message sent confirmation |
| `im.message` | IMMessage | Broadcast to all room subscribers |
| `im.history` | { roomId, messages[] } | Message history response |
| `im.sync` | { roomId, messages[] } | Synced messages |
| `im.typing` | { roomId, sender } | Typing broadcast (exclude sender) |
| `im.error` | { error } | Error response |

## IMMessage Structure

```typescript
{
  id: string;              // UUID
  roomId: string;
  sender: string;          // clientId
  content: string;
  mentionedAgents: string[];  // agent display IDs
  quoteId?: string;        // reply to message ID
  type: 'text';
  timestamp: number;       // milliseconds
}
```

## Agent Mention Flow

1. User sends message with `@agentId` in `mentionedAgents[]`
2. `IMWsHandler.handleSend()` stores message in DB
3. Fire-and-forget: `IMAgentBridge.handleMention(imMsg)`
4. Bridge creates ephemeral session for agent's workspace
5. Streams response via `streamSessionMessage()`
6. Each delta broadcast to room via `im.message`

## Hub Sync

All `im.message` events forwarded to Hub WebSocket:
```typescript
sendToHub({ type: 'im.message', data: imMsg })
```

Hub broadcasts to all connected devices (multi-device sync).

## Error Handling

- Missing `roomId` or `content` → `im.error` (no message stored)
- IM not initialized → `im.error` (module not loaded)

## Database

- `im_rooms`: { id, name, type, members[], lastMessage, lastMessageAt }
- `im_messages`: { id, roomId, sender, content, mentionedAgents[], quoteId, type, timestamp }

## REST Endpoints

- `GET /api/im/rooms` - List all rooms
- `POST /api/im/rooms` - Create room ({ name, type?, members[]? })

**Validation**: Only `name` required, `type` defaults to 'group'
