# groups Table

**Purpose**: Multi-workspace group management with JSON workspace IDs and status tracking.

**Source File**: `server/group-store.ts`

## Schema

```sql
CREATE TABLE IF NOT EXISTS groups (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  workspace_ids TEXT NOT NULL DEFAULT '[]',
  status TEXT NOT NULL DEFAULT 'active',
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
```

## Column Details

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| id | TEXT | NO | Group unique identifier (PK) |
| name | TEXT | NO | Group display name |
| workspace_ids | TEXT | NO | JSON array of workspace paths (e.g., '["/path/ws1", "/path/ws2"]') |
| status | TEXT | NO | Group status ('active', 'archived', etc.) |
| created_at | TEXT | NO | ISO 8601 timestamp |
| updated_at | TEXT | NO | ISO 8601 timestamp (auto-updated on modify) |

## Indexes

- `idx_groups_status` on `status` - Filter groups by status

## Foreign Keys

None (root table)

## Relationships

- **One-to-Many** with `group_tasks` (via FK `group_id` with CASCADE delete)
- **File System**: `~/.sman/group/{id}/CLAUude.md` template initialization

## Application Logic

- `workspace_ids` stored as JSON string, parsed in application layer
- Group directory auto-created at `~/.sman/group/{groupId}/` with CLAUDE.md template
- `updated_at` auto-updated on any modification
- Deleting a group cascades to all tasks and subtasks

## Migrations

None (new table in current version)
