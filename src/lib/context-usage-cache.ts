/**
 * Context Usage Cache
 *
 * Persistent per-session context usage stats.
 * Uses localStorage so data survives page reloads.
 */

const STORAGE_KEY = 'sman-context-usage';

function readStorage(): Record<string, { inputTokens: number; outputTokens: number }> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

function writeStorage(data: Record<string, { inputTokens: number; outputTokens: number }>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
  } catch {
    // Ignore storage errors
  }
}

export const contextUsageCache = {
  get(sessionId: string): { inputTokens: number; outputTokens: number } | null {
    return readStorage()[sessionId] ?? null;
  },

  set(sessionId: string, usage: { inputTokens: number; outputTokens: number }): void {
    const data = readStorage();
    data[sessionId] = usage;
    writeStorage(data);
  },

  delete(sessionId: string): void {
    const data = readStorage();
    delete data[sessionId];
    writeStorage(data);
  },
};
