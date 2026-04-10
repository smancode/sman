// server/bazaar/bazaar-store.ts
import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from '../utils/logger.js';
import fs from 'fs';
import path from 'path';
import type { LocalAgentIdentity, BazaarLocalTask } from './types.js';

export class BazaarStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    const dir = path.dirname(dbPath);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('BazaarStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS identity (
        agent_id TEXT PRIMARY KEY,
        hostname TEXT NOT NULL,
        username TEXT NOT NULL,
        name TEXT NOT NULL,
        server TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );

      CREATE TABLE IF NOT EXISTS tasks (
        task_id TEXT PRIMARY KEY,
        direction TEXT NOT NULL,
        helper_agent_id TEXT,
        helper_name TEXT,
        requester_agent_id TEXT,
        requester_name TEXT,
        question TEXT NOT NULL,
        status TEXT NOT NULL,
        rating INTEGER,
        created_at TEXT NOT NULL,
        completed_at TEXT
      );

      CREATE TABLE IF NOT EXISTS chat_messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        task_id TEXT NOT NULL,
        from_agent TEXT NOT NULL,
        text TEXT NOT NULL,
        created_at TEXT NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_chat_task ON chat_messages(task_id);

      CREATE TABLE IF NOT EXISTS learned_routes (
        capability TEXT NOT NULL,
        agent_id TEXT NOT NULL,
        agent_name TEXT NOT NULL,
        repo TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        PRIMARY KEY (capability, agent_id)
      );

      CREATE INDEX IF NOT EXISTS idx_learned_capability ON learned_routes(capability);

      PRAGMA journal_mode=WAL;
    `);
  }

  // ── Identity ──

  saveIdentity(identity: LocalAgentIdentity): void {
    this.db.prepare(`
      INSERT OR REPLACE INTO identity (agent_id, hostname, username, name, server, updated_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(identity.agentId, identity.hostname, identity.username, identity.name, identity.server, new Date().toISOString());
  }

  getIdentity(): LocalAgentIdentity | undefined {
    return this.db.prepare(
      'SELECT agent_id as agentId, hostname, username, name, server FROM identity'
    ).get() as LocalAgentIdentity | undefined;
  }

  // ── Tasks ──

  saveTask(task: BazaarLocalTask): void {
    this.db.prepare(`
      INSERT OR REPLACE INTO tasks (task_id, direction, helper_agent_id, helper_name,
        requester_agent_id, requester_name, question, status, rating, created_at, completed_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      task.taskId, task.direction, task.helperAgentId ?? null, task.helperName ?? null,
      task.requesterAgentId ?? null, task.requesterName ?? null,
      task.question, task.status, task.rating ?? null, task.createdAt, task.completedAt ?? null,
    );
  }

  updateTaskStatus(taskId: string, status: string, rating?: number, completedAt?: string): void {
    if (rating !== undefined && completedAt) {
      this.db.prepare('UPDATE tasks SET status = ?, rating = ?, completed_at = ? WHERE task_id = ?')
        .run(status, rating, completedAt, taskId);
    } else {
      this.db.prepare('UPDATE tasks SET status = ? WHERE task_id = ?').run(status, taskId);
    }
  }

  listTasks(limit = 50): BazaarLocalTask[] {
    return this.db.prepare(`
      SELECT task_id as taskId, direction, helper_agent_id as helperAgentId,
        helper_name as helperName, requester_agent_id as requesterAgentId,
        requester_name as requesterName, question, status, rating,
        created_at as createdAt, completed_at as completedAt
      FROM tasks ORDER BY created_at DESC LIMIT ?
    `).all(limit) as BazaarLocalTask[];
  }

  getTask(taskId: string): BazaarLocalTask | undefined {
    return this.db.prepare(`
      SELECT task_id as taskId, direction, helper_agent_id as helperAgentId,
        helper_name as helperName, requester_agent_id as requesterAgentId,
        requester_name as requesterName, question, status, rating,
        created_at as createdAt, completed_at as completedAt
      FROM tasks WHERE task_id = ?
    `).get(taskId) as BazaarLocalTask | undefined;
  }

  // ── Chat Messages ──

  saveChatMessage(msg: { taskId: string; from: string; text: string }): void {
    this.db.prepare(`
      INSERT INTO chat_messages (task_id, from_agent, text, created_at)
      VALUES (?, ?, ?, ?)
    `).run(msg.taskId, msg.from, msg.text, new Date().toISOString());
  }

  listChatMessages(taskId: string): Array<{ id: number; taskId: string; from: string; text: string; createdAt: string }> {
    return this.db.prepare(`
      SELECT id, task_id as taskId, from_agent as \`from\`, text, created_at as createdAt
      FROM chat_messages
      WHERE task_id = ?
      ORDER BY created_at ASC
    `).all(taskId) as Array<{ id: number; taskId: string; from: string; text: string; createdAt: string }>;
  }

  close(): void {
    this.db.close();
  }

  // ── Learned Routes ──

  saveLearnedRoute(input: { capability: string; agentId: string; agentName: string; repo: string }): void {
    this.db.prepare(`
      INSERT OR REPLACE INTO learned_routes (capability, agent_id, agent_name, repo, updated_at)
      VALUES (?, ?, ?, ?, ?)
    `).run(input.capability, input.agentId, input.agentName, input.repo, new Date().toISOString());
  }

  findLearnedRoutes(keyword: string): Array<{ capability: string; agentId: string; agentName: string; repo: string }> {
    const escaped = keyword.replace(/%/g, '\\%').replace(/_/g, '\\_');
    return this.db.prepare(`
      SELECT capability, agent_id as agentId, agent_name as agentName, repo
      FROM learned_routes
      WHERE capability LIKE ? ESCAPE '\\'
    `).all(`%${escaped}%`) as Array<{ capability: string; agentId: string; agentName: string; repo: string }>;
  }

  listLearnedRoutes(): Array<{ capability: string; agentId: string; agentName: string; repo: string }> {
    return this.db.prepare(`
      SELECT capability, agent_id as agentId, agent_name as agentName, repo
      FROM learned_routes
      ORDER BY capability, updated_at DESC
    `).all() as Array<{ capability: string; agentId: string; agentName: string; repo: string }>;
  }
}
