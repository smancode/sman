# WS batch.execute

## Signature
```
WS message: { type: "batch.execute", taskId: string }
```

## Business Flow
Starts batch execution in the background. Progress streamed via `batch.progress` events. Completion broadcast via `batch.completed`. Immediate `batch.started` response.

## Called Services
`batchEngine.execute()` — semaphore-controlled concurrency, background execution

## Source
`server/index.ts`
