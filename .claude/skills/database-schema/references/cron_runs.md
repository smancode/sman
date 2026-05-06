# cron_runs Table

## Purpose
Cron execution records with status tracking, timing, and error logging.

## DDL
```sql
CREATE TABLE IF NOT EXISTS cron_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('running', 'success', 'failed')),
  started_at TEXT NOT NULL,
  finished_at TEXT,
  last_activity_at TEXT,
  error_message TEXT,
  FOREIGN KEY (task_id) REFERENCES cron_tasks(id) ON DELETE CASCADE
);
```

## Columns

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| id | INTEGER | NO | Primary key (auto-increment) |
| task_id | TEXT | NO | Foreign key to cron_tasks.id |
| session_id | TEXT | NO | Execution session ID |
| status | TEXT | NO | Execution status |
| started_at | TEXT | NO | Execution start timestamp |
| finished_at | TEXT | YES | Execution finish timestamp |
| last_activity_at | TEXT | YES | Last heartbeat timestamp |
| error_message | TEXT | YES | Failure error message |

## Indexes
- `idx_cron_runs_task` on task_id
- `idx_cron_runs_started` on started_at DESC

## Foreign Keys
- `task_id` → cron_tasks(id) ON DELETE CASCADE

## Notes
- Status lifecycle: running → success/failed
- Heartbeat: `last_activity_at` updated during execution
- CASCADE delete: runs removed when task deleted
- DESC index: efficient "latest runs" queries

## Source File
`/Users/nasakim/projects/sman/server/cron-task-store.ts`
