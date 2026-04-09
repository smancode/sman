# WS batch.pause

## Signature
```
WS message: { type: "batch.pause", taskId: string }
```

## Business Flow
Pauses execution via the semaphore pause signal. Currently running items complete, queued items wait. Returns `batch.paused`.

## Called Services
`batchEngine.pause()`

## Source
`server/index.ts`
