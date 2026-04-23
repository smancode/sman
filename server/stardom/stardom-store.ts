// server/stardom/stardom-store.ts
import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from '../utils/logger.js';
import fs from 'fs';
import path from 'path';
import type { LocalAgentIdentity, StardomLocalTask } from './types.js';

export class StardomStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    const dir = path.dirname(dbPath);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('StardomStore');
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
        experience TEXT DEFAULT '',
        updated_at TEXT NOT NULL,
        PRIMARY KEY (capability, agent_id)
      );

      CREATE INDEX IF NOT EXISTS idx_learned_capability ON learned_routes(capability);

      CREATE TABLE IF NOT EXISTS pair_history (
        partner_id TEXT NOT NULL,
        partner_name TEXT NOT NULL,
        task_count INTEGER DEFAULT 1,
        total_rating REAL DEFAULT 0,
        avg_rating REAL DEFAULT 0,
        last_collaborated_at TEXT NOT NULL,
        PRIMARY KEY (partner_id)
      );

      CREATE TABLE IF NOT EXISTS cached_results (
        task_id TEXT PRIMARY KEY,
        result_text TEXT NOT NULL,
        from_agent TEXT NOT NULL,
        cached_at TEXT NOT NULL DEFAULT (datetime('now'))
      );

      PRAGMA journal_mode=WAL;
    `);

    // Migration: add experience column to existing learned_routes table
    try {
      this.db.exec("ALTER TABLE learned_routes ADD COLUMN experience TEXT DEFAULT ''");
    } catch {
      // Column already exists, ignore
    }
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

  saveTask(task: StardomLocalTask): void {
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

  listTasks(limit = 50): StardomLocalTask[] {
    return this.db.prepare(`
      SELECT task_id as taskId, direction, helper_agent_id as helperAgentId,
        helper_name as helperName, requester_agent_id as requesterAgentId,
        requester_name as requesterName, question, status, rating,
        created_at as createdAt, completed_at as completedAt
      FROM tasks ORDER BY created_at DESC LIMIT ?
    `).all(limit) as StardomLocalTask[];
  }

  getTask(taskId: string): StardomLocalTask | undefined {
    return this.db.prepare(`
      SELECT task_id as taskId, direction, helper_agent_id as helperAgentId,
        helper_name as helperName, requester_agent_id as requesterAgentId,
        requester_name as requesterName, question, status, rating,
        created_at as createdAt, completed_at as completedAt
      FROM tasks WHERE task_id = ?
    `).get(taskId) as StardomLocalTask | undefined;
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

  saveLearnedRoute(input: { capability: string; agentId: string; agentName: string; experience?: string }): void {
    this.db.prepare(`
      INSERT OR REPLACE INTO learned_routes (capability, agent_id, agent_name, experience, updated_at)
      VALUES (?, ?, ?, ?, ?)
    `).run(input.capability, input.agentId, input.agentName, input.experience ?? '', new Date().toISOString());
  }

  findLearnedRoutes(keyword: string): Array<{ capability: string; agentId: string; agentName: string; experience: string }> {
    const escaped = keyword.replace(/%/g, '\\%').replace(/_/g, '\\_');
    return this.db.prepare(`
      SELECT capability, agent_id as agentId, agent_name as agentName, experience
      FROM learned_routes
      WHERE capability LIKE ? ESCAPE '\\' OR experience LIKE ? ESCAPE '\\'
    `).all(`%${escaped}%`, `%${escaped}%`) as Array<{ capability: string; agentId: string; agentName: string; experience: string }>;
  }

  listLearnedRoutes(): Array<{ capability: string; agentId: string; agentName: string; experience: string }> {
    return this.db.prepare(`
      SELECT capability, agent_id as agentId, agent_name as agentName, experience
      FROM learned_routes
      ORDER BY capability, updated_at DESC
    `).all() as Array<{ capability: string; agentId: string; agentName: string; experience: string }>;
  }

  // ── Pair History ──

  savePairHistory(input: { partnerId: string; partnerName: string; rating: number }): void {
    const existing = this.getPairHistory(input.partnerId);
    const now = new Date().toISOString();

    if (existing) {
      const newCount = existing.taskCount + 1;
      const newTotal = existing.totalRating + input.rating;
      const newAvg = Math.round((newTotal / newCount) * 10) / 10;
      this.db.prepare(`
        UPDATE pair_history
        SET partner_name = ?, task_count = ?, total_rating = ?, avg_rating = ?, last_collaborated_at = ?
        WHERE partner_id = ?
      `).run(input.partnerName, newCount, newTotal, newAvg, now, input.partnerId);
    } else {
      this.db.prepare(`
        INSERT INTO pair_history (partner_id, partner_name, task_count, total_rating, avg_rating, last_collaborated_at)
        VALUES (?, ?, 1, ?, ?, ?)
      `).run(input.partnerId, input.partnerName, input.rating, input.rating, now);
    }
  }

  getPairHistory(partnerId: string): { partnerId: string; partnerName: string; taskCount: number; totalRating: number; avgRating: number; lastCollaboratedAt: string } | undefined {
    return this.db.prepare(`
      SELECT partner_id as partnerId, partner_name as partnerName,
        task_count as taskCount, total_rating as totalRating,
        avg_rating as avgRating, last_collaborated_at as lastCollaboratedAt
      FROM pair_history WHERE partner_id = ?
    `).get(partnerId) as any;
  }

  listPairHistories(): Array<{ partnerId: string; partnerName: string; taskCount: number; avgRating: number; lastCollaboratedAt: string }> {
    return this.db.prepare(`
      SELECT partner_id as partnerId, partner_name as partnerName,
        task_count as taskCount, avg_rating as avgRating,
        last_collaborated_at as lastCollaboratedAt
      FROM pair_history ORDER BY avg_rating DESC
    `).all() as any[];
  }

  // ── Cached Results (async task delivery) ──

  saveCachedResult(input: { taskId: string; resultText: string; fromAgent: string }): void {
    this.db.prepare(`
      INSERT OR REPLACE INTO cached_results (task_id, result_text, from_agent, cached_at)
      VALUES (?, ?, ?, datetime('now'))
    `).run(input.taskId, input.resultText, input.fromAgent);
  }

  getCachedResult(taskId: string): { taskId: string; resultText: string; fromAgent: string; cachedAt: string } | undefined {
    return this.db.prepare(`
      SELECT task_id as taskId, result_text as resultText, from_agent as fromAgent, cached_at as cachedAt
      FROM cached_results WHERE task_id = ?
    `).get(taskId) as any;
  }

  deleteCachedResult(taskId: string): void {
    this.db.prepare('DELETE FROM cached_results WHERE task_id = ?').run(taskId);
  }

  /** List all tasks in active state (chatting/matched) — used for sync on reconnect */
  listActiveTasks(): StardomLocalTask[] {
    return this.db.prepare(`
      SELECT task_id as taskId, direction, helper_agent_id as helperAgentId,
        helper_name as helperName, requester_agent_id as requesterAgentId,
        requester_name as requesterName, question, status, rating,
        created_at as createdAt, completed_at as completedAt
      FROM tasks WHERE status IN ('chatting', 'matched')
      ORDER BY created_at DESC
    `).all() as StardomLocalTask[];
  }
}
