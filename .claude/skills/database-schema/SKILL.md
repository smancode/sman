---
id: database-schema
name: 数据库全景知识
description: Database schema knowledge (incremental scan). Core tables, relationships, and index strategies.
category: database
_scanned:
  commitHash: "1ddac60bf3f5dbec4ced87ea1a0b7b680267f41c"
  scannedAt: "2026-05-19T00:00:00.000Z"
  branch: "master"
---

# Database Overview

SQLite (better-sqlite3) at `~/.sman/sman.db` with WAL mode. 18+ tables across 8 stores.

**Key Relationships**: Sessions→Messages (CASCADE), Batch→Items (CASCADE), Cron→Runs (CASCADE), Chatbot→Bot Profile

## Core Tables (Top 15)

| Table | Columns | Purpose | Reference File |
|-------|---------|---------|----------------|
| `sessions` | 10 | Chat session management (workspace, label, token usage, soft delete) | `server/session-store.ts` |
| `messages` | 7 | Message history with content blocks, streaming partial support | `server/session-store.ts` |
| `batch_tasks` | 20 | Batch processing tasks (skill execution, code generation, retry logic) | `server/batch-store.ts` |
| `batch_items` | 10 | Individual batch items with status tracking and cost metrics | `server/batch-store.ts` |
| `cron_tasks` | 8 | Cron task definitions (workspace, skill, expression, source) | `server/cron-task-store.ts` |
| `cron_runs` | 7 | Cron execution records (status, timing, error tracking) | `server/cron-task-store.ts` |
| `chatbot_users` | 3 | Chatbot user state (current workspace, last active) | `server/chatbot/chatbot-store.ts` |
| `chatbot_sessions` | 7 | Chatbot session mapping (user_key → workspace → session_id, bot_label) | `server/chatbot/chatbot-store.ts` |
| `chatbot_workspaces` | 3 | Registered workspace paths for chatbot access | `server/chatbot/chatbot-store.ts` |
| `knowledge_extraction_progress` | 4 | Incremental knowledge extraction tracking | `server/knowledge-extractor-store.ts` |
| `hub_broadcasts` | 4 | Hub broadcast messages (title, body, created_at) | `server/broadcast-store.ts` |
| `stardom_identity` | 5 | Local agent identity registration | `server/stardom/stardom-store.ts` |
| `stardom_tasks` | 11 | Multi-agent task collaboration (direction, status, rating, deadline) | `server/stardom/stardom-store.ts` |
| `stardom_chat_messages` | 4 | Chat messages for agent collaboration | `server/stardom/stardom-store.ts` |
| `stardom_learned_routes` | 5 | Capability-to-agent routing cache with experience | `server/stardom/stardom-store.ts` |

## Design Patterns

Soft delete (sessions), CASCADE deletes, composite PKs, JSON columns, streaming support, token tracking, retry logic, foreign keys, multi-bot support, hub broadcasts

## Index Strategy

- `messages`: `idx_messages_session_id` on session_id
- `sessions`: `idx_sessions_system_id`, `idx_sessions_deleted_at`
- `batch_items`: `idx_batch_items_task`, `idx_batch_items_status` (composite)
- `cron_runs`: `idx_cron_runs_task`, `idx_cron_runs_started` (DESC)
- `stardom_chat_messages`: `idx_chat_task` on task_id
- `stardom_learned_routes`: `idx_learned_capability` on capability
- `stardom_tasks`: `idx_tasks_requester`, `idx_tasks_helper`, `idx_tasks_status` (agent collaboration)

## Migration

Runtime ALTER TABLE with try-catch, check before add, log success/fail. Examples: `chatbot_sessions.bot_label`, `sessions.input_tokens`, `messages.is_partial`

## Recent Changes (Since 57e98c3)

**NEW TABLES**: `hub_broadcasts` (4 cols), `stardom_pair_history` (6 cols), `stardom_cached_results` (4 cols)

**MODIFIED TABLES**: `chatbot_sessions.bot_label`, `stardom_tasks.deadline`, `stardom_learned_routes.experience`

**MIGRATION**: No breaking changes - all additions backward compatible

## Notable Exclusions

- **SmartPath**: File-based storage (`{workspace}/.sman/paths/{pathId}/`) - not in SQLite
- **Settings**: File-based (`~/.sman/config.json`) - not in SQLite
- **Registry**: File-based (`~/.sman/registry.json`) - not in SQLite
- **Hub Remote Server**: Separate service with own SQLite database (`clients`, `reports`, `broadcasts`, `read_log`)
