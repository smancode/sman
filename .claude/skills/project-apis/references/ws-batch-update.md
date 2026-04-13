# WS batch.update

Update fields of an existing batch task.

**Signature:** `batch.update` → `{ taskId, ...partialTaskFields }` → `batch.updated`

## Business Flow

Partial update — only provided fields are updated in SQLite.

## Source

`server/index.ts` — `case 'batch.update'`
Calls: `batchStore.updateTask()` in `server/batch-store.ts`
