import { useEffect, useRef } from 'react';
import { RouterProvider } from 'react-router-dom';
import { TooltipProvider } from '@/components/ui/tooltip';
import { router } from './routes';
import { useWsConnection, recreateClient } from '@/stores/ws-connection';
import { useSettingsStore } from '@/stores/settings';
import { useChatStore } from '@/stores/chat';
import { sessionCache } from '@/lib/session-cache';
import { cronCache } from '@/lib/cron-cache';
import { useTheme } from '@/hooks/useTheme';
import { GitPanel } from '@/features/git/GitPanel';

/**
 * Auto-connect on startup:
 * - Local mode (no stored URL): poll /api/auth/token until backend is ready, then connect
 * - Remote mode (stored URL): use stored token, connect immediately
 */
function useAutoConnect() {
  const connect = useWsConnection((s) => s.connect);
  const initToken = useWsConnection((s) => s.initToken);
  const mounted = useRef(false);

  useEffect(() => {
    if (mounted.current) return;
    mounted.current = true;

    const storedUrl = localStorage.getItem('sman-backend-url') || '';
    const storedToken = localStorage.getItem('sman-backend-token') || '';
    const isRemote = !!storedUrl;

    if (isRemote) {
      // Remote: use stored token, connect immediately
      recreateClient();
      connect();
    } else {
      // Local: poll until backend is ready, then fetch token and connect
      // 0-10s: every 1s, 10-30s: every 3s, 30-60s: every 5s, 60-180s: every 10s
      let cancelled = false;
      const poll = async () => {
        const start = Date.now();
        while (!cancelled && Date.now() - start < 180_000) {
          try {
            await initToken();
            const token = localStorage.getItem('sman-backend-token') || '';
            if (token) {
              recreateClient();
              connect();
              return;
            }
          } catch {
            // backend not ready yet
          }
          const elapsed = Date.now() - start;
          const delay =
            elapsed < 10_000 ? 1000 :
            elapsed < 30_000 ? 3000 :
            elapsed < 60_000 ? 5000 : 10000;
          await new Promise(r => setTimeout(r, delay));
        }
      };
      poll();
      return () => { cancelled = true; };
    }
  }, [connect, initToken]);
}

export default function App() {
  const status = useWsConnection((s) => s.status);
  const client = useWsConnection((s) => s.client);
  const fetchSettings = useSettingsStore((s) => s.fetchSettings);
  const loadSessions = useChatStore((s) => s.loadSessions);

  useTheme();
  useAutoConnect();

  useEffect(() => {
    if (status !== 'connected') return;
    // Warm memory cache from IndexedDB before loading sessions
    sessionCache.loadAll().then(() => {
      cronCache.loadAll();
      fetchSettings();
      loadSessions();
    });
  }, [status, fetchSettings, loadSessions]);

  // Listen for chatbot session creation — silently refresh sidebar
  useEffect(() => {
    if (!client || status !== 'connected') return;
    const handler = () => {
      loadSessions();
    };
    client.on('session.chatbotCreated', handler);
    return () => client.off('session.chatbotCreated', handler);
  }, [client, status, loadSessions]);

  return (
    <TooltipProvider>
      <RouterProvider router={router} />
      <GitPanel />
    </TooltipProvider>
  );
}
