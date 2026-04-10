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

  close(): void {
    this.db.close();
  }
}
