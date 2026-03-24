import { NavLink } from 'react-router-dom';
import {
  Settings as SettingsIcon,
  PanelLeftClose,
  PanelLeft,
  Sun,
  Moon,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { SessionTree } from '@/components/SessionTree';
import { useBusinessSystemsStore } from '@/stores/business-systems';
import { useChatStore } from '@/stores/chat';
import { useWsConnection } from '@/stores/ws-connection';
import { useTheme } from '@/hooks/useTheme';

export function Sidebar() {
  const [collapsed, setCollapsed] = [false, (_c: boolean) => {}];
  const { theme, toggleTheme } = useTheme();
  const connectionStatus = useWsConnection((s) => s.status);

  const currentSessionId = useChatStore((s) => s.currentSessionId);
  const sessions = useChatStore((s) => s.sessions);
  const sessionLabels = useChatStore((s) => s.sessionLabels);
  const systems = useBusinessSystemsStore((s) => s.systems);

  // Find current session's system
  const currentSession = sessions.find((s) => s.key === currentSessionId);
  const currentSystem = currentSession?.systemId
    ? systems.find((sys) => sys.systemId === currentSession.systemId)
    : undefined;

  const handleNewSession = async () => {
    const systemId = systems.length > 0 ? systems[0].systemId : undefined;
    if (!systemId) return;
    await useChatStore.getState().createSession(systemId);
  };

  return (
    <aside
      className={cn(
        'flex shrink-0 flex-col border-r bg-[hsl(var(--sidebar-bg))] transition-all duration-300',
        collapsed ? 'w-16' : 'w-64',
      )}
    >
      {/* Header */}
      <div
        className={cn(
          'flex items-center h-12 pt-6',
          collapsed ? 'justify-center px-2' : 'justify-between px-3',
        )}
        style={{ WebkitAppRegion: 'drag' } as React.CSSProperties}
      >
        <Button
          style={{ WebkitAppRegion: 'no-drag' } as React.CSSProperties}
          variant="ghost"
          size="icon"
          className="h-8 w-8 shrink-0 text-muted-foreground hover:bg-black/5 dark:hover:bg-white/10"
          onClick={() => setCollapsed(!collapsed)}
        >
          {collapsed ? (
            <PanelLeft className="h-[18px] w-[18px]" />
          ) : (
            <PanelLeftClose className="h-[18px] w-[18px]" />
          )}
        </Button>
      </div>

      {/* Session Tree */}
      {!collapsed && (
        <div className="flex-1 overflow-hidden border-t border-[hsl(var(--sidebar-border))]">
          <SessionTree onNewSession={handleNewSession} />
        </div>
      )}

      {/* Current session info */}
      {!collapsed && currentSession && currentSystem && (
        <div className="border-t border-[hsl(var(--sidebar-border))] px-3 py-2.5 bg-[hsl(var(--sidebar-bg))]">
          <div className="flex items-center gap-2 text-xs">
            <span className="font-medium truncate text-foreground/80">{currentSystem.name}</span>
            <span className="text-muted-foreground/40">&rsaquo;</span>
            <span className="truncate flex-1 text-muted-foreground">
              {sessionLabels[currentSession.key] || '新会话'}
            </span>
          </div>
        </div>
      )}

      {/* Footer */}
      <div className="p-2 mt-auto border-t border-[hsl(var(--sidebar-border))]">
        <div className="flex items-center gap-1">
          <NavLink
            to="/settings"
            className={({ isActive }) =>
              cn(
                'flex items-center gap-2.5 rounded-lg px-3 py-2 text-[14px] font-medium transition-all duration-200 flex-1',
                'hover:bg-[hsl(var(--muted))] text-foreground/70',
                isActive && 'bg-[hsl(var(--muted))] text-foreground shadow-sm',
              )
            }
          >
            {({ isActive }) => (
              <>
                <div
                  className={cn(
                    'flex shrink-0 items-center justify-center',
                    isActive ? 'text-foreground' : 'text-muted-foreground',
                  )}
                >
                  <SettingsIcon className="h-[18px] w-[18px]" strokeWidth={2} />
                </div>
                {!collapsed && <span>设置</span>}
              </>
            )}
          </NavLink>

          {/* Connection status */}
          <div
            className={cn(
              'h-2 w-2 rounded-full shrink-0',
              connectionStatus === 'connected'
                ? 'bg-green-500'
                : connectionStatus === 'connecting'
                  ? 'bg-yellow-500 animate-pulse'
                  : 'bg-red-500',
            )}
            title={
              connectionStatus === 'connected'
                ? '已连接'
                : connectionStatus === 'connecting'
                  ? '连接中...'
                  : '未连接'
            }
          />

          {/* Theme toggle */}
          <Button
            variant="ghost"
            size="icon"
            className="h-9 w-9 shrink-0 text-muted-foreground hover:bg-[hsl(var(--muted))] rounded-lg"
            onClick={toggleTheme}
            title={theme === 'light' ? '深色模式' : '浅色模式'}
          >
            {theme === 'light' ? (
              <Moon className="h-[18px] w-[18px]" />
            ) : (
              <Sun className="h-[18px] w-[18px]" />
            )}
          </Button>
        </div>
      </div>
    </aside>
  );
}
