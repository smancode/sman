// src/core/openclaw/client-ws.ts
/**
 * OpenClaw WebSocket Gateway Client
 *
 * Manages WebSocket connection to OpenClaw Gateway with:
 * - Challenge-response device authentication
 * - Automatic request-response matching
 * - Event subscription
 * - Exponential backoff reconnection
 *
 * Based on OpenClaw UI's GatewayBrowserClient
 */

import type {
  PendingRequest,
  EventHandler,
  WSClientConfig,
  WSClientState,
  GatewayResponse,
  GatewayEvent,
  ConnectOptions,
} from "./types";
import {
  loadOrCreateDeviceIdentity,
  buildDeviceAuthPayload,
  signDevicePayload,
  loadDeviceAuthToken,
  storeDeviceAuthToken,
  clearDeviceAuthToken,
} from "./device-auth";

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

// Gateway client constants
const GATEWAY_CLIENT_NAMES = {
  GATEWAY_CLIENT: "gateway-client",
  CONTROL_UI: "openclaw-control-ui",
  WEBCHAT: "webchat",
} as const;

const GATEWAY_CLIENT_MODES = {
  UI: "ui",
  WEBCHAT: "webchat",
  CLI: "cli",
  NODE: "node",
} as const;

export type HelloOk = {
  type: "hello-ok";
  protocol: number;
  server?: {
    version?: string;
    connId?: string;
  };
  auth?: {
    deviceToken?: string;
    role?: string;
    scopes?: string[];
  };
};

export class OpenClawWSClient {
  private ws: WebSocket | null = null;
  private config: WSClientConfig;
  private pendingRequests: Map<string, PendingRequest> = new Map();
  private eventListeners: Map<string, Set<EventHandler>> = new Map();
  private _state: WSClientState = "disconnected";
  private reconnectAttempts = 0;
  private requestId = 0;

  // Connection state
  private connectNonce: string | null = null;
  private connectSent = false;
  private connectResolve?: () => void;
  private connectReject?: (err: Error) => void;

  // Health check
  private heartbeatInterval: ReturnType<typeof setInterval> | null = null;

  // Reconnect
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private shouldAutoReconnect = true;

  // State change callback
  private onStateChange?: (state: WSClientState) => void;

  constructor(config: Partial<WSClientConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  get state(): WSClientState {
    return this._state;
  }

  setStateChangeCallback(callback: (state: WSClientState) => void): void {
    this.onStateChange = callback;
  }

  private setState(state: WSClientState): void {
    this._state = state;
    this.onStateChange?.(state);
  }

  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  getUrl(): string {
    return this.config.url;
  }

  private getReconnectDelay(): number {
    const { baseDelayMs, maxDelayMs, backoffFactor } = this.config.reconnect;
    const delay = baseDelayMs * Math.pow(backoffFactor, this.reconnectAttempts);
    return Math.min(delay, maxDelayMs);
  }

  private resetReconnectState(): void {
    this.reconnectAttempts = 0;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  /** Connect to Gateway with optional auth token */
  async connect(options?: ConnectOptions): Promise<void> {
    if (this.isConnected()) {
      return;
    }

    return new Promise((resolve, reject) => {
      this.connectResolve = resolve;
      this.connectReject = reject;
      this.connectSent = false;
      this.connectNonce = null;

      this.setState("connecting");

      try {
        this.ws = new WebSocket(this.config.url);
      } catch (err) {
        this.setState("disconnected");
        reject(new Error(`Failed to create WebSocket: ${err}`));
        return;
      }

      this.ws.onopen = () => {
        // Wait for connect.challenge event
        console.log("[OpenClawWS] WebSocket opened, waiting for challenge...");
      };

      this.ws.onerror = (err) => {
        console.error("[OpenClawWS] WebSocket error:", err);
      };

      this.ws.onclose = () => {
        this.handleDisconnect();
      };

      this.ws.onmessage = (event) => {
        this.handleMessage(event.data, options);
      };
    });
  }

  /** Handle incoming message */
  private handleMessage(data: string, options?: ConnectOptions): void {
    let message: { type?: string; [key: string]: unknown };
    try {
      message = JSON.parse(data);
    } catch {
      console.error("[OpenClawWS] Failed to parse message:", data);
      return;
    }

    console.log("[OpenClawWS] Received message:", JSON.stringify(message).substring(0, 200));

    if (message.type === "event") {
      const evt = message as unknown as GatewayEvent;
      console.log("[OpenClawWS] Event:", evt.event, "payload:", JSON.stringify(evt.payload));
      if (evt.event === "connect.challenge") {
        // Extract nonce from challenge
        const payload = evt.payload as { nonce?: string } | undefined;
        const nonce = payload?.nonce;
        if (nonce) {
          this.connectNonce = nonce;
          console.log("[OpenClawWS] Received challenge with nonce:", nonce.substring(0, 8));
          console.log("[OpenClawWS] WebSocket readyState:", this.ws?.readyState, "OPEN:", WebSocket.OPEN);
          void this.sendConnect(options);
        } else {
          console.error("[OpenClawWS] Challenge event missing nonce:", evt.payload);
        }
        return;
      }

      // Dispatch other events to listeners
      const listeners = this.eventListeners.get(evt.event);
      if (listeners) {
        for (const handler of listeners) {
          try {
            handler(evt.payload);
          } catch (err) {
            console.error(`[OpenClawWS] Event handler error for ${evt.event}:`, err);
          }
        }
      }
      return;
    }

    if (message.type === "res") {
      const res = message as unknown as GatewayResponse;
      const pending = this.pendingRequests.get(res.id);
      if (!pending) {
        return;
      }

      clearTimeout(pending.timeout);
      this.pendingRequests.delete(res.id);

      if (res.ok) {
        pending.resolve(res.payload);
      } else {
        const error = res.error
          ? new Error(`${res.error.code}: ${res.error.message}`)
          : new Error("Unknown error");
        pending.reject(error);
      }
      return;
    }

    if (message.type === "hello-ok") {
      console.log("[OpenClawWS] Connected successfully!");
      this.setState("connected");
      this.resetReconnectState();
      this.startHealthCheck();

      // Store device token if provided
      const hello = message as HelloOk;
      // TODO: store device token if needed

      this.connectResolve?.();
      this.connectResolve = undefined;
      this.connectReject = undefined;
    }
  }

  /** Send connect request after receiving challenge */
  private async sendConnect(options?: ConnectOptions): Promise<void> {
    if (this.connectSent) return;
    this.connectSent = true;

    const isSecureContext = typeof crypto !== "undefined" && !!crypto.subtle;
    const role = "operator";
    const scopes = ["operator.admin", "operator.approvals", "operator.pairing"];

    let device: {
      id: string;
      publicKey: string;
      signature: string;
      signedAt: number;
      nonce: string;
    } | undefined;

    let authToken = options?.token;

    if (isSecureContext) {
      try {
        const deviceIdentity = await loadOrCreateDeviceIdentity();

        // Check for stored device token
        const storedToken = loadDeviceAuthToken({
          deviceId: deviceIdentity.deviceId,
          role,
        });

        // Use stored token if no explicit token provided
        if (!authToken && storedToken) {
          authToken = storedToken.token;
        }

        // Build and sign the device auth payload
        const signedAtMs = Date.now();
        const nonce = this.connectNonce ?? "";
        const payload = buildDeviceAuthPayload({
          deviceId: deviceIdentity.deviceId,
          clientId: GATEWAY_CLIENT_NAMES.GATEWAY_CLIENT,
          clientMode: GATEWAY_CLIENT_MODES.UI,
          role,
          scopes,
          signedAtMs,
          token: authToken ?? null,
          nonce,
        });

        const signature = await signDevicePayload(deviceIdentity.privateKey, payload);

        device = {
          id: deviceIdentity.deviceId,
          publicKey: deviceIdentity.publicKey,
          signature,
          signedAt: signedAtMs,
          nonce,
        };

        console.log("[OpenClawWS] Device signed:", deviceIdentity.deviceId);
      } catch (err) {
        console.error("[OpenClawWS] Device auth failed:", err);
        // Fall through without device identity
      }
    } else {
      console.warn("[OpenClawWS] Not secure context, skipping device identity");
    }

    // Build connect params
    const params: Record<string, unknown> = {
      minProtocol: 3,
      maxProtocol: 3,
      client: {
        id: GATEWAY_CLIENT_NAMES.GATEWAY_CLIENT,
        version: "1.0.0",
        platform: "desktop",
        mode: GATEWAY_CLIENT_MODES.UI,
      },
      role,
      scopes,
    };

    if (device) {
      params.device = device;
    }

    if (authToken || options?.password) {
      params.auth = {
        token: authToken,
        password: options?.password,
      };
    }

    console.log("[OpenClawWS] Sending connect request...");

    try {
      await this.request<HelloOk>("connect", params);
      // Success handled in handleMessage via hello-ok
    } catch (err) {
      console.error("[OpenClawWS] Connect failed:", err);
      this.connectReject?.(err instanceof Error ? err : new Error("Connect failed"));
      this.ws?.close();
    }
  }

  /** Send RPC request */
  async request<T = unknown>(method: string, params?: Record<string, unknown>): Promise<T> {
    if (!this.isConnected()) {
      throw new Error("Not connected to OpenClaw Gateway");
    }

    const id = `${++this.requestId}`;

    return new Promise<T>((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pendingRequests.delete(id);
        reject(new Error(`Request timeout: ${method}`));
      }, this.config.requestTimeoutMs);

      this.pendingRequests.set(id, {
        resolve: resolve as (value: unknown) => void,
        reject,
        timeout,
      });

      const frame = { type: "req", id, method, params };
      this.ws!.send(JSON.stringify(frame));
    });
  }

  /** Subscribe to event */
  on<T = unknown>(event: string, handler: EventHandler<T>): () => void {
    if (!this.eventListeners.has(event)) {
      this.eventListeners.set(event, new Set());
    }
    this.eventListeners.get(event)!.add(handler as EventHandler);

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

  /** Start heartbeat health check */
  private startHealthCheck(): void {
    if (!this.config.healthCheck.enabled || !this.ws) return;

    this.heartbeatInterval = setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        // OpenClaw Gateway doesn't require explicit heartbeat
        // Connection state is managed by the protocol
      }
    }, this.config.healthCheck.heartbeatIntervalMs);
  }

  /** Stop health check */
  private stopHealthCheck(): void {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
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

    // Reject connect promise if pending
    if (this.connectReject) {
      this.connectReject(new Error("Connection closed"));
      this.connectResolve = undefined;
      this.connectReject = undefined;
    }

    // Check if we should auto-reconnect
    const { maxAttempts } = this.config.reconnect;
    if (!this.shouldAutoReconnect || this.reconnectAttempts >= maxAttempts) {
      console.log(
        `[OpenClawWS] Stopping reconnect (attempts: ${this.reconnectAttempts}/${maxAttempts})`
      );
      this.setState("disconnected");
      return;
    }

    // Schedule reconnect with exponential backoff
    this.reconnectAttempts++;
    const delay = this.getReconnectDelay();
    console.log(
      `[OpenClawWS] Scheduling reconnect ${this.reconnectAttempts}/${maxAttempts} in ${delay}ms`
    );

    this.setState("reconnecting");
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect().catch((err) => {
        console.error("[OpenClawWS] Reconnect failed:", err);
      });
    }, delay);
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
