# stardom_pair_history Table

## Purpose
Track agent collaboration history for reputation and rating analytics.

## DDL
```sql
CREATE TABLE IF NOT EXISTS pair_history (
  partner_id TEXT NOT NULL,
  partner_name TEXT NOT NULL,
  task_count INTEGER DEFAULT 1,
  total_rating REAL DEFAULT 0,
  avg_rating REAL DEFAULT 0,
  last_collaborated_at TEXT NOT NULL,
  PRIMARY KEY (partner_id)
)
```

## Columns

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| partner_id | TEXT | NO | Agent identifier (primary key) |
| partner_name | TEXT | NO | Agent display name |
| task_count | INTEGER | NO | Total collaboration tasks |
| total_rating | REAL | NO | Sum of all ratings |
| avg_rating | REAL | NO | Average rating (total_rating / task_count) |
| last_collaborated_at | TEXT | NO | Last collaboration timestamp |

## Indexes
None (single-row lookups by partner_id)

## Foreign Keys
None

## Notes
- Updated incrementally after each task completion
- Used for leader board and agent ranking
- Rounded to 1 decimal place: `Math.round((total / count) * 10) / 10`
- Sorted by `avg_rating DESC` for leader board display

## Source File
`/Users/nasakim/projects/sman/server/stardom/stardom-store.ts`
