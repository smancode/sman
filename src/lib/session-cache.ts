/**
 * Session Message Cache
 *
 * In-memory cache for chat messages keyed by session ID.
 * Enables instant session switching by serving cached messages
 * immediately while syncing with the backend in the background.
 *
 * Uses unknown[] to avoid tight coupling with chat store's Message type.
 */

interface CacheEntry {
  messages: unknown[];
  syncedAt: number;
}

class SessionCache {
  private cache = new Map<string, CacheEntry>();

  get(sessionId: string): unknown[] | null {
    return this.cache.get(sessionId)?.messages ?? null;
  }

  set(sessionId: string, messages: unknown[]): void {
    this.cache.set(sessionId, { messages: [...messages], syncedAt: Date.now() });
  }

  has(sessionId: string): boolean {
    return this.cache.has(sessionId);
  }

  invalidate(sessionId: string): void {
    this.cache.delete(sessionId);
  }

  /** Milliseconds since last sync, or null if not cached */
  getSyncAge(sessionId: string): number | null {
    const entry = this.cache.get(sessionId);
    if (!entry) return null;
    return Date.now() - entry.syncedAt;
  }

  clear(): void {
    this.cache.clear();
  }
}

export const sessionCache = new SessionCache();
