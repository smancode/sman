import { useEffect } from 'react';
import { RouterProvider } from 'react-router-dom';
import { TooltipProvider } from '@/components/ui/tooltip';
import { router } from './routes';
import { useWsConnection } from '@/stores/ws-connection';
import { useSettingsStore } from '@/stores/settings';
import { useChatStore } from '@/stores/chat';
import { sessionCache } from '@/lib/session-cache';
import { cronCache } from '@/lib/cron-cache';
import { useTheme } from '@/hooks/useTheme';

export default function App() {
  const connect = useWsConnection((s) => s.connect);
  const initToken = useWsConnection((s) => s.initToken);
  const status = useWsConnection((s) => s.status);
  const client = useWsConnection((s) => s.client);
  const fetchSettings = useSettingsStore((s) => s.fetchSettings);
  const loadSessions = useChatStore((s) => s.loadSessions);

  useTheme();

  useEffect(() => {
    // Fetch token first (local mode), then connect
    initToken().finally(() => connect());
  }, [connect, initToken]);

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
    </TooltipProvider>
  );
}
