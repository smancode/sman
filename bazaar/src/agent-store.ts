// bazaar/src/agent-store.ts
import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from './utils/logger.js';

// ── 输入类型 ──

interface RegisterInput {
  id: string;
  username: string;
  hostname: string;
  name: string;
  avatar?: string;
}

interface ProjectInput {
  repo: string;
  skills: string; // JSON string
}

export interface AgentRow {
  id: string;
  username: string;
  hostname: string;
  name: string;
  avatar: string;
  status: string;
  reputation: number;
  lastSeenAt: string | null;
  createdAt: string;
}

export interface ProjectRow {
  agentId: string;
  repo: string;
  skills: string;
  updatedAt: string;
}

export interface AuditRow {
  id: number;
  timestamp: string;
  eventType: string;
  agentId: string;
  targetAgentId: string | null;
  taskId: string | null;
  detail: string;
}

export class AgentStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('AgentStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS agents (
        id TEXT PRIMARY KEY,
        username TEXT UNIQUE NOT NULL,
        hostname TEXT,
        name TEXT NOT NULL,
        avatar TEXT DEFAULT '🧙',
        status TEXT DEFAULT 'offline',
        reputation REAL DEFAULT 0,
        last_seen_at TEXT,
        created_at TEXT NOT NULL
      );

      CREATE TABLE IF NOT EXISTS agent_projects (
        agent_id TEXT NOT NULL,
        repo TEXT NOT NULL,
        skills TEXT NOT NULL DEFAULT '[]',
        updated_at TEXT NOT NULL,
        PRIMARY KEY (agent_id, repo),
        FOREIGN KEY (agent_id) REFERENCES agents(id)
      );

      CREATE TABLE IF NOT EXISTS agent_private_capabilities (
        agent_id TEXT NOT NULL,
        id TEXT NOT NULL,
        name TEXT NOT NULL,
        triggers TEXT DEFAULT '[]',
        source TEXT DEFAULT 'experience',
        updated_at TEXT NOT NULL,
        PRIMARY KEY (agent_id, id),
        FOREIGN KEY (agent_id) REFERENCES agents(id)
      );

      CREATE TABLE IF NOT EXISTS audit_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp TEXT NOT NULL,
        event_type TEXT NOT NULL,
        agent_id TEXT NOT NULL,
        target_agent_id TEXT,
        task_id TEXT,
        detail TEXT NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_audit_agent ON audit_log(agent_id);
      CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(timestamp);
      CREATE INDEX IF NOT EXISTS idx_audit_event ON audit_log(event_type);
      CREATE INDEX IF NOT EXISTS idx_projects_agent ON agent_projects(agent_id);
      CREATE INDEX IF NOT EXISTS idx_privcap_agent ON agent_private_capabilities(agent_id);

      CREATE TABLE IF NOT EXISTS reputation_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        agent_id TEXT NOT NULL,
        task_id TEXT NOT NULL,
        delta REAL NOT NULL,
        reason TEXT NOT NULL,
        source_agent_id TEXT,
        created_at TEXT NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_reputation_agent ON reputation_log(agent_id);
      CREATE INDEX IF NOT EXISTS idx_reputation_created ON reputation_log(created_at);

      PRAGMA journal_mode=WAL;
    `);
  }

  // ── Agent CRUD ──

  registerAgent(input: RegisterInput): AgentRow {
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO agents (id, username, hostname, name, avatar, status, created_at)
      VALUES (?, ?, ?, ?, ?, 'idle', ?)
    `).run(input.id, input.username, input.hostname, input.name, input.avatar ?? '🧙', now);
    return this.getAgent(input.id)!;
  }

  getAgent(id: string): AgentRow | undefined {
    return this.db.prepare(
      'SELECT id, username, hostname, name, avatar, status, reputation, last_seen_at as lastSeenAt, created_at as createdAt FROM agents WHERE id = ?'
    ).get(id) as AgentRow | undefined;
  }

  getAgentByUsername(username: string): AgentRow | undefined {
    return this.db.prepare(
      'SELECT id, username, hostname, name, avatar, status, reputation, last_seen_at as lastSeenAt, created_at as createdAt FROM agents WHERE username = ?'
    ).get(username) as AgentRow | undefined;
  }

  updateAgentStatus(id: string, status: string): void {
    this.db.prepare('UPDATE agents SET status = ? WHERE id = ?').run(status, id);
  }

  updateHeartbeat(id: string): void {
    this.db.prepare('UPDATE agents SET last_seen_at = ?, status = CASE WHEN status = ? THEN ? ELSE status END WHERE id = ?')
      .run(new Date().toISOString(), 'offline', 'idle', id);
  }

  setAgentOffline(id: string): void {
    this.db.prepare('UPDATE agents SET status = ? WHERE id = ?').run('offline', id);
  }

  listOnlineAgents(): AgentRow[] {
    return this.db.prepare(
      "SELECT id, username, hostname, name, avatar, status, reputation, last_seen_at as lastSeenAt, created_at as createdAt FROM agents WHERE status != 'offline'"
    ).all() as AgentRow[];
  }

  // ── Projects ──

  updateProjects(agentId: string, projects: ProjectInput[]): void {
    const now = new Date().toISOString();
    const deleteStmt = this.db.prepare('DELETE FROM agent_projects WHERE agent_id = ?');
    const insertStmt = this.db.prepare('INSERT INTO agent_projects (agent_id, repo, skills, updated_at) VALUES (?, ?, ?, ?)');

    const tx = this.db.transaction(() => {
      deleteStmt.run(agentId);
      for (const p of projects) {
        insertStmt.run(agentId, p.repo, p.skills, now);
      }
    });
    tx();
  }

  getProjects(agentId: string): ProjectRow[] {
    return this.db.prepare(
      'SELECT agent_id as agentId, repo, skills, updated_at as updatedAt FROM agent_projects WHERE agent_id = ?'
    ).all(agentId) as ProjectRow[];
  }

  findAgentsByCapability(keyword: string): { agentId: string; repo: string }[] {
    // 转义 SQL LIKE 通配符防止非预期匹配
    const escaped = keyword.replace(/%/g, '\\%').replace(/_/g, '\\_');
    return this.db.prepare(`
      SELECT ap.agent_id as agentId, ap.repo
      FROM agent_projects ap
      JOIN agents a ON a.id = ap.agent_id
      WHERE a.status != 'offline'
        AND ap.skills LIKE ? ESCAPE '\\'
    `).all(`%${escaped}%`) as { agentId: string; repo: string }[];
  }

  // ── Audit Log ──

  logAudit(eventType: string, agentId: string, targetAgentId?: string, taskId?: string, detail?: Record<string, unknown>): void {
    this.db.prepare(`
      INSERT INTO audit_log (timestamp, event_type, agent_id, target_agent_id, task_id, detail)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(new Date().toISOString(), eventType, agentId, targetAgentId ?? null, taskId ?? null, JSON.stringify(detail ?? {}));
  }

  getAuditLogs(agentId: string, limit = 100): AuditRow[] {
    return this.db.prepare(
      'SELECT id, timestamp, event_type as eventType, agent_id as agentId, target_agent_id as targetAgentId, task_id as taskId, detail FROM audit_log WHERE agent_id = ? ORDER BY timestamp DESC LIMIT ?'
    ).all(agentId, limit) as AuditRow[];
  }

  // ── Reputation ──

  updateReputation(agentId: string, delta: number): void {
    this.db.prepare(`
      UPDATE agents SET reputation = MAX(0, reputation + ?) WHERE id = ?
    `).run(delta, agentId);
  }

  logReputation(agentId: string, taskId: string, delta: number, reason: string, sourceAgentId?: string): void {
    this.db.prepare(`
      INSERT INTO reputation_log (agent_id, task_id, delta, reason, source_agent_id, created_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(agentId, taskId, delta, reason, sourceAgentId ?? null, new Date().toISOString());
  }

  getReputationLogs(agentId: string, limit = 100): Array<{ id: number; taskId: string; delta: number; reason: string; createdAt: string }> {
    return this.db.prepare(`
      SELECT id, task_id as taskId, delta, reason, created_at as createdAt
      FROM reputation_log WHERE agent_id = ?
      ORDER BY created_at DESC LIMIT ?
    `).all(agentId, limit) as Array<{ id: number; taskId: string; delta: number; reason: string; createdAt: string }>;
  }

  getReputationCountToday(agentId: string, sourceAgentId: string): number {
    const today = new Date().toISOString().slice(0, 10);
    const row = this.db.prepare(`
      SELECT COUNT(*) as count FROM reputation_log
      WHERE agent_id = ? AND source_agent_id = ? AND created_at >= ?
    `).get(agentId, sourceAgentId, today) as { count: number } | undefined;
    return row?.count ?? 0;
  }

  getLastCollaborationAt(agentId: string): string | null {
    const row = this.db.prepare(`
      SELECT MAX(created_at) as lastAt FROM reputation_log
      WHERE agent_id = ? AND reason != 'decay'
    `).get(agentId) as { lastAt: string | null } | undefined;
    return row?.lastAt ?? null;
  }

  decayReputation(olderThanDays: number, decayAmount: number): number {
    const cutoff = new Date(Date.now() - olderThanDays * 24 * 60 * 60 * 1000).toISOString();

    const inactiveAgents = this.db.prepare(`
      SELECT a.id, a.reputation
      FROM agents a
      WHERE a.status != 'offline'
        AND (
          NOT EXISTS (SELECT 1 FROM reputation_log rl WHERE rl.agent_id = a.id AND rl.reason != 'decay')
          OR (SELECT MAX(rl2.created_at) FROM reputation_log rl2 WHERE rl2.agent_id = a.id AND rl2.reason != 'decay') < ?
        )
        AND a.reputation > 0
    `).all(cutoff) as Array<{ id: string; reputation: number }>;

    if (inactiveAgents.length === 0) return 0;

    const updateStmt = this.db.prepare(`
      UPDATE agents SET reputation = MAX(0, reputation - ?) WHERE id = ?
    `);
    const logStmt = this.db.prepare(`
      INSERT INTO reputation_log (agent_id, task_id, delta, reason, created_at)
      VALUES (?, '__decay__', ?, 'decay', ?)
    `);

    const now = new Date().toISOString();
    const tx = this.db.transaction(() => {
      for (const agent of inactiveAgents) {
        const actualDecay = Math.min(decayAmount, agent.reputation);
        updateStmt.run(actualDecay, agent.id);
        logStmt.run(agent.id, -actualDecay, now);
      }
    });
    tx();

    return inactiveAgents.length;
  }

  getLeaderboard(limit: number): Array<{
    agentId: string;
    name: string;
    avatar: string;
    reputation: number;
    status: string;
    helpCount: number;
  }> {
    return this.db.prepare(`
      SELECT a.id as agentId, a.name, a.avatar, a.reputation, a.status,
        (SELECT COUNT(*) FROM reputation_log rl WHERE rl.agent_id = a.id AND rl.reason != 'decay') as helpCount
      FROM agents a
      WHERE a.status != 'offline'
      ORDER BY a.reputation DESC
      LIMIT ?
    `).all(limit) as Array<{
      agentId: string;
      name: string;
      avatar: string;
      reputation: number;
      status: string;
      helpCount: number;
    }>;
  }

  close(): void {
    this.db.close();
  }
}
