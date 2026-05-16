# stardom_cached_results Table

## Purpose
Cache async task results for delivery when requester comes back online.

## DDL
```sql
CREATE TABLE IF NOT EXISTS cached_results (
  task_id TEXT PRIMARY KEY,
  result_text TEXT NOT NULL,
  from_agent TEXT NOT NULL,
  cached_at TEXT NOT NULL DEFAULT (datetime('now'))
)
```

## Columns

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| task_id | TEXT | NO | Task identifier (primary key) |
| result_text | TEXT | NO | Task result content |
| from_agent | TEXT | NO | Agent who completed the task |
| cached_at | TEXT | NO | Cache timestamp |

## Indexes
None (direct lookups by task_id)

## Foreign Keys
None

## Notes
- Used for async task delivery in distributed agent network
- Deleted after successful delivery to requester
- `INSERT OR REPLACE` allows cache updates
- Prevents data loss when requester disconnects during execution

## Source File
`/Users/nasakim/projects/sman/server/stardom/stardom-store.ts`
