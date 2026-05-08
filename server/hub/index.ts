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

  if (!hub?.enabled || !hub?.serverUrl) return;

  hubClient = new HubClient({
    getServerUrl: () => settingsManager.getConfig().hub?.serverUrl || '',
    getEnabled: () => settingsManager.getConfig().hub?.enabled ?? false,
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
