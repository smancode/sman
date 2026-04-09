# WS batch.test

## Signature
```
WS message: { type: "batch.test", taskId: string }
```

## Business Flow
Runs the generated code against a single item (dry run). Returns test result with `{ type: "batch.tested", taskId, ...result }`.

## Called Services
`batchEngine.testCode()`

## Source
`server/index.ts`
