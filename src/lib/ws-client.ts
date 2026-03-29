/**
 * WebSocket Client
 * Simple WebSocket wrapper with auto-reconnect and typed event handling.
 */
type EventHandler = (...args: unknown[]) => void;

interface WsClientOptions {
  /** Server port (default: 5880) */
  port?: number;
  /** Full WebSocket URL (overrides port-based URL construction) */
  url?: string;
  /** Auto-reconnect on disconnect (default: true) */
  autoReconnect?: boolean;
  /** Base reconnect delay in ms (default: 1000) */
  reconnectDelay?: number;
  /** Max reconnect delay in ms (default: 30000) */
  maxReconnectDelay?: number;
  /** Auth token for server authentication */
  token?: string;
}

export class WsClient {
  private ws: WebSocket | null = null;
  private handlers = new Map<string, Set<EventHandler>>();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private currentDelay: number;
  private _closed = false;
  private _token: string;

  private readonly port: number;
  private readonly urlOverride: string;
  private readonly autoReconnect: boolean;
  private readonly reconnectDelay: number;
  private readonly maxReconnectDelay: number;

  constructor(options: WsClientOptions = {}) {
    this.port = options.port ?? 5880;
    this.urlOverride = options.url ?? '';
    this.autoReconnect = options.autoReconnect ?? true;
    this.reconnectDelay = options.reconnectDelay ?? 1000;
    this.maxReconnectDelay = options.maxReconnectDelay ?? 30000;
    this.currentDelay = this.reconnectDelay;
    this._token = options.token ?? '';
  }

  /** Set or update the auth token (used before connect or reconnect) */
  set token(value: string) {
    this._token = value;
  }

  get connected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  connect(): void {
    // Already connected or connecting
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    this._closed = false;
    if (this.ws) {
      this.ws.close();
    }

    let wsUrl: string;
    if (this.urlOverride) {
      wsUrl = this.urlOverride;
    } else {
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const host = window.location.hostname;
      wsUrl = `${protocol}//${host}:${this.port}/ws`;
    }
    this.ws = new WebSocket(wsUrl);

    this.ws.onopen = () => {
      this.currentDelay = this.reconnectDelay;
      // Send auth message immediately after connection
      if (this._token) {
        this.ws!.send(JSON.stringify({ type: 'auth.verify', token: this._token }));
      }
    };

    this.ws.onmessage = (event: MessageEvent) => {
      try {
        const msg = JSON.parse(String(event.data));
        if (msg.type === 'auth.verified') {
          this.emit('connected');
          return;
        }
        if (msg.type === 'auth.failed') {
          this.emit('authFailed', msg);
          this._closed = true;
          this.ws?.close();
          return;
        }
        if (msg.type) {
          this.emit(msg.type, msg);
        }
        this.emit('message', msg);
      } catch {
        // Ignore non-JSON messages
      }
    };

    this.ws.onclose = () => {
      this.emit('disconnected');
      if (this.autoReconnect && !this._closed) {
        this.scheduleReconnect();
      }
    };

    this.ws.onerror = () => {
      // onerror fires before onclose; errors are surfaced via onclose
    };
  }

  disconnect(): void {
    this._closed = true;
    this.clearReconnectTimer();
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }

  send(data: object): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error('WebSocket is not connected');
    }
    this.ws.send(JSON.stringify(data));
  }

  on(event: string, handler: EventHandler): void {
    if (!this.handlers.has(event)) {
      this.handlers.set(event, new Set());
    }
    this.handlers.get(event)!.add(handler);
  }

  off(event: string, handler: EventHandler): void {
    this.handlers.get(event)?.delete(handler);
  }

  private emit(event: string, ...args: unknown[]): void {
    this.handlers.get(event)?.forEach((handler) => {
      try {
        handler(...args);
      } catch {
        // Swallow handler errors
      }
    });
  }

  private scheduleReconnect(): void {
    this.clearReconnectTimer();
    this.reconnectTimer = setTimeout(() => {
      this.connect();
    }, this.currentDelay);
    this.currentDelay = Math.min(this.currentDelay * 2, this.maxReconnectDelay);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}
