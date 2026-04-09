# WS batch.get

## Signature
```
WS message: { type: "batch.get", taskId: string }
```

## Business Flow
Returns full task details including `mdContent`, `execTemplate`, `envVars`, `concurrency`, `retryOnFailure`.

## Called Services
`batchStore.getTask()`

## Source
`server/index.ts`
