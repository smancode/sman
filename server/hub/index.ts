import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { SessionStore } from '../session-store.js';
import type { SettingsManager } from '../settings-manager.js';
import type { BroadcastStore } from '../broadcast-store.js';
import { HubClient } from './client.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('Hub');
let hubClient: HubClient | null = null;

function getVersion(): string {
  try {
    const __dirname = path.dirname(fileURLToPath(import.meta.url));
    const pkgPath = path.resolve(__dirname, '../../package.json');
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8'));
    return pkg.version || 'unknown';
  } catch {
    return 'unknown';
  }
}

function getServerUrl(sm: SettingsManager): string {
  return process.env.SMAN_HUB_URL || sm.getConfig().hub?.serverUrl || '';
}

function isHubEnabled(sm: SettingsManager): boolean {
  // SMAN_HUB_URL injected (enterprise build) = always enabled
  if (process.env.SMAN_HUB_URL) return true;
  return sm.getConfig().hub?.enabled ?? false;
}

export function initHub(
  settingsManager: SettingsManager,
  sessionStore: SessionStore,
  broadcastStore: BroadcastStore,
): void {
  const serverUrl = getServerUrl(settingsManager);
  const enabled = isHubEnabled(settingsManager);
  log.info(`initHub: enabled=${enabled}, serverUrl=${serverUrl || '(empty)'}`);

  if (!enabled || !serverUrl) {
    log.info(`Hub disabled (enabled=${enabled}, serverUrl='${serverUrl}')`);
    return;
  }

  hubClient = new HubClient({
    getServerUrl: () => getServerUrl(settingsManager),
    getEnabled: () => isHubEnabled(settingsManager),
    getVersion,
    sessionStore,
    broadcastStore,
  });

  hubClient.start();
}

export function stopHub(): void {
  hubClient?.stop();
  hubClient = null;
}

export function getHubClient(): HubClient | null {
  return hubClient;
}
