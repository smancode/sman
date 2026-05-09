import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from '../utils/logger.js';
import type { ChatbotUserState, ChatbotSession, ChatbotWorkspace } from './types.js';

export class ChatbotStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('ChatbotStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS chatbot_users (
        user_key TEXT PRIMARY KEY,
        current_workspace TEXT NOT NULL,
        last_active_at TEXT NOT NULL DEFAULT (datetime('now'))
      );

      CREATE TABLE IF NOT EXISTS chatbot_sessions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_key TEXT NOT NULL,
        workspace TEXT NOT NULL,
        session_id TEXT NOT NULL,
        sdk_session_id TEXT,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        last_active_at TEXT NOT NULL DEFAULT (datetime('now')),
        UNIQUE(user_key, workspace)
      );

      CREATE TABLE IF NOT EXISTS chatbot_workspaces (
        path TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        added_at TEXT NOT NULL DEFAULT (datetime('now'))
      );
    `);
    this.db.pragma('journal_mode = WAL');

    // Migrate: add bot_label column if missing
    const columns = this.db.pragma('table_info(chatbot_sessions)') as Array<{ name: string }>;
    if (!columns.find(c => c.name === 'bot_label')) {
      this.db.exec('ALTER TABLE chatbot_sessions ADD COLUMN bot_label TEXT');
      this.log.info('Migrated chatbot_sessions: added bot_label column');
    }

    this.log.info('ChatbotStore initialized');
  }

  getUserState(userKey: string): ChatbotUserState | undefined {
    return this.db.prepare(
      'SELECT current_workspace as currentWorkspace, last_active_at as lastActiveAt FROM chatbot_users WHERE user_key = ?'
    ).get(userKey) as ChatbotUserState | undefined;
  }

  setUserState(userKey: string, workspace: string): void {
    this.db.prepare(
      `INSERT INTO chatbot_users (user_key, current_workspace, last_active_at) VALUES (?, ?, datetime('now'))
       ON CONFLICT(user_key) DO UPDATE SET current_workspace = excluded.current_workspace, last_active_at = datetime('now')`
    ).run(userKey, workspace);
  }

  getSession(userKey: string, workspace: string): ChatbotSession | undefined {
    return this.db.prepare(
      'SELECT session_id as sessionId, sdk_session_id as sdkSessionId, created_at as createdAt, last_active_at as lastActiveAt FROM chatbot_sessions WHERE user_key = ? AND workspace = ?'
    ).get(userKey, workspace) as ChatbotSession | undefined;
  }

  setSession(userKey: string, workspace: string, sessionId: string, botLabel?: string): void {
    this.db.prepare(
      `INSERT INTO chatbot_sessions (user_key, workspace, session_id, bot_label, created_at, last_active_at)
       VALUES (?, ?, ?, ?, datetime('now'), datetime('now'))
       ON CONFLICT(user_key, workspace) DO UPDATE SET
         session_id = excluded.session_id,
         bot_label = excluded.bot_label,
         last_active_at = datetime('now')`
    ).run(userKey, workspace, sessionId, botLabel ?? null);
  }

  getSessionsWithBotInfo(): Array<{ userKey: string; sessionId: string; botLabel: string | null; workspace: string }> {
    return this.db.prepare(
      'SELECT user_key as userKey, session_id as sessionId, bot_label as botLabel, workspace FROM chatbot_sessions ORDER BY last_active_at DESC'
    ).all() as Array<{ userKey: string; sessionId: string; botLabel: string | null; workspace: string }>;
  }

  deleteSession(userKey: string, workspace: string): void {
    this.db.prepare(
      'DELETE FROM chatbot_sessions WHERE user_key = ? AND workspace = ?'
    ).run(userKey, workspace);
  }

  updateSdkSessionId(userKey: string, workspace: string, sdkSessionId: string): void {
    this.db.prepare(
      "UPDATE chatbot_sessions SET sdk_session_id = ?, last_active_at = datetime('now') WHERE user_key = ? AND workspace = ?"
    ).run(sdkSessionId, userKey, workspace);
  }

  addWorkspace(wsPath: string, name: string): void {
    this.db.prepare(
      "INSERT OR IGNORE INTO chatbot_workspaces (path, name, added_at) VALUES (?, ?, datetime('now'))"
    ).run(wsPath, name);
  }

  listWorkspaces(): ChatbotWorkspace[] {
    return this.db.prepare(
      'SELECT path, name, added_at as addedAt FROM chatbot_workspaces ORDER BY name'
    ).all() as ChatbotWorkspace[];
  }

  findWorkspace(name: string): string | null {
    const row = this.db.prepare(
      'SELECT path FROM chatbot_workspaces WHERE name = ? OR path LIKE ?'
    ).get(name, `%/${name}`) as { path: string } | undefined;
    return row?.path ?? null;
  }

  isWorkspaceRegistered(wsPath: string): boolean {
    const row = this.db.prepare('SELECT 1 FROM chatbot_workspaces WHERE path = ?').get(wsPath);
    return !!row;
  }

  close(): void {
    this.db.close();
  }
}
