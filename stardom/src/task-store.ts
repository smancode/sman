// stardom/src/task-store.ts
import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from './utils/logger.js';
import fs from 'fs';
import path from 'path';

export interface TaskRow {
  id: string;
  requesterId: string;
  helperId: string | null;
  helperName: string | null;
  question: string;
  capabilityQuery: string;
  status: string;
  rating: number | null;
  feedback: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  deadline: string | null;
}

export interface ChatMessageRow {
  taskId: string;
  from: string;
  text: string;
  timestamp: string;
}

export class TaskStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    const dir = path.dirname(dbPath);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('TaskStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS tasks (
        id TEXT PRIMARY KEY,
        requester_id TEXT NOT NULL,
        helper_id TEXT,
        helper_name TEXT,
        question TEXT NOT NULL,
        capability_query TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'created',
        rating INTEGER,
        feedback TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        completed_at TEXT,
        deadline TEXT
      );

      CREATE TABLE IF NOT EXISTS chat_messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        task_id TEXT NOT NULL,
        from_agent TEXT NOT NULL,
        text TEXT NOT NULL,
        timestamp TEXT NOT NULL,
        FOREIGN KEY (task_id) REFERENCES tasks(id)
      );

      CREATE INDEX IF NOT EXISTS idx_tasks_requester ON tasks(requester_id);
      CREATE INDEX IF NOT EXISTS idx_tasks_helper ON tasks(helper_id);
      CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
      CREATE INDEX IF NOT EXISTS idx_chat_task ON chat_messages(task_id);

      PRAGMA journal_mode=WAL;
    `);
  }

  createTask(input: {
    id: string;
    requesterId: string;
    question: string;
    capabilityQuery: string;
    status: string;
    helperId?: string;
    helperName?: string;
    deadline?: string;
  }): void {
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO tasks (id, requester_id, helper_id, helper_name, question, capability_query, status, created_at, updated_at, deadline)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      input.id, input.requesterId, input.helperId ?? null, input.helperName ?? null,
      input.question, input.capabilityQuery, input.status, now, now, input.deadline ?? null,
    );
  }

  getTask(id: string): TaskRow | undefined {
    return this.db.prepare(`
      SELECT id, requester_id as requesterId, helper_id as helperId, helper_name as helperName,
        question, capability_query as capabilityQuery, status, rating, feedback,
        created_at as createdAt, updated_at as updatedAt, completed_at as completedAt, deadline
      FROM tasks WHERE id = ?
    `).get(id) as TaskRow | undefined;
  }

  updateTaskStatus(id: string, status: string, extra?: {
    helperId?: string;
    helperName?: string;
    rating?: number;
    feedback?: string;
  }): void {
    const now = new Date().toISOString();
    const task = this.getTask(id);
    if (!task) return;

    this.db.prepare(`
      UPDATE tasks SET status = ?, updated_at = ?,
        helper_id = COALESCE(?, helper_id),
        helper_name = COALESCE(?, helper_name),
        rating = COALESCE(?, rating),
        feedback = COALESCE(?, feedback),
        completed_at = CASE WHEN ? IN ('completed', 'rated', 'failed', 'cancelled', 'timeout') THEN ? ELSE completed_at END
      WHERE id = ?
    `).run(
      status, now,
      extra?.helperId ?? null, extra?.helperName ?? null,
      extra?.rating ?? null, extra?.feedback ?? null,
      status, now, id,
    );
  }

  listActiveTasks(): TaskRow[] {
    return this.db.prepare(`
      SELECT id, requester_id as requesterId, helper_id as helperId, helper_name as helperName,
        question, capability_query as capabilityQuery, status, rating, feedback,
        created_at as createdAt, updated_at as updatedAt, completed_at as completedAt, deadline
      FROM tasks WHERE status IN ('created', 'searching', 'offered', 'matched', 'chatting')
      ORDER BY created_at DESC
    `).all() as TaskRow[];
  }

  listTasksByAgent(agentId: string): TaskRow[] {
    return this.db.prepare(`
      SELECT id, requester_id as requesterId, helper_id as helperId, helper_name as helperName,
        question, capability_query as capabilityQuery, status, rating, feedback,
        created_at as createdAt, updated_at as updatedAt, completed_at as completedAt, deadline
      FROM tasks WHERE requester_id = ? OR helper_id = ?
      ORDER BY created_at DESC LIMIT 50
    `).all(agentId, agentId) as TaskRow[];
  }

  getActiveTaskCount(agentId: string): number {
    const row = this.db.prepare(`
      SELECT COUNT(*) as count FROM tasks
      WHERE (requester_id = ? OR helper_id = ?)
        AND status IN ('created', 'searching', 'offered', 'matched', 'chatting')
    `).get(agentId, agentId) as { count: number } | undefined;
    return row?.count ?? 0;
  }

  listTimedOutTasks(timeoutMinutes: number): TaskRow[] {
    const cutoff = new Date(Date.now() - timeoutMinutes * 60_000).toISOString();
    return this.db.prepare(`
      SELECT id, requester_id as requesterId, helper_id as helperId, helper_name as helperName,
        question, capability_query as capabilityQuery, status, rating, feedback,
        created_at as createdAt, updated_at as updatedAt, completed_at as completedAt, deadline
      FROM tasks WHERE status = 'chatting' AND updated_at < ?
    `).all(cutoff) as TaskRow[];
  }

  saveChatMessage(taskId: string, from: string, text: string): void {
    this.db.prepare(`
      INSERT INTO chat_messages (task_id, from_agent, text, timestamp)
      VALUES (?, ?, ?, ?)
    `).run(taskId, from, text, new Date().toISOString());
  }

  listChatMessages(taskId: string): ChatMessageRow[] {
    return this.db.prepare(`
      SELECT task_id as taskId, from_agent as \`from\`, text, timestamp
      FROM chat_messages WHERE task_id = ?
      ORDER BY timestamp ASC
    `).all(taskId) as ChatMessageRow[];
  }

  close(): void {
    this.db.close();
  }
}
