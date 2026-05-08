import os from 'node:os';
import type { SessionStore } from '../session-store.js';
import type { BroadcastStore } from '../broadcast-store.js';
import type { BroadcastMessage, ReportPayload, BroadcastQueryPayload } from './types.js';
import { buildEncryptedRequest, decrypt } from './crypto.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('Hub');
const TIMEOUT_MS = 5000;
const REPORT_INTERVAL_MS = 60 * 60 * 1000;

interface HubDeps {
  getServerUrl: () => string;
  getEnabled: () => boolean;
  getVersion: () => string;
  sessionStore: SessionStore;
  broadcastStore: BroadcastStore;
}

export class HubClient {
  private deps: HubDeps;
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor(deps: HubDeps) {
    this.deps = deps;
  }

  start(): void {
    const enabled = this.deps.getEnabled();
    const serverUrl = this.deps.getServerUrl();
    log.info(`start() called: enabled=${enabled}, serverUrl=${serverUrl || '(empty)'}`);
    if (!enabled || !serverUrl) {
      log.warn(`Hub not starting: enabled=${enabled}, serverUrl='${serverUrl || ''}'`);
      return;
    }
    this.reportHeartbeat();
    this.fetchBroadcasts();
    this.timer = setInterval(() => {
      this.reportHeartbeat();
      this.fetchBroadcasts();
    }, REPORT_INTERVAL_MS);
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  private getClientId(): string {
    const hostname = os.hostname();
    const nets = os.networkInterfaces();
    let ip = '127.0.0.1';
    for (const name of Object.keys(nets)) {
      for (const net of nets[name] || []) {
        if (net.family === 'IPv4' && !net.internal) {
          ip = net.address;
          break;
        }
      }
    }
    return `${hostname}@${ip}`;
  }

  private async reportHeartbeat(): Promise<void> {
    try {
      const clientId = this.getClientId();
      const payload: ReportPayload = {
        clientId,
        version: this.deps.getVersion(),
        hostname: os.hostname(),
        ip: clientId.split('@')[1],
        reportTime: new Date().toISOString(),
        activeSessions: this.deps.sessionStore.getActiveSessionCount(),
      };

      const serverUrl = this.deps.getServerUrl();
      const url = `${serverUrl}/api/report`;
      log.info(`heartbeat → ${url} (clientId=${clientId}, version=${payload.version})`);
      const controller = new AbortController();
      const tid = setTimeout(() => controller.abort(), TIMEOUT_MS);

      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildEncryptedRequest(payload)),
        signal: controller.signal,
      });

      clearTimeout(tid);
      if (!res.ok) {
        log.error(`heartbeat failed: ${res.status} ${await res.text().catch(() => '')}`);
      } else {
        log.info(`heartbeat OK: ${res.status}`);
      }
    } catch (err) {
      log.error(`heartbeat error: ${err instanceof Error ? err.message : err}`);
    }
  }

  private async fetchBroadcasts(): Promise<void> {
    try {
      const clientId = this.getClientId();
      const payload: BroadcastQueryPayload = {
        clientId,
        since: new Date(0).toISOString(),
      };

      const serverUrl = this.deps.getServerUrl();
      const url = `${serverUrl}/api/broadcasts`;
      log.info(`fetchBroadcasts → ${url} (clientId=${clientId})`);
      const controller = new AbortController();
      const tid = setTimeout(() => controller.abort(), TIMEOUT_MS);

      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildEncryptedRequest(payload)),
        signal: controller.signal,
      });

      clearTimeout(tid);
      if (!res.ok) {
        log.error(`fetchBroadcasts failed: ${res.status}`);
        return;
      }

      const body = await res.json();
      const data = decrypt(body.payload) as { messages: BroadcastMessage[] };
      log.info(`fetchBroadcasts got ${data.messages.length} message(s)`);

      const added = this.deps.broadcastStore.mergeBroadcasts(
        data.messages.map(m => ({ id: m.id, title: m.title, body: m.body, createdAt: m.createdAt }))
      );
      if (added > 0) {
        log.info(`Stored ${added} new broadcast(s)`);
      }
    } catch (err) {
      log.error(`fetchBroadcasts error: ${err instanceof Error ? err.message : err}`);
    }
  }
}
