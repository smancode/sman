import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from './utils/logger.js';
import { v4 as uuidv4 } from 'uuid';
import type { SmartPath, SmartPathRun, SmartPathStatus, SmartPathRunStatus } from './types.js';

export class SmartPathStore {
  private static readonly PATH_COLUMNS = [
    'id', 'name', 'workspace', 'steps',
    'status', 'created_at as createdAt', 'updated_at as updatedAt',
  ].join(', ');

  private static readonly RUN_COLUMNS = [
    'id', 'path_id as pathId', 'status',
    'step_results as stepResults', 'started_at as startedAt',
    'finished_at as finishedAt', 'error_message as errorMessage',
  ].join(', ');

  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('SmartPathStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS smart_paths (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        workspace TEXT NOT NULL,
        steps TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'draft'
          CHECK(status IN ('draft','ready','running','completed','failed')),
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
    `);

    this.db.exec(`
      CREATE TABLE IF NOT EXISTS smart_path_runs (
        id TEXT PRIMARY KEY,
        path_id TEXT NOT NULL,
        status TEXT NOT NULL
          CHECK(status IN ('running','completed','failed')),
        step_results TEXT NOT NULL DEFAULT '{}',
        started_at TEXT NOT NULL,
        finished_at TEXT,
        error_message TEXT,
        FOREIGN KEY (path_id) REFERENCES smart_paths(id) ON DELETE CASCADE
      );
    `);

    this.db.exec(`
      CREATE INDEX IF NOT EXISTS idx_smart_path_runs_path ON smart_path_runs(path_id);
    `);

    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
    this.log.info('SmartPathStore initialized');
  }

  // === Path CRUD ===

  createPath(input: {
    name: string;
    workspace: string;
    steps: string;
    status?: SmartPathStatus;
  }): SmartPath {
    if (!input.name || input.name.trim() === '') {
      throw new Error('缺少 name 参数');
    }
    if (!input.workspace || input.workspace.trim() === '') {
      throw new Error('缺少 workspace 参数');
    }
    if (!input.steps || input.steps.trim() === '') {
      throw new Error('缺少 steps 参数');
    }

    const id = uuidv4();
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO smart_paths (id, name, workspace, steps, status, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `).run(id, input.name, input.workspace, input.steps, input.status || 'draft', now, now);
    return this.getPath(id)!;
  }

  getPath(id: string): SmartPath | undefined {
    if (!id) throw new Error('缺少 id 参数');
    const row = this.db.prepare(
      `SELECT ${SmartPathStore.PATH_COLUMNS} FROM smart_paths WHERE id = ?`,
    ).get(id);
    return row ? this.rowToPath(row as Record<string, unknown>) : undefined;
  }

  listPaths(): SmartPath[] {
    const rows = this.db.prepare(
      `SELECT ${SmartPathStore.PATH_COLUMNS} FROM smart_paths ORDER BY created_at DESC`,
    ).all() as Record<string, unknown>[];
    return rows.map(r => this.rowToPath(r));
  }

  updatePath(id: string, updates: Partial<{
    name: string;
    workspace: string;
    steps: string;
    status: SmartPathStatus;
  }>): SmartPath | undefined {
    if (!id) throw new Error('缺少 id 参数');

    const fields: string[] = [];
    const values: (string | number)[] = [];

    if (updates.name !== undefined) {
      fields.push('name = ?');
      values.push(updates.name);
    }
    if (updates.workspace !== undefined) {
      fields.push('workspace = ?');
      values.push(updates.workspace);
    }
    if (updates.steps !== undefined) {
      fields.push('steps = ?');
      values.push(updates.steps);
    }
    if (updates.status !== undefined) {
      fields.push('status = ?');
      values.push(updates.status);
    }

    if (fields.length === 0) return this.getPath(id);

    fields.push('updated_at = ?');
    values.push(new Date().toISOString());
    values.push(id);

    this.db.prepare(`UPDATE smart_paths SET ${fields.join(', ')} WHERE id = ?`).run(...values);
    return this.getPath(id);
  }

  deletePath(id: string): void {
    if (!id) throw new Error('缺少 id 参数');
    this.db.prepare('DELETE FROM smart_path_runs WHERE path_id = ?').run(id);
    this.db.prepare('DELETE FROM smart_paths WHERE id = ?').run(id);
  }

  // === Run CRUD ===

  createRun(pathId: string): SmartPathRun {
    if (!pathId) throw new Error('缺少 pathId 参数');

    const id = uuidv4();
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO smart_path_runs (id, path_id, status, step_results, started_at)
      VALUES (?, ?, ?, ?, ?)
    `).run(id, pathId, 'running', '{}', now);

    return {
      id,
      pathId,
      status: 'running',
      stepResults: '{}',
      startedAt: now,
    };
  }

  getRun(id: string): SmartPathRun | undefined {
    if (!id) throw new Error('缺少 id 参数');
    const row = this.db.prepare(
      `SELECT ${SmartPathStore.RUN_COLUMNS} FROM smart_path_runs WHERE id = ?`,
    ).get(id);
    return row ? this.rowToRun(row as Record<string, unknown>) : undefined;
  }

  listRuns(pathId: string): SmartPathRun[] {
    if (!pathId) throw new Error('缺少 pathId 参数');
    const rows = this.db.prepare(
      `SELECT ${SmartPathStore.RUN_COLUMNS} FROM smart_path_runs WHERE path_id = ? ORDER BY started_at DESC`,
    ).all(pathId) as Record<string, unknown>[];
    return rows.map(r => this.rowToRun(r));
  }

  updateRun(id: string, updates: Partial<{
    status: SmartPathRunStatus;
    stepResults: string;
    finishedAt: string;
    errorMessage: string;
  }>): SmartPathRun | undefined {
    if (!id) throw new Error('缺少 id 参数');

    const fields: string[] = [];
    const values: (string | number)[] = [];

    if (updates.status !== undefined) {
      fields.push('status = ?');
      values.push(updates.status);
    }
    if (updates.stepResults !== undefined) {
      fields.push('step_results = ?');
      values.push(updates.stepResults);
    }
    if (updates.finishedAt !== undefined) {
      fields.push('finished_at = ?');
      values.push(updates.finishedAt);
    }
    if (updates.errorMessage !== undefined) {
      fields.push('error_message = ?');
      values.push(updates.errorMessage);
    }

    if (fields.length === 0) return this.getRun(id);

    values.push(id);
    this.db.prepare(`UPDATE smart_path_runs SET ${fields.join(', ')} WHERE id = ?`).run(...values);
    return this.getRun(id);
  }

  close(): void {
    this.db.close();
  }

  // === Helpers ===

  private rowToPath(row: Record<string, unknown>): SmartPath {
    return {
      id: row.id as string,
      name: row.name as string,
      workspace: row.workspace as string,
      steps: row.steps as string,
      status: row.status as SmartPathStatus,
      createdAt: row.createdAt as string,
      updatedAt: row.updatedAt as string,
    };
  }

  private rowToRun(row: Record<string, unknown>): SmartPathRun {
    return {
      id: row.id as string,
      pathId: row.pathId as string,
      status: row.status as SmartPathRunStatus,
      stepResults: row.stepResults as string,
      startedAt: row.startedAt as string,
      finishedAt: row.finishedAt as string | undefined,
      errorMessage: row.errorMessage as string | undefined,
    };
  }
}
