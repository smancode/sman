import Database from 'better-sqlite3';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { fileURLToPath } from 'url';
import { createLogger, type Logger } from './utils/logger.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export interface Group {
  id: string;
  name: string;
  workspaceIds: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface GroupTask {
  id: string;
  groupId: string;
  title: string;
  description: string | null;
  autoDispatch: number;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface GroupSubtask {
  id: string;
  groupTaskId: string;
  sessionId: string;
  workspace: string;
  title: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

interface CreateGroupInput {
  id: string;
  name: string;
  workspaceIds: string[];
}

export interface CreateGroupTaskInput {
  id: string;
  groupId: string;
  title: string;
  description?: string;
  autoDispatch?: number;
}

export interface CreateGroupSubtaskInput {
  id: string;
  groupTaskId: string;
  sessionId: string;
  workspace: string;
  title: string;
  description?: string;
}

const TASK_FIELDS = 'id, group_id as groupId, title, description, auto_dispatch as autoDispatch, status, created_at as createdAt, updated_at as updatedAt';

export class GroupStore {
  private db: Database.Database;
  private log: Logger;
  private groupBaseDir: string;

  constructor(db: Database.Database) {
    this.db = db;
    this.log = createLogger('GroupStore');
    this.groupBaseDir = path.join(os.homedir(), '.sman', 'group');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS groups (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        workspace_ids TEXT NOT NULL DEFAULT '[]',
        status TEXT NOT NULL DEFAULT 'active',
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        updated_at TEXT NOT NULL DEFAULT (datetime('now'))
      );

      CREATE TABLE IF NOT EXISTS group_tasks (
        id TEXT PRIMARY KEY,
        group_id TEXT NOT NULL,
        title TEXT NOT NULL,
        description TEXT,
        auto_dispatch INTEGER NOT NULL DEFAULT 0,
        status TEXT NOT NULL DEFAULT 'draft',
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
        FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE
      );

      CREATE INDEX IF NOT EXISTS idx_groups_status ON groups(status);
      CREATE INDEX IF NOT EXISTS idx_group_tasks_group_id ON group_tasks(group_id);
      CREATE INDEX IF NOT EXISTS idx_group_tasks_status ON group_tasks(status);

      CREATE TABLE IF NOT EXISTS group_subtasks (
        id TEXT PRIMARY KEY,
        group_task_id TEXT NOT NULL,
        session_id TEXT NOT NULL,
        workspace TEXT NOT NULL,
        title TEXT NOT NULL,
        description TEXT,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
        FOREIGN KEY (group_task_id) REFERENCES group_tasks(id) ON DELETE CASCADE
      );

      CREATE INDEX IF NOT EXISTS idx_subtasks_task_id ON group_subtasks(group_task_id);
      CREATE INDEX IF NOT EXISTS idx_subtasks_session_id ON group_subtasks(session_id);
    `);

    // Drop legacy workspace_tasks table if it exists
    this.db.exec(`DROP TABLE IF EXISTS workspace_tasks`);

    // Migration: add auto_dispatch column if not exists
    try {
      this.db.prepare('SELECT auto_dispatch FROM group_tasks LIMIT 1').get();
    } catch {
      this.db.exec('ALTER TABLE group_tasks ADD COLUMN auto_dispatch INTEGER NOT NULL DEFAULT 0');
      this.log.info('Migrated: added auto_dispatch column to group_tasks table');
    }

    // Migration: add status column if not exists
    try {
      this.db.prepare('SELECT status FROM group_tasks LIMIT 1').get();
    } catch {
      this.db.exec("ALTER TABLE group_tasks ADD COLUMN status TEXT NOT NULL DEFAULT 'draft'");
      this.log.info('Migrated: added status column to group_tasks table');
    }

    this.db.pragma('foreign_keys = ON');
    this.log.info('Group database initialized');
  }

  // ── Group dir operations ──

  getGroupDir(groupId: string): string {
    return path.join(this.groupBaseDir, groupId);
  }

  ensureGroupDir(groupId: string, workspaceIds: string[]): void {
    const dir = this.getGroupDir(groupId);
    fs.mkdirSync(dir, { recursive: true });
    const claudeMdPath = path.join(dir, 'CLAUDE.md');
    if (!fs.existsSync(claudeMdPath)) {
      const templatePath = path.join(__dirname, 'templates', 'group-claude.md');
      let template = fs.readFileSync(templatePath, 'utf-8');
      const names = workspaceIds.map(ws => ws.split(/[/\\]/).pop() || ws);
      template = template.replace('{workspace_list}', names.map(n => `- ${n}`).join('\n'));
      template = template.replace('{workspace_details}', workspaceIds.join('\n'));
      fs.writeFileSync(claudeMdPath, template);
    }
  }

  deleteGroupDir(groupId: string): void {
    const dir = this.getGroupDir(groupId);
    if (fs.existsSync(dir)) {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  }

  // ── Group operations ──

  createGroup(input: CreateGroupInput): Group {
    const { id, name, workspaceIds } = input;
    const workspaceIdsJson = JSON.stringify(workspaceIds);
    this.db.prepare(
      'INSERT INTO groups (id, name, workspace_ids) VALUES (?, ?, ?)'
    ).run(id, name, workspaceIdsJson);

    return this.getGroup(id)!;
  }

  getGroup(id: string): Group | undefined {
    const row = this.db.prepare(
      'SELECT id, name, workspace_ids as workspaceIds, status, created_at as createdAt, updated_at as updatedAt FROM groups WHERE id = ?'
    ).get(id) as Group | undefined;
    return row;
  }

  listGroups(status?: string): Group[] {
    const sql = 'SELECT id, name, workspace_ids as workspaceIds, status, created_at as createdAt, updated_at as updatedAt FROM groups'
      + (status ? ' WHERE status = ?' : '')
      + ' ORDER BY updated_at DESC';
    if (status) {
      return this.db.prepare(sql).all(status) as Group[];
    }
    return this.db.prepare(sql).all() as Group[];
  }

  updateGroup(id: string, updates: Partial<Pick<Group, 'name' | 'workspaceIds' | 'status'>>): Group | undefined {
    const existing = this.getGroup(id);
    if (!existing) return undefined;

    const updatesArray: string[] = [];
    const values: unknown[] = [];

    if (updates.name !== undefined) {
      updatesArray.push('name = ?');
      values.push(updates.name);
    }
    if (updates.workspaceIds !== undefined) {
      updatesArray.push('workspace_ids = ?');
      values.push(JSON.stringify(updates.workspaceIds));
    }
    if (updates.status !== undefined) {
      updatesArray.push('status = ?');
      values.push(updates.status);
    }

    if (updatesArray.length > 0) {
      updatesArray.push("updated_at = datetime('now')");
      values.push(id);

      this.db.prepare(
        `UPDATE groups SET ${updatesArray.join(', ')} WHERE id = ?`
      ).run(...values);
    }

    return this.getGroup(id);
  }

  deleteGroup(id: string): boolean {
    const result = this.db.prepare('DELETE FROM groups WHERE id = ?').run(id);
    return result.changes > 0;
  }

  // ── GroupTask operations ──

  createGroupTask(input: CreateGroupTaskInput): GroupTask {
    const { id, groupId, title, description, autoDispatch } = input;
    this.db.prepare(
      'INSERT INTO group_tasks (id, group_id, title, description, auto_dispatch) VALUES (?, ?, ?, ?, ?)'
    ).run(id, groupId, title, description || null, autoDispatch ?? 0);

    return this.getGroupTask(id)!;
  }

  getGroupTask(id: string): GroupTask | undefined {
    const row = this.db.prepare(
      `SELECT ${TASK_FIELDS} FROM group_tasks WHERE id = ?`
    ).get(id) as GroupTask | undefined;
    return row;
  }

  listGroupTasks(groupId: string): GroupTask[] {
    return this.db.prepare(
      `SELECT ${TASK_FIELDS} FROM group_tasks WHERE group_id = ? ORDER BY updated_at DESC`
    ).all(groupId) as GroupTask[];
  }

  updateGroupTaskStatus(id: string, status: string): GroupTask | undefined {
    this.db.prepare(
      "UPDATE group_tasks SET status = ?, updated_at = datetime('now') WHERE id = ?"
    ).run(status, id);
    return this.getGroupTask(id);
  }

  deleteGroupTask(id: string): boolean {
    const result = this.db.prepare('DELETE FROM group_tasks WHERE id = ?').run(id);
    return result.changes > 0;
  }

  // ── GroupSubtask operations ──

  private readonly SUBTASK_FIELDS = 'id, group_task_id as groupTaskId, session_id as sessionId, workspace, title, description, created_at as createdAt, updated_at as updatedAt';

  createSubtask(input: CreateGroupSubtaskInput): GroupSubtask {
    const { id, groupTaskId, sessionId, workspace, title, description } = input;
    this.db.prepare(
      'INSERT INTO group_subtasks (id, group_task_id, session_id, workspace, title, description) VALUES (?, ?, ?, ?, ?, ?)'
    ).run(id, groupTaskId, sessionId, workspace, title, description || null);

    return this.getSubtask(id)!;
  }

  getSubtask(id: string): GroupSubtask | undefined {
    return this.db.prepare(
      `SELECT ${this.SUBTASK_FIELDS} FROM group_subtasks WHERE id = ?`
    ).get(id) as GroupSubtask | undefined;
  }

  listSubtasks(groupTaskId: string): GroupSubtask[] {
    return this.db.prepare(
      `SELECT ${this.SUBTASK_FIELDS} FROM group_subtasks WHERE group_task_id = ? ORDER BY created_at ASC`
    ).all(groupTaskId) as GroupSubtask[];
  }

  getSubtaskBySessionId(sessionId: string): GroupSubtask | undefined {
    return this.db.prepare(
      `SELECT ${this.SUBTASK_FIELDS} FROM group_subtasks WHERE session_id = ?`
    ).get(sessionId) as GroupSubtask | undefined;
  }

  deleteSubtask(id: string): boolean {
    const result = this.db.prepare('DELETE FROM group_subtasks WHERE id = ?').run(id);
    return result.changes > 0;
  }
}
