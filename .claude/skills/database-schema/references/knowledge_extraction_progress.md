# knowledge_extraction_progress Table

## Purpose
Incremental knowledge extraction tracking per workspace+session.

## DDL
```sql
CREATE TABLE IF NOT EXISTS knowledge_extraction_progress (
  workspace TEXT NOT NULL,
  session_id TEXT NOT NULL,
  last_extracted_message_id INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (workspace, session_id)
);
```

## Columns

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| workspace | TEXT | NO | Project workspace path |
| session_id | TEXT | NO | Session identifier |
| last_extracted_message_id | INTEGER | NO | Last processed message ID |
| updated_at | TEXT | NO | Last extraction timestamp |

## Indexes
PRIMARY KEY (workspace, session_id)

## Foreign Keys
None (logical reference to sessions)

## Notes
- Composite primary key: one row per (workspace, session)
- UPSERT pattern: `ON CONFLICT(workspace, session_id) DO UPDATE`
- Enables incremental extraction: only process new messages
- Triggered on session deletion for knowledge cleanup

## Source File
`/Users/nasakim/projects/sman/server/knowledge-extractor-store.ts`
