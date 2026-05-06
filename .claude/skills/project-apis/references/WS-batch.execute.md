# `batch.execute` WebSocket Endpoint

## Signature
```
Client → Server: { type: 'batch.execute', batchId: string }
Server → Client: { type: 'batch.progress', taskId: string, status: string, progress: number }
```

## Request Parameters
- `batchId` (string, required): Batch task identifier

## Business Flow
1. Validate batchId exists
2. BatchEngine executes all items in batch
3. For each item: create session, run skill, capture output
4. Stream progress via `batch.progress` broadcasts
5. Update item status (pending/running/completed/failed)
6. Final status when all items complete

## Called Services
- `BatchEngine.executeTask()`: Execute batch items
- `ClaudeSessionManager.createTempSession()`: Ephemeral sessions
- `SkillsRegistry.runSkill()`: Execute skill logic
- `BatchStore.updateItem()`: Persist item status

## Source File
`server/index.ts:1262-1280`
