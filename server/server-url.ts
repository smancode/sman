import type { SettingsManager } from './settings-manager.js';
import { createLogger } from './utils/logger.js';

const log = createLogger('ServerUrl');

const PROBE_TIMEOUT_MS = 3000;

/** External server — hardcoded. */
const DEFAULT_EXTERNAL_URL = 'https://www.smancode.com/server';

/** Cached base URL after successful probe or config read. */
let cachedBaseUrl: string | null = null;

/**
 * Probe a server URL by HEAD /api/health.
 * Returns true if the server responds with 2xx within timeout.
 */
async function probeUrl(url: string): Promise<boolean> {
  try {
    const controller = new AbortController();
    const tid = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
    const res = await fetch(`${url}/api/health`, {
      method: 'HEAD',
      signal: controller.signal,
    });
    clearTimeout(tid);
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Try probing a list of candidate URLs sequentially.
 * Returns the first URL that responds, or empty string if none.
 */
async function probeCandidates(urls: string[]): Promise<string> {
  for (const url of urls) {
    if (!url) continue;
    const clean = url.replace(/\/+$/, '');
    log.info(`Probing ${clean}...`);
    if (await probeUrl(clean)) {
      log.info(`Probed OK: ${clean}`);
      return clean;
    }
    log.info(`Probed FAIL: ${clean}`);
  }
  return '';
}

/**
 * Read serverBaseUrl from config (with backward compat).
 * Priority: hub.serverBaseUrl > hub.serverUrl > SMAN_HUB_URL env
 */
function readFromConfig(sm: SettingsManager): string {
  const hub = sm.getConfig().hub;
  if (hub?.serverBaseUrl) return hub.serverBaseUrl;
  if (hub?.serverUrl) return hub.serverUrl;
  if (process.env.SMAN_HUB_URL) return process.env.SMAN_HUB_URL;
  return '';
}

/**
 * Build probe candidate list.
 * 1. External (hardcoded smancode.com)
 * 2. Internal fallback: SMAN_FALLBACK_URL env > config hub.fallbackUrl
 */
function buildCandidates(): string[] {
  const candidates: string[] = [DEFAULT_EXTERNAL_URL];
  const fallback = process.env.SMAN_FALLBACK_URL || '';
  if (fallback && fallback !== DEFAULT_EXTERNAL_URL) {
    candidates.push(fallback);
  }
  return candidates;
}

/**
 * The ONLY place that resolves the server base URL.
 *
 * 1. If cached (from prior probe this session), return it.
 * 2. If config has serverBaseUrl, cache it and return.
 * 3. Build candidate list: config value + fallbackUrl + SMAN_HUB_URL env.
 * 4. Probe each candidate, first success wins.
 * 5. Write winner to config.serverBaseUrl and cache it.
 * 6. If all fail, return '' (caller should handle gracefully).
 */
export async function resolveServerBaseUrl(sm: SettingsManager): Promise<string> {
  if (cachedBaseUrl) return cachedBaseUrl;

  const fromConfig = readFromConfig(sm);
  if (fromConfig) {
    cachedBaseUrl = fromConfig;
    return cachedBaseUrl;
  }

  // No config value — probe candidates
  const hub = sm.getConfig().hub;
  const candidates = buildCandidates();

  if (candidates.length === 0) {
    log.info('No server URL configured and no candidates to probe');
    return '';
  }

  const winner = await probeCandidates(candidates);
  if (winner) {
    // Persist to config
    sm.updateConfig({
      hub: {
        serverBaseUrl: winner,
        serverUrl: winner,
        updateUrl: hub?.updateUrl ?? '',
        fallbackUrl: hub?.fallbackUrl ?? '',
        enabled: true,
        adminToken: hub?.adminToken ?? '',
      },
    });
    cachedBaseUrl = winner;
    log.info(`serverBaseUrl resolved and saved: ${winner}`);
  } else {
    log.info('All server URL candidates unreachable');
  }
  return winner;
}

/**
 * Sync getter — returns cached base URL without probing.
 * Use this for hot-path reads (heartbeat, WS, proxy).
 * Returns empty string if not yet resolved.
 */
export function getServerBaseUrl(sm: SettingsManager): string {
  if (cachedBaseUrl) return cachedBaseUrl;
  return readFromConfig(sm);
}

/**
 * Re-probe if serverBaseUrl is empty.
 * Call this at key moments: settings page, hub entry, stardom entry.
 * If already resolved (config or cache), returns immediately.
 */
export async function ensureServerBaseUrl(sm: SettingsManager): Promise<string> {
  if (getServerBaseUrl(sm)) return getServerBaseUrl(sm);
  return resolveServerBaseUrl(sm);
}

/**
 * Force clear cache (e.g. after config change from UI).
 * Next call to resolveServerBaseUrl will re-probe.
 */
export function invalidateServerBaseUrlCache(): void {
  cachedBaseUrl = null;
}
