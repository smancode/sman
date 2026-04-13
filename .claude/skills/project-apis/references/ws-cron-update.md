# WS cron.update

Update an existing cron task's fields.

**Signature:** `cron.update` → `{ taskId, workspace?, skillName?, cronExpression?, enabled? }` → `cron.updated`

## Business Flow

Updates SQLite record. If `enabled` changed, schedules or unschedules from the `CronScheduler`. Cron expression is validated before update.

## Source

`server/index.ts` — `case 'cron.update'`
Calls: `cronTaskStore.updateTask()`, `cronScheduler.schedule()/unschedule()`
