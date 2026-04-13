# WS batch.cancel

Cancel a running or paused batch task.

**Signature:** `batch.cancel` → `{ taskId: string }` → `batch.cancelled`

## Business Flow

Aborts all in-flight executions and marks the task as cancelled. No further items will run.

## Source

`server/index.ts` — `case 'batch.cancel'`
Calls: `batchEngine.cancel()` in `server/batch-engine.ts`
