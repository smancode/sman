# batch_items Table

## Purpose
Individual batch execution items with status tracking, retry logic, and cost metrics.

## DDL
```sql
CREATE TABLE IF NOT EXISTS batch_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id TEXT NOT NULL,
  item_data TEXT NOT NULL,
  item_index INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending'
    CHECK(status IN ('pending','queued','running','success','failed','skipped')),
  session_id TEXT,
  started_at TEXT,
  finished_at TEXT,
  error_message TEXT,
  cost REAL NOT NULL DEFAULT 0,
  retries INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY (task_id) REFERENCES batch_tasks(id) ON DELETE CASCADE
);
```

## Columns

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| id | INTEGER | NO | Primary key (auto-increment) |
| task_id | TEXT | NO | Foreign key to batch_tasks.id |
| item_data | TEXT | NO | JSON string of item input data |
| item_index | INTEGER | NO | Order within task (0-based) |
| status | TEXT | NO | Execution status |
| session_id | TEXT | YES | Execution session ID (if any) |
| started_at | TEXT | YES | Execution start timestamp |
| finished_at | TEXT | YES | Execution finish timestamp |
| error_message | TEXT | YES | Failure error message |
| cost | REAL | NO | Execution cost |
| retries | INTEGER | NO | Retry attempt count |

## Indexes
- `idx_batch_items_task` on task_id
- `idx_batch_items_status` on (task_id, status)

## Foreign Keys
- `task_id` → batch_tasks(id) ON DELETE CASCADE

## Notes
- Status transitions: pending → queued → running → success/failed/skipped
- Orphan recovery: reset running items to failed on server restart
- Composite index: efficient filtering by task + status
- Cost tracking: sum for total task cost

## Source File
`/Users/nasakim/projects/sman/server/batch-store.ts`
