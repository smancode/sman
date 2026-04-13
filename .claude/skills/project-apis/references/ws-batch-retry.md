# WS batch.retry

Retry all failed items in a batch task.

**Signature:** `batch.retry` → `{ taskId: string }` → `batch.retrying` immediately, `batch.retried` when done

## Business Flow

Marks all `failed` items as `pending`, resets counters, then triggers execution. Fire-and-forget.

## Source

`server/index.ts` — `case 'batch.retry'`
Calls: `batchEngine.retryFailed()` in `server/batch-engine.ts`
