import WebSocket from 'ws';
import type { SessionStore } from '../session-store.js';
import type { SettingsManager } from '../settings-manager.js';
import { buildEncryptedRequest } from './crypto.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('HubWS');

const RECONNECT_BASE_MS = 1000;
const RECONNECT_MAX_MS = 30_000;
const HEARTBEAT_INTERVAL_MS = 30_000;

interface HubWsDeps {
  getServerUrl: () => string;
  getPsk: () => string;
  getClientId: () => string;
  sessionStore: SessionStore;
  settingsManager: SettingsManager;
  onMessage?: (msg: HubWsInbound) => void;
}

export type HubWsInbound = {
  type: string;
  roomId?: string;
  agentId?: string;
  task?: unknown;
  agent?: unknown;
  room?: unknown;
  members?: unknown[];
  agents?: unknown[];
  tasks?: unknown[];
  events?: unknown[];
  reason?: string;
  [key: string]: unknown;
};

export class HubWsClient {
  private deps: HubWsDeps;
  private ws: WebSocket | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectDelay = RECONNECT_BASE_MS;
  private connected = false;
  private registeredAgents = new Map<string, {
    roomId: string;
    workspace: string;
    capabilities: { skills: string[]; techStack: string[]; projectType: string; summary?: string; description?: string };
  }>();

  constructor(deps: HubWsDeps) {
    this.deps = deps;
  }

  start(): void {
    this.connect();
  }

  stop(): void {
    this.cleanup();
    for (const [agentId] of this.registeredAgents) {
      this.send({ type: 'agent.deregister', agentId });
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }

  isConnected(): boolean {
    return this.connected;
  }

  send(msg: Record<string, unknown>): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }

  registerAgent(agentId: string, roomId: string, workspace: string, capabilities: { skills: string[]; techStack: string[]; projectType: string; summary?: string; description?: string }): void {
    this.registeredAgents.set(agentId, { roomId, workspace, capabilities });
    this.send({
      type: 'agent.register',
      agentId,
      roomId,
      workspace,
      capabilities,
    });
  }

  deregisterAgent(agentId: string): void {
    this.registeredAgents.delete(agentId);
    this.send({ type: 'agent.deregister', agentId });
  }

  private connect(): void {
    const serverUrl = this.deps.getServerUrl();
    if (!serverUrl) return;

    const wsUrl = serverUrl.replace(/^http/, 'ws') + '/ws';
    log.info(`Connecting to Hub WS: ${wsUrl}`);

    try {
      this.ws = new WebSocket(wsUrl);
    } catch (err) {
      log.error(`WS connect failed: ${(err as Error).message}`);
      this.scheduleReconnect();
      return;
    }

    this.ws.on('open', () => {
      log.info('Hub WS connected, authenticating...');
      this.authenticate();
    });

    this.ws.on('message', (raw) => {
      try {
        const msg = JSON.parse(raw.toString()) as HubWsInbound;

        if (msg.type === 'auth.ok') {
          this.connected = true;
          this.reconnectDelay = RECONNECT_BASE_MS;
          log.info(`Hub WS authenticated as ${msg.clientId || 'unknown'}`);
          this.startHeartbeat();
          this.reRegisterAgents();
          // Request room list to auto-join
          this.send({ type: 'room.list' });
          this.deps.onMessage?.(msg);
        } else if (msg.type === 'auth.fail') {
          log.error(`Hub WS auth failed: ${msg.reason}`);
          this.ws?.close();
          return;
        } else {
          this.deps.onMessage?.(msg);
        }
      } catch (err) {
        log.error(`WS message parse error: ${(err as Error).message}`);
      }
    });

    this.ws.on('close', (code, reason) => {
      log.info(`Hub WS closed: ${code} ${reason}`);
      this.connected = false;
      this.cleanup();
      this.scheduleReconnect();
    });

    this.ws.on('error', (err) => {
      log.error(`Hub WS error: ${err.message}`);
    });
  }

  private authenticate(): void {
    const psk = this.deps.getPsk();
    const clientId = this.deps.getClientId();
    const enc = buildEncryptedRequest({ clientId });
    this.send({
      type: 'auth.psk',
      payload: enc.payload,
      timestamp: enc.timestamp,
      pskVersion: enc.pskVersion,
    });
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      for (const [agentId] of this.registeredAgents) {
        this.send({ type: 'agent.heartbeat', agentId, status: 'online' });
      }
    }, HEARTBEAT_INTERVAL_MS);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private reRegisterAgents(): void {
    for (const [agentId, info] of this.registeredAgents) {
      this.send({
        type: 'agent.register',
        agentId,
        roomId: info.roomId,
        workspace: info.workspace,
        capabilities: info.capabilities,
      });
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, this.reconnectDelay);
    this.reconnectDelay = Math.min(this.reconnectDelay * 2, RECONNECT_MAX_MS);
  }

  private cleanup(): void {
    this.stopHeartbeat();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}
