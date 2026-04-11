/**
 * Session Message Cache
 *
 * In-memory cache for chat messages and scroll anchor keyed by session ID.
 * Scroll position is stored as the message ID visible at the top of the viewport.
 */

interface CacheEntry {
  messages: unknown[];
  anchorMsgId: string;
}

class SessionCache {
  private cache = new Map<string, CacheEntry>();

  get(sessionId: string): unknown[] | null {
    return this.cache.get(sessionId)?.messages ?? null;
  }

  set(sessionId: string, messages: unknown[]): void {
    const prev = this.cache.get(sessionId);
    this.cache.set(sessionId, { messages: [...messages], anchorMsgId: prev?.anchorMsgId ?? '' });
  }

  setAnchorMsgId(sessionId: string, msgId: string): void {
    const entry = this.cache.get(sessionId);
    if (entry) entry.anchorMsgId = msgId;
  }

  getAnchorMsgId(sessionId: string): string {
    return this.cache.get(sessionId)?.anchorMsgId ?? '';
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
