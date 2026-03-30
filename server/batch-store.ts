import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from './utils/logger';
import { v4 as uuidv4 } from 'uuid';
import type { BatchTask, BatchItem, BatchTaskStatus, BatchItemStatus } from './types';

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
  private static readonly ITEM_COLUMNS = [
    'id', 'task_id as taskId', 'item_data as itemData', 'item_index as itemIndex',
    'status', 'session_id as sessionId', 'started_at as startedAt',
    'finished_at as finishedAt', 'error_message as errorMessage',
    'cost', 'retries',
  ].join(', ');

  private static readonly TASK_COLUMNS = [
    'id', 'workspace', 'skill_name as skillName', 'md_content as mdContent',
    'exec_template as execTemplate', 'generated_code as generatedCode',
    'env_vars as envVars', 'concurrency as concurrency',
    'retry_on_failure as retryOnFailure', 'status',
    'total_items as totalItems', 'success_count as successCount',
    'failed_count as failedCount', 'total_cost as totalCost',
    'started_at as startedAt', 'finished_at as finishedAt',
    'cron_enabled as cronEnabled', 'cron_interval_minutes as cronIntervalMinutes',
    'created_at as createdAt', 'updated_at as updatedAt',
  ].join(', ');

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
        -- TODO(Phase2): 使用 AES-256-GCM 加密 env_vars
        -- 密钥派生自 machine-id，使用 Node.js crypto 模块
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
    const row = this.db.prepare(
      `SELECT ${BatchStore.TASK_COLUMNS} FROM batch_tasks WHERE id = ?`,
    ).get(id);
    return row ? this.rowToTask(row as Record<string, unknown>) : undefined;
  }

  listTasks(): BatchTask[] {
    const rows = this.db.prepare(
      `SELECT ${BatchStore.TASK_COLUMNS} FROM batch_tasks ORDER BY created_at DESC`,
    ).all() as Record<string, unknown>[];
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
    const hasUpdates = Object.values(updates).some(v => v !== undefined);
    if (!hasUpdates) return this.getTask(id);

    this.buildDynamicUpdate(
      'batch_tasks', id, updates,
      BatchStore.TASK_COLUMN_MAP,
      [{ field: 'updated_at', value: new Date().toISOString() }],
    );
    return this.getTask(id);
  }

  deleteTask(id: string): void {
    // Manual delete for safety, even with CASCADE (foreign_keys = ON)
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
    const row = this.db.prepare(
      `SELECT ${BatchStore.ITEM_COLUMNS} FROM batch_items WHERE id = ?`,
    ).get(id);
    return row ? this.rowToItem(row as Record<string, unknown>) : undefined;
  }

  listItems(taskId: string, filter?: ItemFilter): BatchItem[] {
    let sql = `SELECT ${BatchStore.ITEM_COLUMNS} FROM batch_items WHERE task_id = ?`;
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
    const hasUpdates = Object.values(updates).some(v => v !== undefined);
    if (!hasUpdates) return this.getItem(id);

    this.buildDynamicUpdate('batch_items', id, updates, BatchStore.ITEM_COLUMN_MAP);
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
    const rows = this.db.prepare(
      `SELECT ${BatchStore.ITEM_COLUMNS} FROM batch_items WHERE status = 'running'`,
    ).all() as Record<string, unknown>[];
    return rows.map(r => this.rowToItem(r));
  }

  resetRunningItems(reason: string): void {
    const now = new Date().toISOString();
    this.db.prepare(`
      UPDATE batch_items SET status = 'failed', error_message = ?, finished_at = ?
      WHERE status = 'running'
    `).run(reason, now);

    // Also update any running batch tasks
    this.db.prepare(`
      UPDATE batch_tasks SET status = 'failed', finished_at = ?, updated_at = ?
      WHERE status IN ('running', 'queued')
    `).run(now, now);
  }

  resetItemsForExecution(taskId: string): void {
    this.db.prepare(`
      UPDATE batch_items SET status = 'pending', started_at = ?, finished_at = ?, error_message = ?, retries = 0
      WHERE task_id = ?
    `).run(null, null, null, taskId);
  }

  incrementSuccessCount(taskId: string): void {
    this.db.prepare('UPDATE batch_tasks SET success_count = success_count + 1 WHERE id = ?').run(taskId);
  }

  incrementFailedCount(taskId: string): void {
    this.db.prepare('UPDATE batch_tasks SET failed_count = failed_count + 1 WHERE id = ?').run(taskId);
  }

  // === Helpers ===

  private static readonly TASK_COLUMN_MAP: Record<string, string> = {
    status: 'status',
    generatedCode: 'generated_code',
    mdContent: 'md_content',
    execTemplate: 'exec_template',
    envVars: 'env_vars',
    concurrency: 'concurrency',
    retryOnFailure: 'retry_on_failure',
    totalItems: 'total_items',
    successCount: 'success_count',
    failedCount: 'failed_count',
    totalCost: 'total_cost',
    startedAt: 'started_at',
    finishedAt: 'finished_at',
    cronEnabled: 'cron_enabled',
    cronIntervalMinutes: 'cron_interval_minutes',
  };

  private static readonly ITEM_COLUMN_MAP: Record<string, string> = {
    status: 'status',
    sessionId: 'session_id',
    startedAt: 'started_at',
    finishedAt: 'finished_at',
    errorMessage: 'error_message',
    cost: 'cost',
    retries: 'retries',
  };

  private buildDynamicUpdate(
    table: string,
    id: number | string,
    updates: Record<string, unknown>,
    columnMap: Record<string, string>,
    extraFields?: { field: string; value: unknown }[],
  ): void {
    const fields: string[] = [];
    const values: (string | number | null)[] = [];

    for (const [key, value] of Object.entries(updates)) {
      if (value === undefined) continue;
      const col = columnMap[key];
      if (!col) continue;
      const dbValue = key === 'cronEnabled' ? (value ? 1 : 0) : value;
      fields.push(`${col} = ?`);
      values.push(dbValue as string | number | null);
    }

    if (fields.length === 0) return;

    if (extraFields) {
      for (const { field, value } of extraFields) {
        fields.push(`${field} = ?`);
        values.push(value as string | number | null);
      }
    }

    values.push(id);
    this.db.prepare(`UPDATE ${table} SET ${fields.join(', ')} WHERE id = ?`).run(...values);
  }

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
