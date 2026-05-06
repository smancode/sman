# cron_tasks Table

## Purpose
Cron task definitions for scheduled skill execution with workspace-scoped configuration.

## DDL
```sql
CREATE TABLE IF NOT EXISTS cron_tasks (
  id TEXT PRIMARY KEY,
  workspace TEXT NOT NULL,
  skill_name TEXT NOT NULL,
  cron_expression TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT 'manual',
  enabled INTEGER NOT NULL DEFAULT 1,
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
| cron_expression | TEXT | NO | Cron expression (e.g., "0 9 * * 1-5") |
| source | TEXT | NO | Creation source: 'manual' or 'scan' |
| enabled | INTEGER | NO | Active flag (0 = disabled, 1 = enabled) |
| created_at | TEXT | NO | Task creation timestamp |
| updated_at | TEXT | NO | Last update timestamp |

## Indexes
None (primary key + workspace queries)

## Foreign Keys
None

## Notes
- Unique constraint: (workspace, skill_name) enforced at application layer
- Cron validation: uses `cron-parser` library
- Migration: dropped `interval_minutes` column (old format)
- Source tracking: 'scan' for auto-discovered crontab.md files

## Source File
`/Users/nasakim/projects/sman/server/cron-task-store.ts`
