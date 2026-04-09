# WS cron.runs

## Signature
```
WS message: { type: "cron.runs", taskId: string, limit?: number }
```

## Business Flow
Returns run history for a task (latest first). Default limit: 20.

## Called Services
`cronTaskStore.listRuns()`

## Source
`server/index.ts`
