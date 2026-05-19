# Group Store Reference

> Multi-workspace collaboration management with SQLite persistence

## Purpose
Enable users to create groups containing multiple project workspaces, assign tasks to groups, and track subtasks per session.

## Key Changes (v26.520.0)
**NEW FEATURE**: Group workspace management system:
1. Multi-workspace grouping: Combine related projects into a single collaboration space
2. Task assignment: Create tasks at group level with auto-dispatch capability
3. Subtask tracking: Link group tasks to specific chat sessions for execution
4. SQLite persistence: Three-table schema with foreign key cascade deletes

## Architecture
```
GroupStore (SQLite)
├── groups: Group metadata (id, name, workspace_ids[])
├── group_tasks: Task metadata (id, group_id, title, auto_dispatch)
└── group_subtasks: Session linkage (id, group_task_id, session_id, workspace)

Frontend (Zustand)
├── src/stores/group.ts: Group state management
├── src/components/CreateGroupDialog.tsx: Multi-workspace selector
└── src/components/GroupItem.tsx: Group tree UI
```

## Data Schema

### groups Table
```sql
CREATE TABLE groups (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  workspace_ids TEXT NOT NULL DEFAULT '[]',  -- JSON array
  status TEXT NOT NULL DEFAULT 'active',
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
```

### group_tasks Table
```sql
CREATE TABLE group_tasks (
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

### group_subtasks Table
```sql
CREATE TABLE group_subtasks (
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

## Key Files
- **server/group-store.ts**: SQLite store with CRUD operations
- **src/stores/group.ts**: Zustand state (groups, tasks, subtasks)
- **src/components/CreateGroupDialog.tsx**: Group creation UI with workspace selector
- **src/components/GroupItem.tsx**: Group tree item with expand/collapse
- **src/features/hub/**: Group workspace management feature module

## API Methods
- `createGroup(input)`: Create group with workspace list
- `listGroups(status?)`: List all/active groups
- `updateGroup(id, updates)`: Update name/workspaces/status
- `deleteGroup(id)`: Delete group (cascade deletes tasks)
- `createGroupTask(input)`: Create task under group
- `listGroupTasks(groupId)`: List all tasks for group
- `updateGroupTaskStatus(id, status)`: Update task status
- `deleteGroupTask(id)`: Delete task (cascade deletes subtasks)
- `createSubtask(input)`: Link task to session
- `listSubtasks(groupTaskId)`: List subtasks for task
- `getSubtaskBySessionId(sessionId)`: Find subtask by session

## Design Decisions
- **JSON storage**: `workspace_ids` stored as JSON string in SQLite (simpler than junction table)
- **Cascade deletes**: Deleting group → deletes tasks → deletes subtasks
- **Isolation**: Group sessions use same chatbot session manager but separate data paths
- **Directory structure**: Group workspace at `~/.sman/group/{groupId}/CLAUDE.md`
