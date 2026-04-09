# WS batch.generate

## Signature
```
WS message: { type: "batch.generate", taskId: string }
```

## Business Flow
Calls `batchEngine.generateCode()` which uses Claude to generate execution code from `mdContent` + `execTemplate`. Returns `{ type: "batch.generated", taskId, code }`.

## Called Services
`batchEngine.generateCode()`

## Source
`server/index.ts`
