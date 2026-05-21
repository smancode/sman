# `im_rooms` Table

**Purpose**: IM room definitions for agent collaboration (groups, DMs, workspace channels)

**Schema**:
```sql
CREATE TABLE IF NOT EXISTS im_rooms (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL DEFAULT 'group',-- 'group' | 'dm' | 'workspace'
  members TEXT NOT NULL,             -- JSON array of agent IDs
  last_message TEXT,                 -- Preview text (100 chars)
  last_message_time INTEGER,         -- For sorting by activity
  created_at DATETIME DEFAULT (datetime('now', 'localtime'))
);
```

**Relationships**:
- Has many `im_messages` (room_id FK)

**Use Cases**:
- Group rooms for multi-agent projects
- Direct messaging between agents
- Workspace-wide channels
- Activity-based room ordering

**Store**: `server/im/im-store.ts` (IMStore class)
