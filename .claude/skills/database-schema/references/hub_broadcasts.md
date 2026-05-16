# hub_broadcasts Table

## Purpose
Local cache of hub broadcast messages for offline viewing and notification display.

## DDL
```sql
CREATE TABLE IF NOT EXISTS hub_broadcasts (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  created_at TEXT NOT NULL
)
```

## Columns

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| id | TEXT | NO | Primary key (broadcast ID from hub server) |
| title | TEXT | NO | Broadcast message title |
| body | TEXT | NO | Broadcast message body (markdown supported) |
| created_at | TEXT | NO | Broadcast creation timestamp |

## Indexes
None (small table, full scan acceptable)

## Foreign Keys
None

## Notes
- Used for offline notification display in desktop UI
- Synced from remote hub server via `/api/broadcasts` endpoint
- `INSERT OR IGNORE` prevents duplicate storage
- Local cache, can be safely deleted and re-synced

## Source File
`/Users/nasakim/projects/sman/server/broadcast-store.ts`
