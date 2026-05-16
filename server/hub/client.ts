import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import type { SessionStore } from '../session-store.js';
import type { BroadcastStore } from '../broadcast-store.js';
import type { BroadcastMessage, ReportPayload, BroadcastQueryPayload } from './types.js';
import { buildEncryptedRequest, decrypt } from './crypto.js';
import { createLogger } from '../utils/logger.js';
import { getLocalIp } from '../utils/network.js';

const log = createLogger('Hub');
const TIMEOUT_MS = 5000;
const REPORT_INTERVAL_MS = 15 * 60 * 1000;

interface HubDeps {
  getServerUrl: () => string;
  getEnabled: () => boolean;
  getVersion: () => string;
  getPort: () => number;
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

  private async getClientId(): Promise<string> {
    const hostname = os.hostname();
    const ip = await getLocalIp();
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
      const clientId = await this.getClientId();
      const payload: ReportPayload = {
        clientId,
        version: this.deps.getVersion(),
        hostname: os.hostname(),
        ip: clientId.split('@')[1],
        port: this.deps.getPort(),
        reportTime: new Date().toISOString(),
        activeSessions: this.deps.sessionStore.getActiveSessionCount(),
        workspaces: this.deps.sessionStore.getActiveWorkspaces(),
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
        // Handle skill-auto-updater commands from server
        try {
          const resp = JSON.parse(body);
          if (resp.commands && Array.isArray(resp.commands) && resp.commands.length > 0) {
            this.triggerSkillAutoUpdater(resp.commands);
          }
        } catch { /* ignore parse errors */ }
      }
    } catch (err) {
      this.debugLog(`heartbeat ERROR: ${err instanceof Error ? err.message : String(err)}`);
    }
  }

  /**
   * Trigger skill-auto-updater for each workspace via local MCP API.
   * Fire-and-forget: async, don't wait for completion.
   */
  private triggerSkillAutoUpdater(workspaces: string[]): void {
    for (const workspace of workspaces) {
      log.info(`[SkillScheduler] Triggering skill-auto-updater for ${workspace}`);
      this.invokeMcpTool(workspace, 'skill', 'skill-auto-updater').catch(err => {
        log.error(`[SkillScheduler] Failed to trigger for ${workspace}: ${err instanceof Error ? err.message : err}`);
      });
    }
  }

  /** Fire-and-forget: trigger a local MCP skill asynchronously */
  private async invokeMcpTool(workspace: string, _toolType: string, toolId: string, parameters?: string): Promise<void> {
    const port = this.deps.getPort();
    const triggerUrl = `http://127.0.0.1:${port}/api/mcp/tools/trigger`;

    // Get auth token
    const tokenRes = await fetch(`http://127.0.0.1:${port}/api/auth/token`);
    if (!tokenRes.ok) throw new Error(`Failed to get auth token: ${tokenRes.status}`);
    const { token } = await tokenRes.json() as { token: string };

    const reqBody: Record<string, string> = { workspace, toolId };
    if (parameters) reqBody.parameters = parameters;

    const res = await fetch(triggerUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify(reqBody),
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(`MCP trigger failed: ${res.status} ${text}`);
    }
    log.info(`[SkillScheduler] MCP trigger accepted for ${toolId} @ ${workspace}`);
  }

  private async fetchBroadcasts(): Promise<void> {
    try {
      const clientId = await this.getClientId();
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
