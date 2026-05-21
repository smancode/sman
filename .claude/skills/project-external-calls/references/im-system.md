# IM System (SQLite + Hub)

## Call Method
- **Storage**: better-sqlite3 (local SQLite DB)
- **Sync**: Hub WebSocket (cross-device message broadcasting)

## Config Source
- Database path: ~/.sman/sman.db (shared with other tables)
- Tables: 2 (im_messages, im_rooms)
- WAL mode + foreign keys enabled
- Hub connection: server/hub/index.ts (initHub)

## Call Locations
| File | Purpose |
|------|---------|
| server/im/im-store.ts | IM message + room persistence (SQLite) |
| server/im/im-ws-handler.ts | Local WS message handling + Hub forwarding |
| server/im/im-agent-bridge.ts | Agent @mention activation (Claude session bridge) |

## Schema

### im_messages
```sql
CREATE TABLE im_messages (
  id TEXT PRIMARY KEY,
  room_id TEXT NOT NULL,
  sender TEXT NOT NULL,
  content TEXT NOT NULL,
  mentioned_agents TEXT,          -- JSON array of agent IDs
  quote_id TEXT,                  -- Reply-to message ID
  type TEXT NOT NULL DEFAULT 'text',  -- 'text' | 'agent_output' | 'system'
  status TEXT,                    -- 'running' | 'completed' | 'failed'
  attachments TEXT,               -- JSON array
  session_id TEXT,                -- Agent session ID
  timestamp INTEGER NOT NULL,
  created_at DATETIME DEFAULT (datetime('now','localtime')),
  updated_at DATETIME DEFAULT (datetime('now','localtime'))
);
CREATE INDEX idx_im_messages_room_ts ON im_messages(room_id, timestamp);
CREATE INDEX idx_im_messages_sender ON im_messages(sender);
CREATE INDEX idx_im_messages_session ON im_messages(session_id);
```

### im_rooms
```sql
CREATE TABLE im_rooms (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL DEFAULT 'group',  -- 'group' | 'dm' | 'workspace'
  members TEXT NOT NULL,               -- JSON array of client IDs
  last_message TEXT,
  last_message_time INTEGER
);
```

## Purpose
Local SQLite DB for Instant Messaging: group chat, DM, workspace channels, and Agent @mention activation.

## Agent @mention Flow
1. User sends message with `@agent` mention
2. `IMWsHandler.handleSend()` stores message → broadcasts locally
3. Forwards to Hub via `HubWsClient.send({ type: 'im.message', data })`
4. `IMAgentBridge.handleMention()` filters owned agents
5. For each agent:
   - Create `agent_output` message (status=running)
   - Create/reuse Claude session via callback
   - Stream response, broadcast deltas (`im.agent_delta`)
   - Update message (status=completed/failed)

## External Integration
- **Hub Broadcast**: All `im.*` messages forwarded to Hub for cross-device sync
- **Hub Receive**: `HubWsClient` listens for `im.message` from other clients
- **Agent Sessions**: Fully decoupled via injected callbacks (no direct ClaudeSessionManager dep)

## Key Features
- **Real-time Sync**: Hub WS broadcasts messages to all connected devices
- **Agent Collaboration**: @mention triggers local Agent sessions (workspace-scoped)
- **Status Tracking**: Agent output messages track execution status (running/completed/failed)
- **Message Threading**: Support for quote/reply with `quote_id`
- **Streaming**: Agent output streamed via `im.agent_delta` messages
