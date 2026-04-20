import Database from 'better-sqlite3';
import { createLogger, type Logger } from './utils/logger.js';

export interface Session {
  id: string;
  systemId: string;
  workspace: string;
  label?: string;
  isCron?: boolean;
  createdAt: string;
  lastActiveAt: string;
}

export interface Message {
  id: number;
  sessionId: string;
  role: 'user' | 'assistant';
  content: string;
  contentBlocks?: ContentBlock[];
  createdAt: string;
}

export interface ContentBlock {
  type: 'text' | 'thinking' | 'tool_use';
  text?: string;
  thinking?: string;
  id?: string;
  name?: string;
  input?: unknown;
}

interface CreateSessionInput {
  id: string;
  systemId: string;
  workspace: string;
  isCron?: boolean;
}

interface AddMessageInput {
  role: 'user' | 'assistant';
  content: string;
  contentBlocks?: ContentBlock[];
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
        sdk_session_id TEXT,
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

    // Migration: add sdk_session_id column if not exists
    try {
      this.db.prepare('SELECT sdk_session_id FROM sessions LIMIT 1').get();
    } catch {
      this.db.exec('ALTER TABLE sessions ADD COLUMN sdk_session_id TEXT');
      this.log.info('Migrated: added sdk_session_id column to sessions table');
    }

    // Migration: add content_blocks column if not exists
    try {
      this.db.prepare('SELECT content_blocks FROM messages LIMIT 1').get();
    } catch {
      this.db.exec('ALTER TABLE messages ADD COLUMN content_blocks TEXT');
      this.log.info('Migrated: added content_blocks column to messages table');
    }

    // Migration: add is_cron column if not exists
    try {
      this.db.prepare('SELECT is_cron FROM sessions LIMIT 1').get();
    } catch {
      this.db.exec('ALTER TABLE sessions ADD COLUMN is_cron INTEGER DEFAULT 0');
      this.log.info('Migrated: added is_cron column to sessions table');
    }

    // Migration: add deleted_at column if not exists
    try {
      this.db.prepare('SELECT deleted_at FROM sessions LIMIT 1').get();
    } catch {
      this.db.exec("ALTER TABLE sessions ADD COLUMN deleted_at TEXT DEFAULT NULL");
      this.db.exec('CREATE INDEX IF NOT EXISTS idx_sessions_deleted_at ON sessions(deleted_at)');
      this.log.info('Migrated: added deleted_at column to sessions table');
    }

    // Migration: add token usage columns if not exists
    try {
      this.db.prepare('SELECT input_tokens FROM sessions LIMIT 1').get();
    } catch {
      this.db.exec('ALTER TABLE sessions ADD COLUMN input_tokens INTEGER DEFAULT 0');
      this.db.exec('ALTER TABLE sessions ADD COLUMN output_tokens INTEGER DEFAULT 0');
      this.log.info('Migrated: added token usage columns to sessions table');
    }

    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
    this.log.info('Database initialized');
  }

  createSession(input: CreateSessionInput): Session {
    const { id, systemId, workspace, isCron } = input;
    const isCronValue = isCron ? 1 : 0;
    this.db.prepare(
      'INSERT OR IGNORE INTO sessions (id, system_id, workspace, is_cron) VALUES (?, ?, ?, ?)'
    ).run(id, systemId, workspace, isCronValue);

    return this.getSession(id)!;
  }

  getSession(id: string): Session | undefined {
    const row = this.db.prepare(
      'SELECT id, system_id as systemId, workspace, label, is_cron as isCron, created_at as createdAt, last_active_at as lastActiveAt FROM sessions WHERE id = ?'
    ).get(id) as Session | undefined;
    return row;
  }

  listSessions(systemId?: string): Session[] {
    // 默认排除 cron 会话和已软删除的会话
    if (systemId) {
      return this.db.prepare(
        'SELECT id, system_id as systemId, workspace, label, is_cron as isCron, created_at as createdAt, last_active_at as lastActiveAt FROM sessions WHERE system_id = ? AND (is_cron = 0 OR is_cron IS NULL) AND deleted_at IS NULL ORDER BY last_active_at DESC'
      ).all(systemId) as Session[];
    }
    return this.db.prepare(
      'SELECT id, system_id as systemId, workspace, label, is_cron as isCron, created_at as createdAt, last_active_at as lastActiveAt FROM sessions WHERE (is_cron = 0 OR is_cron IS NULL) AND deleted_at IS NULL ORDER BY last_active_at DESC'
    ).all() as Session[];
  }

  addMessage(sessionId: string, input: AddMessageInput): Message {
    const { role, content, contentBlocks } = input;
    this.db.prepare(
      "UPDATE sessions SET last_active_at = datetime('now') WHERE id = ?"
    ).run(sessionId);

    const contentBlocksJson = contentBlocks ? JSON.stringify(contentBlocks) : null;
    const result = this.db.prepare(
      'INSERT INTO messages (session_id, role, content, content_blocks) VALUES (?, ?, ?, ?)'
    ).run(sessionId, role, content, contentBlocksJson);

    return {
      id: result.lastInsertRowid as number,
      sessionId,
      role,
      content,
      contentBlocks,
      createdAt: new Date().toISOString(),
    };
  }

  getMessages(sessionId: string, limit = 1000): Message[] {
    const rows = this.db.prepare(
      'SELECT id, session_id as sessionId, role, content, content_blocks as contentBlocks, created_at as createdAt FROM messages WHERE session_id = ? ORDER BY id ASC LIMIT ?'
    ).all(sessionId, limit) as Array<Omit<Message, 'contentBlocks'> & { contentBlocks: string | null }>;
    return rows.map(row => ({
      ...row,
      contentBlocks: row.contentBlocks ? JSON.parse(row.contentBlocks) as ContentBlock[] : undefined,
    }));
  }

  deleteSession(id: string): void {
    this.db.prepare("UPDATE sessions SET deleted_at = datetime('now') WHERE id = ?").run(id);
  }

  restoreSession(id: string): void {
    this.db.prepare('UPDATE sessions SET deleted_at = NULL WHERE id = ?').run(id);
  }

  updateLabel(id: string, label: string): void {
    this.db.prepare('UPDATE sessions SET label = ? WHERE id = ?').run(label, id);
  }

  updateSdkSessionId(id: string, sdkSessionId: string): void {
    this.db.prepare('UPDATE sessions SET sdk_session_id = ? WHERE id = ?').run(sdkSessionId, id);
  }

  getSdkSessionId(id: string): string | undefined {
    const row = this.db.prepare('SELECT sdk_session_id FROM sessions WHERE id = ?').get(id) as { sdk_session_id: string } | undefined;
    return row?.sdk_session_id;
  }

  updateTokenUsage(id: string, inputTokens: number, outputTokens: number): void {
    this.db.prepare('UPDATE sessions SET input_tokens = ?, output_tokens = ? WHERE id = ?').run(inputTokens, outputTokens, id);
  }

  getTokenUsage(id: string): { inputTokens: number; outputTokens: number } | undefined {
    const row = this.db.prepare('SELECT input_tokens as inputTokens, output_tokens as outputTokens FROM sessions WHERE id = ?').get(id) as { inputTokens: number; outputTokens: number } | undefined;
    return row;
  }

  close(): void {
    this.db.close();
  }
}
