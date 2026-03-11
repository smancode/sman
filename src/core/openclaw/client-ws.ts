// src/core/openclaw/client-ws.ts
/**
 * OpenClaw WebSocket Gateway Client
 *
 * Manages WebSocket connection to OpenClaw Gateway with:
 * - Automatic request-response matching
 * - Event subscription
 * - Reconnection handling
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
  url: "ws://127.0.0.1:18789",
  reconnectIntervalMs: 1000,
  maxReconnectAttempts: 5,
  requestTimeoutMs: 120000,
};

export class OpenClawWSClient {
  private ws: WebSocket | null = null;
  private config: WSClientConfig;
  private pendingRequests: Map<string, PendingRequest> = new Map();
  private eventListeners: Map<string, Set<EventHandler>> = new Map();
  private _state: WSClientState = "disconnected";
  private reconnectAttempts = 0;
  private requestId = 0;

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
        this.setState("error");
        reject(new Error(`Failed to create WebSocket: ${err}`));
        return;
      }

      this.ws.onopen = () => {
        this.setState("connected");
        this.reconnectAttempts = 0;
        resolve();
      };

      this.ws.onerror = () => {
        this.setState("error");
        if (this._state === "connecting") {
          reject(new Error("WebSocket connection failed"));
        }
      };

      this.ws.onclose = () => {
        this.handleDisconnect();
      };

      this.ws.onmessage = (event) => {
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

    // Try to reconnect
    if (this.reconnectAttempts < this.config.maxReconnectAttempts) {
      this.reconnectAttempts++;
      this.setState("connecting");
      setTimeout(() => {
        this.connect().catch(() => {
          // Reconnection failed, will retry
        });
      }, this.config.reconnectIntervalMs);
    } else {
      this.setState("disconnected");
    }
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
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.setState("disconnected");
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
