# WS cron.list

## Signature
```
WS message: { type: "cron.list" }
```

## Business Flow
Returns all cron tasks with `latestRun` and `nextRunAt` computed. Tasks sourced from SQLite, schedule info from `CronScheduler`.

## Called Services
`cronTaskStore.listTasks()` + `cronScheduler.getNextRunAt()` + `cronTaskStore.getLatestRun()`

## Source
`server/index.ts`
