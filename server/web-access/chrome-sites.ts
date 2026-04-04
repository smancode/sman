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
import { CdpEngine } from './cdp-engine.js';

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

/**
 * Query Chrome History SQLite for a single profile.
 * Copies to a temp file because Chrome locks the original.
 */
function queryHistoryDb(profileDir: string, max: number): Array<{ title: string; url: string }> {
  const historyPath = path.join(profileDir, 'History');
  if (!fs.existsSync(historyPath)) return [];

  const tmpPath = path.join(os.tmpdir(), `sman-chrome-history-${Date.now()}.db`);
  try {
    CdpEngine.copyFileLocked(historyPath, tmpPath);
  } catch { return []; }

  try {
    const Database = require('better-sqlite3');
    const db = new Database(tmpPath, { readonly: true });
    const rows = db.prepare(
      'SELECT title, url FROM urls WHERE url IS NOT NULL ORDER BY last_visit_time DESC LIMIT ?'
    ).all(max);
    db.close();
    return rows;
  } catch {
    return [];
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

/** Read Chrome History with titles, returning ALL entries (not deduped).
 *  Used by web_access_find_url to give Claude full context for semantic matching. */
export function readAllHistory(maxEntries = 500): SiteEntry[] {
  const results: SiteEntry[] = [];

  for (const profileDir of getChromeProfileDirs()) {
    if (!fs.existsSync(profileDir)) continue;
    for (const row of queryHistoryDb(profileDir, maxEntries - results.length)) {
      const url = normalizeUrl(row.url);
      if (url) results.push({ name: row.title || '', url });
    }
    if (results.length >= maxEntries) break;
  }

  log.info(`Read ${results.length} history entries for URL matching`);
  return results;
}

/** Read all known sites from Chrome bookmarks + history */
export function discoverChromeSites(maxEntries = 300): SiteEntry[] {
  const seen = new Map<string, string>(); // url -> name

  for (const profileDir of getChromeProfileDirs()) {
    if (!fs.existsSync(profileDir)) continue;

    // Bookmarks first (higher quality names)
    readBookmarks(profileDir, seen, maxEntries);

    // History for broader coverage
    for (const row of queryHistoryDb(profileDir, maxEntries - seen.size)) {
      const url = normalizeUrl(row.url);
      if (url && !seen.has(url)) seen.set(url, row.title || '');
    }

    if (seen.size >= maxEntries) break;
  }

  log.info(`Discovered ${seen.size} sites from Chrome`);
  return Array.from(seen.entries()).map(([url, name]) => ({ name, url }));
}
