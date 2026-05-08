import Database from 'better-sqlite3';
import { createLogger } from './utils/logger.js';

const log = createLogger('BroadcastStore');

export class BroadcastStore {
  private db: Database.Database;

  constructor(db: Database.Database) {
    this.db = db;
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS hub_broadcasts (
        id TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        body TEXT NOT NULL,
        created_at TEXT NOT NULL
      )
    `);
  }

  mergeBroadcasts(messages: Array<{ id: string; title: string; body: string; createdAt: string }>): number {
    const stmt = this.db.prepare(
      'INSERT OR IGNORE INTO hub_broadcasts (id, title, body, created_at) VALUES (?, ?, ?, ?)'
    );
    let added = 0;
    for (const m of messages) {
      const r = stmt.run(m.id, m.title, m.body, m.createdAt);
      if (r.changes > 0) added++;
    }
    if (added > 0) {
      log.info(`Stored ${added} new broadcast(s)`);
    }
    return added;
  }

  getRecent(days: number = 7): Array<{ id: string; title: string; body: string; createdAt: string }> {
    const since = new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString();
    return this.db.prepare(
      "SELECT id, title, body, created_at as createdAt FROM hub_broadcasts WHERE created_at >= ? ORDER BY created_at ASC"
    ).all(since) as Array<{ id: string; title: string; body: string; createdAt: string }>;
  }
}
