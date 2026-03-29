/**
 * WebSocket Connection Store
 * Manages a singleton WsClient instance and exposes connection status.
 */
import { create } from 'zustand';
import { WsClient } from '@/lib/ws-client';
import { setAuthToken } from '@/lib/auth';

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
let listenersRegistered = false;

function getClient(): WsClient {
  if (!singletonClient) {
    singletonClient = new WsClient({ port: 5880 });
  }
  return singletonClient;
}

export const useWsConnection = create<WsConnectionState>((set) => {
  const client = getClient();

  if (!listenersRegistered) {
    client.on('connected', () => set({ status: 'connected' }));
    client.on('disconnected', () => set({ status: 'disconnected' }));
    client.on('authFailed', () => set({ status: 'auth_failed' }));
    listenersRegistered = true;
  }

  return {
    status: 'disconnected',
    client,

    async initToken() {
      try {
        const res = await fetch('/api/auth/token');
        if (res.ok) {
          const data = await res.json();
          client.token = data.token;
          setAuthToken(data.token);
        }
      } catch {
        // Not running locally or backend not ready — token must be configured manually
      }
    },

    connect() {
      set({ status: 'connecting' });
      client.connect();
    },

    disconnect() {
      set({ status: 'disconnected' });
      client.disconnect();
    },
  };
});
