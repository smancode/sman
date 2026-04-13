# WS batch.execute

Execute a batch task (runs in background, progress via `batch.progress` events).

**Signature:** `batch.execute` → `{ taskId: string }` → `batch.started` immediately, `batch.completed` when done

## Business Flow

Runs the batch asynchronously via `batchEngine.execute()`. Progress events (`batch.progress`) are broadcast to all clients. On completion, `batch.completed` is broadcast.

## Source

`server/index.ts` — `case 'batch.execute'`
Calls: `batchEngine.execute()` in `server/batch-engine.ts`
