import { NavLink, useLocation } from 'react-router-dom';
import { useState, useEffect } from 'react';
import {
  Settings as SettingsIcon,
  Sun,
  Moon,
  Clock,
  Sparkles,
  Route,
  ChevronUp,
  Pin,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { SessionTree } from '@/components/SessionTree';
import { useWsConnection } from '@/stores/ws-connection';
import { useTheme } from '@/hooks/useTheme';
import { t } from '@/locales';

export function Sidebar() {
  const { theme, toggleTheme } = useTheme();
  const connectionStatus = useWsConnection((s) => s.status);
  const location = useLocation();
  const isMac = window.sman?.platform === 'darwin' || (!window.sman && navigator.platform?.includes('Mac'));
  const shouldBlur = !['/chat', '/stardom'].includes(location.pathname);
  const [dismissed, setDismissed] = useState(false);
  const [pinned, setPinned] = useState(false);
  const [hovering, setHovering] = useState(false);
  const expanded = pinned || hovering;
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
        <div className="shrink-0 h-[28px]" />
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
        {/* 展开菜单时底部的渐隐遮罩 */}
        <div
          className={cn(
            'absolute bottom-0 left-0 right-0 h-24 z-20 pointer-events-none transition-opacity duration-200',
            expanded ? 'opacity-100' : 'opacity-0',
          )}
          style={{
            background: `linear-gradient(to top, hsl(var(--sidebar-bg)) 0%, transparent 100%)`,
          }}
        />
      </div>

      {/* Footer - 固定在底部，悬浮展开 */}
      <div
        className="p-2 shrink-0 space-y-0.5"
        onMouseEnter={() => setHovering(true)}
        onMouseLeave={() => setHovering(false)}
      >
        {/* 可折叠区域：协作星图、定时任务、地球路径 */}
        <div
          className={cn(
            'overflow-hidden transition-all duration-200',
            expanded ? 'max-h-[200px] opacity-100' : 'max-h-0 opacity-0',
          )}
        >
          <div className="space-y-0.5 mb-0.5">
            <NavLink
              to="/stardom"
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
                  <span>{t('menu.stardom')}</span>
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
                  <span>{t('menu.cron')}</span>
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
                  <span>{t('menu.smartpath')}</span>
                </>
              )}
            </NavLink>
          </div>
        </div>

        {/* 设置行（始终可见） */}
        <div className="flex items-center gap-1">
          <ChevronUp
            className={cn(
              'h-3.5 w-3.5 shrink-0 text-muted-foreground transition-transform duration-200',
              expanded ? 'rotate-180' : '',
            )}
          />
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
                <span>{t('menu.settings')}</span>
              </>
            )}
          </NavLink>

          {/* Pin 按钮：悬浮时显示，点击固定展开 */}
          <Button
            variant="ghost"
            size="icon"
            className={cn(
              'h-7 w-7 shrink-0 text-muted-foreground hover:bg-[hsl(var(--muted))] rounded-md transition-opacity duration-150',
              hovering ? 'opacity-100' : 'opacity-0 pointer-events-none',
            )}
            onClick={() => setPinned((p) => !p)}
            title={pinned ? '取消固定' : '固定展开'}
          >
            <Pin
              className={cn('h-3.5 w-3.5', pinned && 'text-foreground rotate-45')}
            />
          </Button>

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
