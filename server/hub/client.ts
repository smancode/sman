import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
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
    this.debugLog(`HubClient.start() enabled=${enabled}, serverUrl=${serverUrl || '(empty)'}`);
    if (!enabled || !serverUrl) {
      this.debugLog(`Hub NOT starting`);
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

    // Prefer non-VPN, non-virtual NIC IPs: 10.x (enterprise), 192.168.x, 172.16-31.x
    // Skip VPN/tunnel addresses (172.x where x < 16 or > 31), APIPA (169.254.x), virtual adapters
    const candidates: { address: string; priority: number }[] = [];
    const skipNames = /^(tun|tap|veth|vEthernet|docker|br-|vnic|vmware|vbox|hyper-v|wsl)/i;

    for (const [name, interfaces] of Object.entries(nets)) {
      if (skipNames.test(name)) continue;
      for (const net of interfaces || []) {
        if (net.family !== 'IPv4' || net.internal) continue;
        const addr = net.address;
        if (addr.startsWith('169.254.')) continue; // APIPA
        let priority = 0;
        if (addr.startsWith('10.')) priority = 3;           // Enterprise LAN
        else if (addr.startsWith('192.168.')) priority = 2; // Home/office LAN
        else if (/^172\.(1[6-9]|2\d|3[01])\./.test(addr)) priority = 1; // RFC1918 172.16-31
        else priority = 0; // Public IP or other
        candidates.push({ address: addr, priority });
      }
    }

    if (candidates.length > 0) {
      candidates.sort((a, b) => b.priority - a.priority);
      ip = candidates[0].address;
    }
    return `${hostname}@${ip}`;
  }

  private debugLog(msg: string): void {
    try {
      const logPath = path.join(os.homedir(), '.sman', 'hub-debug.log');
      const line = `[${new Date().toISOString()}] ${msg}\n`;
      fs.appendFileSync(logPath, line);
    } catch {}
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
      this.debugLog(`heartbeat → ${url} (clientId=${clientId}, version=${payload.version})`);
      const controller = new AbortController();
      const tid = setTimeout(() => controller.abort(), TIMEOUT_MS);

      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildEncryptedRequest(payload)),
        signal: controller.signal,
      });

      clearTimeout(tid);
      const body = await res.text();
      if (!res.ok) {
        this.debugLog(`heartbeat FAILED: ${res.status} ${body}`);
      } else {
        this.debugLog(`heartbeat OK: ${res.status} ${body}`);
      }
    } catch (err) {
      this.debugLog(`heartbeat ERROR: ${err instanceof Error ? err.message : String(err)}`);
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
