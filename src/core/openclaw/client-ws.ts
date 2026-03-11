// src/core/openclaw/client-ws.ts
/**
 * OpenClaw WebSocket Gateway Client
 *
 * Manages WebSocket connection to OpenClaw Gateway with:
 * - Automatic request-response matching
 * - Event subscription
 * - Exponential backoff reconnection
 * - Heartbeat health check
 */

import type {
  GatewayRequest,
  GatewayResponse,
  GatewayEvent,
  GatewayMessage,
  PendingRequest,
  EventHandler,
  WSClientConfig,
  WSClientState,
} from "./types";

const DEFAULT_CONFIG: WSClientConfig = {
  url: "ws://127.0.0.1:18790",
  requestTimeoutMs: 120000,
  reconnect: {
    baseDelayMs: 1000,
    maxDelayMs: 30000,
    maxAttempts: 10,
    backoffFactor: 1.5,
  },
  healthCheck: {
    enabled: true,
    heartbeatIntervalMs: 30000,
    heartbeatTimeoutMs: 60000,
  },
};

export class OpenClawWSClient {
  private ws: WebSocket | null = null;
  private config: WSClientConfig;
  private pendingRequests: Map<string, PendingRequest> = new Map();
  private eventListeners: Map<string, Set<EventHandler>> = new Map();
  private _state: WSClientState = "disconnected";
  private reconnectAttempts = 0;
  private requestId = 0;

  // Health check
  private heartbeatInterval: ReturnType<typeof setInterval> | null = null;
  private heartbeatTimeout: ReturnType<typeof setTimeout> | null = null;
  private lastHeartbeatTime = 0;

  // Reconnect
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private shouldAutoReconnect = true;

  // State change callbacks
  private onStateChange?: (state: WSClientState) => void;

  constructor(config: Partial<WSClientConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  /** Get current connection state */
  get state(): WSClientState {
    return this._state;
  }

  /** Set state change callback */
  setStateChangeCallback(callback: (state: WSClientState) => void): void {
    this.onStateChange = callback;
  }

  /** Update state and notify */
  private setState(state: WSClientState): void {
    this._state = state;
    this.onStateChange?.(state);
  }

  /** Check if connected */
  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  /** Get current WebSocket URL */
  getUrl(): string {
    return this.config.url;
  }

  /** Calculate reconnect delay with exponential backoff */
  private getReconnectDelay(): number {
    const { baseDelayMs, maxDelayMs, backoffFactor } = this.config.reconnect;
    const delay = baseDelayMs * Math.pow(backoffFactor, this.reconnectAttempts);
    return Math.min(delay, maxDelayMs);
  }

  /** Reset reconnect state after successful connection */
  private resetReconnectState(): void {
    this.reconnectAttempts = 0;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  /** Start heartbeat health check */
  private startHealthCheck(): void {
    if (!this.config.healthCheck.enabled || !this.ws) return;

    this.stopHealthCheck();
    this.lastHeartbeatTime = Date.now();

    // Start heartbeat interval
    this.heartbeatInterval = setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.sendHeartbeat();
      }
    }, this.config.healthCheck.heartbeatIntervalMs);
  }

  /** Send heartbeat and start timeout */
  private sendHeartbeat(): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;

    this.lastHeartbeatTime = Date.now();

    // Send heartbeat frame (OpenClaw uses ping/pong or custom heartbeat)
    // For browser WebSocket, we use a simple approach
    try {
      this.ws.send(JSON.stringify({ type: "heartbeat", timestamp: Date.now() }));
    } catch (err) {
      console.error("[OpenClawWS] Failed to send heartbeat:", err);
    }

    // Start heartbeat timeout
    this.startHeartbeatTimeout();
  }

  /** Start heartbeat timeout */
  private startHeartbeatTimeout(): void {
    if (this.heartbeatTimeout) {
      clearTimeout(this.heartbeatTimeout);
    }
    this.heartbeatTimeout = setTimeout(() => {
      const elapsed = Date.now() - this.lastHeartbeatTime;
      if (elapsed > this.config.healthCheck.heartbeatTimeoutMs) {
        console.warn("[OpenClawWS] Heartbeat timeout, triggering reconnect");
        this.triggerReconnect("heartbeat_timeout");
      }
    }, this.config.healthCheck.heartbeatTimeoutMs);
  }

  /** Stop health check */
  private stopHealthCheck(): void {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
    }
    if (this.heartbeatTimeout) {
      clearTimeout(this.heartbeatTimeout);
      this.heartbeatTimeout = null;
    }
  }

  /** Trigger reconnection */
  private triggerReconnect(reason: string): void {
    console.log(`[OpenClawWS] Triggering reconnect: ${reason}`);
    this.stopHealthCheck();

    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }

    this.handleDisconnect();
  }

  /** Connect to Gateway */
  async connect(): Promise<void> {
    if (this.isConnected()) {
      return;
    }

    return new Promise((resolve, reject) => {
      this.setState("connecting");

      try {
        this.ws = new WebSocket(this.config.url);
      } catch (err) {
        this.setState("disconnected");
        reject(new Error(`Failed to create WebSocket: ${err}`));
        return;
      }

      this.ws.onopen = () => {
        this.setState("connected");
        this.resetReconnectState();
        this.startHealthCheck();
        resolve();
      };

      this.ws.onerror = (err) => {
        console.error("[OpenClawWS] WebSocket error:", err);
        if (this._state === "connecting" || this._state === "reconnecting") {
          this.setState("disconnected");
          reject(new Error("WebSocket connection failed"));
        }
      };

      this.ws.onclose = () => {
        this.stopHealthCheck();
        this.handleDisconnect();
      };

      this.ws.onmessage = (event) => {
        // Reset heartbeat timeout on any message
        this.lastHeartbeatTime = Date.now();
        if (this.heartbeatTimeout) {
          clearTimeout(this.heartbeatTimeout);
          this.heartbeatTimeout = null;
        }
        this.handleMessage(event.data);
      };
    });
  }

  /** Handle incoming message */
  private handleMessage(data: string): void {
    let message: GatewayMessage;
    try {
      message = JSON.parse(data);
    } catch {
      console.error("[OpenClawWS] Failed to parse message:", data);
      return;
    }

    // Ignore heartbeat responses
    const msg = message as unknown as Record<string, unknown>;
    if (msg && msg.type === "heartbeat") {
      return;
    }

    if (message.type === "res") {
      this.handleResponse(message as GatewayResponse);
    } else if (message.type === "event") {
      this.handleEvent(message as GatewayEvent);
    }
  }

  /** Handle response message */
  private handleResponse(response: GatewayResponse): void {
    const pending = this.pendingRequests.get(response.id);
    if (!pending) {
      return;
    }

    clearTimeout(pending.timeout);
    this.pendingRequests.delete(response.id);

    if (response.ok) {
      pending.resolve(response.payload);
    } else {
      const error = response.error
        ? new Error(`${response.error.code}: ${response.error.message}`)
        : new Error("Unknown error");
      pending.reject(error);
    }
  }

  /** Handle event message */
  private handleEvent(event: GatewayEvent): void {
    const listeners = this.eventListeners.get(event.event);
    if (listeners) {
      for (const handler of listeners) {
        try {
          handler(event.payload);
        } catch (err) {
          console.error(`[OpenClawWS] Event handler error for ${event.event}:`, err);
        }
      }
    }
  }

  /** Handle disconnection */
  private handleDisconnect(): void {
    // Reject all pending requests
    for (const [, pending] of this.pendingRequests) {
      clearTimeout(pending.timeout);
      pending.reject(new Error("Connection closed"));
    }
    this.pendingRequests.clear();

    // Clear health check
    this.stopHealthCheck();

    // Check if we should auto-reconnect
    const { maxAttempts } = this.config.reconnect;
    if (!this.shouldAutoReconnect || this.reconnectAttempts >= maxAttempts) {
      console.log(
        `[OpenClawWS] Stopping reconnect (attempts: ${this.reconnectAttempts}/${maxAttempts})`,
      );
      this.setState("disconnected");
      return;
    }

    // Schedule reconnect with exponential backoff
    this.reconnectAttempts++;
    const delay = this.getReconnectDelay();
    console.log(
      `[OpenClawWS] Scheduling reconnect ${this.reconnectAttempts}/${maxAttempts} in ${delay}ms`,
    );

    this.setState("reconnecting");
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect().catch((err) => {
        console.error("[OpenClawWS] Reconnect failed:", err);
        // handleDisconnect will be called again by onclose
      });
    }, delay);
  }

  /** Send RPC request */
  async request<T = unknown>(method: string, params?: Record<string, unknown>): Promise<T> {
    if (!this.isConnected()) {
      throw new Error("Not connected to OpenClaw Gateway");
    }

    const id = `${++this.requestId}`;
    const request: GatewayRequest = {
      type: "req",
      id,
      method,
      params,
    };

    return new Promise<T>((resolve, reject) => {
      // Set up timeout
      const timeout = setTimeout(() => {
        this.pendingRequests.delete(id);
        reject(new Error(`Request timeout: ${method}`));
      }, this.config.requestTimeoutMs);

      // Store pending request
      this.pendingRequests.set(id, {
        resolve: resolve as (value: unknown) => void,
        reject,
        timeout,
      });

      // Send request
      this.ws!.send(JSON.stringify(request));
    });
  }

  /** Subscribe to event */
  on<T = unknown>(event: string, handler: EventHandler<T>): () => void {
    if (!this.eventListeners.has(event)) {
      this.eventListeners.set(event, new Set());
    }
    this.eventListeners.get(event)!.add(handler as EventHandler);

    // Return unsubscribe function
    return () => {
      this.off(event, handler);
    };
  }

  /** Unsubscribe from event */
  off<T = unknown>(event: string, handler: EventHandler<T>): void {
    const listeners = this.eventListeners.get(event);
    if (listeners) {
      listeners.delete(handler as EventHandler);
    }
  }

  /** Disconnect from Gateway */
  disconnect(): void {
    this.shouldAutoReconnect = false;
    this.stopHealthCheck();
    this.resetReconnectState();

    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.setState("disconnected");
  }

  /** Manual reconnect after max attempts reached */
  async manualReconnect(): Promise<void> {
    this.reconnectAttempts = 0;
    this.shouldAutoReconnect = true;
    return this.connect();
  }
}

// Singleton instance
let _wsClient: OpenClawWSClient | null = null;

export function getOpenClawWSClient(): OpenClawWSClient {
  if (!_wsClient) {
    _wsClient = new OpenClawWSClient();
  }
  return _wsClient;
}

export function resetOpenClawWSClient(): void {
  if (_wsClient) {
    _wsClient.disconnect();
    _wsClient = null;
  }
}
