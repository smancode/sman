# WS batch.get

Get a single batch task by ID.

**Signature:** `batch.get` → `{ taskId: string }` → `batch.get` with full task

## Business Flow

Retrieves full task record including `mdContent`, `execTemplate`, `envVars`.

## Source

`server/index.ts` — `case 'batch.get'`
Calls: `batchStore.getTask()` in `server/batch-store.ts`
