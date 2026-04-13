# WS batch.save

Mark a batch task as ready to execute (save generated code).

**Signature:** `batch.save` → `{ taskId: string }` → `batch.saved`

## Business Flow

Validates that items exist and the task is in a valid state, then persists the task so it can be executed.

## Source

`server/index.ts` — `case 'batch.save'`
Calls: `batchEngine.save()` in `server/batch-engine.ts`
