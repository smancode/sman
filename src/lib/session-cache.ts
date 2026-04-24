/**
 * Session Message Cache
 *
 * Two-layer cache: in-memory Map (sync) + IndexedDB (persistent).
 * Keys are namespaced by backend URL so switching backends doesn't mix data.
 *
 * - set() writes to both layers (DB write is async, non-blocking).
 * - get() reads from memory (sync, used by existing callers).
 * - getAsync() reads from memory then falls back to IndexedDB.
 * - loadAll() called on startup to warm the memory cache from IndexedDB.
 */

const DB_NAME = 'sman-cache';
const DB_VERSION = 1;
const STORE_NAME = 'messages';

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
async function dbPut(key: string, messages: unknown[]): Promise<void> {
  try {
    const db = await openDB();
    const tx = db.transaction(STORE_NAME, 'readwrite');
    tx.objectStore(STORE_NAME).put(messages, key);
    tx.oncomplete = () => db.close();
    tx.onerror = () => db.close();
  } catch (e) {
    console.warn('[SessionCache] IndexedDB write failed:', e);
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
    console.warn('[SessionCache] IndexedDB delete failed:', e);
  }
}

class SessionCache {
  private cache = new Map<string, unknown[]>();
  private accessOrder: string[] = [];
  private dbReady: Promise<void> | null = null;
  private loadedBackend: string | null = null;
  private static MAX_ENTRIES = 20;

  /** Get namespaced key for current backend */
  private nk(sessionId: string): string {
    return `${getBackendKey()}:${sessionId}`;
  }

  /** Update access order — move key to end (most recently used), evict oldest if over limit */
  private touch(key: string): void {
    const idx = this.accessOrder.indexOf(key);
    if (idx >= 0) this.accessOrder.splice(idx, 1);
    this.accessOrder.push(key);
    while (this.accessOrder.length > SessionCache.MAX_ENTRIES) {
      const oldest = this.accessOrder.shift()!;
      this.cache.delete(oldest);
      // Don't delete from IndexedDB — it's persistent storage, getAsync can restore it
    }
  }

  /** Load all cached sessions for current backend from IndexedDB into memory (call once on startup) */
  async loadAll(): Promise<void> {
    if (this.dbReady) return this.dbReady;
    this.dbReady = (async () => {
      try {
        const backend = getBackendKey();
        this.loadedBackend = backend;
        const db = await openDB();
        const tx = db.transaction(STORE_NAME, 'readonly');
        const store = tx.objectStore(STORE_NAME);
        const req = store.getAll();
        const keysReq = store.getAllKeys();
        await new Promise<void>((resolve, reject) => {
          tx.oncomplete = () => resolve();
          tx.onerror = () => reject(tx.error);
        });
        const keys = keysReq.result as string[];
        const values = req.result as unknown[][];
        const prefix = `${backend}:`;
        for (let i = 0; i < keys.length; i++) {
          // Only load entries for current backend, and only if not already in memory
          if (keys[i].startsWith(prefix) && !this.cache.has(keys[i])) {
            this.cache.set(keys[i], values[i]);
            this.accessOrder.push(keys[i]);
          }
        }
        db.close();
      } catch (e) {
        console.warn('[SessionCache] IndexedDB loadAll failed:', e);
      }
    })();
    return this.dbReady;
  }

  get(sessionId: string): unknown[] | null {
    const key = this.nk(sessionId);
    const result = this.cache.get(key) ?? null;
    if (result) this.touch(key);
    return result;
  }

  /** Async get — falls back to IndexedDB if memory miss */
  async getAsync(sessionId: string): Promise<unknown[] | null> {
    const key = this.nk(sessionId);
    const mem = this.cache.get(key);
    if (mem) {
      this.touch(key);
      return mem;
    }
    try {
      const db = await openDB();
      const tx = db.transaction(STORE_NAME, 'readonly');
      const req = tx.objectStore(STORE_NAME).get(key);
      const result = await new Promise<unknown[] | null>((resolve) => {
        req.onsuccess = () => resolve(req.result ?? null);
        req.onerror = () => resolve(null);
      });
      db.close();
      if (result) {
        this.cache.set(key, result);
        this.touch(key);
      }
      return result;
    } catch {
      return null;
    }
  }

  set(sessionId: string, messages: unknown[]): void {
    const key = this.nk(sessionId);
    this.cache.set(key, messages);
    this.touch(key);
    dbPut(key, messages);
  }

  has(sessionId: string): boolean {
    return this.cache.has(this.nk(sessionId));
  }

  invalidate(sessionId: string): void {
    const key = this.nk(sessionId);
    this.cache.delete(key);
    const idx = this.accessOrder.indexOf(key);
    if (idx >= 0) this.accessOrder.splice(idx, 1);
    dbDelete(key);
  }

  clear(): void {
    this.cache.clear();
    this.accessOrder = [];
    // Clear IndexedDB
    (async () => {
      try {
        const db = await openDB();
        const tx = db.transaction(STORE_NAME, 'readwrite');
        tx.objectStore(STORE_NAME).clear();
        tx.oncomplete = () => db.close();
        tx.onerror = () => db.close();
      } catch (e) {
        console.warn('[SessionCache] IndexedDB clear failed:', e);
      }
    })();
  }
}

export const sessionCache = new SessionCache();
