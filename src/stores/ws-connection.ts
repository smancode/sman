/**
 * WebSocket Connection Store
 * Manages a singleton WsClient instance and exposes connection status.
 */
import { create } from 'zustand';
import { WsClient } from '@/lib/ws-client';

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected';

interface WsConnectionState {
  status: ConnectionStatus;
  client: WsClient | null;
  connect: () => void;
  disconnect: () => void;
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

  // 只注册一次全局 listener
  if (!listenersRegistered) {
    client.on('connected', () => set({ status: 'connected' }));
    client.on('disconnected', () => set({ status: 'disconnected' }));
    listenersRegistered = true;
  }

  return {
    status: 'disconnected',
    client,

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
