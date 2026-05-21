import { NavLink, useLocation } from 'react-router-dom';
import { useState, useEffect, lazy, Suspense } from 'react';
import {
  Settings as SettingsIcon,
  Sun,
  Moon,
  Clock,
  Sparkles,
  Route,
  Users,
  Scroll,
  MessageCircle,
  MessagesSquare,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { SessionTree } from '@/components/SessionTree';
import { useWsConnection } from '@/stores/ws-connection';
import { useTheme } from '@/hooks/useTheme';
import { t, useLocale } from '@/locales';

const IMListPanel = lazy(() => import('@/features/im/IMListPanel'));

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface SidebarProps {
  showRightPanel: boolean;
}

// ---------------------------------------------------------------------------
// Sidebar — icon nav (48px) + optional right panel (SessionTree or IM list)
// ---------------------------------------------------------------------------

export function Sidebar({ showRightPanel }: SidebarProps) {
  useLocale();
  const { theme, toggleTheme } = useTheme();
  const connectionStatus = useWsConnection((s) => s.status);
  const location = useLocation();
  const isMac = window.sman?.platform === 'darwin' || (!window.sman && navigator.platform?.includes('Mac'));
  const shouldBlur = !['/chat', '/stardom', '/hub', '/achievements', '/im'].includes(location.pathname);
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => { setDismissed(false); }, [location.pathname]);

  const isIMActive = location.pathname === '/im';
  const isChatActive = location.pathname === '/chat';
  const showIMList = location.pathname === '/im';

  return (
    <>
      {/* ===== 左侧 48px 竖向图标导航栏 ===== */}
      <div className="w-12 flex-shrink-0 flex flex-col items-center border-r border-[hsl(var(--border))]/40" style={{ background: 'hsl(var(--sidebar-bg))' }}>
        {isMac ? (
          <div className="shrink-0 h-[28px]" />
        ) : window.sman ? (
          <div className="shrink-0 h-3" />
        ) : null}

        {/* 聊天 */}
        <NavLink
          to="/chat"
          className={() =>
            cn(
              'flex items-center justify-center w-9 h-9 rounded-lg my-0.5 transition-all duration-150',
              'hover:bg-[hsl(var(--sidebar-border))]',
              isChatActive ? 'text-foreground bg-[hsl(var(--sidebar-border))]' : 'text-muted-foreground',
            )
          }
          title={t('menu.chat')}
        >
          <MessageCircle className="h-[18px] w-[18px]" strokeWidth={2} />
        </NavLink>

        {/* IM */}
        <NavLink
          to="/im"
          className={() =>
            cn(
              'flex items-center justify-center w-9 h-9 rounded-lg my-0.5 transition-all duration-150',
              'hover:bg-[hsl(var(--sidebar-border))]',
              isIMActive ? 'text-foreground bg-[hsl(var(--sidebar-border))]' : 'text-muted-foreground',
            )
          }
          title={t('im.title')}
        >
          <MessagesSquare className="h-[18px] w-[18px]" strokeWidth={2} />
        </NavLink>

        {/* 分隔线 */}
        <div className="w-5 h-px bg-[hsl(var(--border))]/40 my-1" />

        {/* 定时任务 */}
        <NavLink
          to="/cron-tasks"
          className={({ isActive }) =>
            cn(
              'flex items-center justify-center w-9 h-9 rounded-lg my-0.5 transition-all duration-150',
              'hover:bg-[hsl(var(--sidebar-border))]',
              isActive ? 'text-foreground bg-[hsl(var(--sidebar-border))]' : 'text-muted-foreground',
            )
          }
          title={t('menu.cron')}
        >
          <Clock className="h-[18px] w-[18px]" strokeWidth={2} />
        </NavLink>

        {/* 地球路径 */}
        <NavLink
          to="/smart-paths"
          className={({ isActive }) =>
            cn(
              'flex items-center justify-center w-9 h-9 rounded-lg my-0.5 transition-all duration-150',
              'hover:bg-[hsl(var(--sidebar-border))]',
              isActive ? 'text-foreground bg-[hsl(var(--sidebar-border))]' : 'text-muted-foreground',
            )
          }
          title={t('menu.smartpath')}
        >
          <Route className="h-[18px] w-[18px]" strokeWidth={2} />
        </NavLink>

        {/* 组队 */}
        <NavLink
          to="/hub"
          className={({ isActive }) =>
            cn(
              'flex items-center justify-center w-9 h-9 rounded-lg my-0.5 transition-all duration-150',
              'hover:bg-[hsl(var(--sidebar-border))]',
              isActive ? 'text-foreground bg-[hsl(var(--sidebar-border))]' : 'text-muted-foreground',
            )
          }
          title={t('menu.hub')}
        >
          <Users className="h-[18px] w-[18px]" strokeWidth={2} />
        </NavLink>

        {/* 协作星图 */}
        <NavLink
          to="/stardom"
          className={({ isActive }) =>
            cn(
              'flex items-center justify-center w-9 h-9 rounded-lg my-0.5 transition-all duration-150',
              'hover:bg-[hsl(var(--sidebar-border))]',
              isActive ? 'text-foreground bg-[hsl(var(--sidebar-border))]' : 'text-muted-foreground',
            )
          }
          title={t('menu.stardom')}
        >
          <Sparkles className="h-[18px] w-[18px]" strokeWidth={2} />
        </NavLink>

        {/* 弹性空间 */}
        <div className="flex-1" />

        {/* 成就 */}
        <NavLink
          to="/achievements"
          className={({ isActive }) =>
            cn(
              'flex items-center justify-center w-9 h-9 rounded-lg my-0.5 transition-all duration-150',
              'hover:bg-[hsl(var(--sidebar-border))]',
              isActive ? 'text-foreground bg-[hsl(var(--sidebar-border))]' : 'text-muted-foreground',
            )
          }
          title={t('menu.achievements')}
        >
          <Scroll className="h-[18px] w-[18px]" strokeWidth={2} />
        </NavLink>

        {/* 设置 */}
        <NavLink
          to="/settings"
          className={({ isActive }) =>
            cn(
              'flex items-center justify-center w-9 h-9 rounded-lg my-0.5 transition-all duration-150',
              'hover:bg-[hsl(var(--sidebar-border))]',
              isActive ? 'text-foreground bg-[hsl(var(--sidebar-border))]' : 'text-muted-foreground',
            )
          }
          title={t('menu.settings')}
        >
          <SettingsIcon className="h-[18px] w-[18px]" strokeWidth={2} />
        </NavLink>

        {/* Connection status */}
        <div
          className={cn(
            'h-2 w-2 rounded-full my-1.5',
            connectionStatus === 'connected'
              ? 'bg-green-500'
              : connectionStatus === 'connecting'
                ? 'bg-yellow-500 animate-pulse'
                : 'bg-red-500',
          )}
          title={
            connectionStatus === 'connected'
              ? t('sidebar.connected')
              : connectionStatus === 'connecting'
                ? t('sidebar.connecting')
                : t('sidebar.disconnected')
          }
        />

        {/* Theme toggle */}
        <Button
          variant="ghost"
          size="icon"
          className="h-9 w-9 shrink-0 text-muted-foreground hover:bg-[hsl(var(--sidebar-border))] rounded-lg mb-2"
          onClick={toggleTheme}
          title={theme === 'light' ? t('sidebar.darkMode') : t('sidebar.lightMode')}
        >
          {theme === 'light' ? (
            <Moon className="h-[18px] w-[18px]" />
          ) : (
            <Sun className="h-[18px] w-[18px]" />
          )}
        </Button>
      </div>

      {/* ===== 右侧面板：SessionTree 或 IM 列表 ===== */}
      {showRightPanel && (
        <div className="flex-1 flex flex-col min-w-0" style={{ background: 'hsl(var(--sidebar-bg))' }}>
          {isMac ? (
            <div className="shrink-0 h-[28px]" />
          ) : window.sman ? (
            <div className="shrink-0 h-3" />
          ) : null}

          {showIMList ? (
            <Suspense fallback={<div className="flex-1" />}>
              <IMListPanel />
            </Suspense>
          ) : (
            <div className="flex-1 overflow-hidden min-h-0 relative">
              <SessionTree />
              {shouldBlur && !dismissed && (
                <div
                  className="absolute inset-0 backdrop-blur-[2px] bg-background/10 z-10 transition-all duration-300 cursor-pointer"
                  onClick={() => setDismissed(true)}
                />
              )}
            </div>
          )}
        </div>
      )}
    </>
  );
}
