# WS cron.delete

## Signature
```
WS message: { type: "cron.delete", taskId: string }
```

## Business Flow
Unschedules from `CronScheduler` and deletes from SQLite. Broadcasts `cron.changed`.

## Source
`server/index.ts`
