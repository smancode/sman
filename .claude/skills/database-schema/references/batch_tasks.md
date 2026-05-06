# batch_tasks Table

## Purpose
Batch processing task definitions for AI skill automation with code generation and retry logic.

## DDL
```sql
CREATE TABLE IF NOT EXISTS batch_tasks (
  id TEXT PRIMARY KEY,
  workspace TEXT NOT NULL,
  skill_name TEXT NOT NULL,
  md_content TEXT NOT NULL,
  exec_template TEXT NOT NULL,
  generated_code TEXT,
  env_vars TEXT NOT NULL DEFAULT '{}',
  concurrency INTEGER NOT NULL DEFAULT 10,
  retry_on_failure INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'draft'
    CHECK(status IN ('draft','generating','generated','testing','tested','saved','queued','running','paused','completed','failed')),
  total_items INTEGER NOT NULL DEFAULT 0,
  success_count INTEGER NOT NULL DEFAULT 0,
  failed_count INTEGER NOT NULL DEFAULT 0,
  total_cost REAL NOT NULL DEFAULT 0,
  started_at TEXT,
  finished_at TEXT,
  cron_enabled INTEGER NOT NULL DEFAULT 0,
  cron_interval_minutes INTEGER,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
```

## Columns

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| id | TEXT | NO | Primary key (UUID) |
| workspace | TEXT | NO | Project workspace path |
| skill_name | TEXT | NO | Target skill name |
| md_content | TEXT | NO | Markdown content (task description) |
| exec_template | TEXT | NO | Execution template string |
| generated_code | TEXT | YES | AI-generated execution code |
| env_vars | TEXT | NO | JSON object of environment variables |
| concurrency | INTEGER | NO | Max concurrent item executions |
| retry_on_failure | INTEGER | NO | Retry attempts per failed item |
| status | TEXT | NO | Task status (draft → generated → running → completed) |
| total_items | INTEGER | NO | Total number of items |
| success_count | INTEGER | NO | Successfully executed items |
| failed_count | INTEGER | NO | Failed items count |
| total_cost | REAL | NO | Total execution cost |
| started_at | TEXT | YES | Execution start timestamp |
| finished_at | TEXT | YES | Execution finish timestamp |
| cron_enabled | INTEGER | NO | Scheduled execution flag (0/1) |
| cron_interval_minutes | INTEGER | YES | Cron interval in minutes |
| created_at | TEXT | NO | Task creation timestamp |
| updated_at | TEXT | NO | Last update timestamp |

## Indexes
None (primary key lookup only)

## Foreign Keys
None

## Notes
- Status lifecycle: draft → generating → generated → testing → tested → saved → queued → running → completed/failed
- env_vars stored as JSON string (TODO: AES-256-GCM encryption planned)
- Counter increments: `success_count++`, `failed_count++` on item completion
- Retry logic: batch items track individual retry attempts

## Source File
`/Users/nasakim/projects/sman/server/batch-store.ts`
