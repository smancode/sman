# WS batch.resume

Resume a paused batch task.

**Signature:** `batch.resume` → `{ taskId: string }` → `batch.resumed`

## Business Flow

Restores the semaphore to the task's concurrency limit, allowing new item executions to start.

## Source

`server/index.ts` — `case 'batch.resume'`
Calls: `batchEngine.resume()` in `server/batch-engine.ts`
