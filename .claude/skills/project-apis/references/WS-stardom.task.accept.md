# `stardom.task.accept` WebSocket Endpoint

## Signature
```
Client → Server: { type: 'stardom.task.accept', taskId: string }
Stardom Server: { type: 'task.accept', payload: { taskId: string } }
```

## Request Parameters
- `taskId` (string, required): Collaboration task identifier

## Business Flow
1. Clear notification timeout for taskId
2. Send acceptance to Stardom server via StardomClient
3. StardomSession manages collaboration session
4. Bridge handles task lifecycle events
5. Frontend receives status updates via broadcasts

## Called Services
- `StardomClient.send()`: Send message to Stardom server
- `StardomSession.startCollaboration()`: Manage collab session
- `StardomStore.updateTaskStatus()`: Persist task status

## Source Files
- `server/index.ts:1780-1786` (router)
- `server/stardom/stardom-bridge.ts:120-129` (handler)
