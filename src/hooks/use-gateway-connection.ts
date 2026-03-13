/**
 * useGatewayConnection Hook
 * Manages connection to OpenClaw Gateway with auto-reconnect
 */
import { useEffect, useCallback, useRef } from 'react';
import { useGatewayStore } from '@/stores/gateway';

export function useGatewayConnection() {
  const url = useGatewayStore((s) => s.url);
  const token = useGatewayStore((s) => s.token);
  const status = useGatewayStore((s) => s.status);
  const setStatus = useGatewayStore((s) => s.setStatus);
  const setConnected = useGatewayStore((s) => s.setConnected);

  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const mountedRef = useRef(true);

  const connect = useCallback(() => {
    if (!url) {
      setStatus({ state: 'disconnected', error: 'Gateway URL not configured' });
      return;
    }

    if (wsRef.current?.readyState === WebSocket.OPEN) {
      return;
    }

    setStatus({ state: 'connecting' });

    try {
      const wsUrl = url.replace(/^http/, 'ws');
      const ws = new WebSocket(wsUrl, token ? [token] : undefined);
      wsRef.current = ws;

      ws.onopen = () => {
        if (!mountedRef.current) return;
        setStatus({ state: 'connected' });
        setConnected(true);
      };

      ws.onclose = (event) => {
        if (!mountedRef.current) return;
        setConnected(false);
        setStatus({
          state: 'disconnected',
          error: event.reason || 'Connection closed',
        });

        // Auto-reconnect after 3 seconds
        if (event.code !== 1000) {
          reconnectTimerRef.current = setTimeout(connect, 3000);
        }
      };

      ws.onerror = () => {
        if (!mountedRef.current) return;
        setStatus({ state: 'disconnected', error: 'Connection failed' });
      };
    } catch (error) {
      setStatus({
        state: 'disconnected',
        error: error instanceof Error ? error.message : 'Invalid gateway URL',
      });
    }
  }, [url, token, setStatus, setConnected]);

  const disconnect = useCallback(() => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    if (wsRef.current) {
      wsRef.current.close(1000, 'User disconnected');
      wsRef.current = null;
    }
    setConnected(false);
    setStatus({ state: 'disconnected' });
  }, [setConnected, setStatus]);

  const testConnection = useCallback(async (): Promise<boolean> => {
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
  }, [url, token]);

  // Cleanup on unmount
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      disconnect();
    };
  }, [disconnect]);

  return {
    status,
    connect,
    disconnect,
    testConnection,
    isConnected: status.state === 'connected',
    isConnecting: status.state === 'connecting',
  };
}
