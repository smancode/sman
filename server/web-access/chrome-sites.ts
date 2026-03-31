/**
 * Chrome Site Discovery — reads Chrome Bookmarks + History
 * to build a site index for web-access auto-discovery.
 *
 * Bookmarks: JSON file, direct read
 * History: SQLite database, copy then read (Chrome locks the file)
 */

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createLogger } from '../utils/logger.js';

const log = createLogger('ChromeSites');

export interface SiteEntry {
  name: string;
  url: string;
}

// --- Chrome profile paths ---

function getChromeProfileDirs(): string[] {
  const home = os.homedir();
  const platform = os.platform();

  if (platform === 'darwin') {
    return [
      path.join(home, 'Library/Application Support/Google/Chrome/Default'),
      path.join(home, 'Library/Application Support/Google/Chrome/Profile 1'),
    ];
  }
  if (platform === 'linux') {
    return [
      path.join(home, '.config/google-chrome/Default'),
      path.join(home, '.config/google-chrome/Profile 1'),
    ];
  }
  if (platform === 'win32') {
    const localAppData = process.env.LOCALAPPDATA || '';
    return [
      path.join(localAppData, 'Google/Chrome/User Data/Default'),
      path.join(localAppData, 'Google/Chrome/User Data/Profile 1'),
    ];
  }
  return [];
}

// --- Bookmarks ---

function extractBookmarkUrls(obj: any, results: Map<string, string>, max: number): void {
  if (results.size >= max) return;

  if (obj.type === 'url' && obj.url) {
    const url = normalizeUrl(obj.url);
    if (url && !results.has(url)) {
      results.set(url, obj.name || '');
    }
  }

  for (const child of obj.children || []) {
    extractBookmarkUrls(child, results, max);
    if (results.size >= max) return;
  }
}

function readBookmarks(profileDir: string, results: Map<string, string>, max: number): void {
  const bookmarkPath = path.join(profileDir, 'Bookmarks');
  try {
    const content = fs.readFileSync(bookmarkPath, 'utf-8');
    const data = JSON.parse(content);
    for (const root of Object.values(data.roots || {})) {
      if (typeof root === 'object' && root !== null) {
        extractBookmarkUrls(root, results, max);
      }
    }
  } catch { /* skip */ }
}

// --- History ---

function readHistory(profileDir: string, results: Map<string, string>, max: number): void {
  const historyPath = path.join(profileDir, 'History');
  if (!fs.existsSync(historyPath)) return;

  // Copy to temp file — Chrome locks the original
  const tmpPath = path.join(os.tmpdir(), `sman-chrome-history-${Date.now()}.db`);
  try {
    fs.copyFileSync(historyPath, tmpPath);
  } catch { return; }

  try {
    // Use better-sqlite3 if available, otherwise skip
    const Database = require('better-sqlite3');
    const db = new Database(tmpPath, { readonly: true });
    const rows = db.prepare(
      'SELECT title, url FROM urls ORDER BY last_visit_time DESC LIMIT ?'
    ).all(max);

    for (const row of rows) {
      const url = normalizeUrl(row.url);
      if (url && !results.has(url)) {
        results.set(url, row.title || '');
      }
    }
    db.close();
  } catch {
    // better-sqlite3 not available or read error — skip history
  } finally {
    try { fs.unlinkSync(tmpPath); } catch { /* cleanup */ }
  }
}

// --- URL normalization ---

function normalizeUrl(raw: string): string | null {
  if (!raw) return null;
  const lower = raw.toLowerCase();
  if (!lower.startsWith('http://') && !lower.startsWith('https://')) return null;
  if (lower.includes('localhost') || lower.includes('127.0.0.1') || lower.includes('0.0.0.0')) return null;

  try {
    const parsed = new URL(raw);
    // Remove trailing slash for consistency
    return parsed.origin + parsed.pathname.replace(/\/+$/, '');
  } catch {
    return null;
  }
}

// --- Public API ---

/** Read all known sites from Chrome bookmarks + history */
export function discoverChromeSites(maxEntries = 300): SiteEntry[] {
  const seen = new Map<string, string>(); // url → name
  const profiles = getChromeProfileDirs();

  for (const profileDir of profiles) {
    if (!fs.existsSync(profileDir)) continue;

    // Bookmarks first (higher quality names)
    readBookmarks(profileDir, seen, maxEntries);
    // History for broader coverage
    readHistory(profileDir, seen, maxEntries);

    if (seen.size >= maxEntries) break;
  }

  log.info(`Discovered ${seen.size} sites from Chrome`);
  return Array.from(seen.entries()).map(([url, name]) => ({ name, url }));
}

/** Format sites as compact text for system prompt injection */
export function formatSitesForPrompt(entries: SiteEntry[]): string {
  if (entries.length === 0) return '';

  const lines = entries.map(e => `- ${e.name}: ${e.url}`);
  return `## Known Sites (from Chrome)

The following sites are known to the user from their Chrome bookmarks and browsing history.
When the user mentions a site by name or keyword (e.g., "ITSM", "Jira", "Confluence", "OA"), match it against this list and use the corresponding URL.

${lines.join('\n')}
`;
}
