# sessions Table

## Purpose
Chat session management with workspace tracking, token usage, and soft delete support.

## DDL
```sql
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  system_id TEXT NOT NULL,
  workspace TEXT NOT NULL,
  label TEXT,
  sdk_session_id TEXT,
  is_cron INTEGER DEFAULT 0,
  deleted_at TEXT DEFAULT NULL,
  input_tokens INTEGER DEFAULT 0,
  output_tokens INTEGER DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  last_active_at TEXT NOT NULL DEFAULT (datetime('now'))
);
```

## Columns

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| id | TEXT | NO | Primary key (UUID) |
| system_id | TEXT | NO | System identifier (deprecated, equals workspace) |
| workspace | TEXT | NO | Project workspace path |
| label | TEXT | YES | User-defined session label |
| sdk_session_id | TEXT | YES | Claude SDK session ID (V2) |
| is_cron | INTEGER | NO | Flag for cron-triggered sessions (0/1) |
| deleted_at | TEXT | YES | Soft delete timestamp (NULL = active) |
| input_tokens | INTEGER | NO | Total input tokens consumed |
| output_tokens | INTEGER | NO | Total output tokens consumed |
| created_at | TEXT | NO | Session creation timestamp |
| last_active_at | TEXT | NO | Last activity timestamp |

## Indexes
- `idx_sessions_system_id` on system_id
- `idx_sessions_deleted_at` on deleted_at

## Foreign Keys
None

## Notes
- Soft delete pattern: `deleted_at IS NULL` for active sessions
- Token tracking for cost monitoring and analytics
- Cron sessions excluded from UI list (`is_cron = 0` filter)
- Migration: added columns incrementally (label, sdk_session_id, is_cron, deleted_at, token usage)

## Source File
`/Users/nasakim/projects/sman/server/session-store.ts`
