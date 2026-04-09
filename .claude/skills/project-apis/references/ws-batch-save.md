# WS batch.save

## Signature
```
WS message: { type: "batch.save", taskId: string }
```

## Business Flow
Writes generated code to disk (`{workspace}/.sman/batch/{taskId}/`). Returns `{ type: "batch.saved", task }`.

## Called Services
`batchEngine.save()`

## Source
`server/index.ts`
