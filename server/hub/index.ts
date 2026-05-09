import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import os from 'node:os';
import type { SessionStore } from '../session-store.js';
import type { SettingsManager } from '../settings-manager.js';
import type { BroadcastStore } from '../broadcast-store.js';
import { HubClient } from './client.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('Hub');
let hubClient: HubClient | null = null;

function getVersion(): string {
  const __dirname = path.dirname(fileURLToPath(import.meta.url));
  // Probe upward to find package.json with a version field.
  // Source: server/hub/ → ../../package.json (project root)
  // Compiled: dist/server/server/hub/ → ../../../../package.json (project root)
  // Electron packaged: app.asar/server/hub/ → ../../package.json
  for (const rel of ['../../package.json', '../../../package.json', '../../../../package.json']) {
    try {
      const pkgPath = path.resolve(__dirname, rel);
      const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8'));
      if (pkg.version) return pkg.version;
    } catch { /* continue */ }
  }
  return 'unknown';
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

export function getHubStatus(): Record<string, unknown> {
  return {
    initialized: hubClient !== null,
    SMAN_HUB_URL: process.env.SMAN_HUB_URL || '(not set)',
    SMAN_PSK: process.env.SMAN_PSK ? '(set, ' + process.env.SMAN_PSK.length + ' chars)' : '(not set, will use bundled hub.key)',
  };
}
