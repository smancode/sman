# SQLite (local)

## Call Method
better-sqlite3 — synchronous SQLite driver, ESM interop

## Config Source
- Database path: hardcoded ~/.sman/sman.db (passed as homeDir + '/sman.db')
- No dynamic path config; always at ~/.sman/sman.db

## Call Locations
| File | Purpose |
|------|---------|
| server/session-store.ts | Session + message storage |
| server/cron-task-store.ts | Cron task + run storage |
| server/batch-store.ts | Batch task + item storage |
| server/chatbot/chatbot-store.ts | Chatbot user state storage |
| server/stardom/stardom-store.ts | Stardom agent + task registry |
| server/web-access/chrome-sites.ts | Chrome bookmark/history DB (read-only) |

## Purpose
Local SQLite DB for all persistent state: sessions, messages, cron/batch tasks,
chatbot sessions, Stardom registry, and Chrome browser data (bookmarks/history read-only).
