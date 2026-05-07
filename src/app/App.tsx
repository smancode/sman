import { useEffect, useRef, useState } from 'react';
import { RouterProvider } from 'react-router-dom';
import { TooltipProvider } from '@/components/ui/tooltip';
import { router } from './routes';
import { useWsConnection, recreateClient } from '@/stores/ws-connection';
import { useSettingsStore } from '@/stores/settings';
import { useChatStore } from '@/stores/chat';
import { sessionCache } from '@/lib/session-cache';
import { cronCache } from '@/lib/cron-cache';
import { useTheme } from '@/hooks/useTheme';
import { useLanguage } from '@/hooks/useLanguage';
import { GitPanel } from '@/features/git/GitPanel';
import { t } from '@/locales';

function ConnectingOverlay({ status }: { status: string }) {
  const [dots, setDots] = useState('');
  useEffect(() => {
    const timer = setInterval(() => setDots(d => d.length >= 3 ? '' : d + '.'), 500);
    return () => clearInterval(timer);
  }, []);
  const label = status === 'connecting' ? t('common.loading') : t('common.loading');
  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center bg-background/80 backdrop-blur-sm">
      <div className="flex flex-col items-center gap-3 text-muted-foreground">
        <div className="h-6 w-6 animate-spin rounded-full border-2 border-current border-t-transparent" />
        <span className="text-sm">{label}{dots}</span>
      </div>
    </div>
  );
}

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
  useLanguage();
  useAutoConnect();

  useEffect(() => {
    if (status !== 'connected') return;
    // Run all init tasks in parallel — no need to serialize
    Promise.all([
      sessionCache.loadAll(),
      cronCache.loadAll(),
      fetchSettings(),
    ]).then(() => {
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
      {status !== 'connected' && <ConnectingOverlay status={status} />}
      <RouterProvider router={router} />
      <GitPanel />
    </TooltipProvider>
  );
}
