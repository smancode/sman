// server/stardom/stardom-client.ts
import WebSocket from 'ws';
import { v4 as uuidv4 } from 'uuid';
import os from 'os';
import { createLogger, type Logger } from '../utils/logger.js';
import type { StardomStore } from './stardom-store.js';
import type { LocalAgentIdentity } from './types.js';

interface ClientOptions {
  getAgentDescription: () => string;
  getAgentDomains: () => string[];
  heartbeatIntervalMs?: number;
}

const DEFAULT_HEARTBEAT_MS = 30_000;
const RECONNECT_BASE_DELAY_MS = 1_000;
const RECONNECT_MAX_DELAY_MS = 60_000;

export class StardomClient {
  private log: Logger;
  private store: StardomStore;
  private ws: WebSocket | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectAttempts = 0;
  private stopped = false;
  private options: ClientOptions;

  // 外部消息处理器
  public onMessage: ((msg: { type: string; payload: Record<string, unknown> }) => void) | null = null;

  // Called after successful connection/reconnection
  public onReconnect: (() => void) | null = null;

  constructor(store: StardomStore, options: ClientOptions) {
    this.store = store;
    this.options = options;
    this.log = createLogger('StardomClient');
  }

  async connect(): Promise<void> {
    const identity = this.store.getIdentity();
    if (!identity) {
      this.log.warn('No identity saved, cannot connect');
      return;
    }

    this.stopped = false;
    return this.doConnect(identity);
  }

  private async doConnect(identity: LocalAgentIdentity): Promise<void> {
    return new Promise((resolve, reject) => {
      const url = `ws://${identity.server}`;
      this.log.info(`Connecting to stardom: ${url}`);

      this.ws = new WebSocket(url);
      let settled = false;
      const timeoutId = setTimeout(() => {
        if (settled) return;
        settled = true;
        this.ws?.close();
        reject(new Error('Connection timeout'));
      }, 5_000);

      this.ws.on('open', () => {
        clearTimeout(timeoutId);
        if (settled) return;
        settled = true;
        this.log.info('Connected to stardom server');
        this.reconnectAttempts = 0;

        // 发送注册消息
        const domains = this.options.getAgentDomains();
        this.send({
          id: uuidv4(),
          type: 'agent.register',
          payload: {
            agentId: identity.agentId,
            username: identity.username,
            hostname: os.hostname(),
            name: identity.name,
            description: this.options.getAgentDescription(),
            ...(domains.length > 0 ? { domains } : {}),
          },
        });

        // 启动心跳
        this.startHeartbeat(identity.agentId);

        // Notify bridge to sync active tasks
        if (this.onReconnect) {
          this.onReconnect();
        }

        resolve();
      });

      this.ws.on('message', (data) => {
        try {
          const msg = JSON.parse(data.toString());
          if (msg.type === 'ack') return; // 忽略 ack

          // 调用外部处理器
          if (this.onMessage) {
            this.onMessage({ type: msg.type, payload: msg.payload ?? {} });
          }
        } catch (err) {
          this.log.error('Failed to parse server message', { error: String(err) });
        }
      });

      this.ws.on('close', () => {
        this.log.info('Disconnected from stardom');
        this.stopHeartbeat();
        if (!this.stopped) this.scheduleReconnect(identity);
      });

      this.ws.on('error', (err) => {
        clearTimeout(timeoutId);
        if (settled) return;
        settled = true;
        this.log.error('WebSocket error', { error: err.message });
        reject(err);
      });
    });
  }

  send(msg: { id: string; type: string; payload: Record<string, unknown> }): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }

  /** Send task.sync for a specific task to the stardom server */
  sendTaskSync(taskId: string): void {
    this.send({
      id: uuidv4(),
      type: 'task.sync',
      payload: { taskId },
    });
  }

  disconnect(): void {
    this.stopped = true;
    this.stopHeartbeat();

    const identity = this.store.getIdentity();
    if (identity && this.ws?.readyState === WebSocket.OPEN) {
      this.send({
        id: uuidv4(),
        type: 'agent.offline',
        payload: { agentId: identity.agentId },
      });
    }

    this.ws?.close();
    this.ws = null;
  }

  private startHeartbeat(agentId: string): void {
    this.stopHeartbeat();
    const interval = this.options.heartbeatIntervalMs ?? DEFAULT_HEARTBEAT_MS;
    this.heartbeatTimer = setInterval(() => {
      this.send({
        id: uuidv4(),
        type: 'agent.heartbeat',
        payload: { agentId, status: 'idle', activeTaskCount: 0 },
      });
    }, interval);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private scheduleReconnect(identity: LocalAgentIdentity): void {
    if (this.stopped) return;
    const delay = Math.min(
      RECONNECT_BASE_DELAY_MS * Math.pow(2, this.reconnectAttempts),
      RECONNECT_MAX_DELAY_MS,
    );
    this.reconnectAttempts++;
    this.log.info(`Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);

    setTimeout(async () => {
      if (!this.stopped) {
        try {
          await this.doConnect(identity);
        } catch {
          // doConnect 内部会再次触发 close → scheduleReconnect
        }
      }
    }, delay);
  }
}
