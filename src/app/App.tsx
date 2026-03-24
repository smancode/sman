import { useEffect } from 'react';
import { RouterProvider } from 'react-router-dom';
import { TooltipProvider } from '@/components/ui/tooltip';
import { router } from './routes';
import { useWsConnection } from '@/stores/ws-connection';
import { useSettingsStore } from '@/stores/settings';
import { useBusinessSystemsStore } from '@/stores/business-systems';
import { useChatStore } from '@/stores/chat';
import { useTheme } from '@/hooks/useTheme';

export default function App() {
  const connect = useWsConnection((s) => s.connect);
  const status = useWsConnection((s) => s.status);
  const fetchSettings = useSettingsStore((s) => s.fetchSettings);
  const loadSystems = useBusinessSystemsStore((s) => s.loadSystems);
  const loadSkills = useBusinessSystemsStore((s) => s.loadSkills);
  const loadSessions = useChatStore((s) => s.loadSessions);

  useTheme();

  useEffect(() => {
    connect();
  }, [connect]);

  useEffect(() => {
    if (status !== 'connected') return;
    fetchSettings();
    loadSystems();
    loadSkills();
  }, [status, fetchSettings, loadSystems, loadSkills]);

  useEffect(() => {
    if (status !== 'connected') return;
    loadSessions();
  }, [status, loadSessions]);

  return (
    <TooltipProvider>
      <RouterProvider router={router} />
    </TooltipProvider>
  );
}
