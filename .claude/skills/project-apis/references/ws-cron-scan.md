# WS cron.scan

Scan all workspaces for skills with `crontab.md` and sync to the database.

**Signature:** `cron.scan` → `cron.scanned` with sync results

## Business Flow

1. Lists all workspaces from sessions
2. Scans each `{workspace}/.claude/skills/` for `crontab.md`
3. Creates missing tasks, updates expressions, removes orphaned tasks
4. Returns summary: `added`, `updated`, `removed` counts

## Source

`server/index.ts` — `case 'cron.scan'`
Calls: `cronScheduler.scanAndSync()` in `server/cron-scheduler.ts`
