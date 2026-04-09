# WS batch.cancel

## Signature
```
WS message: { type: "batch.cancel", taskId: string }
```

## Business Flow
Stops a running batch and marks remaining items as cancelled. Returns `batch.cancelled`.

## Called Services
`batchEngine.cancel()`

## Source
`server/index.ts`
