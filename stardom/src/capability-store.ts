// stardom/src/capability-store.ts
import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import fs from 'fs';
import path from 'path';

export interface CapabilityInput {
  name: string;
  description: string;
  version: string;
  category: string;
  packageUrl: string;
  readme?: string;
}

export interface CapabilityRow {
  name: string;
  description: string;
  version: string;
  category: string;
  packageUrl: string;
  readme: string;
  createdAt: string;
  updatedAt: string;
}

export class CapabilityStore {
  private db: Database;

  constructor(dbPath: string) {
    const dir = path.dirname(dbPath);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    this.db = new DatabaseConstructor(dbPath);
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS capabilities (
        name TEXT PRIMARY KEY,
        description TEXT NOT NULL,
        version TEXT NOT NULL,
        category TEXT NOT NULL,
        package_url TEXT NOT NULL,
        readme TEXT DEFAULT '',
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );

      PRAGMA journal_mode=WAL;
    `);
  }

  publish(input: CapabilityInput): void {
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO capabilities (name, description, version, category, package_url, readme, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(name) DO UPDATE SET
        description = excluded.description,
        version = excluded.version,
        category = excluded.category,
        package_url = excluded.package_url,
        readme = excluded.readme,
        updated_at = excluded.updated_at
    `).run(input.name, input.description, input.version, input.category, input.packageUrl, input.readme ?? '', now, now);
  }

  get(name: string): CapabilityRow | undefined {
    return this.db.prepare(`
      SELECT name, description, version, category, package_url as packageUrl,
        readme, created_at as createdAt, updated_at as updatedAt
      FROM capabilities WHERE name = ?
    `).get(name) as CapabilityRow | undefined;
  }

  search(keyword: string): CapabilityRow[] {
    const escaped = keyword.replace(/%/g, '\\%').replace(/_/g, '\\_');
    return this.db.prepare(`
      SELECT name, description, version, category, package_url as packageUrl,
        readme, created_at as createdAt, updated_at as updatedAt
      FROM capabilities
      WHERE name LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\'
      ORDER BY updated_at DESC
    `).all(`%${escaped}%`, `%${escaped}%`) as CapabilityRow[];
  }

  list(): CapabilityRow[] {
    return this.db.prepare(`
      SELECT name, description, version, category, package_url as packageUrl,
        readme, created_at as createdAt, updated_at as updatedAt
      FROM capabilities ORDER BY category, name
    `).all() as CapabilityRow[];
  }

  remove(name: string): void {
    this.db.prepare('DELETE FROM capabilities WHERE name = ?').run(name);
  }

  close(): void {
    this.db.close();
  }
}
