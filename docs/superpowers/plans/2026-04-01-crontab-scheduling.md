# Crontab Scheduling Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `intervalMinutes`-based cron scheduling with standard 5-field cron expressions (`minute hour day month weekday`). Add auto-scanning of workspace `.claude/skills/*/crontab.md` files to discover and sync crontab tasks.

**Design Spec:** `docs/superpowers/specs/2026-04-01-crontab-scheduling-design.md`

**Tech Stack:** TypeScript, `node-cron`, `cron-parser`, `@types/node-cron`, vitest, better-sqlite3

---

## File Structure

```
Files to modify:
  package.json                                     ← add node-cron, cron-parser, @types/node-cron
  server/types.ts                                  ← CronTask.intervalMinutes → cronExpression
  server/cron-task-store.ts                        ← DB migration, column rename, CRUD signature change
  server/cron-scheduler.ts                         ← setInterval → node-cron, scanAndSync, auto-scan timer
  server/cron-executor.ts                          ← crontab.md first line parsing
  server/index.ts                                  ← cron.* handlers, cron.scan, fix duplicate start()
  src/types/settings.ts                            ← CronTask type update
  src/stores/cron.ts                               ← scanCronTasks, param changes
  src/features/settings/CronTaskSettings.tsx        ← cron expression UI, auto-scan button

New test files:
  tests/server/cron-task-store.test.ts             ← CronTaskStore migration + CRUD tests
  tests/server/cron-expression.test.ts             ← cron expression parsing helper tests
  tests/server/cron-scheduler.test.ts              ← CronScheduler with node-cron tests
  tests/server/cron-executor.test.ts               ← Executor crontab.md parsing tests
```

---

## Chunk 1: Dependencies & Types

### Task 1: Install dependencies

**Files:** `package.json`

- [ ] **Step 1: Run pnpm add**
  ```bash
  pnpm add node-cron cron-parser && pnpm add -D @types/node-cron
  ```
  Verify `node-cron`, `cron-parser`, `@types/node-cron` appear in `package.json`.

### Task 2: Update server CronTask type

**Files:** `server/types.ts`

- [ ] **Step 1: Edit `CronTask` interface**
  Change `intervalMinutes: number` to `cronExpression: string`:
  ```typescript
  export interface CronTask {
    id: string;
    workspace: string;
    skillName: string;
    cronExpression: string;  // was: intervalMinutes: number
    enabled: boolean;
    createdAt: string;
    updatedAt: string;
  }
  ```
  **Do NOT** change `BatchTask.cronIntervalMinutes` — that is out of scope.

### Task 3: Update frontend CronTask type

**Files:** `src/types/settings.ts`

- [ ] **Step 1: Edit `CronTask` interface**
  Change `intervalMinutes: number` to `cronExpression: string`:
  ```typescript
  export interface CronTask {
    id: string;
    workspace: string;
    skillName: string;
    cronExpression: string;  // was: intervalMinutes: number
    enabled: boolean;
    createdAt: string;
    updatedAt: string;
    latestRun?: CronRun;
    nextRunAt?: string | null;
  }
  ```

---

## Chunk 2: Backend Data Layer (CronTaskStore)

### Task 4: Write tests for CronTaskStore with cronExpression

**Files:** Create `tests/server/cron-task-store.test.ts`

- [ ] **Step 1: Write test file**

  Test cases (all use temp DB in `os.tmpdir()`, vitest `beforeEach`/`afterEach` pattern):

  1. **Migration from interval_minutes**: Create DB with old schema (`interval_minutes` column only), insert a row with `interval_minutes=30`, then instantiate `CronTaskStore`. Verify `cron_expression` column exists and equals `'*/30 * * * *'`.
  2. **Migration idempotent**: Instantiate `CronTaskStore` twice on same DB. Second `init()` does not throw. Existing `cron_expression` values are preserved (not overwritten).
  3. **createTask with cronExpression**: Create task with `{ workspace, skillName, cronExpression: '0 9 * * 1-5' }`. Verify returned task has `cronExpression === '0 9 * * 1-5'` and `enabled === true`.
  4. **getTask returns cronExpression**: Create a task, then `getTask(id)`. Verify `cronExpression` field is present and correct.
  5. **listTasks returns cronExpression**: Create 2 tasks. Verify both have `cronExpression`.
  6. **updateTask with cronExpression**: Create a task, then update `cronExpression` to `'*/5 * * * *'`. Verify updated value.
  7. **updateTask with enabled**: Create a task (enabled=true), update `enabled` to false. Verify.
  8. **deleteTask**: Create then delete. `getTask()` returns `undefined`.
  9. **Run records unchanged**: `createRun`/`updateRun`/`getLatestRun` still work after migration.

  ```typescript
  import { describe, it, expect, beforeEach, afterEach } from 'vitest';
  import { CronTaskStore } from '../../server/cron-task-store.js';
  import fs from 'fs';
  import path from 'path';
  import os from 'os';
  import betterSqlite3 from 'better-sqlite3';
  // @ts-expect-error - better-sqlite3 ESM interop
  const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3;

  describe('CronTaskStore', () => {
    // ... beforeEach/afterEach with temp DB
    // ... test cases above
  });
  ```

  **Do NOT implement the store changes yet.** Tests should fail (RED).

- [ ] **Step 2: Run tests, confirm RED**
  ```bash
  pnpm test -- tests/server/cron-task-store.test.ts
  ```
  All new tests should fail (compilation errors or assertion failures).

### Task 5: Implement CronTaskStore changes

**Files:** `server/cron-task-store.ts`

- [ ] **Step 1: Add migration in `init()`**

  After `CREATE TABLE IF NOT EXISTS cron_tasks`, add:
  ```typescript
  // Migration: add cron_expression column
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

- [ ] **Step 2: Update `createTask` signature and implementation**

  Change from `intervalMinutes: number` to `cronExpression: string`:
  ```typescript
  createTask(input: { workspace: string; skillName: string; cronExpression: string }): CronTask {
    const id = uuidv4();
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO cron_tasks (id, workspace, skill_name, cron_expression, enabled, created_at, updated_at)
      VALUES (?, ?, ?, ?, 1, ?, ?)
    `).run(id, input.workspace, input.skillName, input.cronExpression, now, now);
    return this.getTask(id)!;
  }
  ```

- [ ] **Step 3: Update `getTask` query**

  Replace `interval_minutes as intervalMinutes` with `cron_expression as cronExpression` in the SELECT.

- [ ] **Step 4: Update `listTasks` and `listEnabledTasks` queries**

  Same column alias change as Step 3.

- [ ] **Step 5: Update `updateTask` signature and implementation**

  Change `intervalMinutes` to `cronExpression` in the `Partial<Pick<...>>` type and the field mapping:
  ```typescript
  updateTask(id: string, updates: Partial<Pick<CronTask, 'workspace' | 'skillName' | 'cronExpression' | 'enabled'>>): CronTask | undefined {
    // ...
    if (updates.cronExpression !== undefined) {
      fields.push('cron_expression = ?');
      values.push(updates.cronExpression);
    }
    // ...
  }
  ```

### Task 6: Verify CronTaskStore tests pass

- [ ] **Step 1: Run tests**
  ```bash
  pnpm test -- tests/server/cron-task-store.test.ts
  ```
  All tests must pass (GREEN).

---

## Chunk 3: Cron Expression Utilities

### Task 7: Write tests for cron expression parsing helper

**Files:** Create `tests/server/cron-expression.test.ts`

- [ ] **Step 1: Write test file**

  Test cases for a new helper module `server/cron-utils.ts`:

  1. **validateCronExpression — valid expressions**: `'0 9 * * 1-5'`, `'*/5 * * * *'`, `'30 */2 * * *'`, `'0 0 1 1 *'` all return `{ valid: true }`.
  2. **validateCronExpression — invalid expressions**: `'invalid'`, `''`, `'* * *'` (3 fields), `'60 * * * *'` (out of range) all return `{ valid: false, error: string }`.
  3. **parseCrontabFirstLine — valid first line**: Input `'0 9 * * 1-5\n检查订单状态'` returns `{ cronExpression: '0 9 * * 1-5', promptContent: '检查订单状态' }`.
  4. **parseCrontabFirstLine — no cron expression**: Input `'检查订单状态\n有异常则通知'` returns `{ cronExpression: null, promptContent: '检查订单状态\n有异常则通知' }` (entire content is prompt).
  5. **parseCrontabFirstLine — BOM handling**: Input `'\uFEFF0 9 * * 1-5\n检查订单'` returns `{ cronExpression: '0 9 * * 1-5', promptContent: '检查订单' }`.
  6. **parseCrontabFirstLine — empty content**: Returns `{ cronExpression: null, promptContent: '' }`.
  7. **getNextRuns — returns next 3 fire times**: For `'0 9 * * 1-5'`, returns array of 3 ISO date strings.
  8. **getNextRuns — invalid expression**: Returns empty array.

  ```typescript
  import { describe, it, expect } from 'vitest';
  import { validateCronExpression, parseCrontabFirstLine, getNextRuns } from '../../server/cron-utils.js';

  describe('cron-utils', () => {
    describe('validateCronExpression', () => { /* ... */ });
    describe('parseCrontabFirstLine', () => { /* ... */ });
    describe('getNextRuns', () => { /* ... */ });
  });
  ```

- [ ] **Step 2: Run tests, confirm RED**
  ```bash
  pnpm test -- tests/server/cron-expression.test.ts
  ```

### Task 8: Implement cron-utils.ts

**Files:** Create `server/cron-utils.ts`

- [ ] **Step 1: Create the helper module**

  ```typescript
  import parser from 'cron-parser';

  export interface CronValidationResult {
    valid: boolean;
    error?: string;
  }

  /**
   * Validate a 5-field cron expression using cron-parser.
   */
  export function validateCronExpression(expr: string): CronValidationResult {
    try {
      parser.parseExpression(expr);
      return { valid: true };
    } catch (err) {
      return { valid: false, error: err instanceof Error ? err.message : 'Invalid cron expression' };
    }
  }

  /**
   * Parse crontab.md content: extract cron expression from first line (if valid),
   * return remaining lines as prompt content.
   * Strips UTF-8 BOM and trims whitespace before parsing.
   */
  export function parseCrontabFirstLine(content: string): {
    cronExpression: string | null;
    promptContent: string;
  } {
    const trimmed = content.replace(/^\uFEFF/, '').trim();
    if (!trimmed) {
      return { cronExpression: null, promptContent: '' };
    }

    const firstNewline = trimmed.indexOf('\n');
    const firstLine = firstNewline === -1
      ? trimmed
      : trimmed.substring(0, firstNewline);

    const validation = validateCronExpression(firstLine.trim());
    if (validation.valid) {
      const rest = firstNewline === -1 ? '' : trimmed.substring(firstNewline + 1);
      return { cronExpression: firstLine.trim(), promptContent: rest.trim() };
    }

    // First line is not a valid cron expression → entire content is prompt
    return { cronExpression: null, promptContent: trimmed };
  }

  /**
   * Get the next N fire times for a cron expression.
   * Returns ISO date strings. Returns empty array on invalid expression.
   */
  export function getNextRuns(cronExpression: string, count = 3): string[] {
    try {
      const interval = parser.parseExpression(cronExpression);
      const results: string[] = [];
      for (let i = 0; i < count; i++) {
        const next = interval.next();
        if (next) results.push(next.toDate().toISOString());
        else break;
      }
      return results;
    } catch {
      return [];
    }
  }
  ```

### Task 9: Verify cron-utils tests pass

- [ ] **Step 1: Run tests**
  ```bash
  pnpm test -- tests/server/cron-expression.test.ts
  ```
  All tests must pass (GREEN).

---

## Chunk 4: Scheduler Rewrite

### Task 10: Write tests for CronScheduler with cron expressions

**Files:** Create `tests/server/cron-scheduler.test.ts`

- [ ] **Step 1: Write test file**

  Test cases:

  1. **schedule with cron expression**: Create a `CronTask` with `cronExpression: '* * * * *'`. Call `scheduler.schedule(task)`. Verify `getNextRunAt(task.id)` returns a non-null ISO string.
  2. **unschedule**: Schedule a task, then `unschedule(taskId)`. Verify `getNextRunAt(taskId)` returns null.
  3. **schedule disabled task**: Call `schedule` with `enabled: false`. Verify no job is registered (`getNextRunAt` returns null).
  4. **idempotent start**: Call `start()` twice. Verify no duplicate timers (use `isStarted` flag — check that second call is a no-op via log or internal state).
  5. **stop clears all jobs**: Schedule 2 tasks, call `stop()`. Verify `getNextRunAt` returns null for both.
  6. **getNextRunAt with cron-parser**: For task with `cronExpression: '0 9 * * 1-5'`, verify `getNextRunAt` returns a future date that falls on a weekday at 09:00 UTC.

  Use mock `CronTaskStore` (vitest `vi.fn()` or manual stub). The `CronExecutor` is not directly invoked in scheduler tests — mock via constructor injection or skip execution by not waiting for the cron tick.

  ```typescript
  import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
  // ... mock store, test cases
  ```

- [ ] **Step 2: Run tests, confirm RED**
  ```bash
  pnpm test -- tests/server/cron-scheduler.test.ts
  ```

### Task 11: Implement CronScheduler with node-cron

**Files:** `server/cron-scheduler.ts`

- [ ] **Step 1: Replace `setInterval` with `node-cron`**

  Key changes:
  - Import `cron` from `node-cron`
  - Change `timers` map from `Map<string, ReturnType<typeof setInterval>>` to `Map<string, cron.ScheduledTask>`
  - Remove `nextRunAt` map (no longer needed — use `cron-parser` on demand)
  - In `schedule()`: replace `setInterval(...)` with `cron.schedule(task.cronExpression, () => this.executeTask(task))`
  - In `unschedule()`: replace `clearInterval(timer)` with `task.stop()`
  - In `stop()`: iterate and call `task.stop()` on each, then clear the map
  - In `getNextRunAt()`: use `cron-parser` to compute next fire time instead of reading from map:
    ```typescript
    getNextRunAt(taskId: string): string | null {
      const task = this.taskStore.getTask(taskId);
      if (!task || !task.enabled) return null;
      try {
        const interval = parser.parseExpression(task.cronExpression);
        return interval.next().toDate().toISOString();
      } catch {
        return null;
      }
    }
    ```

  Full new structure:
  ```typescript
  import cron from 'node-cron';
  import parser from 'cron-parser';
  import { validateCronExpression } from './cron-utils.js';
  // ... other imports

  export class CronScheduler {
    private jobs = new Map<string, cron.ScheduledTask>();
    private isStarted = false;
    private autoScanTimer: ReturnType<typeof setInterval> | null = null;
    private isScanning = false;
    private sessionStore: SessionStore | null = null;
    // ... executor, log, taskStore

    setSessionStore(store: SessionStore): void { this.sessionStore = store; }
    setSessionManager(sm: ClaudeSessionManager): void { this.executor.setSessionManager(sm); }

    start(): void {
      if (this.isStarted) return;  // idempotent
      this.isStarted = true;
      // schedule enabled tasks + start zombie check + start auto-scan timer
    }

    stop(): void {
      this.isStarted = false;
      // stop all jobs + stop zombie check + stop auto-scan timer
    }

    schedule(task: CronTask): void {
      this.unschedule(task.id);
      if (!task.enabled) return;
      if (!validateCronExpression(task.cronExpression).valid) {
        this.log.warn(`Invalid cron expression for task ${task.id}: ${task.cronExpression}`);
        return;
      }
      const job = cron.schedule(task.cronExpression, () => this.executeTask(task));
      this.jobs.set(task.id, job);
    }

    unschedule(taskId: string): void {
      const job = this.jobs.get(taskId);
      if (job) { job.stop(); this.jobs.delete(taskId); }
    }

    getNextRunAt(taskId: string): string | null { /* cron-parser based */ }

    // scanAndSync and auto-scan timer in Task 12
  }
  ```

### Task 12: Implement scanAndSync with reentrancy guard

**Files:** `server/cron-scheduler.ts` (continued)

- [ ] **Step 1: Add `scanAndSync` method**

  ```typescript
  async scanAndSync(): Promise<{ created: number; updated: number; skipped: number; disabled: number }> {
    if (this.isScanning) {
      this.log.info('scanAndSync already in progress, skipping');
      return { created: 0, updated: 0, skipped: 0, disabled: 0 };
    }
    if (!this.sessionStore) {
      throw new Error('SessionStore not set, cannot scan workspaces');
    }

    this.isScanning = true;
    const result = { created: 0, updated: 0, skipped: 0, disabled: 0 };

    try {
      // 1. Collect all unique workspaces
      const sessions = this.sessionStore.listSessions();
      const workspaces = [...new Set(sessions.map(s => s.workspace))];

      // 2. Track which (workspace, skillName) pairs we find
      const foundPairs = new Set<string>();

      for (const workspace of workspaces) {
        const skillsDir = path.join(workspace, '.claude', 'skills');
        if (!fs.existsSync(skillsDir)) continue;

        const entries = fs.readdirSync(skillsDir, { withFileTypes: true });
        for (const entry of entries) {
          if (!entry.isDirectory()) continue;

          const crontabPath = path.join(skillsDir, entry.name, 'crontab.md');
          const pairKey = `${workspace}::${entry.name}`;
          foundPairs.add(pairKey);

          if (!fs.existsSync(crontabPath)) continue;

          const content = fs.readFileSync(crontabPath, 'utf-8');
          const { cronExpression } = parseCrontabFirstLine(content);

          if (!cronExpression) {
            result.skipped++;
            continue;
          }

          const validation = validateCronExpression(cronExpression);
          if (!validation.valid) {
            this.log.warn(`Invalid cron expression in ${crontabPath}: ${cronExpression}`);
            result.skipped++;
            continue;
          }

          // Check existing task
          const existingTasks = this.taskStore.listTasks();
          const existing = existingTasks.find(
            t => t.workspace === workspace && t.skillName === entry.name
          );

          if (existing) {
            if (existing.cronExpression !== cronExpression) {
              this.taskStore.updateTask(existing.id, { cronExpression });
              // Re-schedule if enabled
              if (existing.enabled) {
                const updated = this.taskStore.getTask(existing.id);
                if (updated) this.schedule(updated);
              }
              result.updated++;
            } else {
              result.skipped++;
            }
          } else {
            const task = this.taskStore.createTask({
              workspace,
              skillName: entry.name,
              cronExpression,
            });
            this.schedule(task);
            result.created++;
          }
        }
      }

      // 5. Disable tasks whose crontab.md was deleted (source='scan' tracking)
      // Simple heuristic: if a task's (workspace, skillName) pair was not found
      // and it was auto-created (we track via source field or just check if crontab.md is gone)
      const allTasks = this.taskStore.listTasks();
      for (const task of allTasks) {
        if (!task.enabled) continue;
        const pairKey = `${task.workspace}::${task.skillName}`;
        if (foundPairs.has(pairKey)) continue;

        // Check if crontab.md still exists
        const crontabPath = path.join(task.workspace, '.claude', 'skills', task.skillName, 'crontab.md');
        if (!fs.existsSync(crontabPath)) {
          this.unschedule(task.id);
          this.taskStore.updateTask(task.id, { enabled: false });
          result.disabled++;
        }
      }
    } finally {
      this.isScanning = false;
    }

    this.log.info('scanAndSync completed', result);
    return result;
  }
  ```

- [ ] **Step 2: Add auto-scan timer in `start()`/`stop()`**

  In `start()`, after scheduling tasks:
  ```typescript
  // Auto-scan every 30 minutes
  this.autoScanTimer = setInterval(() => {
    this.scanAndSync().catch(err => {
      this.log.error('Auto-scan failed', { error: err });
    });
  }, 30 * 60 * 1000);
  ```

  In `stop()`:
  ```typescript
  if (this.autoScanTimer) {
    clearInterval(this.autoScanTimer);
    this.autoScanTimer = null;
  }
  ```

  Add required imports at top of file:
  ```typescript
  import path from 'path';
  import fs from 'fs';
  import { parseCrontabFirstLine } from './cron-utils.js';
  ```

### Task 13: Verify scheduler tests pass

- [ ] **Step 1: Run tests**
  ```bash
  pnpm test -- tests/server/cron-scheduler.test.ts
  ```
  All tests must pass (GREEN).

---

## Chunk 5: Executor Update

### Task 14: Write tests for CronExecutor crontab.md parsing

**Files:** Create `tests/server/cron-executor.test.ts`

- [ ] **Step 1: Write test file**

  Test cases (mock `CronTaskStore` and `ClaudeSessionManager`):

  1. **crontab.md with cron expression first line**: Create a temp `crontab.md` with content `'0 9 * * 1-5\n检查订单状态\n有异常则通知'`. Call `executor.execute(task)`. Verify the prompt sent to `sendMessageForCron` is `'/skillName 检查订单状态\n有异常则通知'` (first line skipped).
  2. **crontab.md without cron expression**: Content = `'检查订单状态\n有异常则通知'`. Verify prompt = `'/skillName 检查订单状态\n有异常则通知'` (entire content used).
  3. **crontab.md with BOM**: Content = `'\uFEFF0 9 * * 1-5\n检查订单'`. Verify prompt = `'/skillName 检查订单'` (BOM stripped, first line skipped).
  4. **crontab.md with only cron expression (no prompt body)**: Content = `'0 9 * * *'`. Verify prompt = `'/skillName '` (empty prompt body after stripping cron line).
  5. **crontab.md not found**: Task points to nonexistent file. Verify executor logs warning and returns early (no session created).

  Use temp directories and real files for crontab.md. Mock `ClaudeSessionManager` with `vi.fn()`.

  ```typescript
  import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
  import fs from 'fs';
  import path from 'path';
  import os from 'os';
  // ... test cases
  ```

- [ ] **Step 2: Run tests, confirm RED**
  ```bash
  pnpm test -- tests/server/cron-executor.test.ts
  ```

### Task 15: Implement executor changes

**Files:** `server/cron-executor.ts`

- [ ] **Step 1: Import `parseCrontabFirstLine`**
  ```typescript
  import { parseCrontabFirstLine } from './cron-utils.js';
  ```

- [ ] **Step 2: Update the `execute()` method's crontab.md reading**

  Replace:
  ```typescript
  const crontabContent = fs.readFileSync(crontabPath, 'utf-8').trim();
  if (!crontabContent) { /* ... */ }
  const prompt = `/${task.skillName} ${crontabContent}`;
  ```

  With:
  ```typescript
  const rawContent = fs.readFileSync(crontabPath, 'utf-8');
  const { cronExpression: _expr, promptContent } = parseCrontabFirstLine(rawContent);
  if (!promptContent) {
    this.log.warn(`crontab.md has no prompt content for task ${task.id}`);
    return;
  }
  const prompt = `/${task.skillName} ${promptContent}`;
  ```

  The `_expr` (cron expression) is extracted but not used by the executor — it was already used during scan/scheduling. The key purpose is to skip it from the prompt.

### Task 16: Verify executor tests pass

- [ ] **Step 1: Run tests**
  ```bash
  pnpm test -- tests/server/cron-executor.test.ts
  ```
  All tests must pass (GREEN).

---

## Chunk 6: Backend Integration (server/index.ts)

### Task 17: Update cron.* WebSocket handlers

**Files:** `server/index.ts`

- [ ] **Step 1: Update `cron.create` handler**

  Replace `intervalMinutes` with `cronExpression`:
  ```typescript
  case 'cron.create': {
    if (!msg.workspace || !msg.skillName || !msg.cronExpression) {
      throw new Error('Missing required fields: workspace, skillName, cronExpression');
    }
    // Validate cron expression
    const { validateCronExpression } = await import('./cron-utils.js');
    const validation = validateCronExpression(msg.cronExpression as string);
    if (!validation.valid) {
      throw new Error(`Invalid cron expression: ${validation.error}`);
    }
    const task = cronTaskStore.createTask({
      workspace: msg.workspace as string,
      skillName: msg.skillName as string,
      cronExpression: msg.cronExpression as string,
    });
    cronScheduler.schedule(task);
    ws.send(JSON.stringify({ type: 'cron.created', task }));
    break;
  }
  ```

  Move the `import { validateCronExpression } from './cron-utils.js'` to the top-level imports instead of dynamic import.

- [ ] **Step 2: Update `cron.update` handler**

  Replace `intervalMinutes` with `cronExpression`:
  ```typescript
  case 'cron.update': {
    if (!msg.taskId) throw new Error('Missing taskId');
    // Validate cron expression if provided
    if (msg.cronExpression) {
      const validation = validateCronExpression(msg.cronExpression as string);
      if (!validation.valid) {
        throw new Error(`Invalid cron expression: ${validation.error}`);
      }
    }
    const task = cronTaskStore.updateTask(msg.taskId as string, {
      workspace: msg.workspace as string | undefined,
      skillName: msg.skillName as string | undefined,
      cronExpression: msg.cronExpression as string | undefined,
      enabled: msg.enabled as boolean | undefined,
    });
    if (task) {
      if (task.enabled) {
        cronScheduler.schedule(task);
      } else {
        cronScheduler.unschedule(task.id);
      }
    }
    ws.send(JSON.stringify({ type: 'cron.updated', task }));
    break;
  }
  ```

- [ ] **Step 3: Update `cron.list` handler**

  The `nextRunAt` computation already uses `cronScheduler.getNextRunAt()` which now uses `cron-parser`. No change needed unless `cronTaskStore.listTasks()` return type changed (it did — `cronExpression` instead of `intervalMinutes`). The spread `...task` will automatically include `cronExpression`.

### Task 18: Add cron.scan handler

**Files:** `server/index.ts`

- [ ] **Step 1: Add `cron.scan` case to the switch**

  Insert after the `cron.execute` case:
  ```typescript
  case 'cron.scan': {
    try {
      const result = await cronScheduler.scanAndSync();
      const tasks = cronTaskStore.listTasks().map(task => ({
        ...task,
        latestRun: cronTaskStore.getLatestRun(task.id),
        nextRunAt: cronScheduler.getNextRunAt(task.id),
      }));
      ws.send(JSON.stringify({ type: 'cron.scanned', ...result, tasks }));
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
    }
    break;
  }
  ```

### Task 19: Fix duplicate start() and add setSessionStore

**Files:** `server/index.ts`

- [ ] **Step 1: Remove first duplicate `cronScheduler.start()` at line 130**

  Delete the line:
  ```typescript
  cronScheduler.start()  // line 130 — DUPLICATE, remove this
  ```

- [ ] **Step 2: Add `setSessionStore` call before the second `start()`**

  Before line 167 (`cronScheduler.start()`), add:
  ```typescript
  cronScheduler.setSessionStore(store);
  ```

  The final sequence should be:
  ```typescript
  // Set up cron scheduler with session manager
  cronScheduler.setSessionManager(sessionManager);
  cronScheduler.setSessionStore(store);
  cronScheduler.start();
  ```

- [ ] **Step 3: Add top-level import for `validateCronExpression`**

  Add to imports at top of file:
  ```typescript
  import { validateCronExpression } from './cron-utils.js';
  ```

### Task 20: Verify backend compiles

- [ ] **Step 1: Compile backend**
  ```bash
  pnpm build:server
  ```
  No TypeScript errors.

- [ ] **Step 2: Run all backend tests**
  ```bash
  pnpm test
  ```
  All existing + new tests pass.

---

## Chunk 7: Frontend

### Task 21: Update cron store

**Files:** `src/stores/cron.ts`

- [ ] **Step 1: Update `createTask` signature**

  Change from `intervalMinutes: number` to `cronExpression: string`:
  ```typescript
  createTask: async (workspace: string, skillName: string, cronExpression: string) => {
    // ...
    client.send({ type: 'cron.create', workspace, skillName, cronExpression });
  },
  ```

- [ ] **Step 2: Update `updateTask` signature**

  Change `intervalMinutes?: number` to `cronExpression?: string`:
  ```typescript
  updateTask: async (taskId: string, updates: { workspace?: string; skillName?: string; cronExpression?: string; enabled?: boolean }) => {
    // ...
    client.send({ type: 'cron.update', taskId, ...updates });
  },
  ```

- [ ] **Step 3: Add `scanCronTasks` action**

  ```typescript
  scanCronTasks: async () => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'cron.scanned', (data) => {
        unsub();
        unsubErr();
        set({ tasks: data.tasks as CronTask[], loading: false });
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      set({ loading: true, error: null });
      client.send({ type: 'cron.scan' });
    });
  },
  ```

  Add `scanCronTasks` to the `CronState` interface:
  ```typescript
  scanCronTasks: () => Promise<void>;
  ```

### Task 22: Update CronTaskSettings.tsx

**Files:** `src/features/settings/CronTaskSettings.tsx`

- [ ] **Step 1: Replace interval input with cron expression input**

  Remove `intervalValue`, `intervalUnit` state variables. Replace with `cronExpression` state:
  ```typescript
  const [cronExpression, setCronExpression] = useState('*/30 * * * *');
  ```

  Replace the "执行间隔" form section with:
  ```tsx
  <div className="space-y-2">
    <Label>Cron 表达式</Label>
    <Input
      type="text"
      placeholder="*/30 * * * *"
      value={cronExpression}
      onChange={(e) => setCronExpression(e.target.value)}
      className="font-mono"
    />
    <p className="text-xs text-muted-foreground">
      格式: 分 时 日 月 周 (例: 0 9 * * 1-5 = 工作日9点)
    </p>
  </div>
  ```

- [ ] **Step 2: Update `handleSave`**

  Replace interval logic with cron expression:
  ```typescript
  const handleSave = async () => {
    if (!selectedWorkspace || !selectedSkill || !cronExpression.trim()) return;

    try {
      if (isEditing) {
        await updateTask(editingTask.id, { workspace: selectedWorkspace, skillName: selectedSkill, cronExpression: cronExpression.trim() });
        setEditingTask(null);
      } else {
        await createTask(selectedWorkspace, selectedSkill, cronExpression.trim());
        setShowForm(false);
      }
      setSelectedWorkspace('');
      setSelectedSkill('');
      setCronExpression('*/30 * * * *');
    } catch (err) {
      console.error('Failed to save task:', err);
    }
  };
  ```

- [ ] **Step 3: Update `handleEdit`**

  Replace interval back-computation with:
  ```typescript
  const handleEdit = (task: CronTask) => {
    setEditingTask(task);
    setSelectedWorkspace(task.workspace);
    setSelectedSkill(task.skillName);
    setCronExpression(task.cronExpression);
  };
  ```

- [ ] **Step 4: Update `handleCancel`**

  Replace interval reset:
  ```typescript
  const handleCancel = () => {
    setShowForm(false);
    setEditingTask(null);
    setSelectedWorkspace('');
    setSelectedSkill('');
    setCronExpression('*/30 * * * *');
  };
  ```

- [ ] **Step 5: Update `TaskItem` to display cron expression**

  Replace `<span>每 {task.intervalMinutes} 分钟</span>` with:
  ```tsx
  <span className="font-mono">{task.cronExpression}</span>
  ```

- [ ] **Step 6: Add auto-scan button to header**

  Add `scanCronTasks` to the destructured store. In the header section, add a scan button next to the "新建任务" button:
  ```tsx
  <Button variant="outline" onClick={async () => { await scanCronTasks(); }} disabled={loading}>
    <Timer className="h-4 w-4 mr-2" />
    扫描 crontab
  </Button>
  ```

  Note: `Timer` icon is already imported.

- [ ] **Step 7: Update submit button disabled check**

  Change from checking workspace+skill to also checking cronExpression:
  ```tsx
  disabled={!selectedWorkspace || !selectedSkill || !cronExpression.trim()}
  ```

### Task 23: Verify frontend compiles

- [ ] **Step 1: Build frontend**
  ```bash
  pnpm build
  ```
  No TypeScript errors.

---

## Chunk 8: Integration Verification

### Task 24: Run all tests

- [ ] **Step 1: Full test suite**
  ```bash
  pnpm test
  ```
  All tests pass (existing + new cron tests).

### Task 25: Manual smoke test checklist

- [ ] **Step 1: Start dev server**
  ```bash
  ./dev.sh
  ```

- [ ] **Step 2: Verify migration**
  - If existing DB has cron tasks with `interval_minutes`, verify they appear with `cron_expression` = `*/{n} * * * *` in the UI.

- [ ] **Step 3: Verify create task**
  - Open Settings > 定时任务
  - Create a new task with cron expression `*/1 * * * *` (every minute)
  - Verify task appears in list with correct expression

- [ ] **Step 4: Verify auto-scan**
  - Create a `.claude/skills/test-skill/crontab.md` in a workspace with:
    ```
    */2 * * * *
    Test prompt content
    ```
  - Click "扫描 crontab" button
  - Verify new task is auto-created with expression `*/2 * * * *`

- [ ] **Step 5: Verify edit task**
  - Edit an existing task, change cron expression
  - Verify updated expression shown in list

- [ ] **Step 6: Verify toggle and delete**
  - Toggle a task off → verify unscheduled (no `nextRunAt`)
  - Delete a task → verify removed from list

- [ ] **Step 7: Verify crontab.md with BOM**
  - Create a crontab.md with UTF-8 BOM prefix
  - Scan → verify it is parsed correctly without errors
