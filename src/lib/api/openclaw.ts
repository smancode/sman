// src/lib/api/openclaw.ts
/**
 * OpenClaw Svelte Store Integration
 *
 * Provides reactive state management for OpenClaw connection
 * and automatic initialization.
 */

import { writable, derived } from "svelte/store";
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

/** Initialize OpenClaw connection */
export async function initializeOpenClaw(): Promise<void> {
  if (_apiInstance) {
    return; // Already initialized
  }

  // Step 1: Start OpenClaw sidecar via Tauri
  console.log("[OpenClaw] Starting sidecar...");
  const startResult = await openclawApi.start();
  if (!startResult.success) {
    const errMsg = `Failed to start OpenClaw sidecar: ${startResult.error}`;
    console.error("[OpenClaw]", errMsg);
    connectionError.set(errMsg);
    throw new Error(errMsg);
  }
  console.log("[OpenClaw] Sidecar started:", startResult.data);

  // Wait a moment for the sidecar to be ready
  await new Promise((resolve) => setTimeout(resolve, 1000));

  // Step 2: Connect WebSocket
  const api = getOpenClawAPI();
  _apiInstance = api;

  // Set up state tracking via private client access
  const client = api["client"];
  client.setStateChangeCallback((state: WSClientState) => {
    connectionState.set(state);
    // Clear error on non-disconnected states that indicate progress
    if (state === "connected" || state === "connecting" || state === "reconnecting") {
      connectionError.set(null);
    }
  });

  try {
    connectionState.set("connecting");
    await api.connect();
    connectionState.set("connected");
    console.log("[OpenClaw] WebSocket connected");
  } catch (err) {
    connectionState.set("disconnected");
    connectionError.set(err instanceof Error ? err.message : "Connection failed");
    throw err;
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
