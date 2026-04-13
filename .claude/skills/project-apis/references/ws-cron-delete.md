# WS cron.delete

Delete a cron task and remove it from the scheduler.

**Signature:** `cron.delete` → `{ taskId: string }` → `cron.deleted`

## Business Flow

Calls `cronScheduler.unschedule()` then `cronTaskStore.deleteTask()`. Broadcasts `cron.changed`.

## Source

`server/index.ts` — `case 'cron.delete'`
