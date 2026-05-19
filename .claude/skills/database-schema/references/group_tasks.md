# group_tasks Table

**Purpose**: Task definitions within groups with auto-dispatch and status tracking.

**Source File**: `server/group-store.ts`

## Schema

```sql
CREATE TABLE IF NOT EXISTS group_tasks (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  auto_dispatch INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'draft',
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE
);
```

## Column Details

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| id | TEXT | NO | Task unique identifier (PK) |
| group_id | TEXT | NO | Parent group ID (FK → groups.id) |
| title | TEXT | NO | Task title/name |
| description | TEXT | YES | Optional task description |
| auto_dispatch | INTEGER | NO | Auto-dispatch flag (0=disabled, 1=enabled) |
| status | TEXT | NO | Task status ('draft', 'active', 'completed', etc.) |
| created_at | TEXT | NO | ISO 8601 timestamp |
| updated_at | TEXT | NO | ISO 8601 timestamp (auto-updated on modify) |

## Indexes

- `idx_group_tasks_group_id` on `group_id` - Query tasks by group
- `idx_group_tasks_status` on `status` - Filter tasks by status

## Foreign Keys

- `group_id` → `groups(id)` with **CASCADE DELETE**

## Relationships

- **Many-to-One** with `groups` (via `group_id`)
- **One-to-Many** with `group_subtasks` (via FK `group_task_id` with CASCADE delete)

## Application Logic

- `auto_dispatch`: 0 = manual dispatch, 1 = automatic dispatch to subtasks
- Deleting a group cascades to delete all tasks
- Deleting a task cascades to delete all subtasks
- `updated_at` auto-updated on status change

## Migrations

**MIGRATION REQUIRED** (runtime):
1. `auto_dispatch` column added with default 0
2. `status` column added with default 'draft'

```sql
ALTER TABLE group_tasks ADD COLUMN auto_dispatch INTEGER NOT NULL DEFAULT 0;
ALTER TABLE group_tasks ADD COLUMN status TEXT NOT NULL DEFAULT 'draft';
```

**Backward Compatibility**: ✅ Safe (defaults provided)
