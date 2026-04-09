# WS batch.resume

## Signature
```
WS message: { type: "batch.resume", taskId: string }
```

## Business Flow
Resumes a paused batch execution. Returns `batch.resumed`.

## Called Services
`batchEngine.resume()`

## Source
`server/index.ts`
