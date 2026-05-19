# chatbot_sessions Table

**Purpose**: Chatbot session mapping: links user_key + workspace to Sman session_id with multi-bot support, chat type tracking, and idle session management.

**Source File**: `server/chatbot/chatbot-store.ts`

## Schema

```sql
CREATE TABLE IF NOT EXISTS chatbot_sessions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_key TEXT NOT NULL,
  workspace TEXT NOT NULL,
  session_id TEXT NOT NULL,
  sdk_session_id TEXT,
  bot_label TEXT,
  chat_type TEXT NOT NULL DEFAULT 'single',
  idle_reset INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  last_active_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(user_key, workspace)
);
```

## Column Details

| Name | Type | Nullable | Description |
|------|------|----------|-------------|
| id | INTEGER | NO | Primary key (auto-increment) |
| user_key | TEXT | NO | Foreign user identifier (format: `wecom:{botProfileId}:{userId}`) |
| workspace | TEXT | NO | Project workspace path |
| session_id | TEXT | NO | Sman session ID |
| sdk_session_id | TEXT | YES | Claude SDK session ID |
| bot_label | TEXT | YES | Bot profile label for multi-bot identification |
| chat_type | TEXT | NO | Chat mode: 'single' or 'group' |
| idle_reset | INTEGER | NO | Flag for idle reset notification (0=reset, 1=pending) |
| created_at | TEXT | NO | Session creation timestamp |
| last_active_at | TEXT | NO | Last activity timestamp |

## Indexes

UNIQUE constraint on (user_key, workspace)

## Foreign Keys

None (logical reference to sessions.id)

## Relationships

- **Many-to-One** with `chatbot_users` (via `user_key`)
- **Many-to-One** with `chatbot_workspaces` (via `workspace`)
- **Logical Link** to `sessions.id` (via `session_id`)

## Application Logic

- **One Session Per Pair**: UNIQUE constraint ensures one session per (user_key, workspace)
- **UPSERT Pattern**: `ON CONFLICT(user_key, workspace) DO UPDATE SET ...`
- **Chat Types**:
  - `'single'`: Private chat (1:1 user-bot)
  - `'group'`: Group chat (multi-user channel)
- **Idle Management**:
  - `idle_reset = 1`: Marked for reset notification (user hasn't chatted in threshold time)
  - `consumeIdleReset()`: Clears flag and returns true if any session had flag set
  - `getIdleSessions()`: Queries sessions older than threshold minutes
- **Multi-User Support**: Enables multiple users to access shared workspaces through chatbots
- **Bot Session Isolation**: `user_key` format `wecom:{botProfileId}:{userId}` ensures per-bot, per-user isolation

## Migrations

**MIGRATION REQUIRED** (runtime):
```sql
ALTER TABLE chatbot_sessions ADD COLUMN bot_label TEXT;
ALTER TABLE chatbot_sessions ADD COLUMN chat_type TEXT NOT NULL DEFAULT 'single';
ALTER TABLE chatbot_sessions ADD COLUMN idle_reset INTEGER NOT NULL DEFAULT 0;
```

**Backward Compatibility**: ✅ Safe (defaults provided)

**Migration Order**: 
1. `bot_label` (existing, added earlier)
2. `chat_type` (new, default 'single')
3. `idle_reset` (new, default 0)
