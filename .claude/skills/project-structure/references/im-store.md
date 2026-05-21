# IMStore — SQLite Persistence

**Purpose**: Persist IM rooms and messages to SQLite database.

**Schema**:
```sql
CREATE TABLE im_rooms (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL,  -- 'group' | 'dm' | 'workspace'
  members TEXT NOT NULL,  -- JSON array of member IDs
  last_message TEXT,
  last_message_time INTEGER
);

CREATE TABLE im_messages (
  id TEXT PRIMARY KEY,
  room_id TEXT NOT NULL,
  sender TEXT NOT NULL,
  content TEXT NOT NULL,
  mentioned_agents TEXT,  -- JSON array or NULL
  quote_id TEXT,
  type TEXT NOT NULL,  -- 'text' | 'agent_output' | 'system'
  status TEXT,  -- 'running' | 'completed' | 'failed' (for agent_output)
  attachments TEXT,  -- JSON array or NULL
  session_id TEXT,  -- Claude session ID (for agent_output)
  timestamp INTEGER NOT NULL,
  FOREIGN KEY (room_id) REFERENCES im_rooms(id) ON DELETE CASCADE
);
```

**Key Methods**:
- `getRooms(type?)`: List rooms (optional type filter)
- `getRoom(id)`: Get single room by ID
- `createRoom(room)`: Insert new room
- `getMessages(roomId, limit?, before?)`: Get message history (pagination)
- `insertMessage(msg)`: Insert new message
- `updateMessageContent(id, content)`: Update message content
- `updateMessageStatus(id, status)`: Update agent_output status
- `getMessage(id)`: Get single message by ID
- `getRecentRooms(limit?)`: Get rooms sorted by last_message_time

**Cascade Deletes**: Deleting a room cascades to all its messages.

**Timestamps**: All timestamps are Unix milliseconds (Date.now()).
