# SQLite (better-sqlite3)

## Call Method
`better-sqlite3` — synchronous SQLite binding for Node.js. Single `sman.db` file.

## Config Source
- DB path: `~/.sman/sman.db` (default), or `SMANBASE_HOME` env var overrides
- All stores share the same file; WAL mode + foreign keys enabled

## Call Locations
- `server/session-store.ts` — chat sessions + messages (line 49)
- `server/cron-task-store.ts` — cron tasks + run history (line 24)
- `server/batch-store.ts` — batch tasks + items (line 47)
- `server/chatbot/chatbot-store.ts` — chatbot users/sessions/workspaces (line 12)
- `server/web-access/chrome-sites.ts` — read-only Chrome History access (line 97)

## Purpose
All local persistence: sessions, messages, cron/batch task state, chatbot user state, and browser history discovery.
