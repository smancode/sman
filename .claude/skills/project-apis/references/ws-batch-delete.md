# WS batch.delete

Delete a batch task and all its items.

**Signature:** `batch.delete` → `{ taskId: string }` → `batch.deleted`

## Business Flow

Deletes task and all associated items from SQLite. Running tasks are cancelled first by the `BatchEngine`.

## Source

`server/index.ts` — `case 'batch.delete'`
Calls: `batchStore.deleteTask()` in `server/batch-store.ts`
