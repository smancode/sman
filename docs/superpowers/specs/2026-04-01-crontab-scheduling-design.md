# Crontab Scheduling Design

> **Date**: 2026-04-01
> **Status**: Approved

## Goal

Replace the fixed-interval (`intervalMinutes`) cron scheduling with standard 5-field cron expressions, and add automatic scanning of business system skill directories to discover and sync crontab tasks.

## Background

Current implementation uses `setInterval` with a fixed `intervalMinutes` field. This is limiting compared to Linux crontab's 5-field expression format (`分 时 日 月 周`). Users want to define schedules like "every weekday at 9:00" (`0 9 * * 1-5`).

## Requirements

1. **Cron expression support**: Replace `intervalMinutes` with standard 5-field cron expression
2. **Auto-scan**: Scan all workspace `.claude/skills/*/crontab.md` files, parse cron expressions, and sync to DB
3. **Manual creation preserved**: Users can still manually create tasks with custom cron expressions
4. **Auto-scan trigger**: Manual button + automatic every 30 minutes
5. **Duplicate handling**: Same workspace + skill → update expression; new → create
6. **Backward compatible migration**: Convert existing `intervalMinutes` to `*/{n} * * * *`

## crontab.md Format

```
0 9 * * 1-5
检查今天所有订单的状态，如果有异常订单则汇总通知
```

- Line 1: Standard 5-field cron expression (`minute hour day month weekday`)
- Lines 2+: Prompt content (sent as `/{skillName} {content}` to Claude)
- If line 1 is not a valid cron expression, the entire file is treated as prompt-only (backward compatible, task must be created manually with a cron expression)

## Data Model

### CronTask (changed)

```typescript
interface CronTask {
  id: string;
  workspace: string;
  skillName: string;
  cronExpression: string;  // was: intervalMinutes: number
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}
```

### Database Migration

Use `try/catch ALTER TABLE` pattern consistent with `session-store.ts`:

```typescript
// In CronTaskStore.init(), after CREATE TABLE IF NOT EXISTS:
try {
  this.db.exec('ALTER TABLE cron_tasks ADD COLUMN cron_expression TEXT');
} catch {
  // Column already exists, skip
}

// Migrate existing data: interval_minutes → cron_expression
this.db.exec(`
  UPDATE cron_tasks
  SET cron_expression = '*/' || interval_minutes || ' * * * *'
  WHERE cron_expression IS NULL AND interval_minutes IS NOT NULL
`);
```

- Keep `interval_minutes` column (harmless, avoids table rebuild on older SQLite)
- All reads/writes use `cron_expression`; `interval_minutes` is dead code after migration
- Node.js 22 bundles SQLite 3.39+ (supports DROP COLUMN), but keeping old column is simpler

## Architecture

### Dependencies

- `node-cron`: Cron-based scheduling (replaces `setInterval`)
- `cron-parser`: Calculate next run time (replaces manual timestamp tracking)
- `@types/node-cron`: TypeScript types

### CronScheduler (changed)

```typescript
// Old
const timer = setInterval(() => executeTask(task), intervalMs);

// New
const job = cron.schedule(task.cronExpression, () => executeTask(task));
```

- `schedule(task)`: Register a node-cron job
- `unschedule(taskId)`: Stop and remove a job
- `getNextRunAt(taskId)`: Use `cron-parser` to calculate next fire time

### Auto-Scan Logic

New method on `CronScheduler`:

```typescript
async scanAndSync(): Promise<{ created: number; updated: number; skipped: number; disabled: number }>
```

**Workspace source**: `CronScheduler` needs access to all known workspaces. Inject `SessionStore` (or a workspace list provider) via a new `setSessionStore(store: SessionStore)` method, called from `server/index.ts` alongside `setSessionManager()`.

**Scan steps**:
1. Collect all unique workspaces from `SessionStore.listSessions()`
2. For each workspace, scan `.claude/skills/*/crontab.md`
3. Parse first line as cron expression — strip UTF-8 BOM (`\uFEFF`) and trim whitespace before parsing; use `cron-parser.parseExpression()` for validation, skip on error
4. For each (workspace, skillName):
   - Existing task → update `cronExpression` if different
   - No task → create new task (enabled by default)
5. **Deleted crontab.md handling**: For tasks that were auto-created (tracked via `source='scan'` field), if the corresponding `crontab.md` no longer exists, disable the task (`enabled=false`)
6. Return counts

**Reentrancy protection**: Use an `isScanning` boolean flag. If `scanAndSync()` is called while already running, skip and return current counts.

### Auto-Scan Timer

- 30-minute `setInterval` in `CronScheduler`
- Runs `scanAndSync()` on each tick
- Started alongside `start()`, stopped on `stop()`
- `start()` must be idempotent (guard with `isStarted` flag) to prevent duplicate timers if called twice

## Executor Behavior Change

### crontab.md Parsing in CronExecutor

Current behavior: reads entire `crontab.md` content and sends as `/{skillName} {content}`.

New behavior:
1. Read first line, attempt to parse as cron expression via `cron-parser`
2. If valid → **skip first line**, send lines 2+ as prompt content
3. If invalid → treat entire file as prompt (backward compatible with existing files that have no cron expression)
4. Strip UTF-8 BOM (`\uFEFF`) and trim before parsing

This ensures the cron expression metadata line is not leaked into the Claude prompt.

### Cron Expression Validation

All API endpoints that accept `cronExpression` (`cron.create`, `cron.update`) must validate the expression using `cron-parser.parseExpression()` before persisting. Return an error message on invalid expressions.

## API Changes

### New WebSocket Message

**`cron.scan`**
- Direction: Client → Server
- Response: `{ type: 'cron.scanned', created, updated, skipped, tasks }`

### Changed WebSocket Messages

**`cron.create`**
- Old: `{ workspace, skillName, intervalMinutes }`
- New: `{ workspace, skillName, cronExpression }`

**`cron.update`**
- Old: `{ taskId, intervalMinutes?, enabled? }`
- New: `{ taskId, cronExpression?, enabled? }`

**`cron.list` response**
- Each task now includes `cronExpression` instead of `intervalMinutes`
- `nextRunAt` now calculated via `cron-parser`

## Frontend Changes

### CronTaskSettings.tsx

1. **Auto-scan button**: Added to page header, calls `cron.scan`
2. **Create form**: Replace interval input with cron expression input (text field)
3. **Task card**: Display cron expression + human-readable description (e.g., "工作日 09:00")
4. **Edit form**: Same as create form

### Human-readable description

Use `cron-parser` to compute next 3 fire times and display them, plus the raw expression. No need for a separate "humanizer" library — showing "下次执行: 今天 09:00, 明天 09:00, 后天 09:00" is more useful than "At 09:00 on every weekday".

## File Change Summary

| File | Change |
|------|--------|
| `package.json` | Add `node-cron`, `cron-parser`, `@types/node-cron` |
| `server/types.ts` | `CronTask.cronExpression` replaces `intervalMinutes` |
| `server/cron-task-store.ts` | Schema migration, column rename |
| `server/cron-scheduler.ts` | `node-cron` + `cron-parser`, auto-scan timer |
| `server/cron-executor.ts` | Read crontab.md first line as expression (minor) |
| `server/index.ts` | Add `cron.scan` handler |
| `src/types/settings.ts` | Update `CronTask` type |
| `src/stores/cron.ts` | Add `scanCronTasks()`, update params |
| `src/features/settings/CronTaskSettings.tsx` | UI overhaul |

## Out of Scope

- **BatchTask.cronIntervalMinutes**: The `batch_tasks` table has a parallel `cron_interval_minutes` field. This is not in scope for this design. Batch tasks have their own scheduling mechanism. If batch tasks also need cron expression support, it should be a separate spec.
- **Manual tasks without crontab.md**: When a user manually creates a task, the executor still reads `crontab.md` for prompt content. If `crontab.md` doesn't exist, the executor skips execution with a warning. This is existing behavior and acceptable — manual tasks are expected to have a crontab.md or at minimum a skill directory. The user can also provide a generic prompt by creating a minimal crontab.md.

## Test Plan

1. **Migration**: Existing tasks with `intervalMinutes` convert to correct cron expressions
2. **Cron scheduling**: Tasks fire at correct times (test with `* * * * *` = every minute)
3. **Auto-scan**: Creates tasks from crontab.md files, updates existing ones
4. **Auto-scan disabled tasks**: Tasks whose crontab.md was deleted get disabled
5. **Invalid expression**: Gracefully skipped during scan
6. **Expression validation**: `cron.create` and `cron.update` reject invalid expressions
7. **Manual creation**: Users can still create tasks with any valid cron expression
8. **Enable/Disable**: Toggling works correctly with node-cron jobs
9. **Next run calculation**: `cron-parser` returns correct next fire times
10. **Reentrancy**: Concurrent scan calls don't create duplicates
11. **BOM handling**: crontab.md files with UTF-8 BOM parse correctly
