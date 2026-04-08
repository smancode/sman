/**
 * WebSocket Connection Store
 * Manages a singleton WsClient instance and exposes connection status.
 */
import { create } from 'zustand';
import { WsClient } from '@/lib/ws-client';
import { setAuthToken, setHttpBaseUrl } from '@/lib/auth';

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'auth_failed';

interface WsConnectionState {
  status: ConnectionStatus;
  client: WsClient | null;
  connect: () => void;
  disconnect: () => void;
  /** Fetch auth token from local backend and sync to WsClient + authFetch */
  initToken: () => Promise<void>;
}

let singletonClient: WsClient | null = null;

function getStoredUrl(): string {
  if (typeof window === 'undefined') return '';
  return localStorage.getItem('sman-backend-url') || '';
}

function getStoredToken(): string {
  if (typeof window === 'undefined') return '';
  return localStorage.getItem('sman-backend-token') || '';
}

function registerListeners(client: WsClient) {
  client.on('connected', () => useWsConnection.setState({ status: 'connected' }));
  client.on('disconnected', () => useWsConnection.setState({ status: 'disconnected' }));
  client.on('authFailed', () => useWsConnection.setState({ status: 'auth_failed' }));
}

/** Create or recreate WsClient — reads URL + token from localStorage */
export function recreateClient(): WsClient {
  // Disconnect old client
  if (singletonClient) {
    singletonClient.disconnect();
  }

  const url = getStoredUrl() || undefined;
  const token = getStoredToken();

  singletonClient = new WsClient({
    port: 5880,
    url,
    token,
  });

  // Sync HTTP base URL so authFetch hits the right backend
  setHttpBaseUrl(url || '');

  registerListeners(singletonClient);
  useWsConnection.setState({ client: singletonClient });
  return singletonClient;
}

export const useWsConnection = create<WsConnectionState>((set, get) => ({
  status: 'disconnected',
  client: null,

  init: () => {
    // Lazy init: create client on first use, avoiding TDZ
    if (!singletonClient) {
      recreateClient();
    }
  },

  async initToken() {
    try {
      const res = await fetch('/api/auth/token');
      if (res.ok) {
        const data = await res.json();
        const c = get().client;
        if (c) c.token = data.token;
        setAuthToken(data.token);
        localStorage.setItem('sman-backend-token', data.token);
        return;
      }
    } catch {
      // Not running locally or backend not ready
    }
    // Fallback: use stored token (for remote mode where /api/auth/token is 403)
    const stored = getStoredToken();
    if (stored) {
      setAuthToken(stored);
    }
  },

  connect() {
    if (!singletonClient) recreateClient();
    set({ status: 'connecting' });
    const c = get().client;
    if (c) c.connect();
  },

  disconnect() {
    set({ status: 'disconnected' });
    const c = get().client;
    if (c) c.disconnect();
  },
}));
