# `im_messages` Table

**Purpose**: Real-time messaging system for multi-agent collaboration (IM rooms)

**Schema**:
```sql
CREATE TABLE IF NOT EXISTS im_messages (
  id TEXT PRIMARY KEY,
  room_id TEXT NOT NULL,            -- FK: im_rooms.id
  sender TEXT NOT NULL,
  content TEXT NOT NULL,
  mentioned_agents TEXT,            -- JSON array of agent IDs
  quote_id TEXT,                    -- Reply to message ID
  type TEXT NOT NULL DEFAULT 'text',-- 'text' | 'agent_output' | 'system'
  status TEXT DEFAULT NULL,         -- 'running' | 'completed' | 'failed'
  attachments TEXT,                 -- JSON array
  session_id TEXT,                  -- Optional link to chat session
  timestamp INTEGER NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'localtime')),
  updated_at DATETIME DEFAULT (datetime('now', 'localtime'))
);
```

**Indexes**:
- `idx_im_messages_room_ts` ON `(room_id, timestamp)` - Room timeline queries
- `idx_im_messages_sender` ON `(sender)` - Agent-specific message retrieval
- `idx_im_messages_session` ON `(session_id)` - Session-linked messages

**Relationships**:
- Belongs to `im_rooms` (room_id)
- Optional link to `sessions` (session_id)

**Use Cases**:
- Multi-agent chat in collaboration rooms
- Agent output streaming with status updates
- Message threading via quote_id
- @mentions for agent coordination

**Store**: `server/im/im-store.ts` (IMStore class)
