import os from 'node:os';
import type { SessionStore } from '../session-store.js';
import type { BroadcastMessage, ReportPayload, BroadcastQueryPayload, AckPayload } from './types.js';
import { buildEncryptedRequest, decrypt } from './crypto.js';

const TIMEOUT_MS = 5000;
const REPORT_INTERVAL_MS = 60 * 60 * 1000;

interface HubDeps {
  getServerUrl: () => string;
  getEnabled: () => boolean;
  getVersion: () => string;
  sessionStore: SessionStore;
  onBroadcast: (messages: BroadcastMessage[]) => void;
}

export class HubClient {
  private deps: HubDeps;
  private timer: ReturnType<typeof setInterval> | null = null;
  private lastBroadcastFetch: string;

  constructor(deps: HubDeps) {
    this.deps = deps;
    this.lastBroadcastFetch = new Date(0).toISOString();
  }

  start(): void {
    if (!this.deps.getEnabled() || !this.deps.getServerUrl()) return;
    this.reportHeartbeat();
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

  private getActiveSessionCount(): number {
    return this.deps.sessionStore.getActiveSessionCount();
  }

  async reportHeartbeat(): Promise<void> {
    try {
      const clientId = this.getClientId();
      const payload: ReportPayload = {
        clientId,
        version: this.deps.getVersion(),
        hostname: os.hostname(),
        ip: clientId.split('@')[1],
        reportTime: new Date().toISOString(),
        activeSessions: this.getActiveSessionCount(),
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
        console.error(`[hub] report failed: ${res.status}`);
      }
    } catch {
      // silent
    }
  }

  async fetchBroadcasts(): Promise<void> {
    try {
      const payload: BroadcastQueryPayload = {
        clientId: this.getClientId(),
        since: this.lastBroadcastFetch,
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
      const data = decrypt(body.payload) as { messages: BroadcastMessage[]; hasMore: boolean };

      if (data.messages.length > 0) {
        this.lastBroadcastFetch = new Date().toISOString();
        this.deps.onBroadcast(data.messages);
      }
    } catch {
      // silent
    }
  }

  async ackBroadcasts(ids: string[]): Promise<void> {
    if (ids.length === 0) return;
    try {
      const payload: AckPayload = {
        clientId: this.getClientId(),
        broadcastIds: ids,
      };

      const url = `${this.deps.getServerUrl()}/api/ack`;
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
        console.error(`[hub] ack failed: ${res.status}`);
      }
    } catch {
      // silent
    }
  }
}
