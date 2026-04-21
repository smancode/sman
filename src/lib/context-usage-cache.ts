/**
 * Context Usage Cache
 *
 * Two-layer cache: in-memory Map (sync) + IndexedDB (persistent).
 * Keys are namespaced by backend URL so switching backends doesn't mix data.
 *
 * - set() writes to both layers (DB write is async, non-blocking).
 * - get() reads from memory (sync, used by switchSession).
 * - getAsync() reads from memory then falls back to IndexedDB.
 */

const DB_NAME = 'sman-context-usage';
const DB_VERSION = 1;
const STORE_NAME = 'usage';

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

/** Fire-and-forget write to IndexedDB */
async function dbPut(key: string, usage: unknown): Promise<void> {
  try {
    const db = await openDB();
    const tx = db.transaction(STORE_NAME, 'readwrite');
    tx.objectStore(STORE_NAME).put(usage, key);
    tx.oncomplete = () => db.close();
    tx.onerror = () => db.close();
  } catch (e) {
    console.warn('[ContextUsageCache] IndexedDB write failed:', e);
  }
}

/** Fire-and-forget delete from IndexedDB */
async function dbDelete(key: string): Promise<void> {
  try {
    const db = await openDB();
    const tx = db.transaction(STORE_NAME, 'readwrite');
    tx.objectStore(STORE_NAME).delete(key);
    tx.oncomplete = () => db.close();
    tx.onerror = () => db.close();
  } catch (e) {
    console.warn('[ContextUsageCache] IndexedDB delete failed:', e);
  }
}

class ContextUsageCache {
  private cache = new Map<string, { inputTokens: number; outputTokens: number }>();

  /** Get namespaced key for current backend */
  private nk(sessionId: string): string {
    return `${getBackendKey()}:${sessionId}`;
  }

  get(sessionId: string): { inputTokens: number; outputTokens: number } | null {
    return this.cache.get(this.nk(sessionId)) ?? null;
  }

  /** Async get — falls back to IndexedDB if memory miss */
  async getAsync(sessionId: string): Promise<{ inputTokens: number; outputTokens: number } | null> {
    const key = this.nk(sessionId);
    const mem = this.cache.get(key);
    if (mem) return mem;
    try {
      const db = await openDB();
      const tx = db.transaction(STORE_NAME, 'readonly');
      const req = tx.objectStore(STORE_NAME).get(key);
      const result = await new Promise<{ inputTokens: number; outputTokens: number } | null>((resolve) => {
        req.onsuccess = () => resolve(req.result ?? null);
        req.onerror = () => resolve(null);
      });
      db.close();
      if (result) {
        this.cache.set(key, result);
      }
      return result;
    } catch {
      return null;
    }
  }

  set(sessionId: string, usage: { inputTokens: number; outputTokens: number }): void {
    const key = this.nk(sessionId);
    this.cache.set(key, usage);
    dbPut(key, usage);
  }

  invalidate(sessionId: string): void {
    const key = this.nk(sessionId);
    this.cache.delete(key);
    dbDelete(key);
  }
}

export const contextUsageCache = new ContextUsageCache();
