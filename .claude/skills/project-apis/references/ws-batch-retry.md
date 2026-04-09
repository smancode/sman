# WS batch.retry

## Signature
```
WS message: { type: "batch.retry", taskId: string }
```

## Business Flow
Resets all failed items to `pending` and re-queues them. Completion broadcasts `batch.retried`. Returns `batch.retrying` immediately.

## Called Services
`batchEngine.retryFailed()`

## Source
`server/index.ts`
