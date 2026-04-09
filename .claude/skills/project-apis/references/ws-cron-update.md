# WS cron.update

## Signature
```
WS message: {
  type: "cron.update",
  taskId: string,
  workspace?: string,
  skillName?: string,
  cronExpression?: string,
  enabled?: boolean
}
```

## Business Flow
Updates task in SQLite. Validates `cronExpression` if provided. Re-schedules or unschedules via `CronScheduler`. Broadcasts `cron.changed`.

## Source
`server/index.ts`
