# WS batch.delete

## Signature
```
WS message: { type: "batch.delete", taskId: string }
```

## Business Flow
Deletes task and all its items from SQLite. Returns `{ type: "batch.deleted", taskId }`.

## Source
`server/index.ts`
