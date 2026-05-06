# chatbot_users Table

## Purpose
Chatbot user state management for workspace switching and activity tracking.

## DDL
```sql
CREATE TABLE IF NOT EXISTS chatbot_users (
  user_key TEXT PRIMARY KEY,
  current_workspace TEXT NOT NULL,
  last_active_at TEXT NOT NULL DEFAULT (datetime('now'))
);
```

## Columns

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| user_key | TEXT | NO | Primary key (user identifier: WeCom userId, etc.) |
| current_workspace | TEXT | NO | Current active workspace path |
| last_active_at | TEXT | NO | Last activity timestamp |

## Indexes
None (primary key lookup)

## Foreign Keys
None

## Notes
- UPSERT pattern: `ON CONFLICT(user_key) DO UPDATE`
- Workspace switching: updates current_workspace
- Used by WeCom, Feishu, WeChat bots

## Source File
`/Users/nasakim/projects/sman/server/chatbot/chatbot-store.ts`
