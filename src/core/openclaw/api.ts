// src/core/openclaw/api.ts
/**
 * OpenClaw High-Level API
 *
 * Provides semantic methods for chat operations,
 * hiding WebSocket protocol details.
 */

import { getOpenClawWSClient, OpenClawWSClient } from "./client-ws";
import type {
  ChatEventPayload,
  ChatHistoryMessage,
  HealthResult,
  EventHandler,
  ConnectResult,
  ChatSendResult,
  ChatHistoryResult,
} from "./types";

export class OpenClawAPI {
  private client: OpenClawWSClient;
  private connected = false;

  constructor(client: OpenClawWSClient = getOpenClawWSClient()) {
    this.client = client;

    // Track connection state
    this.client.setStateChangeCallback((state) => {
      this.connected = state === "connected";
    });
  }

  /** Connect to Gateway and authenticate */
  async connect(options?: { token?: string; password?: string }): Promise<void> {
    await this.client.connect();

    // Authenticate
    const params: Record<string, unknown> = {};
    if (options?.token) params.token = options.token;
    if (options?.password) params.password = options.password;

    await this.client.request<ConnectResult>("connect", params);
    this.connected = true;
  }

  /** Check if connected */
  isConnected(): boolean {
    return this.connected && this.client.isConnected();
  }

  /** Send chat message */
  async sendMessage(
    sessionKey: string,
    message: string,
    options?: {
      thinking?: string;
      timeoutMs?: number;
    },
  ): Promise<{ runId: string }> {
    const params: Record<string, unknown> = {
      sessionKey,
      message,
      idempotencyKey: crypto.randomUUID(),
    };

    if (options?.thinking) params.thinking = options.thinking;
    if (options?.timeoutMs) params.timeoutMs = options.timeoutMs;

    const result = await this.client.request<ChatSendResult>("chat.send", params);
    return { runId: result.runId };
  }

  /** Subscribe to chat events */
  onChatEvent(handler: EventHandler<ChatEventPayload>): () => void {
    return this.client.on<ChatEventPayload>("chat.event", handler);
  }

  /** Get chat history */
  async getHistory(sessionKey: string, limit = 100): Promise<ChatHistoryMessage[]> {
    const params: Record<string, unknown> = { sessionKey, limit };
    const result = await this.client.request<ChatHistoryResult>("chat.history", params);
    return result.messages || [];
  }

  /** Abort chat run */
  async abort(sessionKey: string, runId?: string): Promise<void> {
    await this.client.request("chat.abort", { sessionKey, runId });
  }

  /** Health check */
  async healthCheck(): Promise<boolean> {
    try {
      const result = await this.client.request<HealthResult>("health");
      return result.status === "ok";
    } catch {
      return false;
    }
  }

  /** Disconnect from Gateway */
  disconnect(): void {
    this.client.disconnect();
    this.connected = false;
  }
}

// Singleton instance
let _api: OpenClawAPI | null = null;

export function getOpenClawAPI(): OpenClawAPI {
  if (!_api) {
    _api = new OpenClawAPI();
  }
  return _api;
}

export function resetOpenClawAPI(): void {
  if (_api) {
    _api.disconnect();
    _api = null;
  }
}
