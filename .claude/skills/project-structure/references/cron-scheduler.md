# Cron Scheduler (server/cron-scheduler.ts)

Schedules and executes recurring tasks using cron expressions.

## Cron Format

Standard 5-field cron: `minute hour day-of-month month day-of-week`
Example: `0 9 * * 1-5` = 9am weekdays

## Key Methods

- `schedule()`: Register new cron job
- `unschedule()`: Remove job by ID
- `listJobs()`: List all active jobs
- `updateJob()`: Modify existing job

## Persistence

Jobs stored in SQLite via `CronTaskStore`. Survives server restarts.

## Execution

- Uses `node-cron` for scheduling
- Each job creates new SDK session for execution
- Results logged to `~/.sman/logs/cron-{date}.log`

## Session Isolation

Cron jobs use isolated sessions (`isCron: true` flag). Don't appear in main session list.

## Important

Cron jobs run in server context, not tied to any desktop UI. Can run offline.
