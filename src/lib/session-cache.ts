/**
 * Session Message Cache
 *
 * In-memory cache for chat messages keyed by session ID.
 */

class SessionCache {
  private cache = new Map<string, unknown[]>();

  get(sessionId: string): unknown[] | null {
    return this.cache.get(sessionId) ?? null;
  }

  set(sessionId: string, messages: unknown[]): void {
    this.cache.set(sessionId, [...messages]);
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
