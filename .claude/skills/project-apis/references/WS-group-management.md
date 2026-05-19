# Group Management WebSocket APIs

> Multi-workspace task management system for coordinating work across projects.

## Database Schema

```sql
CREATE TABLE groups (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  workspace_ids TEXT NOT NULL DEFAULT '[]',  -- JSON array
  status TEXT NOT NULL DEFAULT 'active',
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE group_tasks (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  auto_dispatch INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'draft',
  FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE
);

CREATE TABLE group_subtasks (
  id TEXT PRIMARY KEY,
  group_task_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  workspace TEXT NOT NULL,
  title TEXT NOT NULL,
  FOREIGN KEY (group_task_id) REFERENCES group_tasks(id) ON DELETE CASCADE
);
```

## Group Operations

### `group.create` / `group.created`
```json
// Request
{ "type": "group.create", "groupId": "string", "name": "string", "workspaceIds": ["string"] }

// Response
{ "type": "group.created", "group": { /* group object */ } }
```

### `group.list` → `{ "type": "group.list", "groups": [...] }`
### `group.update` → `{ "type": "group.updated", "group": {...} }`
### `group.delete` → `{ "type": "group.deleted", "groupId": "string" }`

## Task Operations

### `group-task.create` / `group-task.created`
```json
// Request
{ "type": "group-task.create", "groupId": "string", "title": "string", "description?: "string", "autoDispatch?: 0|1 }

// Response
{ "type": "group-task.created", "taskId": "string", "sessionId": "string" }
```

**Side Effects:** Creates Claude session with `parentTaskId = taskId`

### `group-task.list` → `{ "type": "group-task.list", "groupId": "string", "tasks": [...] }`
### `group-task.delete` → `{ "type": "group-task.deleted", "taskId": "string" }`

### `group-task.dispatch` / `group-task.dispatched`
```json
// Request
{ "type": "group-task.dispatch", "taskId": "string", "subtasks": [{ "id", "sessionId", "workspace", "title", "description?" }] }

// Response
{ "type": "group-task.dispatched", "taskId": "string", "subtasks": ["string"] }
```

## Subtask Operations

### `group-subtask.list` → `{ "type": "group-subtask.list", "taskId": "string", "subtasks": [...] }`

## Risk Analysis

### ⚠️ MISSING: Subtask Execution Events
**Issue:** No progress events for subtask execution. Frontend cannot track completion.

**Fix:** Add `group-subtask.started`, `group-subtask.completed`, `group-subtask.failed` events.

### ⚠️ POTENTIAL: Session Orphaning
**Issue:** Deleting group task doesn't delete associated session from `sessions` table.

**Impact:** Orphaned sessions with broken `parentTaskId` references.

### ✅ SAFE: Cascade Delete
Foreign key constraints ensure database integrity on group deletion.

## Integration

- **Session Store Linkage**: `sessions.parent_task_id` → `group_tasks.id`
- **Frontend Display**: Session list shows `parentTaskId` field
- **Chatbot Integration**: Bot sessions can create group tasks spanning multiple workspaces
