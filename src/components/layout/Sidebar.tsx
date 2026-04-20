import { NavLink, useLocation } from 'react-router-dom';
import { useState, useEffect } from 'react';
import {
  Settings as SettingsIcon,
  Sun,
  Moon,
  Clock,
  Sparkles,
  Route,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { SessionTree } from '@/components/SessionTree';
import { useWsConnection } from '@/stores/ws-connection';
import { useTheme } from '@/hooks/useTheme';

export function Sidebar() {
  const { theme, toggleTheme } = useTheme();
  const connectionStatus = useWsConnection((s) => s.status);
  const location = useLocation();
  const isMac = window.sman?.platform === 'darwin' || (!window.sman && navigator.platform?.includes('Mac'));
  const shouldBlur = !['/chat', '/bazaar'].includes(location.pathname);
  const [dismissed, setDismissed] = useState(false);
  const blurSession = shouldBlur && !dismissed;

  // 切换页面时重置 dismissed 状态
  useEffect(() => { setDismissed(false); }, [location.pathname]);

  return (
    <aside
      className={cn(
        'flex flex-col backdrop-blur-sm transition-all duration-300 w-64 h-full',
      )}
      style={{ background: 'hsl(var(--sidebar-bg))' }}
    >
      {/* macOS traffic lights 占位 / Windows 标题栏对齐 */}
      {isMac ? (
        <div className="shrink-0 h-8" />
      ) : window.sman ? (
        <div className="shrink-0 h-3" />
      ) : null}

      {/* Session Tree */}
      <div className="flex-1 overflow-hidden min-h-0 relative">
        <SessionTree />
        {blurSession && (
          <div
            className="absolute inset-0 backdrop-blur-[2px] bg-background/10 z-10 transition-all duration-300 cursor-pointer"
            onClick={() => setDismissed(true)}
          />
        )}
      </div>

      {/* Footer - 固定在底部 */}
      <div className="p-2 shrink-0 space-y-0.5">
        <NavLink
          to="/bazaar"
          className={({ isActive }) =>
            cn(
              'flex items-center gap-2.5 rounded-lg px-3 py-2 text-[14px] font-medium transition-all duration-200',
              'hover:bg-[hsl(var(--sidebar-border))] text-foreground/70',
              isActive && 'bg-[hsl(var(--sidebar-bg))] text-foreground',
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
                <Sparkles className="h-[18px] w-[18px]" strokeWidth={2} />
              </div>
              <span>协作星图</span>
            </>
          )}
        </NavLink>

        <NavLink
          to="/cron-tasks"
          className={({ isActive }) =>
            cn(
              'flex items-center gap-2.5 rounded-lg px-3 py-2 text-[14px] font-medium transition-all duration-200',
              'hover:bg-[hsl(var(--sidebar-border))] text-foreground/70',
              isActive && 'bg-[hsl(var(--sidebar-bg))] text-foreground',
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
                <Clock className="h-[18px] w-[18px]" strokeWidth={2} />
              </div>
              <span>定时任务</span>
            </>
          )}
        </NavLink>

        <NavLink
          to="/smart-paths"
          className={({ isActive }) =>
            cn(
              'flex items-center gap-2.5 rounded-lg px-3 py-2 text-[14px] font-medium transition-all duration-200',
              'hover:bg-[hsl(var(--sidebar-border))] text-foreground/70',
              isActive && 'bg-[hsl(var(--sidebar-bg))] text-foreground',
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
                <Route className="h-[18px] w-[18px]" strokeWidth={2} />
              </div>
              <span>地球路径</span>
            </>
          )}
        </NavLink>

        <div className="flex items-center gap-1">
          <NavLink
            to="/settings"
            className={({ isActive }) =>
              cn(
                'flex items-center gap-2.5 rounded-lg px-3 py-2 text-[14px] font-medium transition-all duration-200 flex-1',
                'hover:bg-[hsl(var(--sidebar-border))] text-foreground/70',
                isActive && 'bg-[hsl(var(--sidebar-bg))] text-foreground',
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
                <span>设置</span>
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
