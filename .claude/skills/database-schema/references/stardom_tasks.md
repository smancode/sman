# stardom_tasks Table

## Purpose
Multi-agent task collaboration tracking (helper ↔ requester interactions).

## DDL
```sql
CREATE TABLE IF NOT EXISTS tasks (
  task_id TEXT PRIMARY KEY,
  direction TEXT NOT NULL,
  helper_agent_id TEXT,
  helper_name TEXT,
  requester_agent_id TEXT,
  requester_name TEXT,
  question TEXT NOT NULL,
  status TEXT NOT NULL,
  rating INTEGER,
  created_at TEXT NOT NULL,
  completed_at TEXT
);
```

## Columns

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| task_id | TEXT | NO | Primary key (UUID) |
| direction | TEXT | NO | Task direction: 'help-wanted' or 'offer-help' |
| helper_agent_id | TEXT | YES | Helper agent identifier |
| helper_name | TEXT | YES | Helper agent display name |
| requester_agent_id | TEXT | YES | Requester agent identifier |
| requester_name | TEXT | YES | Requester agent display name |
| question | TEXT | NO | Task description/question |
| status | TEXT | NO | Task status (chatting, matched, completed, failed) |
| rating | INTEGER | YES | Collaboration rating (1-5) |
| created_at | TEXT | NO | Task creation timestamp |
| completed_at | TEXT | YES | Task completion timestamp |

## Indexes
None (primary key lookup)

## Foreign Keys
None

## Notes
- Agent matchmaking: helper/requester pairing
- Rating system: post-task quality feedback
- Active tasks: `status IN ('chatting', 'matched')`

## Source File
`/Users/nasakim/projects/sman/server/stardom/stardom-store.ts`
