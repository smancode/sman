# WS batch.pause

Pause a running batch task (stops after current item completes).

**Signature:** `batch.pause` → `{ taskId: string }` → `batch.paused`

## Business Flow

Sets the semaphore to 0, preventing new item starts. Current in-flight executions complete normally. Semaphore state is managed by `Semaphore` class.

## Source

`server/index.ts` — `case 'batch.pause'`
Calls: `batchEngine.pause()` in `server/batch-engine.ts`
