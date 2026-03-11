// src/lib/api/openclaw.ts
/**
 * OpenClaw Svelte Store Integration
 *
 * Provides reactive state management for OpenClaw connection
 * and automatic initialization.
 */

import { writable, derived, get } from "svelte/store";
import { getOpenClawAPI, OpenClawAPI } from "../../core/openclaw/api";
import { openclawApi } from "./tauri";
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
    console.log("[OpenClaw] Already connected");
    return;
  }

  // Reset instance if it exists but is not connected
  _apiInstance = null;

  try {
    // Get token from sidecar
    console.log("[OpenClaw] Getting auth token...");
    const tokenResponse = await openclawApi.getToken();
    if (!tokenResponse.success || !tokenResponse.data) {
      throw new Error("Failed to get OpenClaw auth token");
    }
    const token = tokenResponse.data;
    console.log("[OpenClaw] Got token:", token.substring(0, 8) + "...");

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
    // Connect with token
    await api.connect({ token });
    // Only set instance after successful connection
    _apiInstance = api;
    connectionState.set("connected");
    console.log("[OpenClaw] WebSocket connected successfully");
  } catch (err) {
    const errorMsg = err instanceof Error ? err.message : "Connection failed";
    console.error("[OpenClaw] Initialization failed:", errorMsg);
    connectionState.set("disconnected");
    connectionError.set(errorMsg);
    // Throw to let caller know connection failed
    throw err;
  }
}

/** Get current connection error */
export function getConnectionError(): string | null {
  return get(connectionError);
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
