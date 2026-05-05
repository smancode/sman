# Session Store

**Purpose**: SQLite storage for sessions and messages

## Key Files
- `server/session-store.ts` — SQLite database operations

## Responsibilities
1. **Session CRUD**
   - Create session (workspace, label)
   - Update session (label)
   - Delete session
   - List sessions (grouped by workspace)
   - Get session by ID

2. **Message Storage**
   - Append message (user/assistant/tool_use)
   - List messages (by session_id)
   - Stream messages (cursor-based)

3. **Schema**
   - `sessions` table: id, workspace, label, created_at, updated_at, sdk_session_id
   - `messages` table: id, session_id, role, content, created_at, tool_use_id, tool_name

## Dependencies
- better-sqlite3
- Database path: `~/.sman/sman.db`

## Notes
- ESM module (uses `path.dirname(fileURLToPath(import.meta.url))` instead of `__dirname`)
- Prepared statements for performance
- Transaction support for batch operations
