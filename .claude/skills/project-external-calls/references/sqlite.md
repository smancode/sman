# SQLite (better-sqlite3)

## Overview
All persistent state stored in SQLite via better-sqlite3 (synchronous native SQLite bindings for Node.js).

## Call Method
ORM-like synchronous API via better-sqlite3 npm package. No query builder - raw SQL strings.

## Config Source
- Main DB: ~/.sman/sman.db
- Bazaar DB: ~/.sman/bazaar.db
- Both paths configured via SMANBASE_HOME env var (default: ~/.sman)

Source: ~/.sman/config.json (sets data directory)

## Call Locations

| File | Schema |
|------|--------|
| server/session-store.ts | sessions, messages + migrations |
| server/cron-task-store.ts | cron_tasks, cron_runs |
| server/batch-store.ts | batch_tasks, batch_items, batch_env_vars |
| server/chatbot/chatbot-store.ts | chatbot_users, chatbot_sessions, chatbot_workspaces |
| server/chatbot/weixin-store.ts | WeChat bot token + cursor |
| server/bazaar/bazaar-store.ts | Bazaar agent identity, tasks, reputation |
| server/web-access/chrome-sites.ts | Reads Chrome History SQLite for URL discovery |

## Purpose
All persistent storage. Sessions, messages, cron/batch tasks, chatbot state, bazaar state, Chrome browsing history for URL discovery.
