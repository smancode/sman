# Session Store (server/session-store.ts)

SQLite-based storage for sessions and messages using `better-sqlite3`.

## Schema

**sessions table**: `id, systemId, workspace, label, isCron, deletedAt, createdAt, lastActiveAt`
**messages table**: `id, sessionId, role, content, contentBlocks, isPartial, createdAt`

## Key Methods

- `createSession()`: Insert new session row
- `getSession()`: Retrieve session by ID
- `listSessions()`: List all non-deleted sessions
- `deleteSession()`: Soft-delete (sets deletedAt)
- `updateSessionLabel()`: Update session label
- `addMessage()`: Insert message row
- `getMessages()`: Retrieve messages for session
- `updateLastActive()`: Update lastActiveAt timestamp

## Content Blocks

Messages store `contentBlocks` array for structured content:
- `text`: Plain text blocks
- `thinking`: Model thinking blocks
- `tool_use`: Tool invocation blocks
- `image`: Image attachments
- `attached_file`: File attachments

## Database Location

`~/.sman/sman.db` (SQLite file)

## Important

Uses `better-sqlite3` for synchronous queries (safe for single-threaded Node.js).
