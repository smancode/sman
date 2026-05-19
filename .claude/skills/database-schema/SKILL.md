---
id: database-schema
name: 数据库全景知识
description: Database schema knowledge (incremental scan). Core tables, relationships, and index strategies.
category: database
_scanned:
  commitHash: "c63e3fcf76ba9e8b362d9d73ebccab934d1d998d"
  scannedAt: "2026-05-20T00:00:00.000Z"
  branch: "master"
---

# Database Overview

SQLite (better-sqlite3) at `~/.sman/sman.db` with WAL mode. 21+ tables across 9 stores.

**Key Relationships**: Sessions→Messages (CASCADE), Groups→Tasks→Subtasks (CASCADE), Batch→Items (CASCADE), Cron→Runs (CASCADE), Chatbot→Bot Profile

## Core Tables (Top 18)

| Table | Columns | Purpose | Reference File |
|-------|---------|---------|----------------|
| `sessions` | 11 | Chat session management (workspace, label, parent_task_id, token usage, soft delete) | `server/session-store.ts` |
| `messages` | 7 | Message history with content blocks, streaming partial support | `server/session-store.ts` |
| `groups` | 6 | Multi-workspace groups with JSON workspace_ids, status tracking | `server/group-store.ts` |
| `group_tasks` | 8 | Tasks within groups with auto_dispatch, status, CASCADE FK to groups | `server/group-store.ts` |
| `group_subtasks` | 8 | Subtasks linking sessions to group tasks, CASCADE FK to group_tasks | `server/group-store.ts` |
| `batch_tasks` | 20 | Batch processing tasks (skill execution, code generation, retry logic) | `server/batch-store.ts` |
| `batch_items` | 10 | Individual batch items with status tracking and cost metrics | `server/batch-store.ts` |
| `cron_tasks` | 8 | Cron task definitions (workspace, skill, expression, source) | `server/cron-task-store.ts` |
| `cron_runs` | 7 | Cron execution records (status, timing, error tracking) | `server/cron-task-store.ts` |
| `chatbot_users` | 3 | Chatbot user state (current workspace, last active) | `server/chatbot/chatbot-store.ts` |
| `chatbot_sessions` | 9 | Chatbot session mapping (chat_type, idle_reset, bot_label, multi-bot support) | `server/chatbot/chatbot-store.ts` |
| `chatbot_workspaces` | 3 | Registered workspace paths for chatbot access | `server/chatbot/chatbot-store.ts` |
| `knowledge_extraction_progress` | 4 | Incremental knowledge extraction tracking | `server/knowledge-extractor-store.ts` |
| `hub_broadcasts` | 4 | Hub broadcast messages (title, body, created_at) | `server/broadcast-store.ts` |
| `stardom_identity` | 5 | Local agent identity registration | `server/stardom/stardom-store.ts` |
| `stardom_tasks` | 11 | Multi-agent task collaboration (direction, status, rating, deadline) | `server/stardom/stardom-store.ts` |
| `stardom_chat_messages` | 4 | Chat messages for agent collaboration | `server/stardom/stardom-store.ts` |
| `stardom_learned_routes` | 5 | Capability-to-agent routing cache with experience | `server/stardom/stardom-store.ts` |

## Design Patterns

Soft delete (sessions), CASCADE deletes (groups/batch/cron), composite PKs, JSON columns (workspace_ids), streaming support (is_partial), token tracking, retry logic, foreign keys, multi-bot support, hub broadcasts, group task hierarchy

## Index Strategy

- `messages`: `idx_messages_session_id` on session_id
- `sessions`: `idx_sessions_system_id`, `idx_sessions_deleted_at`
- `groups`: `idx_groups_status` on status
- `group_tasks`: `idx_group_tasks_group_id`, `idx_group_tasks_status`
- `group_subtasks`: `idx_subtasks_task_id`, `idx_subtasks_session_id`
- `batch_items`: `idx_batch_items_task`, `idx_batch_items_status` (composite)
- `cron_runs`: `idx_cron_runs_task`, `idx_cron_runs_started` (DESC)
- `stardom_chat_messages`: `idx_chat_task` on task_id
- `stardom_learned_routes`: `idx_learned_capability` on capability
- `stardom_tasks`: `idx_tasks_requester`, `idx_tasks_helper`, `idx_tasks_status` (agent collaboration)

## Migration

Runtime ALTER TABLE with try-catch, check before add, log success/fail. Examples: `chatbot_sessions.chat_type`, `chatbot_sessions.idle_reset`, `sessions.parent_task_id`, `group_tasks.status`

## Recent Changes (Since 1ddac60)

**NEW TABLES**: `groups` (6 cols), `group_tasks` (8 cols), `group_subtasks` (8 cols)

**MODIFIED TABLES**:
- `chatbot_sessions`: Added `chat_type` (default 'single'), `idle_reset` (default 0)
- `sessions`: Added `parent_task_id` (nullable), excludes group task sessions from listSessions()
- `group_tasks`: Migrations for `auto_dispatch`, `status` columns

**DROPPED TABLES**: `workspace_tasks` (legacy, replaced by group_subtasks)

**MIGRATION**: All additions backward compatible. ⚠️ BEHAVIOR CHANGE: `listSessions()` now excludes group task workspaces (`~/.sman/group/%`)

**RISK ASSESSMENT**: Low - new feature isolation, no breaking schema changes

## Notable Exclusions

- **SmartPath**: File-based storage (`{workspace}/.sman/paths/{pathId}/`) - not in SQLite
- **Settings**: File-based (`~/.sman/config.json`) - not in SQLite
- **Registry**: File-based (`~/.sman/registry.json`) - not in SQLite
- **Group Data**: File-based (`~/.sman/group/{groupId}/CLAUDE.md`) - not in SQLite
- **Hub Remote Server**: Separate service with own SQLite database (`clients`, `reports`, `broadcasts`, `read_log`)
