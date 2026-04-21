import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from './utils/logger.js';

export interface ExtractionProgress {
  workspace: string;
  sessionId: string;
  lastExtractedMessageId: number;
  updatedAt: string;
}

/**
 * SQLite store for knowledge extraction progress tracking.
 * Records which messages have been extracted per workspace+session,
 * so incremental extraction can pick up from where it left off.
 */
export class KnowledgeExtractorStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('KnowledgeExtractorStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS knowledge_extraction_progress (
        workspace TEXT NOT NULL,
        session_id TEXT NOT NULL,
        last_extracted_message_id INTEGER NOT NULL DEFAULT 0,
        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
        PRIMARY KEY (workspace, session_id)
      );
    `);
    this.db.pragma('journal_mode = WAL');
    this.log.info('Knowledge extraction progress table initialized');
  }

  getProgress(workspace: string, sessionId: string): ExtractionProgress | undefined {
    return this.db.prepare(
      'SELECT workspace, session_id as sessionId, last_extracted_message_id as lastExtractedMessageId, updated_at as updatedAt FROM knowledge_extraction_progress WHERE workspace = ? AND session_id = ?'
    ).get(workspace, sessionId) as ExtractionProgress | undefined;
  }

  setProgress(workspace: string, sessionId: string, lastExtractedMessageId: number): void {
    this.db.prepare(
      `INSERT INTO knowledge_extraction_progress (workspace, session_id, last_extracted_message_id, updated_at) VALUES (?, ?, ?, datetime('now'))
       ON CONFLICT(workspace, session_id) DO UPDATE SET last_extracted_message_id = excluded.last_extracted_message_id, updated_at = datetime('now')`
    ).run(workspace, sessionId, lastExtractedMessageId);
  }

  getAllProgressForWorkspace(workspace: string): ExtractionProgress[] {
    return this.db.prepare(
      'SELECT workspace, session_id as sessionId, last_extracted_message_id as lastExtractedMessageId, updated_at as updatedAt FROM knowledge_extraction_progress WHERE workspace = ?'
    ).all(workspace) as ExtractionProgress[];
  }

  close(): void {
    this.db.close();
  }
}
