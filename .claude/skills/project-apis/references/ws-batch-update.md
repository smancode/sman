# WS batch.update

## Signature
```
WS message: { type: "batch.update", taskId: string, ...updates }
```

## Business Flow
Partial update of task fields in SQLite. Returns `{ type: "batch.updated", task }`.

## Source
`server/index.ts`
