# WS cron.list

List all cron tasks with next run time and latest run info.

**Signature:** `cron.list` → `cron.list` with tasks + metadata

## Business Flow

Reads from `CronTaskStore`, enriches each task with `getLatestRun()` and `getNextRunAt()` from the scheduler.

## Source

`server/index.ts` — `case 'cron.list'`
Calls: `cronTaskStore.listTasks()`, `cronScheduler.getNextRunAt()` in `server/cron-scheduler.ts`
