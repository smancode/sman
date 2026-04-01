import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from './utils/logger.js';
import { v4 as uuidv4 } from 'uuid';
import type { CronTask, CronRun } from './types.js';

const TASK_COLUMNS = `
  id, workspace, skill_name as skillName, cron_expression as cronExpression,
  CASE WHEN enabled = 1 THEN 1 ELSE 0 END as enabled,
  created_at as createdAt, updated_at as updatedAt
`;

function mapTaskRow(row: Record<string, unknown>): CronTask {
  return { ...row, enabled: Boolean(row.enabled) } as CronTask;
}

export class CronTaskStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('CronTaskStore');
    this.init();
  }

  private init(): void {
    // 创建定时任务表
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS cron_tasks (
        id TEXT PRIMARY KEY,
        workspace TEXT NOT NULL,
        skill_name TEXT NOT NULL,
        cron_expression TEXT NOT NULL,
        enabled INTEGER NOT NULL DEFAULT 1,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
    `);

    // 创建执行记录表
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS cron_runs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        task_id TEXT NOT NULL,
        session_id TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('running', 'success', 'failed')),
        started_at TEXT NOT NULL,
        finished_at TEXT,
        last_activity_at TEXT,
        error_message TEXT,
        FOREIGN KEY (task_id) REFERENCES cron_tasks(id) ON DELETE CASCADE
      );
    `);

    // 创建索引
    this.db.exec(`
      CREATE INDEX IF NOT EXISTS idx_cron_runs_task ON cron_runs(task_id);
      CREATE INDEX IF NOT EXISTS idx_cron_runs_started ON cron_runs(started_at DESC);
    `);

    // Migration: 旧表有 interval_minutes 列，直接删表重建（旧数据不重要，扫描可重新拉取）
    const tableInfo = this.db.pragma('table_info(cron_tasks)') as { name: string }[];
    if (tableInfo.some(c => c.name === 'interval_minutes')) {
      this.db.exec('DROP TABLE IF EXISTS cron_tasks');
      this.db.exec(`
        CREATE TABLE cron_tasks (
          id TEXT PRIMARY KEY,
          workspace TEXT NOT NULL,
          skill_name TEXT NOT NULL,
          cron_expression TEXT NOT NULL,
          enabled INTEGER NOT NULL DEFAULT 1,
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL
        )
      `);
      this.log.info('Migrated cron_tasks: dropped old table with interval_minutes');
    }

    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
    this.log.info('CronTaskStore initialized');
  }

  // === Task CRUD ===

  createTask(input: { workspace: string; skillName: string; cronExpression: string }): CronTask {
    const id = uuidv4();
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO cron_tasks (id, workspace, skill_name, cron_expression, enabled, created_at, updated_at)
      VALUES (?, ?, ?, ?, 1, ?, ?)
    `).run(id, input.workspace, input.skillName, input.cronExpression, now, now);

    return this.getTask(id)!;
  }

  getTask(id: string): CronTask | undefined {
    const row = this.db.prepare(`
      SELECT ${TASK_COLUMNS} FROM cron_tasks WHERE id = ?
    `).get(id) as Record<string, unknown> | undefined;
    if (!row) return undefined;
    return mapTaskRow(row);
  }

  getTaskByWorkspaceAndSkill(workspace: string, skillName: string): CronTask | undefined {
    const row = this.db.prepare(`
      SELECT ${TASK_COLUMNS} FROM cron_tasks WHERE workspace = ? AND skill_name = ?
    `).get(workspace, skillName) as Record<string, unknown> | undefined;
    if (!row) return undefined;
    return mapTaskRow(row);
  }

  listTasks(): CronTask[] {
    const rows = this.db.prepare(`
      SELECT ${TASK_COLUMNS} FROM cron_tasks ORDER BY created_at DESC
    `).all() as Record<string, unknown>[];
    return rows.map(mapTaskRow);
  }

  listEnabledTasks(): CronTask[] {
    const rows = this.db.prepare(`
      SELECT ${TASK_COLUMNS} FROM cron_tasks WHERE enabled = 1 ORDER BY created_at DESC
    `).all() as Record<string, unknown>[];
    return rows.map(mapTaskRow);
  }

  updateTask(id: string, updates: Partial<Pick<CronTask, 'workspace' | 'skillName' | 'cronExpression' | 'enabled'>>): CronTask | undefined {
    const fields: string[] = [];
    const values: (string | number | boolean)[] = [];

    if (updates.workspace !== undefined) {
      fields.push('workspace = ?');
      values.push(updates.workspace);
    }
    if (updates.skillName !== undefined) {
      fields.push('skill_name = ?');
      values.push(updates.skillName);
    }
    if (updates.cronExpression !== undefined) {
      fields.push('cron_expression = ?');
      values.push(updates.cronExpression);
    }
    if (updates.enabled !== undefined) {
      fields.push('enabled = ?');
      values.push(updates.enabled ? 1 : 0);
    }

    if (fields.length === 0) return this.getTask(id);

    fields.push('updated_at = ?');
    values.push(new Date().toISOString());
    values.push(id);

    this.db.prepare(`
      UPDATE cron_tasks SET ${fields.join(', ')} WHERE id = ?
    `).run(...values);

    return this.getTask(id);
  }

  deleteTask(id: string): void {
    this.db.prepare('DELETE FROM cron_tasks WHERE id = ?').run(id);
  }

  // === Run Records ===

  createRun(taskId: string, sessionId: string): CronRun {
    const now = new Date().toISOString();
    const result = this.db.prepare(`
      INSERT INTO cron_runs (task_id, session_id, status, started_at, last_activity_at)
      VALUES (?, ?, 'running', ?, ?)
    `).run(taskId, sessionId, now, now);

    return {
      id: result.lastInsertRowid as number,
      taskId,
      sessionId: sessionId,
      status: 'running',
      startedAt: now,
      finishedAt: null,
      lastActivityAt: now,
      errorMessage: null,
    };
  }

  updateRun(id: number, updates: { status?: 'running' | 'success' | 'failed'; lastActivityAt?: string; errorMessage?: string }): void {
    const fields: string[] = [];
    const values: (string | number)[] = [];

    if (updates.status !== undefined) {
      fields.push('status = ?');
      values.push(updates.status);
      if (updates.status !== 'running') {
        fields.push('finished_at = ?');
        values.push(new Date().toISOString());
      }
    }
    if (updates.lastActivityAt !== undefined) {
      fields.push('last_activity_at = ?');
      values.push(updates.lastActivityAt);
    }
    if (updates.errorMessage !== undefined) {
      fields.push('error_message = ?');
      values.push(updates.errorMessage);
    }

    if (fields.length === 0) return;

    values.push(id);
    this.db.prepare(`UPDATE cron_runs SET ${fields.join(', ')} WHERE id = ?`).run(...values);
  }

  getLatestRun(taskId: string): CronRun | undefined {
    const row = this.db.prepare(`
      SELECT id, task_id as taskId, session_id as sessionId, status,
             started_at as startedAt, finished_at as finishedAt,
             last_activity_at as lastActivityAt, error_message as errorMessage
      FROM cron_runs WHERE task_id = ? ORDER BY started_at DESC LIMIT 1
    `).get(taskId);
    return row as CronRun | undefined;
  }

  listRuns(taskId: string, limit = 20): CronRun[] {
    return this.db.prepare(`
      SELECT id, task_id as taskId, session_id as sessionId, status,
             started_at as startedAt, finished_at as finishedAt,
             last_activity_at as lastActivityAt, error_message as errorMessage
      FROM cron_runs WHERE task_id = ? ORDER BY started_at DESC LIMIT ?
    `).all(taskId, limit) as CronRun[];
  }

  getRunningRuns(): CronRun[] {
    return this.db.prepare(`
      SELECT id, task_id as taskId, session_id as sessionId, status,
             started_at as startedAt, finished_at as finishedAt,
             last_activity_at as lastActivityAt, error_message as errorMessage
      FROM cron_runs WHERE status = 'running'
    `).all() as CronRun[];
  }

  close(): void {
    this.db.close();
  }
}
