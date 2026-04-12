/**
 * Cron Task Cache
 *
 * Two-layer cache: in-memory (sync) + IndexedDB (persistent).
 * Keys are namespaced by backend URL so switching backends doesn't mix data.
 *
 * - set() writes to both layers (DB write is async, non-blocking).
 * - get() reads from memory (sync).
 * - getAsync() reads from memory then falls back to IndexedDB.
 * - loadAll() called on startup to warm memory cache from IndexedDB.
 */

import type { CronTask } from '@/types/settings';

const DB_NAME = 'sman-cache';
const DB_VERSION = 1;
const STORE_NAME = 'cron-tasks';

function openDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME);
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function getBackendKey(): string {
  if (typeof window === 'undefined') return '_local';
  return localStorage.getItem('sman-backend-url') || '_local';
}

async function dbPut(key: string, tasks: CronTask[]): Promise<void> {
  try {
    const db = await openDB();
    const tx = db.transaction(STORE_NAME, 'readwrite');
    tx.objectStore(STORE_NAME).put(tasks, key);
    tx.oncomplete = () => db.close();
    tx.onerror = () => db.close();
  } catch (e) {
    console.warn('[CronCache] IndexedDB write failed:', e);
  }
}

class CronCache {
  private cached: CronTask[] | null = null;
  private cachedBackend: string | null = null;
  private dbReady: Promise<void> | null = null;

  /** Get namespaced key for current backend */
  private nk(): string {
    return getBackendKey();
  }

  /** Load cached tasks for current backend from IndexedDB into memory (call once on startup) */
  async loadAll(): Promise<void> {
    if (this.dbReady) return this.dbReady;
    this.dbReady = (async () => {
      try {
        const backend = this.nk();
        this.cachedBackend = backend;
        const db = await openDB();
        const tx = db.transaction(STORE_NAME, 'readonly');
        const req = tx.objectStore(STORE_NAME).get(backend);
        await new Promise<void>((resolve, reject) => {
          tx.oncomplete = () => resolve();
          tx.onerror = () => reject(tx.error);
        });
        if (req.result && !this.cached) {
          this.cached = req.result as CronTask[];
        }
        db.close();
      } catch (e) {
        console.warn('[CronCache] IndexedDB loadAll failed:', e);
      }
    })();
    return this.dbReady;
  }

  get(): CronTask[] | null {
    // Backend changed → memory cache is stale, return null
    if (this.cachedBackend && this.cachedBackend !== this.nk()) {
      return null;
    }
    return this.cached;
  }

  /** Async get — falls back to IndexedDB if memory miss or backend changed */
  async getAsync(): Promise<CronTask[] | null> {
    const backend = this.nk();
    // Memory hit for current backend
    if (this.cached && this.cachedBackend === backend) return this.cached;
    try {
      const db = await openDB();
      const tx = db.transaction(STORE_NAME, 'readonly');
      const req = tx.objectStore(STORE_NAME).get(backend);
      const result = await new Promise<CronTask[] | null>((resolve) => {
        req.onsuccess = () => resolve(req.result ?? null);
        req.onerror = () => resolve(null);
      });
      db.close();
      if (result) {
        this.cached = result;
        this.cachedBackend = backend;
      }
      return result;
    } catch {
      return null;
    }
  }

  set(tasks: CronTask[]): void {
    this.cached = tasks;
    this.cachedBackend = this.nk();
    dbPut(this.nk(), tasks);
  }

  has(): boolean {
    if (this.cachedBackend && this.cachedBackend !== this.nk()) return false;
    return this.cached !== null;
  }
}

export const cronCache = new CronCache();
