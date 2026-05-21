# IMWsHandler — WebSocket Events

**Purpose**: Handle IM-related WebSocket messages from local clients and Hub.

## Local Client Messages

| Type | Direction | Params | Response |
|------|-----------|--------|----------|
| `im.send` | Client→Server | roomId, content, mentionedAgents?, quoteId? | `im.message` (broadcast to room) |
| `im.history` | Client→Server | roomId, limit?, before? | `im.history` (message array) |
| `im.sync` | Client→Server | (request full sync) | `im.sync` (rooms + messages) |
| `im.typing` | Client→Server | roomId, isTyping | `im.typing` (broadcast to room) |

## Hub Integration Messages

| Type | Direction | Data |
|------|-----------|------|
| `im.message` | Hub→Server | Full message object (inserted to store) |
| `im.agent_delta` | Hub→Server | Streaming chunk (data: { messageId, agentId, content }) |

## Key Methods

- `handleLocalMessage(msg, ws, clientInfo)`: Route local client messages
- `handleHubMessage(msg)`: Route Hub messages
- `setAgentBridge(bridge)`: Inject IMAgentBridge for @mention handling

## Broadcast Events

- `im.message`: New message (all room members via Hub or local broadcast)
- `im.agent_delta`: Agent streaming chunk (all room members)
- `im.typing`: Typing indicator (all room members except sender)

## Error Handling

Missing required params (roomId, content) → `im.error` response to sender only.

## Sender Identification

Sender ID is derived from `clientInfo.clientId` (format: `{clientId}/{agentDisplayId}` for agent outputs).
