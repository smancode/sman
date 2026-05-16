# Hub Client Reference

> Multi-agent collaboration client for connecting to Hub server

## Purpose
Manages HTTP/WebSocket connection to Hub server for multi-agent collaboration, room management, and task distribution.

## Key Files
- **server/hub/client.ts**: `HubClient` - HTTP client for room/agent/task APIs
- **server/hub/hub-ws-client.ts**: `HubWsClient` - WebSocket for real-time updates
- **server/hub/types.ts**: Core types (HubConfig, ReportPayload, BroadcastMessage)
- **server/hub/task-worker.ts**: Async task execution worker
- **server/hub/crypto.ts**: PSK encryption for secure communication
- **server/hub/evaluation-handler.ts**: Skill evaluation triggering
- **server/hub/init-reader.ts**: INIT.md parsing for workspace context

## Main Interfaces

### HubConfig
```typescript
interface HubConfig {
  serverUrl: string;
  enabled: boolean;
}
```

### HubClient Methods
- `report(payload: ReportPayload)`: Report server status
- `queryBroadcasts(payload: BroadcastQueryPayload)`: Get broadcast messages
- `ackBroadcasts(payload: AckPayload)`: Acknowledge received broadcasts
- `createRoom(...)`: Create collaboration room
- `joinRoom(roomId, password?)`: Join existing room
- `leaveRoom(roomId)`: Leave room
- `dissolveRoom(roomId)`: Dissolve room (owner only)
- `listRooms(...)`: List available rooms
- `listAgents(roomId)`: List agents in room
- `triggerSkill(...)`: Trigger skill execution on Hub

## Dependencies
- **server/server-url.ts**: Dynamic port detection
- **server/utils/network.ts**: Client ID generation
- **server/settings-manager.ts**: Hub configuration storage
- **@tanstack/react-query**: Frontend query hooks (src/queries/use-hub.ts)

## Integration Points
- **server/index.ts**: Hub initialization on server start
- **server/broadcast-store.ts**: Broadcast message storage
- **src/features/hub/**: Frontend Hub dashboard UI
