# group_subtasks Table

**Purpose**: Individual subtasks linking sessions to group tasks for cross-workspace execution.

**Source File**: `server/group-store.ts`

## Schema

```sql
CREATE TABLE IF NOT EXISTS group_subtasks (
  id TEXT PRIMARY KEY,
  group_task_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  workspace TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (group_task_id) REFERENCES group_tasks(id) ON DELETE CASCADE
);
```

## Column Details

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| id | TEXT | NO | Subtask unique identifier (PK) |
| group_task_id | TEXT | NO | Parent group task ID (FK → group_tasks.id) |
| session_id | TEXT | NO | Associated session ID (links to sessions.id, no FK constraint) |
| workspace | TEXT | NO | Workspace path for this subtask |
| title | TEXT | NO | Subtask title |
| description | TEXT | YES | Optional subtask description |
| created_at | TEXT | NO | ISO 8601 timestamp |
| updated_at | TEXT | NO | ISO 8601 timestamp |

## Indexes

- `idx_subtasks_task_id` on `group_task_id` - Query subtasks by task
- `idx_subtasks_session_id` on `session_id` - Reverse lookup session → subtask

## Foreign Keys

- `group_task_id` → `group_tasks(id)` with **CASCADE DELETE**
- `session_id` → **NO FK CONSTRAINT** (app-level link to `sessions.id`)

## Relationships

- **Many-to-One** with `group_tasks` (via `group_task_id`)
- **Many-to-One** with `sessions` (via `session_id`, app-level link)

## Application Logic

- Links group tasks to actual execution sessions
- One session can be linked to at most one subtask (unique index on session_id implied by usage)
- Deleting a task cascades to delete all subtasks
- `session_id` does NOT have FK constraint to avoid circular dependency (sessions.parent_task_id references back)
- App-level consistency: `sessions.parent_task_id` should match `group_subtasks.group_task_id`

## Migrations

None (new table in current version)

## ⚠️ Consistency Note

**Dual Linking Pattern**:
- `group_subtasks.session_id` → `sessions.id` (subtask → session)
- `sessions.parent_task_id` → `group_subtasks.group_task_id` (session → parent task)

Both directions must be maintained by application logic. No database-level FK ensures flexibility but requires careful updates.
