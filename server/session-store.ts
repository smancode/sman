import Database from 'better-sqlite3';
import { createLogger, type Logger } from './utils/logger.js';

export interface Session {
  id: string;
  systemId: string;
  workspace: string;
  label?: string;
  createdAt: string;
  lastActiveAt: string;
}

export interface Message {
  id: number;
  sessionId: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: string;
}

interface CreateSessionInput {
  id: string;
  systemId: string;
  workspace: string;
}

interface AddMessageInput {
  role: 'user' | 'assistant';
  content: string;
}

export class SessionStore {
  private db: Database.Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new Database(dbPath);
    this.log = createLogger('SessionStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY,
        system_id TEXT NOT NULL,
        workspace TEXT NOT NULL,
        label TEXT,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        last_active_at TEXT NOT NULL DEFAULT (datetime('now'))
      );

      CREATE TABLE IF NOT EXISTS messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id TEXT NOT NULL,
        role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
        content TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
      );

      CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages(session_id);
      CREATE INDEX IF NOT EXISTS idx_sessions_system_id ON sessions(system_id);
    `);

    // Migration: add label column if not exists
    try {
      this.db.prepare('SELECT label FROM sessions LIMIT 1').get();
    } catch {
      this.db.exec('ALTER TABLE sessions ADD COLUMN label TEXT');
      this.log.info('Migrated: added label column to sessions table');
    }

    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
    this.log.info('Database initialized');
  }

  createSession(input: CreateSessionInput): Session {
    const { id, systemId, workspace } = input;
    this.db.prepare(
      'INSERT OR IGNORE INTO sessions (id, system_id, workspace) VALUES (?, ?, ?)'
    ).run(id, systemId, workspace);

    return this.getSession(id)!;
  }

  getSession(id: string): Session | undefined {
    const row = this.db.prepare(
      'SELECT id, system_id as systemId, workspace, label, created_at as createdAt, last_active_at as lastActiveAt FROM sessions WHERE id = ?'
    ).get(id) as Session | undefined;
    return row;
  }

  listSessions(systemId?: string): Session[] {
    if (systemId) {
      return this.db.prepare(
        'SELECT id, system_id as systemId, workspace, label, created_at as createdAt, last_active_at as lastActiveAt FROM sessions WHERE system_id = ? ORDER BY last_active_at DESC'
      ).all(systemId) as Session[];
    }
    return this.db.prepare(
      'SELECT id, system_id as systemId, workspace, label, created_at as createdAt, last_active_at as lastActiveAt FROM sessions ORDER BY last_active_at DESC'
    ).all() as Session[];
  }

  addMessage(sessionId: string, input: AddMessageInput): Message {
    const { role, content } = input;
    this.db.prepare(
      "UPDATE sessions SET last_active_at = datetime('now') WHERE id = ?"
    ).run(sessionId);

    const result = this.db.prepare(
      'INSERT INTO messages (session_id, role, content) VALUES (?, ?, ?)'
    ).run(sessionId, role, content);

    return {
      id: result.lastInsertRowid as number,
      sessionId,
      role,
      content,
      createdAt: new Date().toISOString(),
    };
  }

  getMessages(sessionId: string, limit = 1000): Message[] {
    return this.db.prepare(
      'SELECT id, session_id as sessionId, role, content, created_at as createdAt FROM messages WHERE session_id = ? ORDER BY id ASC LIMIT ?'
    ).all(sessionId, limit) as Message[];
  }

  deleteSession(id: string): void {
    this.db.prepare('DELETE FROM sessions WHERE id = ?').run(id);
  }

  updateLabel(id: string, label: string): void {
    this.db.prepare('UPDATE sessions SET label = ? WHERE id = ?').run(label, id);
  }

  close(): void {
    this.db.close();
  }
}
