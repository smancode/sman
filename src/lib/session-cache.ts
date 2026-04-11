/**
 * Session Message Cache
 *
 * Two-layer cache: in-memory Map (sync) + IndexedDB (persistent).
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

/** Fire-and-forget write to IndexedDB */
async function dbPut(sessionId: string, messages: unknown[]): Promise<void> {
  try {
    const db = await openDB();
    const tx = db.transaction(STORE_NAME, 'readwrite');
    tx.objectStore(STORE_NAME).put(messages, sessionId);
    tx.oncomplete = () => db.close();
    tx.onerror = () => db.close();
  } catch (e) {
    console.warn('[SessionCache] IndexedDB write failed:', e);
  }
}

/** Fire-and-forget delete from IndexedDB */
async function dbDelete(sessionId: string): Promise<void> {
  try {
    const db = await openDB();
    const tx = db.transaction(STORE_NAME, 'readwrite');
    tx.objectStore(STORE_NAME).delete(sessionId);
    tx.oncomplete = () => db.close();
    tx.onerror = () => db.close();
  } catch (e) {
    console.warn('[SessionCache] IndexedDB delete failed:', e);
  }
}

class SessionCache {
  private cache = new Map<string, unknown[]>();
  private dbReady: Promise<void> | null = null;

  /** Load all cached sessions from IndexedDB into memory (call once on startup) */
  async loadAll(): Promise<void> {
    if (this.dbReady) return this.dbReady;
    this.dbReady = (async () => {
      try {
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
        for (let i = 0; i < keys.length; i++) {
          // Only load into memory if not already present (memory is fresher)
          if (!this.cache.has(keys[i])) {
            this.cache.set(keys[i], values[i]);
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
    return this.cache.get(sessionId) ?? null;
  }

  /** Async get — falls back to IndexedDB if memory miss */
  async getAsync(sessionId: string): Promise<unknown[] | null> {
    const mem = this.cache.get(sessionId);
    if (mem) return mem;
    try {
      const db = await openDB();
      const tx = db.transaction(STORE_NAME, 'readonly');
      const req = tx.objectStore(STORE_NAME).get(sessionId);
      const result = await new Promise<unknown[] | null>((resolve) => {
        req.onsuccess = () => resolve(req.result ?? null);
        req.onerror = () => resolve(null);
      });
      db.close();
      if (result) {
        this.cache.set(sessionId, result);
      }
      return result;
    } catch {
      return null;
    }
  }

  set(sessionId: string, messages: unknown[]): void {
    this.cache.set(sessionId, [...messages]);
    dbPut(sessionId, messages);
  }

  has(sessionId: string): boolean {
    return this.cache.has(sessionId);
  }

  invalidate(sessionId: string): void {
    this.cache.delete(sessionId);
    dbDelete(sessionId);
  }

  clear(): void {
    this.cache.clear();
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
