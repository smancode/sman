# sessions Table

**Purpose**: Chat session management with workspace tracking, parent task linking, token usage, and soft delete support.

**Source File**: `server/session-store.ts`

## Schema

```sql
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  system_id TEXT NOT NULL,
  workspace TEXT NOT NULL,
  label TEXT,
  sdk_session_id TEXT,
  is_cron INTEGER DEFAULT 0,
  parent_task_id TEXT DEFAULT NULL,
  deleted_at TEXT DEFAULT NULL,
  input_tokens INTEGER DEFAULT 0,
  output_tokens INTEGER DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  last_active_at TEXT NOT NULL DEFAULT (datetime('now'))
);
```

## Column Details

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| id | TEXT | NO | Primary key (UUID) |
| system_id | TEXT | NO | System identifier (deprecated, equals workspace) |
| workspace | TEXT | NO | Project workspace path |
| label | TEXT | YES | User-defined session label |
| sdk_session_id | TEXT | YES | Claude SDK session ID (V2) |
| is_cron | INTEGER | NO | Flag for cron-triggered sessions (0/1) |
| parent_task_id | TEXT | YES | Parent group task ID (links to group_tasks.id) |
| deleted_at | TEXT | YES | Soft delete timestamp (NULL = active) |
| input_tokens | INTEGER | NO | Total input tokens consumed |
| output_tokens | INTEGER | NO | Total output tokens consumed |
| created_at | TEXT | NO | Session creation timestamp |
| last_active_at | TEXT | NO | Last activity timestamp |

## Indexes

- `idx_sessions_system_id` on system_id
- `idx_sessions_deleted_at` on deleted_at

## Foreign Keys

None (app-level reference to `group_tasks.id` via `parent_task_id`)

## Relationships

- **Many-to-One** with `group_tasks` (via `parent_task_id`, app-level link)
- **One-to-Many** with `messages` (via FK `session_id` with CASCADE delete)
- **Reverse Link** from `group_subtasks.session_id` (app-level consistency)

## Application Logic

- **Soft Delete Pattern**: `deleted_at IS NULL` for active sessions
- **Token Tracking**: Cost monitoring and analytics
- **Group Task Sessions**:
  - `listSessions()` excludes sessions with workspace starting with `~/.sman/group/`
  - `parent_task_id` links to `group_tasks.id` when session is part of a group task
  - Dual link maintained with `group_subtasks.session_id` (bidirectional consistency)
- **Cron Sessions**: Excluded from UI list (`is_cron = 0` filter)
- **SDK Session ID**: Links to Claude SDK V2 sessions, can be cleared via `clearSdkSessionId()`

## Migrations

**MIGRATION REQUIRED** (runtime):
```sql
ALTER TABLE sessions ADD COLUMN parent_task_id TEXT DEFAULT NULL;
```

**Backward Compatibility**: ✅ Safe (nullable, defaults to NULL)

**⚠️ BEHAVIOR CHANGE**: `listSessions()` now excludes group task workspaces (`~/.sman/group/%`). This may affect queries expecting all non-cron sessions.
