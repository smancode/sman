/**
 * WeChat Personal Bot — Credential & Cursor Persistence
 * Stores to ~/.sman/weixin/
 */
import fs from 'fs';
import path from 'path';
import type { WeixinAccountData } from './weixin-types.js';

// ── Path Helpers ──

function weixinDir(homeDir: string): string {
  return path.join(homeDir, 'weixin');
}

function accountsDir(homeDir: string): string {
  return path.join(weixinDir(homeDir), 'accounts');
}

function accountPath(homeDir: string, accountId: string): string {
  return path.join(accountsDir(homeDir), `${accountId}.json`);
}

function syncPath(homeDir: string, accountId: string): string {
  return path.join(accountsDir(homeDir), `${accountId}.sync.json`);
}

function indexPath(homeDir: string): string {
  return path.join(weixinDir(homeDir), 'accounts.json');
}

function ensureDir(dir: string): void {
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
}

// ── Account CRUD ──

export function saveAccount(
  homeDir: string,
  accountId: string,
  data: WeixinAccountData,
): void {
  const dir = accountsDir(homeDir);
  ensureDir(dir);

  // Merge with existing data
  const existing = loadAccount(homeDir, accountId);
  const merged: WeixinAccountData = {
    ...existing,
    ...data,
    savedAt: new Date().toISOString(),
  };

  const filePath = accountPath(homeDir, accountId);
  fs.writeFileSync(filePath, JSON.stringify(merged, null, 2), 'utf-8');
  try {
    fs.chmodSync(filePath, 0o600);
  } catch {
    // chmod may fail on some platforms, non-critical
  }

  // Register in index
  registerAccountId(homeDir, accountId);
}

export function loadAccount(
  homeDir: string,
  accountId: string,
): WeixinAccountData | null {
  const filePath = accountPath(homeDir, accountId);
  if (!fs.existsSync(filePath)) return null;
  try {
    const raw = fs.readFileSync(filePath, 'utf-8');
    return JSON.parse(raw) as WeixinAccountData;
  } catch {
    return null;
  }
}

export function clearAccount(homeDir: string, accountId: string): void {
  // Remove account file
  const ap = accountPath(homeDir, accountId);
  if (fs.existsSync(ap)) fs.unlinkSync(ap);

  // Remove sync file
  const sp = syncPath(homeDir, accountId);
  if (fs.existsSync(sp)) fs.unlinkSync(sp);

  // Remove from index
  unregisterAccountId(homeDir, accountId);
}

export function listAccountIds(homeDir: string): string[] {
  const ip = indexPath(homeDir);
  if (!fs.existsSync(ip)) return [];
  try {
    return JSON.parse(fs.readFileSync(ip, 'utf-8')) as string[];
  } catch {
    return [];
  }
}

// ── Index Management ──

function registerAccountId(homeDir: string, accountId: string): void {
  const dir = weixinDir(homeDir);
  ensureDir(dir);

  const ids = listAccountIds(homeDir);
  if (!ids.includes(accountId)) {
    ids.push(accountId);
    fs.writeFileSync(indexPath(homeDir), JSON.stringify(ids, null, 2), 'utf-8');
  }
}

function unregisterAccountId(homeDir: string, accountId: string): void {
  const ids = listAccountIds(homeDir);
  const filtered = ids.filter((id) => id !== accountId);
  fs.writeFileSync(indexPath(homeDir), JSON.stringify(filtered, null, 2), 'utf-8');
}

// ── Cursor (get_updates_buf) ──

export function saveCursor(homeDir: string, accountId: string, buf: string): void {
  const dir = accountsDir(homeDir);
  ensureDir(dir);
  fs.writeFileSync(syncPath(homeDir, accountId), JSON.stringify({ get_updates_buf: buf }), 'utf-8');
}

export function loadCursor(homeDir: string, accountId: string): string | undefined {
  const sp = syncPath(homeDir, accountId);
  if (!fs.existsSync(sp)) return undefined;
  try {
    const data = JSON.parse(fs.readFileSync(sp, 'utf-8'));
    return data.get_updates_buf;
  } catch {
    return undefined;
  }
}
