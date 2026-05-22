import Database from 'better-sqlite3';
import path from 'node:path';
import os from 'node:os';
import { createLogger, type Logger } from './utils/logger.js';
import { emitAchievementEvent } from './achievement-events.js';

export interface Session {
  id: string;
  systemId: string;
  workspace: string;
  label?: string;
  isCron?: boolean;
  parentTaskId?: string | null;
  deletedAt?: string | null;
  createdAt: string;
  lastActiveAt: string;
}

export interface Message {
  id: number;
  sessionId: string;
  role: 'user' | 'assistant';
  content: string;
  contentBlocks?: ContentBlock[];
  isPartial?: boolean;
  createdAt: string;
}

export interface ContentBlock {
  type: 'text' | 'thinking' | 'tool_use' | 'image' | 'attached_file';
  text?: string;
  thinking?: string;
  id?: string;
  name?: string;
  input?: unknown;
  source?: {
    type: string;
    media_type: string;
    data: string;
  };
  fileName?: string;
  filePath?: string;
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

    // Migration: add is_partial column to messages for streaming progress saves
    try {
      this.db.prepare('SELECT is_partial FROM messages LIMIT 1').get();
    } catch {
      this.db.exec('ALTER TABLE messages ADD COLUMN is_partial INTEGER DEFAULT 0');
      this.log.info('Migrated: added is_partial column to messages table');
    }

    // Migration: add parent_task_id column if not exists
    try {
      this.db.prepare('SELECT parent_task_id FROM sessions LIMIT 1').get();
    } catch {
      this.db.exec('ALTER TABLE sessions ADD COLUMN parent_task_id TEXT DEFAULT NULL');
      this.log.info('Migrated: added parent_task_id column to sessions table');
    }

    // IM tables — create tables first, then migrate columns, then create indexes
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS im_messages (
        id TEXT PRIMARY KEY,
        room_id TEXT NOT NULL,
        sender TEXT NOT NULL,
        content TEXT NOT NULL,
        mentioned_agents TEXT,
        quote_id TEXT,
        type TEXT NOT NULL DEFAULT 'text',
        status TEXT DEFAULT NULL,
        attachments TEXT,
        session_id TEXT,
        timestamp INTEGER NOT NULL,
        created_at DATETIME DEFAULT (datetime('now', 'localtime')),
        updated_at DATETIME DEFAULT (datetime('now', 'localtime'))
      );

      CREATE TABLE IF NOT EXISTS im_rooms (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        type TEXT NOT NULL DEFAULT 'group',
        members TEXT NOT NULL,
        last_message TEXT,
        last_message_time INTEGER,
        created_at DATETIME DEFAULT (datetime('now', 'localtime'))
      );
    `);

    // IM messages seq migration — must happen before index creation
    try {
      this.db.exec('ALTER TABLE im_messages ADD COLUMN seq INTEGER DEFAULT 0');
      this.log.info('Migrated: added seq column to im_messages table');
    } catch {
      // Column already exists — ignore
    }

    // IM read receipts migration
    try {
      this.db.exec('ALTER TABLE im_rooms ADD COLUMN last_read TEXT DEFAULT "{}"');
      this.log.info('Migrated: added last_read column to im_rooms table');
    } catch {
      // column already exists
    }

    // Create indexes after migrations
    this.db.exec(`
      CREATE INDEX IF NOT EXISTS idx_im_messages_room_ts ON im_messages(room_id, timestamp);
      CREATE INDEX IF NOT EXISTS idx_im_messages_sender ON im_messages(sender);
      CREATE INDEX IF NOT EXISTS idx_im_messages_session ON im_messages(session_id);
      CREATE INDEX IF NOT EXISTS idx_im_messages_room_seq ON im_messages(room_id, seq);
    `);

    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
    this.log.info('Database initialized');
  }

  getDatabase(): Database.Database {
    return this.db;
  }

  createSession(input: CreateSessionInput): Session {
    const { id, systemId, workspace, isCron } = input;
    const isCronValue = isCron ? 1 : 0;
    this.db.prepare(
      'INSERT OR IGNORE INTO sessions (id, system_id, workspace, is_cron) VALUES (?, ?, ?, ?)'
    ).run(id, systemId, workspace, isCronValue);

    emitAchievementEvent({ type: 'session_created', data: { workspace } });

    return this.getSession(id)!;
  }

  getSession(id: string): Session | undefined {
    const row = this.db.prepare(
      'SELECT id, system_id as systemId, workspace, label, is_cron as isCron, parent_task_id as parentTaskId, created_at as createdAt, last_active_at as lastActiveAt FROM sessions WHERE id = ?'
    ).get(id) as Session | undefined;
    return row;
  }

  listSessions(systemId?: string): Session[] {
    const groupBaseDir = path.join(os.homedir(), '.sman', 'group');
    // 排除 cron 会话、已软删除的会话、group task 会话（workspace 指向 group 目录的）
    const baseWhere = "(is_cron = 0 OR is_cron IS NULL) AND deleted_at IS NULL AND workspace NOT LIKE ?";
    const fields = 'id, system_id as systemId, workspace, label, is_cron as isCron, parent_task_id as parentTaskId, created_at as createdAt, last_active_at as lastActiveAt';
    if (systemId) {
      return this.db.prepare(
        `SELECT ${fields} FROM sessions WHERE system_id = ? AND ${baseWhere} ORDER BY last_active_at DESC`
      ).all(systemId, `${groupBaseDir}/%`) as Session[];
    }
    return this.db.prepare(
      `SELECT ${fields} FROM sessions WHERE ${baseWhere} ORDER BY last_active_at DESC`
    ).all(`${groupBaseDir}/%`) as Session[];
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

  /** Fix non-canonical workspace paths (e.g. \core → D:\core on Windows) */
  updateWorkspace(sessionId: string, workspace: string): void {
    this.db.prepare(
      'UPDATE sessions SET workspace = ?, system_id = ? WHERE id = ?'
    ).run(workspace, workspace, sessionId);
  }

  setParentTaskId(sessionId: string, parentTaskId: string): void {
    this.db.prepare(
      'UPDATE sessions SET parent_task_id = ? WHERE id = ?'
    ).run(parentTaskId, sessionId);
  }

  /**
   * Upsert a partial assistant message for streaming progress.
   * Uses is_partial=1 flag on a regular 'assistant' message.
   * Called periodically during streaming so refresh doesn't lose in-progress content.
   */
  upsertPartialMessage(sessionId: string, content: string, contentBlocks?: unknown[]): void {
    const contentBlocksJson = contentBlocks ? JSON.stringify(contentBlocks) : null;
    const existing = this.db.prepare(
      'SELECT id FROM messages WHERE session_id = ? AND is_partial = 1 ORDER BY id DESC LIMIT 1'
    ).get(sessionId) as { id: number } | undefined;

    if (existing) {
      this.db.prepare(
        'UPDATE messages SET content = ?, content_blocks = ? WHERE id = ?'
      ).run(content, contentBlocksJson, existing.id);
    } else {
      this.db.prepare(
        'INSERT INTO messages (session_id, role, content, content_blocks, is_partial) VALUES (?, ?, ?, ?, 1)'
      ).run(sessionId, 'assistant', content, contentBlocksJson);
    }
  }

  /**
   * Remove all partial messages for a session (called when stream completes).
   */
  clearPartialMessages(sessionId: string): void {
    this.db.prepare(
      'DELETE FROM messages WHERE session_id = ? AND is_partial = 1'
    ).run(sessionId);
  }

  getMessages(sessionId: string, limit = 1000): Message[] {
    const rows = this.db.prepare(
      'SELECT id, session_id as sessionId, role, content, content_blocks as contentBlocks, is_partial as isPartial, created_at as createdAt FROM messages WHERE session_id = ? ORDER BY id ASC LIMIT ?'
    ).all(sessionId, limit) as Array<Omit<Message, 'contentBlocks' | 'isPartial'> & { contentBlocks: string | null; isPartial: number }>;
    return rows.map(row => ({
      ...row,
      isPartial: row.isPartial === 1 || undefined,
      contentBlocks: row.contentBlocks ? JSON.parse(row.contentBlocks) as ContentBlock[] : undefined,
    }));
  }

  /**
   * Get messages with id > afterId for incremental extraction.
   */
  getMessagesAfterId(sessionId: string, afterId: number, limit = 2000): Message[] {
    const rows = this.db.prepare(
      'SELECT id, session_id as sessionId, role, content, content_blocks as contentBlocks, is_partial as isPartial, created_at as createdAt FROM messages WHERE session_id = ? AND id > ? ORDER BY id ASC LIMIT ?'
    ).all(sessionId, afterId, limit) as Array<Omit<Message, 'contentBlocks' | 'isPartial'> & { contentBlocks: string | null; isPartial: number }>;
    return rows.map(row => ({
      ...row,
      isPartial: row.isPartial === 1 || undefined,
      contentBlocks: row.contentBlocks ? JSON.parse(row.contentBlocks) as ContentBlock[] : undefined,
    }));
  }

  /**
   * Get sessions for a workspace. By default excludes soft-deleted and cron sessions.
   * Set includeDeleted=true to also include deleted sessions (for knowledge extraction).
   */
  getSessionsByWorkspace(workspace: string, includeDeleted = false): Session[] {
    const sql = includeDeleted
      ? 'SELECT id, system_id as systemId, workspace, label, is_cron as isCron, deleted_at as deletedAt, created_at as createdAt, last_active_at as lastActiveAt FROM sessions WHERE workspace = ? AND (is_cron = 0 OR is_cron IS NULL) ORDER BY last_active_at DESC'
      : 'SELECT id, system_id as systemId, workspace, label, is_cron as isCron, created_at as createdAt, last_active_at as lastActiveAt FROM sessions WHERE workspace = ? AND deleted_at IS NULL AND (is_cron = 0 OR is_cron IS NULL) ORDER BY last_active_at DESC';
    return this.db.prepare(sql).all(workspace) as Session[];
  }

  /** Get a single session's workspace path. */
  getSessionWorkspace(sessionId: string): string | undefined {
    const row = this.db.prepare('SELECT workspace FROM sessions WHERE id = ?').get(sessionId) as { workspace: string } | undefined;
    return row?.workspace;
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

  clearSdkSessionId(id: string): void {
    this.db.prepare('UPDATE sessions SET sdk_session_id = NULL WHERE id = ?').run(id);
  }

  getActiveSessionCount(): number {
    const row = this.db.prepare(
      "SELECT COUNT(*) as c FROM sessions WHERE last_active_at > datetime('now', '-1 hour') AND deleted_at IS NULL"
    ).get() as { c: number };
    return row.c;
  }

  /** Get distinct active workspaces (excluding deleted, cron, and iterate/collect-bot sessions) */
  getActiveWorkspaces(): string[] {
    const rows = this.db.prepare(
      'SELECT DISTINCT workspace FROM sessions WHERE deleted_at IS NULL AND (is_cron = 0 OR is_cron IS NULL) ORDER BY workspace'
    ).all() as Array<{ workspace: string }>;
    const iterateDir = path.join(os.homedir(), '.sman', 'iterate');
    return rows.map(r => r.workspace).filter(ws => !ws.startsWith(iterateDir));
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
