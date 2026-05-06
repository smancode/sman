# messages Table

## Purpose
Message history with structured content blocks and streaming progress tracking.

## DDL
```sql
CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL,
  role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
  content TEXT NOT NULL,
  content_blocks TEXT,
  is_partial INTEGER DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);
```

## Columns

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| id | INTEGER | NO | Primary key (auto-increment) |
| session_id | TEXT | NO | Foreign key to sessions.id |
| role | TEXT | NO | Message role: 'user' or 'assistant' |
| content | TEXT | NO | Plain text content |
| content_blocks | TEXT | YES | JSON array of structured blocks (thinking, tool_use, image) |
| is_partial | INTEGER | NO | Streaming progress flag (0 = final, 1 = in-progress) |
| created_at | TEXT | NO | Message creation timestamp |

## Indexes
- `idx_messages_session_id` on session_id

## Foreign Keys
- `session_id` → sessions(id) ON DELETE CASCADE

## Notes
- Content blocks structure: `[{type, text?, thinking?, id?, name?, input?, source?}]`
- Partial messages: temporary during streaming, deleted on completion
- CASCADE delete: messages removed when session deleted
- Migration: added content_blocks and is_partial for V2 streaming

## Source File
`/Users/nasakim/projects/sman/server/session-store.ts`
