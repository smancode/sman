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
  ChatAttachment,
  EventHandler,
  ConnectOptions,
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

  /** Connect to Gateway with optional auth token */
  async connect(options?: ConnectOptions): Promise<void> {
    console.log("[DIAGNOSTIC] OpenClawAPI.connect:");
    console.log(
      "  options.token:",
      options?.token ? `${options.token.substring(0, 32)}...` : "undefined",
    );
    console.log("  token length:", options?.token?.length || 0);
    await this.client.connect(options);
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
      deliver?: boolean;
      attachments?: ChatAttachment[];
      timeoutMs?: number;
    },
  ): Promise<{ runId: string }> {
    const params: Record<string, unknown> = {
      sessionKey,
      message,
      idempotencyKey: crypto.randomUUID(),
    };

    if (options?.thinking) params.thinking = options.thinking;
    if (typeof options?.deliver === "boolean") params.deliver = options.deliver;
    if (options?.attachments) params.attachments = options.attachments;
    if (options?.timeoutMs) params.timeoutMs = options.timeoutMs;

    const result = await this.client.request<{ runId: string }>(
      "chat.send",
      params,
    );
    return result;
  }

  /** Subscribe to chat events */
  onChatEvent(handler: EventHandler<ChatEventPayload>): () => void {
    return this.client.on<ChatEventPayload>("chat", handler);
  }

  /** Get chat history */
  async getHistory(
    sessionKey: string,
    limit = 100,
  ): Promise<ChatHistoryMessage[]> {
    const result = await this.client.request<{
      messages?: ChatHistoryMessage[];
    }>("chat.history", { sessionKey, limit });
    return result.messages || [];
  }

  /** Abort chat run */
  async abort(sessionKey: string, runId?: string): Promise<void> {
    await this.client.request("chat.abort", { sessionKey, runId });
  }

  /** Health check */
  async healthCheck(): Promise<boolean> {
    try {
      const result = await this.client.request<{ status: string }>("health");
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
