# WS batch.create

## Signature
```
WS message: {
  type: "batch.create",
  workspace: string,
  skillName: string,
  mdContent: string,
  execTemplate: string,
  envVars?: Record<string, string>,
  concurrency?: number,
  retryOnFailure?: number
}
```

## Business Flow
Creates a batch task in SQLite. Does not execute. Returns `{ type: "batch.created", task }`.

## Called Services
`batchStore.createTask()`

## Source
`server/index.ts`
