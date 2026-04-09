# WS batch.list

## Signature
```
WS message: { type: "batch.list" }
```

## Business Flow
Returns all batch tasks from SQLite with current status (pending, running, paused, completed, failed).

## Called Services
`batchStore.listTasks()`

## Source
`server/index.ts`
