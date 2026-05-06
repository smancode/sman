# chatbot_sessions Table

## Purpose
Chatbot session mapping: links user_key + workspace to Sman session_id.

## DDL
```sql
CREATE TABLE IF NOT EXISTS chatbot_sessions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_key TEXT NOT NULL,
  workspace TEXT NOT NULL,
  session_id TEXT NOT NULL,
  sdk_session_id TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  last_active_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(user_key, workspace)
);
```

## Columns

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| id | INTEGER | NO | Primary key (auto-increment) |
| user_key | TEXT | NO | Foreign user identifier |
| workspace | TEXT | NO | Project workspace path |
| session_id | TEXT | NO | Sman session ID |
| sdk_session_id | TEXT | YES | Claude SDK session ID |
| created_at | TEXT | NO | Session creation timestamp |
| last_active_at | TEXT | NO | Last activity timestamp |

## Indexes
UNIQUE constraint on (user_key, workspace)

## Foreign Keys
None (logical reference to sessions.id)

## Notes
- One session per (user_key, workspace) pair
- UPSERT pattern: `ON CONFLICT(user_key, workspace) DO UPDATE`
- Enables multi-user chatbot access to shared workspaces

## Source File
`/Users/nasakim/projects/sman/server/chatbot/chatbot-store.ts`
