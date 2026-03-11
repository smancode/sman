// src/lib/api/openclaw.ts
/**
 * OpenClaw Svelte Store Integration
 *
 * Provides reactive state management for OpenClaw connection
 * and automatic initialization.
 */

import { writable, derived } from "svelte/store";
import { getOpenClawAPI, OpenClawAPI } from "../../core/openclaw/api";
import type { WSClientState, ChatEventPayload } from "../../core/openclaw/types";

// Connection state store
export const connectionState = writable<WSClientState>("disconnected");

// Connection error store
export const connectionError = writable<string | null>(null);

// API instance (not exposed as store, use getOpenClawAPI() directly)
let _apiInstance: OpenClawAPI | null = null;

// Is connected derived store
export const isConnected = derived(
  connectionState,
  ($state) => $state === "connected",
);

/** Initialize OpenClaw WebSocket connection (does NOT start sidecar) */
export async function initializeOpenClaw(): Promise<void> {
  // If already initialized and connected, return
  if (_apiInstance && _apiInstance.isConnected()) {
    return;
  }

  try {
    // Connect WebSocket - assumes sidecar is already running
    console.log("[OpenClaw] Connecting WebSocket to ws://127.0.0.1:18790...");
    const api = getOpenClawAPI();

    // Set up state tracking via private client access
    const client = api["client"];
    client.setStateChangeCallback((state: WSClientState) => {
      console.log("[OpenClaw] State changed to:", state);
      connectionState.set(state);
      // Clear error on non-disconnected states that indicate progress
      if (state === "connected" || state === "connecting" || state === "reconnecting") {
        connectionError.set(null);
      }
    });

    connectionState.set("connecting");
    await api.connect();
    // Only set instance after successful connection
    _apiInstance = api;
    connectionState.set("connected");
    console.log("[OpenClaw] WebSocket connected successfully");
  } catch (err) {
    console.error("[OpenClaw] Initialization failed:", err);
    connectionState.set("disconnected");
    connectionError.set(err instanceof Error ? err.message : "Connection failed");
    // Don't throw, allow UI to show error state
  }
}

/** Subscribe to chat events with auto-reconnect handling */
export function subscribeToChatEvents(
  handler: (event: ChatEventPayload) => void,
): () => void {
  if (!_apiInstance) {
    console.warn("[OpenClaw] Not connected, cannot subscribe to chat events");
    return () => {};
  }

  return _apiInstance.onChatEvent(handler);
}

/** Send message with error handling */
export async function sendChatMessage(
  sessionKey: string,
  message: string,
  options?: { thinking?: string; timeoutMs?: number },
): Promise<{ runId: string }> {
  if (!_apiInstance || !_apiInstance.isConnected()) {
    throw new Error("Not connected to OpenClaw");
  }

  return _apiInstance.sendMessage(sessionKey, message, options);
}

/** Disconnect OpenClaw */
export function disconnectOpenClaw(): void {
  if (_apiInstance) {
    _apiInstance.disconnect();
    _apiInstance = null;
  }
  connectionState.set("disconnected");
  connectionError.set(null);
}

/** Get API instance for advanced usage */
export function getAPI(): OpenClawAPI | null {
  return _apiInstance;
}
