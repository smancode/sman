/**
 * Global Gateway Connection Manager
 * Wraps GatewayClient singleton and syncs status with gateway store
 */
import { useGatewayStore } from '@/stores/gateway';
import {
  createGatewayClient,
  getGatewayClient,
  resetGatewayClient,
  GatewayClient,
} from '@/lib/gateway-client';
import type { GatewayStatus } from '@/types/gateway';

// Map GatewayClient status to store status
function mapClientStatus(
  state: 'connecting' | 'connected' | 'disconnected' | 'error',
  error?: string
): GatewayStatus {
  if (state === 'error') {
    return { state: 'disconnected', error };
  }
  return { state, error };
}

function toWsUrl(httpUrl: string): string {
  return httpUrl.replace(/^http/, 'ws');
}

function ensureClient(): GatewayClient {
  const store = useGatewayStore.getState();
  const existingClient = getGatewayClient();

  const wsUrl = toWsUrl(store.url);

  if (existingClient) {
    existingClient.updateConfig({ url: wsUrl, token: store.token });
    return existingClient;
  }

  const client = createGatewayClient({
    url: wsUrl,
    token: store.token,
    autoReconnect: true,
  });

  // Wire up status changes to store
  client.onStatusChange = (state, error) => {
    const setStatus = useGatewayStore.getState().setStatus;
    const setConnected = useGatewayStore.getState().setConnected;
    setStatus(mapClientStatus(state, error));
    setConnected(state === 'connected');
  };

  return client;
}

async function doConnect(): Promise<void> {
  const store = useGatewayStore.getState();
  if (!store.url) {
    store.setStatus({ state: 'disconnected', error: 'Gateway URL not configured' });
    return;
  }

  const client = ensureClient();
  await client.connect();
}

function doDisconnect(): void {
  const client = getGatewayClient();
  if (client) {
    client.disconnect();
  }
  const store = useGatewayStore.getState();
  store.setConnected(false);
  store.setStatus({ state: 'disconnected' });
}

async function doTestConnection(): Promise<boolean> {
  const store = useGatewayStore.getState();
  const url = store.url;
  const token = store.token;

  if (!url) {
    return false;
  }

  try {
    const response = await fetch(`${url}/health`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    return response.ok;
  } catch {
    return false;
  }
}

/**
 * Hook to access gateway connection
 * Uses the shared GatewayClient instance
 */
export function useGatewayConnection() {
  const status = useGatewayStore((s) => s.status);

  return {
    status,
    connect: doConnect,
    disconnect: doDisconnect,
    testConnection: doTestConnection,
    isConnected: status.state === 'connected',
    isConnecting: status.state === 'connecting',
  };
}

// Export for direct access if needed
export const gatewayConnection = {
  connect: doConnect,
  disconnect: doDisconnect,
  testConnection: doTestConnection,
  getClient: getGatewayClient,
  reset: resetGatewayClient,
};
