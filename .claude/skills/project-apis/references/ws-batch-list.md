# WS batch.list

List all batch tasks.

**Signature:** `batch.list` → `batch.list` with task summaries

## Business Flow

Returns all batch tasks from `BatchStore`. Does not include per-item details.

## Source

`server/index.ts` — `case 'batch.list'`
Calls: `batchStore.listTasks()` in `server/batch-store.ts`
