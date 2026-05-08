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
    if (!this.deps.getEnabled() || !this.deps.getServerUrl()) return;
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

      const url = `${this.deps.getServerUrl()}/api/report`;
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
        log.error(`heartbeat failed: ${res.status}`);
      }
    } catch {
      // silent
    }
  }

  private async fetchBroadcasts(): Promise<void> {
    try {
      const payload: BroadcastQueryPayload = {
        clientId: this.getClientId(),
        since: new Date(0).toISOString(),
      };

      const url = `${this.deps.getServerUrl()}/api/broadcasts`;
      const controller = new AbortController();
      const tid = setTimeout(() => controller.abort(), TIMEOUT_MS);

      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildEncryptedRequest(payload)),
        signal: controller.signal,
      });

      clearTimeout(tid);
      if (!res.ok) return;

      const body = await res.json();
      const data = decrypt(body.payload) as { messages: BroadcastMessage[] };

      const added = this.deps.broadcastStore.mergeBroadcasts(
        data.messages.map(m => ({ id: m.id, title: m.title, body: m.body, createdAt: m.createdAt }))
      );
      if (added > 0) {
        log.info(`Fetched ${added} new broadcast(s)`);
      }
    } catch {
      // silent
    }
  }
}
