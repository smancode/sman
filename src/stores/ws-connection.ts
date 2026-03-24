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

function getClient(): WsClient {
  if (!singletonClient) {
    singletonClient = new WsClient({ port: 5880 });
  }
  return singletonClient;
}

export const useWsConnection = create<WsConnectionState>((set) => {
  const client = getClient();

  return {
    status: 'disconnected',
    client,

    connect() {
      client.on('connected', () => set({ status: 'connected' }));
      client.on('disconnected', () => set({ status: 'disconnected' }));
      set({ status: 'connecting' });
      client.connect();
    },

    disconnect() {
      set({ status: 'disconnected' });
      client.disconnect();
    },
  };
});
