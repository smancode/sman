---
id: database-schema
name: 数据库全景知识
description: 数据库全景知识（首次扫描）。包含核心表结构、关联关系、索引策略。
category: database
_scanned:
  commitHash: "4db35f24f89dda0c11aa6aad83ba7bb7f8df368a"
  scannedAt: "2026-05-06T00:00:00.000Z"
  branch: "master"
---

# Database Overview

Sman uses SQLite (better-sqlite3) as the primary database engine, with database files stored in `~/.sman/sman.db`. The system employs WAL (Write-Ahead Logging) mode for better concurrency and crash recovery.

- **Engine**: SQLite 3 (better-sqlite3)
- **Table Count**: 15 core tables across 7 stores
- **Key Relationships**: Sessions → Messages (CASCADE DELETE), Batch Tasks → Items (CASCADE DELETE), Cron Tasks → Runs (CASCADE DELETE)

## Core Tables

| Table | Columns | Purpose | Reference File |
|-------|---------|---------|----------------|
| `sessions` | 10 | Chat session management (workspace, label, token usage, soft delete) | `server/session-store.ts` |
| `messages` | 7 | Message history with content blocks, streaming partial support | `server/session-store.ts` |
| `batch_tasks` | 20 | Batch processing tasks (skill execution, code generation, retry logic) | `server/batch-store.ts` |
| `batch_items` | 10 | Individual batch items with status tracking and cost metrics | `server/batch-store.ts` |
| `cron_tasks` | 8 | Cron task definitions (workspace, skill, expression, source) | `server/cron-task-store.ts` |
| `cron_runs` | 7 | Cron execution records (status, timing, error tracking) | `server/cron-task-store.ts` |
| `chatbot_users` | 3 | Chatbot user state (current workspace, last active) | `server/chatbot/chatbot-store.ts` |
| `chatbot_sessions` | 6 | Chatbot session mapping (user_key → workspace → session_id) | `server/chatbot/chatbot-store.ts` |
| `chatbot_workspaces` | 3 | Registered workspace paths for chatbot access | `server/chatbot/chatbot-store.ts` |
| `knowledge_extraction_progress` | 4 | Incremental knowledge extraction tracking | `server/knowledge-extractor-store.ts` |
| `stardom_identity` | 5 | Local agent identity registration | `server/stardom/stardom-store.ts` |
| `stardom_tasks` | 10 | Multi-agent task collaboration (direction, status, rating) | `server/stardom/stardom-store.ts` |
| `stardom_chat_messages` | 4 | Chat messages for agent collaboration | `server/stardom/stardom-store.ts` |
| `stardom_learned_routes` | 5 | Capability-to-agent routing cache | `server/stardom/stardom-store.ts` |
| `stardom_pair_history` | 6 | Agent collaboration history (task count, ratings) | `server/stardom/stardom-store.ts` |

## Key Design Patterns

1. **Soft Delete**: Sessions use `deleted_at` for soft deletion (knowledge extraction requires deleted sessions)
2. **Cascade Deletes**: Messages, batch_items, and cron_runs use CASCADE on parent deletion
3. **Composite Primary Keys**: `knowledge_extraction_progress` uses (workspace, session_id)
4. **JSON Columns**: Content blocks, batch item data stored as TEXT (JSON)
5. **Streaming Support**: `messages.is_partial` tracks in-progress assistant messages
6. **Token Tracking**: Sessions store `input_tokens` and `output_tokens` for cost monitoring
7. **Retry Logic**: Batch items track `retries` count for failure recovery
8. **Foreign Keys**: All stores enable `PRAGMA foreign_keys = ON`

## Index Strategy

- `messages`: `idx_messages_session_id` on session_id
- `sessions`: `idx_sessions_system_id`, `idx_sessions_deleted_at`
- `batch_items`: `idx_batch_items_task`, `idx_batch_items_status` (composite)
- `cron_runs`: `idx_cron_runs_task`, `idx_cron_runs_started` (DESC)
- `stardom_chat_messages`: `idx_chat_task` on task_id
- `stardom_learned_routes`: `idx_learned_capability` on capability

## Migration Strategy

All stores use runtime ALTER TABLE migrations with try-catch blocks:
- Check column existence before adding
- Log migration success/failure
- Support zero-downtime upgrades

## Notable Exclusions

- **SmartPath**: File-based storage (`{workspace}/.sman/paths/{pathId}/`) - not in SQLite
- **Settings**: File-based (`~/.sman/config.json`) - not in SQLite
- **Registry**: File-based (`~/.sman/registry.json`) - not in SQLite
