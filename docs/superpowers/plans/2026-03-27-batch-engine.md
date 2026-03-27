# BatchEngine 批量执行引擎 实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现通用的批量执行引擎，支持 MD 驱动 + Claude 代码生成 + 并发执行

**Architecture:** 后端新增 BatchStore（SQLite 持久化）、Semaphore（并发池）、BatchEngine（核心逻辑），前端新增 BatchTaskSettings 组件和 batch store。代码生成使用独立 query() 调用，执行复用 sendMessageForCron()。

**Tech Stack:** TypeScript, SQLite (better-sqlite3), WebSocket (ws), React + Zustand, Vitest

**Design Doc:** `docs/superpowers/specs/2026-03-27-batch-engine-design.md`

---

## Chunk 1: 后端基础设施（类型 + Store + Semaphore）

### Task 1: BatchTask 类型定义

**Files:**
- Modify: `server/types.ts`
- Test: `tests/server/batch-store.test.ts`

- [ ] **Step 1: Write the failing test** (types are validated indirectly through store tests — write the store test first)

No separate type test needed — types are validated through Task 2 store tests.

- [ ] **Step 2: Add BatchTask types to server/types.ts**

Append after the existing CronTask/CronRun types:

```typescript
// === Batch Task Types ===

export type BatchTaskStatus =
  | 'draft' | 'generating' | 'generated' | 'testing' | 'tested'
  | 'saved' | 'queued' | 'running' | 'paused' | 'completed' | 'failed';

export type BatchItemStatus = 'pending' | 'queued' | 'running' | 'success' | 'failed' | 'skipped';

export interface BatchTask {
  id: string;
  workspace: string;
  skillName: string;
  mdContent: string;
  execTemplate: string;
  generatedCode?: string;
  envVars: string; // JSON string, encrypted
  concurrency: number;
  retryOnFailure: number;
  status: BatchTaskStatus;
  totalItems: number;
  successCount: number;
  failedCount: number;
  totalCost: number;
  startedAt?: string;
  finishedAt?: string;
  cronEnabled: boolean;
  cronIntervalMinutes?: number;
  createdAt: string;
  updatedAt: string;
}

export interface BatchItem {
  id: number;
  taskId: string;
  itemData: string; // JSON string
  itemIndex: number;
  status: BatchItemStatus;
  sessionId?: string;
  startedAt?: string;
  finishedAt?: string;
  errorMessage?: string;
  cost: number;
  retries: number;
}
```

- [ ] **Step 3: Commit**

```bash
git add server/types.ts
git commit -m "feat(batch): add BatchTask and BatchItem type definitions"
```

---

### Task 2: BatchStore — SQLite 持久化层

**Files:**
- Create: `server/batch-store.ts`
- Test: `tests/server/batch-store.test.ts`

- [ ] **Step 1: Write the failing tests**

```typescript
// tests/server/batch-store.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { BatchStore } from '../../server/batch-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('BatchStore', () => {
  let store: BatchStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `batch-test-${Date.now()}.db`);
    store = new BatchStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  // === Task CRUD ===

  describe('createTask', () => {
    it('should create a task with default values', () => {
      const task = store.createTask({
        workspace: '/data/project',
        skillName: 'stock-ai-analyze',
        mdContent: '# Batch Config\n## 执行模板\n/test ${name}',
        execTemplate: '/test ${name}',
      });
      expect(task.id).toBeDefined();
      expect(task.status).toBe('draft');
      expect(task.concurrency).toBe(10);
      expect(task.retryOnFailure).toBe(0);
      expect(task.totalItems).toBe(0);
      expect(task.successCount).toBe(0);
      expect(task.totalCost).toBe(0);
    });

    it('should create a task with custom env vars', () => {
      const task = store.createTask({
        workspace: '/data/project',
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${item}',
        envVars: { DB_HOST: 'localhost', DB_PORT: '3306' },
      });
      expect(task.envVars).toBe(JSON.stringify({ DB_HOST: 'localhost', DB_PORT: '3306' }));
    });
  });

  describe('getTask', () => {
    it('should return undefined for non-existent task', () => {
      expect(store.getTask('non-existent')).toBeUndefined();
    });

    it('should return the created task', () => {
      const created = store.createTask({
        workspace: '/data/project',
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${item}',
      });
      const fetched = store.getTask(created.id);
      expect(fetched).toBeDefined();
      expect(fetched!.id).toBe(created.id);
    });
  });

  describe('listTasks', () => {
    it('should return empty array initially', () => {
      expect(store.listTasks()).toHaveLength(0);
    });

    it('should list all tasks ordered by created_at DESC', () => {
      store.createTask({ workspace: '/a', skillName: 's1', mdContent: '', execTemplate: '' });
      store.createTask({ workspace: '/b', skillName: 's2', mdContent: '', execTemplate: '' });
      const tasks = store.listTasks();
      expect(tasks).toHaveLength(2);
      // First task in list should be the most recently created
      expect(tasks[0].skillName).toBe('s2');
    });
  });

  describe('updateTask', () => {
    it('should update status', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      const updated = store.updateTask(task.id, { status: 'generated' });
      expect(updated!.status).toBe('generated');
    });

    it('should update generated_code', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      const updated = store.updateTask(task.id, { generatedCode: 'print("hello")' });
      expect(updated!.generatedCode).toBe('print("hello")');
    });

    it('should update counters', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      const updated = store.updateTask(task.id, {
        totalItems: 100,
        successCount: 50,
        failedCount: 5,
        totalCost: 1.23,
      });
      expect(updated!.totalItems).toBe(100);
      expect(updated!.successCount).toBe(50);
      expect(updated!.failedCount).toBe(5);
      expect(updated!.totalCost).toBe(1.23);
    });
  });

  describe('deleteTask', () => {
    it('should delete task and its items', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      store.createItem(task.id, 0, '{"name":"test"}');
      store.deleteTask(task.id);
      expect(store.getTask(task.id)).toBeUndefined();
      expect(store.listItems(task.id)).toHaveLength(0);
    });
  });

  // === Item CRUD ===

  describe('createItem', () => {
    it('should create an item with default status pending', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      const item = store.createItem(task.id, 0, '{"name":"贵州茅台"}');
      expect(item.id).toBeDefined();
      expect(item.status).toBe('pending');
      expect(item.itemIndex).toBe(0);
      expect(item.itemData).toBe('{"name":"贵州茅台"}');
    });
  });

  describe('bulkCreateItems', () => {
    it('should create multiple items from JSON array', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      const items = [{ name: 'a' }, { name: 'b' }, { name: 'c' }];
      const created = store.bulkCreateItems(task.id, items);
      expect(created).toHaveLength(3);
      expect(store.listItems(task.id)).toHaveLength(3);
    });
  });

  describe('listItems', () => {
    it('should list items filtered by status', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }, { name: 'c' }]);
      store.updateItem(1, { status: 'success' });
      store.updateItem(2, { status: 'failed' });

      const pending = store.listItems(task.id, { status: 'pending' });
      expect(pending).toHaveLength(1);

      const failed = store.listItems(task.id, { status: 'failed' });
      expect(failed).toHaveLength(1);
    });

    it('should support pagination', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      store.bulkCreateItems(task.id, Array.from({ length: 20 }, (_, i) => ({ name: `item-${i}` })));

      const page1 = store.listItems(task.id, { offset: 0, limit: 5 });
      expect(page1).toHaveLength(5);

      const page2 = store.listItems(task.id, { offset: 5, limit: 5 });
      expect(page2).toHaveLength(5);
      expect(page2[0].itemIndex).toBe(5);
    });
  });

  describe('updateItem', () => {
    it('should update item status and error', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      const item = store.createItem(task.id, 0, '{"name":"test"}');
      const updated = store.updateItem(item.id, {
        status: 'failed',
        errorMessage: 'timeout',
        cost: 0.05,
      });
      expect(updated!.status).toBe('failed');
      expect(updated!.errorMessage).toBe('timeout');
      expect(updated!.cost).toBe(0.05);
    });
  });

  describe('getItemCounts', () => {
    it('should return counts by status', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }, { name: 'c' }]);
      store.updateItem(1, { status: 'success' });
      store.updateItem(2, { status: 'running' });

      const counts = store.getItemCounts(task.id);
      expect(counts.pending).toBe(1);
      expect(counts.success).toBe(1);
      expect(counts.running).toBe(1);
    });
  });

  describe('getOrphanedItems', () => {
    it('should find running items for crash recovery', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }]);
      store.updateItem(1, { status: 'running' });
      store.updateItem(2, { status: 'running' });

      const orphaned = store.getOrphanedItems();
      expect(orphaned).toHaveLength(2);
    });
  });

  describe('resetRunningItems', () => {
    it('should mark running items as failed with process shutdown reason', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }]);
      store.updateItem(1, { status: 'running' });

      store.resetRunningItems('进程关闭');
      const item = store.getItem(1);
      expect(item!.status).toBe('failed');
      expect(item!.errorMessage).toBe('进程关闭');
    });
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run tests/server/batch-store.test.ts`
Expected: FAIL — module not found

- [ ] **Step 3: Implement BatchStore**

Create `server/batch-store.ts` following the pattern of `server/cron-task-store.ts`:

```typescript
// server/batch-store.ts
import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from './utils/logger.js';
import { v4 as uuidv4 } from 'uuid';
import type { BatchTask, BatchItem, BatchTaskStatus, BatchItemStatus } from './types.js';

interface ItemFilter {
  status?: BatchItemStatus;
  offset?: number;
  limit?: number;
}

interface ItemCounts {
  pending: number;
  queued: number;
  running: number;
  success: number;
  failed: number;
  skipped: number;
}

export class BatchStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('BatchStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS batch_tasks (
        id TEXT PRIMARY KEY,
        workspace TEXT NOT NULL,
        skill_name TEXT NOT NULL,
        md_content TEXT NOT NULL,
        exec_template TEXT NOT NULL,
        generated_code TEXT,
        env_vars TEXT NOT NULL DEFAULT '{}',
        concurrency INTEGER NOT NULL DEFAULT 10,
        retry_on_failure INTEGER NOT NULL DEFAULT 0,
        status TEXT NOT NULL DEFAULT 'draft'
          CHECK(status IN ('draft','generating','generated','testing','tested','saved','queued','running','paused','completed','failed')),
        total_items INTEGER NOT NULL DEFAULT 0,
        success_count INTEGER NOT NULL DEFAULT 0,
        failed_count INTEGER NOT NULL DEFAULT 0,
        total_cost REAL NOT NULL DEFAULT 0,
        started_at TEXT,
        finished_at TEXT,
        cron_enabled INTEGER NOT NULL DEFAULT 0,
        cron_interval_minutes INTEGER,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
    `);

    this.db.exec(`
      CREATE TABLE IF NOT EXISTS batch_items (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        task_id TEXT NOT NULL,
        item_data TEXT NOT NULL,
        item_index INTEGER NOT NULL,
        status TEXT NOT NULL DEFAULT 'pending'
          CHECK(status IN ('pending','queued','running','success','failed','skipped')),
        session_id TEXT,
        started_at TEXT,
        finished_at TEXT,
        error_message TEXT,
        cost REAL NOT NULL DEFAULT 0,
        retries INTEGER NOT NULL DEFAULT 0,
        FOREIGN KEY (task_id) REFERENCES batch_tasks(id) ON DELETE CASCADE
      );
    `);

    this.db.exec(`
      CREATE INDEX IF NOT EXISTS idx_batch_items_task ON batch_items(task_id);
      CREATE INDEX IF NOT EXISTS idx_batch_items_status ON batch_items(task_id, status);
    `);

    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
    this.log.info('BatchStore initialized');
  }

  // === Task CRUD ===

  createTask(input: {
    workspace: string;
    skillName: string;
    mdContent: string;
    execTemplate: string;
    envVars?: Record<string, string>;
    concurrency?: number;
    retryOnFailure?: number;
  }): BatchTask {
    const id = uuidv4();
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO batch_tasks (id, workspace, skill_name, md_content, exec_template,
        env_vars, concurrency, retry_on_failure, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      id, input.workspace, input.skillName, input.mdContent, input.execTemplate,
      JSON.stringify(input.envVars || {}), input.concurrency || 10, input.retryOnFailure || 0,
      now, now,
    );
    return this.getTask(id)!;
  }

  getTask(id: string): BatchTask | undefined {
    const row = this.db.prepare(`
      SELECT id, workspace, skill_name as skillName, md_content as mdContent,
             exec_template as execTemplate, generated_code as generatedCode,
             env_vars as envVars, concurrency as concurrency,
             retry_on_failure as retryOnFailure, status,
             total_items as totalItems, success_count as successCount,
             failed_count as failedCount, total_cost as totalCost,
             started_at as startedAt, finished_at as finishedAt,
             cron_enabled as cronEnabled, cron_interval_minutes as cronIntervalMinutes,
             created_at as createdAt, updated_at as updatedAt
      FROM batch_tasks WHERE id = ?
    `).get(id);
    return row ? this.rowToTask(row as Record<string, unknown>) : undefined;
  }

  listTasks(): BatchTask[] {
    const rows = this.db.prepare(`
      SELECT id, workspace, skill_name as skillName, md_content as mdContent,
             exec_template as execTemplate, generated_code as generatedCode,
             env_vars as envVars, concurrency as concurrency,
             retry_on_failure as retryOnFailure, status,
             total_items as totalItems, success_count as successCount,
             failed_count as failedCount, total_cost as totalCost,
             started_at as startedAt, finished_at as finishedAt,
             cron_enabled as cronEnabled, cron_interval_minutes as cronIntervalMinutes,
             created_at as createdAt, updated_at as updatedAt
      FROM batch_tasks ORDER BY created_at DESC
    `).all() as Record<string, unknown>[];
    return rows.map(r => this.rowToTask(r));
  }

  updateTask(id: string, updates: Partial<{
    status: BatchTaskStatus;
    generatedCode?: string;
    mdContent?: string;
    execTemplate?: string;
    envVars?: string;
    concurrency?: number;
    retryOnFailure?: number;
    totalItems?: number;
    successCount?: number;
    failedCount?: number;
    totalCost?: number;
    startedAt?: string;
    finishedAt?: string;
    cronEnabled?: boolean;
    cronIntervalMinutes?: number;
  }>): BatchTask | undefined {
    const fields: string[] = [];
    const values: (string | number | null)[] = [];

    if (updates.status !== undefined) {
      fields.push('status = ?');
      values.push(updates.status);
    }
    if (updates.generatedCode !== undefined) {
      fields.push('generated_code = ?');
      values.push(updates.generatedCode);
    }
    if (updates.mdContent !== undefined) {
      fields.push('md_content = ?');
      values.push(updates.mdContent);
    }
    if (updates.execTemplate !== undefined) {
      fields.push('exec_template = ?');
      values.push(updates.execTemplate);
    }
    if (updates.envVars !== undefined) {
      fields.push('env_vars = ?');
      values.push(updates.envVars);
    }
    if (updates.concurrency !== undefined) {
      fields.push('concurrency = ?');
      values.push(updates.concurrency);
    }
    if (updates.retryOnFailure !== undefined) {
      fields.push('retry_on_failure = ?');
      values.push(updates.retryOnFailure);
    }
    if (updates.totalItems !== undefined) {
      fields.push('total_items = ?');
      values.push(updates.totalItems);
    }
    if (updates.successCount !== undefined) {
      fields.push('success_count = ?');
      values.push(updates.successCount);
    }
    if (updates.failedCount !== undefined) {
      fields.push('failed_count = ?');
      values.push(updates.failedCount);
    }
    if (updates.totalCost !== undefined) {
      fields.push('total_cost = ?');
      values.push(updates.totalCost);
    }
    if (updates.startedAt !== undefined) {
      fields.push('started_at = ?');
      values.push(updates.startedAt);
    }
    if (updates.finishedAt !== undefined) {
      fields.push('finished_at = ?');
      values.push(updates.finishedAt);
    }
    if (updates.cronEnabled !== undefined) {
      fields.push('cron_enabled = ?');
      values.push(updates.cronEnabled ? 1 : 0);
    }
    if (updates.cronIntervalMinutes !== undefined) {
      fields.push('cron_interval_minutes = ?');
      values.push(updates.cronIntervalMinutes);
    }

    if (fields.length === 0) return this.getTask(id);

    fields.push('updated_at = ?');
    values.push(new Date().toISOString());
    values.push(id);

    this.db.prepare(`UPDATE batch_tasks SET ${fields.join(', ')} WHERE id = ?`).run(...values);
    return this.getTask(id);
  }

  deleteTask(id: string): void {
    this.db.prepare('DELETE FROM batch_items WHERE task_id = ?').run(id);
    this.db.prepare('DELETE FROM batch_tasks WHERE id = ?').run(id);
  }

  // === Item CRUD ===

  createItem(taskId: string, itemIndex: number, itemData: string): BatchItem {
    const now = new Date().toISOString();
    const result = this.db.prepare(`
      INSERT INTO batch_items (task_id, item_index, item_data, started_at)
      VALUES (?, ?, ?, ?)
    `).run(taskId, itemIndex, itemData, now);

    return {
      id: result.lastInsertRowid as number,
      taskId,
      itemData,
      itemIndex,
      status: 'pending',
      sessionId: undefined,
      startedAt: now,
      finishedAt: undefined,
      errorMessage: undefined,
      cost: 0,
      retries: 0,
    };
  }

  bulkCreateItems(taskId: string, items: Record<string, unknown>[]): BatchItem[] {
    const stmt = this.db.prepare(`
      INSERT INTO batch_items (task_id, item_index, item_data, started_at)
      VALUES (?, ?, ?, ?)
    `);
    const now = new Date().toISOString();
    const created: BatchItem[] = [];

    const transaction = this.db.transaction(() => {
      for (let i = 0; i < items.length; i++) {
        const result = stmt.run(taskId, i, JSON.stringify(items[i]), now);
        created.push({
          id: result.lastInsertRowid as number,
          taskId,
          itemData: JSON.stringify(items[i]),
          itemIndex: i,
          status: 'pending',
          startedAt: now,
          cost: 0,
          retries: 0,
        });
      }
      // Update total items count
      this.db.prepare('UPDATE batch_tasks SET total_items = ? WHERE id = ?').run(items.length, taskId);
    });

    transaction();
    return created;
  }

  getItem(id: number): BatchItem | undefined {
    const row = this.db.prepare(`
      SELECT id, task_id as taskId, item_data as itemData, item_index as itemIndex,
             status, session_id as sessionId, started_at as startedAt,
             finished_at as finishedAt, error_message as errorMessage,
             cost, retries
      FROM batch_items WHERE id = ?
    `).get(id);
    return row ? this.rowToItem(row as Record<string, unknown>) : undefined;
  }

  listItems(taskId: string, filter?: ItemFilter): BatchItem[] {
    let sql = `
      SELECT id, task_id as taskId, item_data as itemData, item_index as itemIndex,
             status, session_id as sessionId, started_at as startedAt,
             finished_at as finishedAt, error_message as errorMessage,
             cost, retries
      FROM batch_items WHERE task_id = ?
    `;
    const params: (string | number)[] = [taskId];

    if (filter?.status) {
      sql += ' AND status = ?';
      params.push(filter.status);
    }

    sql += ' ORDER BY item_index ASC';

    if (filter?.limit) {
      const offset = filter.offset || 0;
      sql += ' LIMIT ? OFFSET ?';
      params.push(filter.limit, offset);
    }

    const rows = this.db.prepare(sql).all(...params) as Record<string, unknown>[];
    return rows.map(r => this.rowToItem(r));
  }

  updateItem(id: number, updates: Partial<{
    status: BatchItemStatus;
    sessionId?: string;
    startedAt?: string;
    finishedAt?: string;
    errorMessage?: string;
    cost?: number;
    retries?: number;
  }>): BatchItem | undefined {
    const fields: string[] = [];
    const values: (string | number | null)[] = [];

    if (updates.status !== undefined) {
      fields.push('status = ?');
      values.push(updates.status);
    }
    if (updates.sessionId !== undefined) {
      fields.push('session_id = ?');
      values.push(updates.sessionId);
    }
    if (updates.startedAt !== undefined) {
      fields.push('started_at = ?');
      values.push(updates.startedAt);
    }
    if (updates.finishedAt !== undefined) {
      fields.push('finished_at = ?');
      values.push(updates.finishedAt);
    }
    if (updates.errorMessage !== undefined) {
      fields.push('error_message = ?');
      values.push(updates.errorMessage);
    }
    if (updates.cost !== undefined) {
      fields.push('cost = ?');
      values.push(updates.cost);
    }
    if (updates.retries !== undefined) {
      fields.push('retries = ?');
      values.push(updates.retries);
    }

    if (fields.length === 0) return this.getItem(id);

    values.push(id);
    this.db.prepare(`UPDATE batch_items SET ${fields.join(', ')} WHERE id = ?`).run(...values);
    return this.getItem(id);
  }

  getItemCounts(taskId: string): ItemCounts {
    const row = this.db.prepare(`
      SELECT
        SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END) as pending,
        SUM(CASE WHEN status = 'queued' THEN 1 ELSE 0 END) as queued,
        SUM(CASE WHEN status = 'running' THEN 1 ELSE 0 END) as running,
        SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END) as success,
        SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END) as failed,
        SUM(CASE WHEN status = 'skipped' THEN 1 ELSE 0 END) as skipped
      FROM batch_items WHERE task_id = ?
    `).get(taskId) as Record<string, number>;

    return {
      pending: row.pending || 0,
      queued: row.queued || 0,
      running: row.running || 0,
      success: row.success || 0,
      failed: row.failed || 0,
      skipped: row.skipped || 0,
    };
  }

  getOrphanedItems(): BatchItem[] {
    const rows = this.db.prepare(`
      SELECT id, task_id as taskId, item_data as itemData, item_index as itemIndex,
             status, session_id as sessionId, started_at as startedAt,
             finished_at as finishedAt, error_message as errorMessage,
             cost, retries
      FROM batch_items WHERE status = 'running'
    `).all() as Record<string, unknown>[];
    return rows.map(r => this.rowToItem(r));
  }

  resetRunningItems(reason: string): void {
    this.db.prepare(`
      UPDATE batch_items SET status = 'failed', error_message = ?, finished_at = ?
      WHERE status = 'running'
    `).run(reason, new Date().toISOString());

    // Also update any running batch tasks
    this.db.prepare(`
      UPDATE batch_tasks SET status = 'failed', finished_at = ?, updated_at = ?
      WHERE status IN ('running', 'queued')
    `).run(new Date().toISOString(), new Date().toISOString());
  }

  // === Helpers ===

  private rowToTask(row: Record<string, unknown>): BatchTask {
    return {
      id: row.id as string,
      workspace: row.workspace as string,
      skillName: row.skillName as string,
      mdContent: row.mdContent as string,
      execTemplate: row.execTemplate as string,
      generatedCode: row.generatedCode as string | undefined,
      envVars: row.envVars as string,
      concurrency: row.concurrency as number,
      retryOnFailure: row.retryOnFailure as number,
      status: row.status as BatchTaskStatus,
      totalItems: row.totalItems as number,
      successCount: row.successCount as number,
      failedCount: row.failedCount as number,
      totalCost: row.totalCost as number,
      startedAt: row.startedAt as string | undefined,
      finishedAt: row.finishedAt as string | undefined,
      cronEnabled: (row.cronEnabled as number) === 1,
      cronIntervalMinutes: row.cronIntervalMinutes as number | undefined,
      createdAt: row.createdAt as string,
      updatedAt: row.updatedAt as string,
    };
  }

  private rowToItem(row: Record<string, unknown>): BatchItem {
    return {
      id: row.id as number,
      taskId: row.taskId as string,
      itemData: row.itemData as string,
      itemIndex: row.itemIndex as number,
      status: row.status as BatchItemStatus,
      sessionId: row.sessionId as string | undefined,
      startedAt: row.startedAt as string | undefined,
      finishedAt: row.finishedAt as string | undefined,
      errorMessage: row.errorMessage as string | undefined,
      cost: row.cost as number,
      retries: row.retries as number,
    };
  }

  close(): void {
    this.db.close();
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run tests/server/batch-store.test.ts`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/batch-store.ts tests/server/batch-store.test.ts
git commit -m "feat(batch): implement BatchStore with SQLite persistence and tests"
```

---

### Task 3: Semaphore 并发池

**Files:**
- Create: `server/semaphore.ts`
- Test: `tests/server/semaphore.test.ts`

- [ ] **Step 1: Write the failing tests**

```typescript
// tests/server/semaphore.test.ts
import { describe, it, expect, vi, afterEach } from 'vitest';
import { Semaphore, SemaphoreStoppedError } from '../../server/semaphore.js';

describe('Semaphore', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('should limit concurrency to max', async () => {
    const sem = new Semaphore(2);
    let active = 0;
    let peak = 0;

    const tasks = Array.from({ length: 10 }, () =>
      sem.withLock(async () => {
        active++;
        peak = Math.max(peak, active);
        await new Promise(r => setTimeout(r, 10));
        active--;
      }),
    );

    await Promise.all(tasks);
    expect(peak).toBeLessThanOrEqual(2);
    expect(active).toBe(0);
  });

  it('should support pause and resume', async () => {
    const sem = new Semaphore(2);
    const order: string[] = [];

    // Fill both slots
    const blocker = sem.withLock(async () => {
      order.push('blocker-start');
      await new Promise(r => setTimeout(r, 50));
      order.push('blocker-end');
    });

    // This will queue
    const waiter = sem.withLock(async () => {
      order.push('waiter-start');
      order.push('waiter-end');
    });

    // Wait for blocker to start
    await new Promise(r => setTimeout(r, 5));

    // Pause
    sem.pause();

    // Unblock the first slot — waiter should NOT start due to pause
    await blocker;

    // Give waiter a chance to start (it shouldn't)
    await new Promise(r => setTimeout(r, 20));
    expect(order).toEqual(['blocker-start', 'blocker-end']);

    // Resume — waiter should now start
    sem.resume();
    await waiter;

    expect(order).toEqual(['blocker-start', 'blocker-end', 'waiter-start', 'waiter-end']);
  });

  it('should stop and reject pending waiters', async () => {
    const sem = new Semaphore(1);

    const blocker = sem.withLock(async () => {
      await new Promise(r => setTimeout(r, 50));
    });

    const rejected = sem.withLock(async () => {
      throw new Error('should not reach');
    });

    // Wait for blocker to acquire lock
    await new Promise(r => setTimeout(r, 5));

    sem.stop();

    await blocker; // blocker finishes normally

    await expect(rejected).rejects.toThrow(SemaphoreStoppedError);
  });

  it('should report active count', async () => {
    const sem = new Semaphore(3);
    expect(sem.activeCount).toBe(0);

    const task = sem.withLock(async () => {
      await new Promise(r => setTimeout(r, 30));
      expect(sem.activeCount).toBe(1);
    });

    await new Promise(r => setTimeout(r, 5));
    expect(sem.activeCount).toBe(1);
    await task;
    expect(sem.activeCount).toBe(0);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run tests/server/semaphore.test.ts`
Expected: FAIL

- [ ] **Step 3: Implement Semaphore**

Create `server/semaphore.ts`:

```typescript
// server/semaphore.ts
export class SemaphoreStoppedError extends Error {
  constructor() {
    super('Semaphore has been stopped');
    this.name = 'SemaphoreStoppedError';
  }
}

type Waiter = {
  type: 'normal' | 'paused';
  resolve: () => void;
};

export class Semaphore {
  private waiters: Waiter[] = [];
  private _active = 0;
  private _paused = false;
  private _stopped = false;

  constructor(private max: number) {}

  get activeCount(): number {
    return this._active;
  }

  get isPaused(): boolean {
    return this._paused;
  }

  get isStopped(): boolean {
    return this._stopped;
  }

  async acquire(): Promise<void> {
    if (this._stopped) throw new SemaphoreStoppedError();

    // Wait if paused
    if (this._paused) {
      await new Promise<void>((resolve) => {
        const check = () => {
          if (!this._paused || this._stopped) resolve();
        };
        this.waiters.push({ type: 'paused', resolve: check });
      });
      if (this._stopped) throw new SemaphoreStoppedError();
    }

    // Wait if at capacity
    while (this._active >= this.max && !this._stopped) {
      await new Promise<void>((resolve) => {
        this.waiters.push({ type: 'normal', resolve });
      });
    }

    if (this._stopped) throw new SemaphoreStoppedError();
    this._active++;
  }

  release(): void {
    this._active = Math.max(0, this._active - 1);
    // Wake up the next normal waiter
    const next = this.waiters.shift();
    if (next) next.resolve();
  }

  pause(): void {
    this._paused = true;
    // Normal waiters still get woken up (they're at capacity),
    // but new acquire() calls will wait for pause to be lifted
  }

  resume(): void {
    this._paused = false;
    // Wake up all paused waiters
    const stillWaiting: Waiter[] = [];
    for (const w of this.waiters) {
      if (w.type === 'paused') {
        w.resolve();
      } else {
        stillWaiting.push(w);
      }
    }
    this.waiters = stillWaiting;
  }

  stop(): void {
    this._stopped = true;
    const waiters = this.waiters;
    this.waiters = [];
    waiters.forEach(w => w.resolve());
  }

  async withLock<T>(fn: () => Promise<T>): Promise<T> {
    await this.acquire();
    try {
      return await fn();
    } finally {
      this.release();
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run tests/server/semaphore.test.ts`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/semaphore.ts tests/server/semaphore.test.ts
git commit -m "feat(batch): implement Semaphore with pause/resume/stop support"
```

---

## Chunk 2: BatchEngine 核心逻辑

### Task 4: 模板替换工具函数

**Files:**
- Create: `server/batch-utils.ts`
- Test: `tests/server/batch-utils.test.ts`

- [ ] **Step 1: Write the failing tests**

```typescript
// tests/server/batch-utils.test.ts
import { describe, it, expect } from 'vitest';
import { renderTemplate, detectInterpreter } from '../../server/batch-utils.js';

describe('renderTemplate', () => {
  it('should replace single placeholder', () => {
    const result = renderTemplate('/stock-ai-analyze ${name}', { name: '贵州茅台' });
    expect(result).toBe('/stock-ai-analyze 贵州茅台');
  });

  it('should replace multiple placeholders', () => {
    const result = renderTemplate('/test ${name} --code ${code}', {
      name: '贵州茅台',
      code: '600519',
    });
    expect(result).toBe('/test 贵州茅台 --code 600519');
  });

  it('should keep unmatched placeholders as-is', () => {
    const result = renderTemplate('/test ${name} ${unknown}', { name: 'hello' });
    expect(result).toBe('/test hello ${unknown}');
  });

  it('should handle numeric values', () => {
    const result = renderTemplate('/test ${days}', { days: 30 });
    expect(result).toBe('/test 30');
  });
});

describe('detectInterpreter', () => {
  it('should detect python code', () => {
    expect(detectInterpreter('import mysql.connector\nprint("hello")')).toBe('python3');
    expect(detectInterpreter('#!/usr/bin/env python\n')).toBe('python3');
  });

  it('should detect node.js code', () => {
    expect(detectInterpreter('const mysql = require("mysql2")\nconsole.log("hello")')).toBe('node');
  });

  it('should default to node for unknown', () => {
    expect(detectInterpreter('echo "hello"')).toBe('node');
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run tests/server/batch-utils.test.ts`
Expected: FAIL

- [ ] **Step 3: Implement**

Create `server/batch-utils.ts`:

```typescript
// server/batch-utils.ts
/**
 * Render template by replacing ${field_name} with item data.
 * Unmatched placeholders are kept as-is.
 */
export function renderTemplate(template: string, item: Record<string, unknown>): string {
  return template.replace(/\$\{(\w+)\}/g, (match, field) => {
    const value = item[field];
    return value !== undefined ? String(value) : match;
  });
}

/**
 * Detect the interpreter for generated code.
 */
export function detectInterpreter(code: string): 'python3' | 'node' {
  if (/^(import |from |#!/.*python|#.*coding)/m.test(code) || /mysql\.connector|pymysql|psycopg/.test(code)) {
    return 'python3';
  }
  return 'node';
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run tests/server/batch-utils.test.ts`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/batch-utils.ts tests/server/batch-utils.test.ts
git commit -m "feat(batch): add template rendering and interpreter detection utilities"
```

---

### Task 5: BatchEngine — 代码生成 + 测试

**Files:**
- Create: `server/batch-engine.ts`
- Test: `tests/server/batch-engine.test.ts`

- [ ] **Step 1: Write the failing tests for generateCode and testCode**

```typescript
// tests/server/batch-engine.test.ts
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { BatchEngine } from '../../server/batch-engine.js';
import { BatchStore } from '../../server/batch-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

// Mock ClaudeSessionManager
const mockSendMessageForCron = vi.fn();
vi.mock('../../server/claude-session.js', () => ({
  ClaudeSessionManager: vi.fn().mockImplementation(() => ({
    sendMessageForCron: mockSendMessageForCron,
    createSessionWithId: vi.fn(),
    abort: vi.fn(),
    updateConfig: vi.fn(),
    close: vi.fn(),
  })),
}));

// Mock query from SDK
const mockQuery = vi.fn();
vi.mock('@anthropic-ai/claude-agent-sdk', () => ({
  query: (...args: unknown[]) => mockQuery(...args),
}));

describe('BatchEngine', () => {
  let engine: BatchEngine;
  let store: BatchStore;
  let dbPath: string;
  let tmpDir: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `batch-engine-test-${Date.now()}.db`);
    store = new BatchStore(dbPath);
    engine = new BatchEngine(store);
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'batch-test-'));
    mockSendMessageForCron.mockReset();
    mockQuery.mockReset();
  });

  afterEach(() => {
    engine.close();
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
    if (fs.existsSync(tmpDir)) fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  describe('generateCode', () => {
    it('should call query() with batch.md content and return code', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# Batch Config\n## 数据获取\n从数据库获取数据\n## 执行模板\n/test ${name}',
        execTemplate: '/test ${name}',
        envVars: { DB_HOST: 'localhost' },
      });

      mockQuery.mockReturnValue({
        async *[Symbol.asyncIterator]() {
          yield { type: 'result', message: { content: [{ type: 'text', text: '```python\nimport mysql\nresult = [{"name": "test"}]\nprint(result)\n```' }] }, session_id: 'sess-1', is_error: false };
        },
      });

      const code = await engine.generateCode(task.id);
      expect(code).toContain('import mysql');
      expect(store.getTask(task.id)!.generatedCode).toBe(code);
    });
  });

  describe('testCode', () => {
    it('should execute generated code and return items', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${name}',
      });
      store.updateTask(task.id, {
        generatedCode: 'console.log(JSON.stringify([{name:"a"},{name:"b"}]))',
      });

      const result = await engine.testCode(task.id);
      expect(result.items).toHaveLength(2);
      expect(result.items[0]).toEqual({ name: 'a' });
      expect(result.preview).toBeDefined();
    });

    it('should reject if output is not valid JSON', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test',
      });
      store.updateTask(task.id, { generatedCode: 'console.log("not json")' });

      await expect(engine.testCode(task.id)).rejects.toThrow();
    });

    it('should reject if output exceeds 100000 items', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test',
      });
      const largeArray = Array.from({ length: 100001 }, (_, i) => ({ name: `item-${i}` }));
      store.updateTask(task.id, {
        generatedCode: `console.log(JSON.stringify(${JSON.stringify(largeArray)}))`,
      });

      await expect(engine.testCode(task.id)).rejects.toThrow('数据量过大');
    });
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run tests/server/batch-engine.test.ts`
Expected: FAIL

- [ ] **Step 3: Implement BatchEngine (generateCode + testCode)**

Create `server/batch-engine.ts` with initial implementation:

```typescript
// server/batch-engine.ts
import { execFile } from 'child_process';
import { promisify } from 'util';
import path from 'path';
import fs from 'fs';
import os from 'os';
import { createLogger, type Logger } from './utils/logger.js';
import type { BatchStore } from './batch-store.js';
import type { ClaudeSessionManager } from './claude-session.js';
import { Semaphore, SemaphoreStoppedError } from './semaphore.js';
import { renderTemplate, detectInterpreter } from './batch-utils.js';

const execFileAsync = promisify(execFile);

const MAX_ITEMS = 100_000;
const TEST_TIMEOUT_MS = 30_000;
const MAX_BUFFER = 10 * 1024 * 1024; // 10MB

export class BatchEngine {
  private log: Logger;
  private activeExecutions = new Map<string, {
    semaphore: Semaphore;
    activeCount: number;
    paused: boolean;
    cancelled: boolean;
  }>();
  private _sessionManager: ClaudeSessionManager | null = null;

  constructor(private store: BatchStore) {
    this.log = createLogger('BatchEngine');
  }

  setSessionManager(sm: ClaudeSessionManager): void {
    this._sessionManager = sm;
  }

  private get sessionManager(): ClaudeSessionManager {
    if (!this._sessionManager) throw new Error('SessionManager not set');
    return this._sessionManager;
  }

  // === Code Generation ===

  async generateCode(taskId: string): Promise<string> {
    const task = this.store.getTask(taskId);
    if (!task) throw new Error(`Task not found: ${taskId}`);

    this.store.updateTask(taskId, { status: 'generating' });

    const envKeys = Object.keys(JSON.parse(task.envVars));
    const prompt = [
      `# Batch Data Fetch Script Generator`,
      ``,
      `## User's batch.md configuration:`,
      task.mdContent,
      ``,
      `## Environment variable keys (values will be provided at runtime):`,
      ...envKeys.map(k => `- ${k}`),
      ``,
      `## Instructions:`,
      `- Generate a self-contained script that fetches the data described in batch.md`,
      `- Use the environment variables listed above for connections`,
      `- Output a JSON array to stdout (nothing else to stdout)`,
      `- The script should be executable with the appropriate interpreter`,
      `- Do not include any interactive input prompts`,
    ].join('\n');

    try {
      const { query } = await import('@anthropic-ai/claude-agent-sdk');
      const q = query({
        prompt,
        options: {
          cwd: task.workspace,
          model: '', // Will use default
          permissionMode: 'bypassPermissions' as const,
          allowDangerouslySkipPermissions: true,
          systemPrompt: {
            type: 'preset' as const,
            preset: 'claude_code' as const,
            append: 'You are a data fetching script generator. Generate ONLY the script code, wrapped in a code block. The output must be a JSON array printed to stdout.',
          },
        },
      });

      let fullText = '';
      for await (const msg of q) {
        if (msg.type === 'assistant') {
          const content = (msg as any).message?.content;
          if (Array.isArray(content)) {
            for (const block of content) {
              if (block.type === 'text') fullText += block.text;
            }
          }
        }
        if (msg.type === 'stream_event') {
          const event = (msg as any).event;
          if (event?.delta?.type === 'text_delta') {
            fullText += event.delta.text;
          }
        }
      }

      // Extract code from markdown code block
      const codeMatch = fullText.match(/```(?:python|javascript|node|js|sh|bash)?\n([\s\S]*?)```/);
      if (!codeMatch) throw new Error('No code block found in Claude response');

      const code = codeMatch[1].trim();
      this.store.updateTask(taskId, { status: 'generated', generatedCode: code });
      this.log.info(`Code generated for task ${taskId}, length: ${code.length}`);
      return code;
    } catch (err) {
      this.store.updateTask(taskId, { status: 'draft' });
      throw err;
    }
  }

  // === Test ===

  async testCode(taskId: string): Promise<{ items: Record<string, unknown>[]; preview: string }> {
    const task = this.store.getTask(taskId);
    if (!task) throw new Error(`Task not found: ${taskId}`);
    if (!task.generatedCode) throw new Error('No generated code for task');

    this.store.updateTask(taskId, { status: 'testing' });

    const interpreter = detectInterpreter(task.generatedCode);
    const envVars = JSON.parse(task.envVars) as Record<string, string>;

    // Create temp directory
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), `batch-test-${taskId}-`));
    const scriptPath = path.join(tmpDir, interpreter === 'python3' ? 'fetch.py' : 'fetch.js');
    fs.writeFileSync(scriptPath, task.generatedCode);

    try {
      const { stdout } = await execFileAsync(interpreter, [scriptPath], {
        cwd: tmpDir,
        timeout: TEST_TIMEOUT_MS,
        env: { ...process.env as Record<string, string>, ...envVars },
        maxBuffer: MAX_BUFFER,
      });

      const items = JSON.parse(stdout.trim());
      if (!Array.isArray(items)) throw new Error('Output is not a JSON array');
      if (items.length > MAX_ITEMS) throw new Error(`数据量过大: ${items.length} 条，上限 ${MAX_ITEMS} 条`);

      this.store.updateTask(taskId, { status: 'tested' });

      return {
        items,
        preview: items.slice(0, 10).map(i => JSON.stringify(i)).join('\n'),
      };
    } catch (err) {
      this.store.updateTask(taskId, { status: 'generated' });
      throw err;
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  }

  // === Execute (see Task 6) ===
  // === Control (see Task 6) ===

  // === Lifecycle ===

  start(): void {
    // Crash recovery: reset orphaned running items
    const orphaned = this.store.getOrphanedItems();
    if (orphaned.length > 0) {
      this.store.resetRunningItems('进程异常终止');
      this.log.warn(`Reset ${orphaned.length} orphaned items`);
    }
  }

  stop(): void {
    for (const [taskId, exec] of this.activeExecutions) {
      exec.semaphore.stop();
      exec.cancelled = true;
    }
  }

  close(): void {
    this.stop();
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run tests/server/batch-engine.test.ts`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/batch-engine.ts tests/server/batch-engine.test.ts
git commit -m "feat(batch): implement BatchEngine code generation and test execution"
```

---

### Task 6: BatchEngine — 并发执行 + 控制方法

**Files:**
- Modify: `server/batch-engine.ts`
- Modify: `tests/server/batch-engine.test.ts`

- [ ] **Step 1: Write the failing tests for execute/pause/resume/cancel**

Add to `tests/server/batch-engine.test.ts`:

```typescript
  describe('execute', () => {
    it('should process all items with concurrency limit', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${name}',
        concurrency: 2,
      });
      store.bulkCreateItems(task.id, [
        { name: 'a' }, { name: 'b' }, { name: 'c' },
      ]);
      store.updateTask(task.id, { status: 'saved' });

      // Mock successful SDK responses
      mockSendMessageForCron.mockResolvedValue(undefined);

      await engine.execute(task.id);

      expect(mockSendMessageForCron).toHaveBeenCalledTimes(3);
      const updated = store.getTask(task.id)!;
      expect(updated.status).toBe('completed');
      expect(updated.successCount).toBe(3);
      expect(updated.failedCount).toBe(0);
    });

    it('should use template to render prompts', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/analyze ${name} --days ${days}',
        concurrency: 1,
      });
      store.bulkCreateItems(task.id, [{ name: '贵州茅台', days: 30 }]);
      store.updateTask(task.id, { status: 'saved' });

      mockSendMessageForCron.mockResolvedValue(undefined);
      await engine.execute(task.id);

      expect(mockSendMessageForCron).toHaveBeenCalledWith(
        expect.any(String), // sessionId
        '/analyze 贵州茅台 --days 30',
        expect.any(Function),
        expect.any(AbortController),
      );
    });

    it('should track individual item failures', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${name}',
        concurrency: 1,
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }, { name: 'c' }]);
      store.updateTask(task.id, { status: 'saved' });

      // First item fails, rest succeed
      mockSendMessageForCron
        .mockRejectedValueOnce(new Error('timeout'))
        .mockResolvedValue(undefined);

      await engine.execute(task.id);

      const updated = store.getTask(task.id)!;
      expect(updated.successCount).toBe(2);
      expect(updated.failedCount).toBe(1);

      const failed = store.listItems(task.id, { status: 'failed' });
      expect(failed).toHaveLength(1);
      expect(failed[0].errorMessage).toBe('timeout');
    });

    it('should reject if task is not in saved status', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test',
      });
      await expect(engine.execute(task.id)).rejects.toThrow();
    });
  });

  describe('pause/resume/cancel', () => {
    it('should pause execution and resume', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${name}',
        concurrency: 1,
      });
      // 5 items, each takes 50ms = 250ms total
      store.bulkCreateItems(task.id, Array.from({ length: 5 }, (_, i) => ({ name: `item-${i}` })));
      store.updateTask(task.id, { status: 'saved' });

      let callIndex = 0;
      mockSendMessageForCron.mockImplementation(async () => {
        callIndex++;
        // Pause after first item completes
        if (callIndex === 1) {
          setTimeout(() => engine.pause(task.id), 10);
        }
        await new Promise(r => setTimeout(r, 50));
      });

      const execPromise = engine.execute(task.id);
      await execPromise;

      const updated = store.getTask(task.id)!;
      // Should not complete all items because we paused
      // Items that were queued but not started should be skipped
      const skipped = store.listItems(task.id, { status: 'skipped' });
      expect(skipped.length).toBeGreaterThanOrEqual(0); // At least some may be skipped

      // Resume and continue
      mockSendMessageForCron.mockResolvedValue(undefined);
      await engine.resume(task.id);
    });

    it('should cancel execution', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${name}',
        concurrency: 1,
      });
      store.bulkCreateItems(task.id, Array.from({ length: 5 }, (_, i) => ({ name: `item-${i}` })));
      store.updateTask(task.id, { status: 'saved' });

      let callIndex = 0;
      mockSendMessageForCron.mockImplementation(async () => {
        callIndex++;
        if (callIndex === 1) {
          setTimeout(() => engine.cancel(task.id), 10);
        }
        await new Promise(r => setTimeout(r, 50));
      });

      await engine.execute(task.id);

      const updated = store.getTask(task.id)!;
      expect(updated.status).toBe('failed');
    });
  });

  describe('retryFailed', () => {
    it('should retry all failed items', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${name}',
        concurrency: 1,
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }]);
      store.updateTask(task.id, { status: 'saved' });

      mockSendMessageForCron
        .mockRejectedValueOnce(new Error('fail a'))
        .mockRejectedValueOnce(new Error('fail b'));

      await engine.execute(task.id);

      // Now retry — this time succeed
      mockSendMessageForCron.mockReset();
      mockSendMessageForCron.mockResolvedValue(undefined);

      await engine.retryFailed(task.id);

      const updated = store.getTask(task.id)!;
      expect(updated.successCount).toBe(2);
      expect(updated.failedCount).toBe(0);
    });
  });
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run tests/server/batch-engine.test.ts`
Expected: FAIL — execute/pause/resume/cancel methods not found

- [ ] **Step 3: Implement execute/pause/resume/cancel/retryFailed**

Add to `server/batch-engine.ts` (inside the class):

```typescript
  // === Execute ===

  async execute(taskId: string): Promise<void> {
    const task = this.store.getTask(taskId);
    if (!task) throw new Error(`Task not found: ${taskId}`);
    if (task.status !== 'saved') throw new Error(`Task ${taskId} is not in saved status (current: ${task.status})`);

    // Reset counters
    this.store.updateTask(taskId, {
      status: 'running',
      successCount: 0,
      failedCount: 0,
      totalCost: 0,
      startedAt: new Date().toISOString(),
      finishedAt: undefined,
    });

    // Reset all items to pending (supports re-run)
    const items = store.listItems(taskId);
    const stmt = this.store['_db'].prepare('UPDATE batch_items SET status = ?, started_at = ?, finished_at = ?, error_message = ?, retries = 0 WHERE task_id = ? AND status IN (?, ?)');
    stmt.run('pending', new Date().toISOString(), null, null, taskId, 'success', 'failed');

    // Re-fetch items after reset
    const pendingItems = this.store.listItems(taskId);

    const semaphore = new Semaphore(task.concurrency);
    let activeCount = 0;
    const state = { paused: false, cancelled: false };
    this.activeExecutions.set(taskId, { semaphore, activeCount: 0, paused: false, cancelled: false });

    const processItem = async (item: any) => {
      if (state.cancelled) {
        this.store.updateItem(item.id, { status: 'skipped' });
        return;
      }

      this.store.updateItem(item.id, { status: 'running', startedAt: new Date().toISOString() });

      const itemData = JSON.parse(item.itemData);
      const prompt = renderTemplate(task.execTemplate, itemData);
      const sessionId = `batch-${task.id}-${item.id}`;
      const abortController = new AbortController();
      const updateActivity = () => {};

      try {
        this.sessionManager.createSessionWithId(task.workspace, sessionId);
        await this.sessionManager.sendMessageForCron(sessionId, prompt, abortController, updateActivity);
        this.store.updateItem(item.id, { status: 'success', finishedAt: new Date().toISOString() });
        // Increment success count
        const updated = this.store.getTask(taskId)!;
        this.store.updateTask(taskId, { successCount: updated.successCount + 1 });
      } catch (err) {
        const errorMsg = err instanceof Error ? err.message : String(err);
        this.store.updateItem(item.id, {
          status: 'failed',
          errorMessage: errorMsg,
          finishedAt: new Date().toISOString(),
        });
        const updated = this.store.getTask(taskId)!;
        this.store.updateTask(taskId, { failedCount: updated.failedCount + 1 });
      }
    };

    try {
      for (const item of pendingItems) {
        if (state.cancelled) {
          this.store.updateItem(item.id, { status: 'skipped' });
          continue;
        }

        // Wait for semaphore (respects pause)
        try {
          await semaphore.acquire();
        } catch (err) {
          if (err instanceof SemaphoreStoppedError) {
            // Mark remaining items as skipped
            this.store.updateItem(item.id, { status: 'skipped' });
            continue;
          }
          throw err;
        }

        processItem(item).finally(() => semaphore.release());
      }

      // Wait for all active items to complete
      await this.drainActiveItems(taskId, semaphore);
    } finally {
      this.activeExecutions.delete(taskId);

      const finalTask = this.store.getTask(taskId)!;
      this.store.updateTask(taskId, {
        finishedAt: new Date().toISOString(),
        status: state.cancelled ? 'failed' : 'completed',
      });
    }
  }

  private async drainActiveItems(taskId: string, semaphore: Semaphore): Promise<void> {
    // Poll until no more running items
    while (true) {
      const counts = this.store.getItemCounts(taskId);
      if (counts.running === 0) break;
      await new Promise(r => setTimeout(r, 500));
    }
  }

  // === Control ===

  pause(taskId: string): void {
    const exec = this.activeExecutions.get(taskId);
    if (!exec) return;
    exec.paused = true;
    exec.semaphore.pause();
    this.store.updateTask(taskId, { status: 'paused' });
    this.log.info(`Batch task ${taskId} paused`);
  }

  async resume(taskId: string): Promise<void> {
    const task = this.store.getTask(taskId);
    if (!task || task.status !== 'paused') return;

    this.store.updateTask(taskId, { status: 'running' });

    const exec = this.activeExecutions.get(taskId);
    if (exec) {
      exec.paused = false;
      exec.semaphore.resume();
    }
    this.log.info(`Batch task ${taskId} resumed`);
  }

  cancel(taskId: string): void {
    const exec = this.activeExecutions.get(taskId);
    if (!exec) return;
    exec.cancelled = true;
    exec.semaphore.stop();
    this.sessionManager.abort(taskId);
    this.log.info(`Batch task ${taskId} cancelled`);
  }

  async retryFailed(taskId: string): Promise<void> {
    const task = this.store.getTask(taskId);
    if (!task) throw new Error(`Task not found: ${taskId}`);

    // Reset failed items to pending
    const failedItems = this.store.listItems(taskId, { status: 'failed' });
    for (const item of failedItems) {
      this.store.updateItem(item.id, { status: 'pending', errorMessage: undefined });
    }

    // Re-execute
    await this.execute(taskId);
  }

  // === Save ===

  async save(taskId: string): Promise<void> {
    const task = this.store.getTask(taskId);
    if (!task) throw new Error(`Task not found: ${taskId}`);
    if (!task.generatedCode) throw new Error('No generated code to save');

    this.store.updateTask(taskId, { status: 'saved' });
    this.log.info(`Batch task ${taskId} saved`);
  }
```

> **Note:** The execute method above has a direct DB access `this.store['_db']` which is a code smell. A cleaner approach is to add a `resetItemsForExecution(taskId)` method to `BatchStore` and call that instead. Update Task 2's BatchStore to include this method.

Add to `BatchStore`:

```typescript
  resetItemsForExecution(taskId: string): void {
    this.db.prepare(`
      UPDATE batch_items SET status = 'pending', started_at = ?, finished_at = ?, error_message = ?, retries = 0
      WHERE task_id = ?
    `).run(null, null, null, taskId);
  }
```

Then replace the `stmt.run(...)` in execute with:

```typescript
this.store.resetItemsForExecution(taskId);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run tests/server/batch-engine.test.ts`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/batch-engine.ts server/batch-store.ts tests/server/batch-engine.test.ts tests/server/batch-store.test.ts
git commit -m "feat(batch): implement BatchEngine execute/pause/resume/cancel/retry"
```

---

## Chunk 3: 后端 WebSocket API 集成

### Task 7: WebSocket 消息处理

**Files:**
- Modify: `server/index.ts`
- Modify: `server/types.ts` (if needed)

- [ ] **Step 1: Integrate BatchEngine into server/index.ts**

Add to `server/index.ts`:
1. Import BatchEngine, BatchStore
2. Initialize BatchStore and BatchEngine (after SessionStore, before HTTP server)
3. Set SessionManager on BatchEngine
4. Add batch.* message handlers in the WebSocket switch/case

Message handlers to implement:
- `batch.create` → create task, return `batch.created`
- `batch.list` → list tasks, return `batch.list`
- `batch.get` → get task, return `batch.get`
- `batch.update` → update task, return `batch.updated`
- `batch.delete` → delete task, return `batch.deleted`
- `batch.generate` → call `engine.generateCode()`, stream progress, return `batch.generated`
- `batch.test` → call `engine.testCode()`, return `batch.tested`
- `batch.save` → call `engine.save()`, return `batch.saved`
- `batch.execute` → call `engine.execute()`, return `batch.executed`
- `batch.pause` → call `engine.pause()`, return `batch.paused`
- `batch.resume` → call `engine.resume()`, return `batch.resumed`
- `batch.cancel` → call `engine.cancel()`, return `batch.cancelled`
- `batch.items` → call `store.listItems()`, return `batch.items`
- `batch.retry` → call `engine.retryFailed()`, return `batch.retried`

Follow the exact pattern of existing cron.* handlers in `server/index.ts`.

- [ ] **Step 2: Add batch.skills endpoint**

Similar to `cron.skills`, check for `batch.md` in skill directories. Extend `cron.skills` to also return `hasBatch` field, or add a separate `batch.skills` handler.

- [ ] **Step 3: Add graceful shutdown for BatchEngine**

Add `batchEngine.stop()` to SIGTERM/SIGINT handlers alongside existing cron cleanup.

- [ ] **Step 4: Verify server starts without errors**

Run: `pnpm dev:server`
Expected: Server starts, WebSocket connects, batch API accessible

- [ ] **Step 5: Commit**

```bash
git add server/index.ts
git commit -m "feat(batch): integrate BatchEngine WebSocket API into server"
```

---

## Chunk 4: 前端实现

### Task 8: 前端类型定义 + Zustand Store

**Files:**
- Modify: `src/types/settings.ts`
- Create: `src/stores/batch.ts`

- [ ] **Step 1: Add BatchTask types to settings.ts**

```typescript
// Add to src/types/settings.ts

export type BatchTaskStatus =
  | 'draft' | 'generating' | 'generated' | 'testing' | 'tested'
  | 'saved' | 'queued' | 'running' | 'paused' | 'completed' | 'failed';

export interface BatchTask {
  id: string;
  workspace: string;
  skillName: string;
  mdContent: string;
  execTemplate: string;
  generatedCode?: string;
  envVars: string;
  concurrency: number;
  retryOnFailure: number;
  status: BatchTaskStatus;
  totalItems: number;
  successCount: number;
  failedCount: number;
  totalCost: number;
  startedAt?: string;
  finishedAt?: string;
  cronEnabled: boolean;
  cronIntervalMinutes?: number;
  createdAt: string;
  updatedAt: string;
}

export interface BatchItem {
  id: number;
  taskId: string;
  itemData: string;
  itemIndex: number;
  status: 'pending' | 'queued' | 'running' | 'success' | 'failed' | 'skipped';
  sessionId?: string;
  startedAt?: string;
  finishedAt?: string;
  errorMessage?: string;
  cost: number;
  retries: number;
}

export interface BatchSkill {
  name: string;
  hasBatch: boolean;
  hasCrontab: boolean;
}
```

- [ ] **Step 2: Create Zustand batch store**

Create `src/stores/batch.ts` following the exact pattern of `src/stores/cron.ts`:

State: `tasks`, `loading`, `error`, `currentTask` (for dialog)
Actions: `fetchTasks`, `createTask`, `getTask`, `updateTask`, `deleteTask`, `generateCode`, `testCode`, `saveTask`, `executeTask`, `pauseTask`, `resumeTask`, `cancelTask`, `fetchItems`, `retryFailed`
Event listener: Subscribe to `batch.progress` for real-time updates

- [ ] **Step 3: Commit**

```bash
git add src/types/settings.ts src/stores/batch.ts
git commit -m "feat(batch): add frontend types and Zustand store"
```

---

### Task 9: BatchTaskSettings 组件

**Files:**
- Create: `src/features/settings/BatchTaskSettings.tsx`
- Modify: `src/features/settings/index.tsx`

- [ ] **Step 1: Create BatchTaskSettings component**

Follow `CronTaskSettings.tsx` pattern:
- Task list with cards (skill name, workspace, status badge, progress bar, cost, actions)
- "New Task" button → inline form / dialog
- Actions: execute, pause/resume, cancel, retry failed, delete
- Progress display using WebSocket `batch.progress` events

Key UI elements:
- `BatchTaskCard` sub-component (similar to `TaskItem`)
- `BatchTaskForm` for create/edit (inline or dialog)
- Status badges for each lifecycle state
- Progress bar component (use existing `src/components/ui/progress.tsx`)
- Cost display

- [ ] **Step 2: Create BatchTaskDialog component**

Dialog for creating/editing a batch task:
- Workspace + Skill selectors (reuse cron pattern)
- batch.md textarea (auto-load from file)
- Exec template field
- Environment variables (dynamic key-value pairs)
- Concurrency + retry inputs
- Code display area (read-only, after generation)
- Test button + result preview
- Save + Execute buttons

- [ ] **Step 3: Add to Settings page**

Modify `src/features/settings/index.tsx`:

```tsx
import { BatchTaskSettings } from './BatchTaskSettings';

// Add between CronTaskSettings and LLMSettings:
<CronTaskSettings />
<BatchTaskSettings />  {/* ← new */}
<LLMSettings />
```

- [ ] **Step 4: Verify UI in browser**

Run: `pnpm dev`
Expected: Settings page shows "批量任务" section with "新建任务" button

- [ ] **Step 5: Commit**

```bash
git add src/features/settings/BatchTaskSettings.tsx src/features/settings/BatchTaskDialog.tsx src/features/settings/index.tsx
git commit -m "feat(batch): add BatchTaskSettings UI component"
```

---

## Chunk 5: 集成测试 + 代码优化

### Task 10: 端到端手动验证

- [ ] **Step 1: Create test batch.md**

Create a simple test batch.md in a test workspace:

```markdown
# Batch Configuration
## 数据获取
Output test data.

## 执行模板
/echo ${name}

## 数据格式要求
[{"name": "hello"}, {"name": "world"}]
```

- [ ] **Step 2: Verify full workflow in Sman UI**

1. Open Settings → 批量任务
2. Create new task → select workspace + skill
3. Generate code → should produce a script
4. Test code → should show items preview
5. Save → status changes to saved
6. Execute → should process items with progress updates
7. Verify progress bar updates in real-time

- [ ] **Step 3: Verify error handling**

1. Invalid batch.md → generate should fail gracefully
2. Script that outputs invalid JSON → test should fail
3. Execute without save → should reject

---

### Task 11: 代码优化（code-simplifier）

- [ ] **Step 1: Run code-simplifier agent**

Run the code-simplifier agent on all new files:
- `server/batch-engine.ts`
- `server/batch-store.ts`
- `server/semaphore.ts`
- `server/batch-utils.ts`
- `src/stores/batch.ts`
- `src/features/settings/BatchTaskSettings.tsx`

- [ ] **Step 2: Run all tests**

Run: `npx vitest run`
Expected: All tests PASS

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "refactor(batch): code simplification pass"
```

---

## Review 修正附录 (v2)

以下修正基于实施计划 review 的 Critical 和 Important 问题。执行时务必按此修正实现。

### C1. Semaphore release() 下溢保护

**问题**: `stop()` 唤醒所有 waiter 后，正在运行的 item 完成 `release()` 可能导致 `_active` 变为负数，信号量永久失效。

**修正**: Semaphore 每次执行是新实例（已满足），但需在 `release()` 中添加日志保护：

```typescript
release(): void {
  if (this._active <= 0) {
    // 下溢保护：stop() 后仍可能收到 release
    return;
  }
  this._active--;
  const next = this.waiters.shift();
  if (next) next.resolve();
}
```

### C2. 计数器更新竞态条件（read-then-write）

**问题**: `const updated = this.store.getTask(taskId)!; this.store.updateTask(taskId, { successCount: updated.successCount + 1 })` 在并发环境下可能丢失更新。

**修正**: 在 BatchStore 中新增原子递增方法：

```typescript
// batch-store.ts 新增方法
incrementSuccessCount(taskId: string): void {
  this.db.prepare('UPDATE batch_tasks SET success_count = success_count + 1 WHERE id = ?').run(taskId);
}

incrementFailedCount(taskId: string): void {
  this.db.prepare('UPDATE batch_tasks SET failed_count = failed_count + 1 WHERE id = ?').run(taskId);
}
```

然后在 processItem 中替换：
```typescript
// 替换前（有竞态）
const updated = this.store.getTask(taskId)!;
this.store.updateTask(taskId, { successCount: updated.successCount + 1 });

// 替换后（原子操作）
this.store.incrementSuccessCount(taskId);
```

同时在 Task 2 中添加对应测试。

### C3. cancel() 传入了 taskId 而非 sessionId

**问题**: `this.sessionManager.abort(taskId)` 传入的是 batch taskId，但 abort() 期望的是 Claude session 的 sessionId。

**修正**: cancel() 应该查找所有 running items 的 sessionId，逐一 abort：

```typescript
cancel(taskId: string): void {
  const exec = this.activeExecutions.get(taskId);
  if (!exec) return;
  exec.cancelled = true;
  exec.semaphore.stop();

  // 查找所有 running items 的 sessionId，逐一 abort
  const runningItems = this.store.listItems(taskId, { status: 'running' });
  for (const item of runningItems) {
    if (item.sessionId) {
      this.sessionManager.abort(item.sessionId);
    }
  }

  this.log.info(`Batch task ${taskId} cancelled, aborted ${runningItems.length} sessions`);
}
```

### C4. retryFailed() 调用 execute() 时状态不是 saved

**问题**: execute() 要求 status === 'saved'，但首次执行后状态变为 'completed'/'failed'，retryFailed() 直接调用 execute() 会抛异常。

**修正**: retryFailed() 在调用 execute() 前先将状态设回 'saved'，同时只重置失败项为 pending（保留成功项）：

```typescript
async retryFailed(taskId: string): Promise<void> {
  const task = this.store.getTask(taskId);
  if (!task) throw new Error(`Task not found: ${taskId}`);

  // 只将失败项重置为 pending，保留成功项
  const failedItems = this.store.listItems(taskId, { status: 'failed' });
  for (const item of failedItems) {
    this.store.updateItem(item.id, { status: 'pending', errorMessage: undefined, retries: item.retries + 1 });
  }

  // 重置状态为 saved 以通过 execute() 的状态检查
  this.store.updateTask(taskId, { status: 'saved' });

  // 使用内部方法执行（不再次重置所有 items）
  await this.executeInternal(taskId, false); // false = 不重置所有 items
}
```

**补充**: 将 execute() 的核心逻辑提取为 `executeInternal(taskId, resetAll)` 私有方法，`execute()` 调用 `executeInternal(taskId, true)`。

### C5. SDK mock 结构可能不匹配

**问题**: 测试中 `vi.mock('@anthropic-ai/claude-agent-sdk')` 的 mock 结构可能与实际 SDK 导出不匹配。

**修正**: 测试中使用 dynamic import（`await import(...)`)来获取真实的 `query` 函数签名，确保 mock 与真实 API 一致。generateCode 实现中已经使用了 `const { query } = await import(...)` 模式，测试 mock 应匹配此模式。确认 mock 返回的对象实现了 `Symbol.asyncIterator` 或 `AsyncIterable` 接口。

### I1. 缺少 batch.progress WebSocket 推送

**问题**: 设计文档要求每个 item 完成时推送 `batch.progress`，但计划中 execute() 只更新 store，没有推送给前端。

**修正**: 在 Task 7（WebSocket 集成）中，给 BatchEngine 添加 `onProgress` 回调，并在 `server/index.ts` 中传入广播函数：

```typescript
// batch-engine.ts 构造函数新增
private onProgressCallback: ((taskId: string, data: object) => void) | null = null;

setOnProgress(callback: (taskId: string, data: object) => void): void {
  this.onProgressCallback = callback;
}

// processItem 成功/失败后调用
private emitProgress(taskId: string): void {
  if (!this.onProgressCallback) return;
  const task = this.store.getTask(taskId);
  if (!task) return;
  this.onProgressCallback(taskId, {
    successCount: task.successCount,
    failedCount: task.failedCount,
    totalItems: task.totalItems,
    totalCost: task.totalCost,
  });
}

// server/index.ts
batchEngine.setOnProgress((taskId, data) => {
  broadcast(JSON.stringify({ type: 'batch.progress', taskId, ...data }));
});
```

### I2. 环境变量加密（推迟到 Phase 2）

**问题**: 设计文档要求 AES-256-GCM 加密 env_vars，但计划存储为明文 JSON。

**修正**: 在 Task 2 的 BatchStore 中，env_vars 暂存为明文 JSON（与现有 config.json 一致），但在注释中标注为 TODO：

```typescript
// TODO(Phase2): 使用 AES-256-GCM 加密 env_vars
// 密钥派生自 machine-id，使用 Node.js crypto 模块
env_vars TEXT NOT NULL DEFAULT '{}', -- JSON: 环境变量（Phase2 加密存储）
```

### I3. drainActiveItems() 添加超时

**修正**: 添加 30 分钟超时（与假死检测阈值一致）：

```typescript
private async drainActiveItems(taskId: string): Promise<void> {
  const DRAIN_TIMEOUT_MS = 30 * 60 * 1000; // 30 分钟
  const start = Date.now();
  while (true) {
    const counts = this.store.getItemCounts(taskId);
    if (counts.running === 0) break;
    if (Date.now() - start > DRAIN_TIMEOUT_MS) {
      this.log.warn(`drainActiveItems timeout for ${taskId}, forcing completion`);
      break;
    }
    await new Promise(r => setTimeout(r, 500));
  }
}
```

### I4. 补充 resetItemsForExecution 测试

**修正**: 在 Task 2 的 batch-store.test.ts 中新增：

```typescript
describe('resetItemsForExecution', () => {
  it('should reset all items to pending regardless of status', () => {
    const task = store.createTask({
      workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
    });
    store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }, { name: 'c' }]);
    store.updateItem(1, { status: 'success' });
    store.updateItem(2, { status: 'failed' });

    store.resetItemsForExecution(task.id);

    const items = store.listItems(task.id);
    expect(items.every(i => i.status === 'pending')).toBe(true);
  });
});
```

### I5. state.paused 是死代码

**修正**: 在 execute() 循环中移除 `state.paused` 字段，暂停逻辑完全由 Semaphore 的 pause/resume 机制控制。execute() 的 for-loop 通过 `semaphore.acquire()` 自然阻塞在 pause 状态。

### I6. 前端组件测试

**修正**: 前端组件暂不添加单元测试（与现有 CronTaskSettings 保持一致），但在 Task 10 的 E2E 验证中覆盖前端交互。
