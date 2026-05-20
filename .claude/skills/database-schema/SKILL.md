---
id: database-schema
name: 数据库全景知识
description: Database schema knowledge (incremental scan). Core tables, relationships, and index strategies.
category: database
_scanned:
  commitHash: "353989234d641c959d8c0aa37aea150735c4ccd8"
  scannedAt: "2026-05-21T00:00:00.000Z"
  branch: "master"
---

# Database Overview

SQLite (better-sqlite3) at `~/.sman/sman.db` with WAL mode. 25+ tables across 10 stores.

**Key Relationships**: Sessions→Messages (CASCADE), Groups→Tasks→Subtasks (CASCADE), Batch→Items (CASCADE), Cron→Runs (CASCADE), Chatbot→Bot Profile

## Core Tables (Top 15)

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
| `chatbot_sessions` | 9 | Chatbot session mapping (chat_type, idle_reset, bot_label, multi-bot support) | `server/chatbot/chatbot-store.ts` |
| `stardom_tasks` | 11 | Multi-agent task collaboration (direction, status, rating, deadline) | `server/stardom/stardom-store.ts` |
| `achievement_progress` | 4 | Achievement unlock status (current_value, unlocked_at, notified_at) | `server/achievement-store.ts` |
| `achievement_stats` | 3 | Key-value metrics (total_sessions, total_tokens, etc.) | `server/achievement-store.ts` |
| `achievement_streaks` | 4 | Activity streak tracking (singleton table, current/longest) | `server/achievement-store.ts` |
| `achievement_board` | 7 | Leaderboard cache synced from hub (agent_id, total_points, tier_counts) | `server/achievement-store.ts` |

## Design Patterns

Soft delete (sessions), CASCADE deletes (groups/batch/cron), composite PKs, JSON columns (workspace_ids, tier_counts, dimension_scores), streaming support (is_partial), token tracking, retry logic, foreign keys, multi-bot support, hub broadcasts, group task hierarchy, singleton table (achievement_streaks), UPSERT patterns, event-driven metrics (achievement system)

## Index Strategy

**Note**: Degraded scan - index details omitted for brevity. Key indexes exist on:
- `messages`: session_id
- `sessions`: system_id, deleted_at
- `groups`: status
- `group_tasks`: group_id, status
- `group_subtasks`: task_id, session_id
- `batch_items`: task_id, status (composite)
- `cron_runs`: task_id, started_at (DESC)
- `stardom_tasks`: requester_id, helper_id, status (agent collaboration)
- Achievement tables: No additional indexes (PK-based lookups, singleton pattern, or full scans for leaderboard)

## Migration

Runtime ALTER TABLE with try-catch, check before add, log success/fail. Examples: `chatbot_sessions.chat_type`, `chatbot_sessions.idle_reset`, `sessions.parent_task_id`, `group_tasks.status`, `group_tasks.auto_dispatch`

## Recent Changes (Since c63e3fcf)

**NEW TABLES**: `achievement_progress` (4 cols), `achievement_stats` (3 cols), `achievement_streaks` (4 cols), `achievement_board` (7 cols)

**MODIFIED TABLES**: None (achievement system is additive)

**INTEGRATION**: Event-driven metrics emitted from session/cron/batch/smart-path engines via `achievement-events.ts`

**RISK ASSESSMENT**: Low - new feature isolation, no breaking schema changes, UPSERT patterns safe

## Notable Exclusions

- **SmartPath**: File-based storage (`{workspace}/.sman/paths/{pathId}/`) - not in SQLite
- **Settings**: File-based (`~/.sman/config.json`) - not in SQLite
- **Registry**: File-based (`~/.sman/registry.json`) - not in SQLite
- **Group Data**: File-based (`~/.sman/group/{groupId}/CLAUDE.md`) - not in SQLite
- **Hub Remote Server**: Separate service with own SQLite database (`clients`, `reports`, `broadcasts`, `read_log`)
