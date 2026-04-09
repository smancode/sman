# WS cron.scan

## Signature
```
WS message: { type: "cron.scan" }
```

## Business Flow
Scans all workspace skills for `crontab.md` files and syncs tasks to SQLite. Removes stale tasks (source=scan, skill no longer exists). Reschedules updated tasks.

## Called Services
`cronScheduler.scanAndSync()` → `CronTaskStore` + `CronScheduler`

## Source
`server/index.ts`
