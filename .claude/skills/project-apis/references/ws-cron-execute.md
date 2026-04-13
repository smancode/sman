# WS cron.execute

Trigger immediate execution of a cron task (fire-and-forget).

**Signature:** `cron.execute` → `{ taskId: string }` → `cron.executed` immediately, then async run

## Business Flow

Returns `cron.executed` immediately, then calls `cronScheduler.executeNow()` in background. Errors are logged but not sent to client (use `cron.runs` to check result).

## Source

`server/index.ts` — `case 'cron.execute'`
Calls: `cronScheduler.executeNow()` in `server/cron-executor.ts`
