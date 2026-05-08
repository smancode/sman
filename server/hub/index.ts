import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { SessionStore } from '../session-store.js';
import type { SettingsManager } from '../settings-manager.js';
import type { BroadcastStore } from '../broadcast-store.js';
import { HubClient } from './client.js';

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

export function initHub(
  settingsManager: SettingsManager,
  sessionStore: SessionStore,
  broadcastStore: BroadcastStore,
): void {
  const config = settingsManager.getConfig();
  const hub = config.hub;

  // Priority: SMAN_HUB_URL env > config.hub.serverUrl
  const serverUrl = process.env.SMAN_HUB_URL || hub?.serverUrl || '';
  // Enterprise build: SMAN_HUB_URL injected = auto-enable (PSK has built-in fallback)
  const forceEnabled = !!process.env.SMAN_HUB_URL;

  if ((!hub?.enabled && !forceEnabled) || !serverUrl) return;

  hubClient = new HubClient({
    getServerUrl: () => process.env.SMAN_HUB_URL || settingsManager.getConfig().hub?.serverUrl || '',
    getEnabled: () => {
      if (process.env.SMAN_HUB_URL) return true;
      return settingsManager.getConfig().hub?.enabled ?? false;
    },
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
