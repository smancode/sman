/**
 * Session Message Cache
 *
 * In-memory cache for chat messages and scroll position keyed by session ID.
 */

interface CacheEntry {
  messages: unknown[];
  scrollTop: number;
}

class SessionCache {
  private cache = new Map<string, CacheEntry>();

  get(sessionId: string): unknown[] | null {
    return this.cache.get(sessionId)?.messages ?? null;
  }

  getScrollTop(sessionId: string): number {
    return this.cache.get(sessionId)?.scrollTop ?? -1;
  }

  set(sessionId: string, messages: unknown[]): void {
    const prev = this.cache.get(sessionId);
    this.cache.set(sessionId, { messages: [...messages], scrollTop: prev?.scrollTop ?? -1 });
  }

  setScrollTop(sessionId: string, scrollTop: number): void {
    const entry = this.cache.get(sessionId);
    if (entry) entry.scrollTop = scrollTop;
  }

  has(sessionId: string): boolean {
    return this.cache.has(sessionId);
  }

  invalidate(sessionId: string): void {
    this.cache.delete(sessionId);
  }

  clear(): void {
    this.cache.clear();
  }
}

export const sessionCache = new SessionCache();
