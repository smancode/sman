---
id: database-schema
name: 数据库全景知识
description: Database schema knowledge (incremental scan). Core tables, relationships, and index strategies.
category: database
_scanned:
  commitHash: "70d53baa472e0b2f87d9b0080e3239118c1f1ec7"
  scannedAt: "2026-05-22T00:00:00.000Z"
  branch: "master"
---

# Database Overview

SQLite (better-sqlite3) at `~/.sman/sman.db` with WAL mode. 38 tables across 12 stores.

**Key Relationships**: Sessions→Messages (CASCADE), Groups→Tasks→Subtasks (CASCADE), Batch→Items (CASCADE), Cron→Runs (CASCADE), Chatbot→Bot Profile, IM Rooms→Messages (CASCADE)

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
| `chatbot_sessions` | 9 | Chatbot session mapping (chat_type, idle_reset, bot_label, multi-bot support) | `server/chatbot/chatbot-store.ts` |
| `stardom_tasks` | 11 | Multi-agent task collaboration (direction, status, rating, deadline) | `server/stardom/stardom-store.ts` |
| `im_rooms` | 6 | IM room definitions for agent collaboration (group/DM/workspace) | `server/im/im-store.ts` |
| `im_messages` | 12 | Real-time messaging (mentions, quotes, attachments, agent output streaming) | `server/im/im-store.ts` |
| `smartpath_run_log` | 11 | Execution history for SmartPath workflows (mode, args, status, timing) | `server/smart-path-store.ts` |
| `achievement_progress` | 4 | Achievement unlock status (current_value, unlocked_at, notified_at) | `server/achievement-store.ts` |
| `achievement_stats` | 3 | Key-value metrics (total_sessions, total_tokens, etc.) | `server/achievement-store.ts` |
| `achievement_streaks` | 4 | Activity streak tracking (singleton table, current/longest) | `server/achievement-store.ts` |
| `achievement_board` | 7 | Leaderboard cache synced from hub (agent_id, total_points, tier_counts) | `server/achievement-store.ts` |

## Design Patterns

Soft delete (sessions), CASCADE deletes (groups/batch/cron/IM), composite PKs, JSON columns (workspace_ids, tier_counts, dimension_scores, members, attachments), streaming support (is_partial, agent_output), token tracking, retry logic, foreign keys, multi-bot support, hub broadcasts, group task hierarchy, singleton table (achievement_streaks), UPSERT patterns, event-driven metrics (achievement system), real-time messaging (IM rooms), workflow execution logging (SmartPath)

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
- `im_messages`: room_id+timestamp (composite), sender, session_id (room timeline queries, agent retrieval)
- `smartpath_run_log`: No additional indexes (PK-based, queried by path_id+workspace with LIMIT 50)
- Achievement tables: No additional indexes (PK-based lookups, singleton pattern, or full scans for leaderboard)

## Migration

Runtime ALTER TABLE with try-catch, check before add, log success/fail. Examples: `chatbot_sessions.chat_type`, `chatbot_sessions.idle_reset`, `sessions.parent_task_id`, `group_tasks.status`, `group_tasks.auto_dispatch`

## Recent Changes (Since 3539892)

**NEW TABLES**: `im_messages` (12 cols), `im_rooms` (6 cols), `smartpath_run_log` (11 cols)

**MODIFIED TABLES**: None

**INTEGRATION**:
- IM system: Multi-agent real-time messaging with room-based collaboration
- SmartPath: Execution logging for workflow automation (links to file-based paths)

**RISK ASSESSMENT**: Low - new feature isolation, no breaking schema changes, additive only

**PREVIOUS CHANGES (Since c63e3fcf)**:
- `achievement_progress` (4 cols), `achievement_stats` (3 cols), `achievement_streaks` (4 cols), `achievement_board` (7 cols)

## Notable Exclusions

- **SmartPath**: File-based storage (`{workspace}/.sman/paths/{pathId}/`) - not in SQLite
- **Settings**: File-based (`~/.sman/config.json`) - not in SQLite
- **Registry**: File-based (`~/.sman/registry.json`) - not in SQLite
- **Group Data**: File-based (`~/.sman/group/{groupId}/CLAUDE.md`) - not in SQLite
- **Hub Remote Server**: Separate service with own SQLite database (`clients`, `reports`, `broadcasts`, `read_log`)
