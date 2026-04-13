# WS batch.items

List items within a batch task with optional status filter and pagination.

**Signature:** `batch.items` → `{ taskId, status?, offset?, limit? }` → `batch.items`

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `taskId` | string | Yes | Batch task UUID |
| `status` | string | No | Filter: `pending`, `running`, `success`, `failed`, `skipped` |
| `offset` | number | No | Pagination offset |
| `limit` | number | No | Page size |

## Business Flow

Returns batch items from `BatchStore` with execution results.

## Source

`server/index.ts` — `case 'batch.items'`
Calls: `batchStore.listItems()` in `server/batch-store.ts`
