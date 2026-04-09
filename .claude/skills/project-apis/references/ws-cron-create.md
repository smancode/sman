# WS cron.create

## Signature
```
WS message: {
  type: "cron.create",
  workspace: string,
  skillName: string,
  cronExpression: string,
  source?: "manual"
}
```

## Business Flow
Validates cron expression via `cron-parser`. Creates task in SQLite. Schedules via `CronScheduler.schedule()`. Broadcasts `cron.changed` to all clients.

## Called Services
`CronExpressionParser.parse()` (validation) + `cronTaskStore.createTask()` + `cronScheduler.schedule()`

## Source
`server/index.ts`
