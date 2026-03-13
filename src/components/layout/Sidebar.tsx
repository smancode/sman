/**
 * Sidebar Component (Web Version)
 * Navigation sidebar with menu items using React Router.
 * Simplified version without Electron-specific features.
 */
import { NavLink } from 'react-router-dom';
import {
  Network,
  Bot,
  Puzzle,
  Clock,
  Settings as SettingsIcon,
  PanelLeftClose,
  PanelLeft,
  Cpu,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';

interface NavItemProps {
  to: string;
  icon: React.ReactNode;
  label: string;
  collapsed?: boolean;
}

function NavItem({ to, icon, label, collapsed }: NavItemProps) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          'flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[14px] font-medium transition-colors',
          'hover:bg-black/5 dark:hover:bg-white/5 text-foreground/80',
          isActive
            ? 'bg-black/5 dark:bg-white/10 text-foreground'
            : '',
          collapsed && 'justify-center px-0'
        )
      }
    >
      {({ isActive }) => (
        <>
          <div className={cn("flex shrink-0 items-center justify-center", isActive ? "text-foreground" : "text-muted-foreground")}>
            {icon}
          </div>
          {!collapsed && (
            <span className="flex-1 overflow-hidden text-ellipsis whitespace-nowrap">{label}</span>
          )}
        </>
      )}
    </NavLink>
  );
}

// Web version uses local state for sidebar collapse (no settings store)
export function Sidebar() {
  // For now, use a simple local state. Will be replaced with settings store later.
  const sidebarCollapsed = false;
  const setSidebarCollapsed = (_collapsed: boolean) => {
    // Will be implemented with settings store
  };

  const navItems = [
    { to: '/models', icon: <Cpu className="h-[18px] w-[18px]" strokeWidth={2} />, label: 'Models' },
    { to: '/agents', icon: <Bot className="h-[18px] w-[18px]" strokeWidth={2} />, label: 'Agents' },
    { to: '/channels', icon: <Network className="h-[18px] w-[18px]" strokeWidth={2} />, label: 'Channels' },
    { to: '/skills', icon: <Puzzle className="h-[18px] w-[18px]" strokeWidth={2} />, label: 'Skills' },
    { to: '/cron', icon: <Clock className="h-[18px] w-[18px]" strokeWidth={2} />, label: 'Cron' },
  ];

  return (
    <aside
      className={cn(
        'flex shrink-0 flex-col border-r bg-[#eae8e1]/60 dark:bg-background transition-all duration-300',
        sidebarCollapsed ? 'w-16' : 'w-64'
      )}
    >
      {/* Top Header Toggle */}
      <div className={cn("flex items-center p-2 h-12", sidebarCollapsed ? "justify-center" : "justify-between")}>
        {!sidebarCollapsed && (
          <div className="flex items-center gap-2 px-2 overflow-hidden">
            <span className="text-sm font-semibold truncate whitespace-nowrap text-foreground/90">
              SmanWeb
            </span>
          </div>
        )}
        <Button
          variant="ghost"
          size="icon"
          className="h-8 w-8 shrink-0 text-muted-foreground hover:bg-black/5 dark:hover:bg-white/10"
          onClick={() => setSidebarCollapsed(!sidebarCollapsed)}
        >
          {sidebarCollapsed ? (
            <PanelLeft className="h-[18px] w-[18px]" />
          ) : (
            <PanelLeftClose className="h-[18px] w-[18px]" />
          )}
        </Button>
      </div>

      {/* Navigation */}
      <nav className="flex flex-col px-2 gap-0.5">
        {navItems.map((item) => (
          <NavItem
            key={item.to}
            {...item}
            collapsed={sidebarCollapsed}
          />
        ))}
      </nav>

      {/* Footer */}
      <div className="p-2 mt-auto">
        <NavLink
          to="/settings"
          className={({ isActive }) =>
            cn(
              'flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[14px] font-medium transition-colors',
              'hover:bg-black/5 dark:hover:bg-white/5 text-foreground/80',
              isActive && 'bg-black/5 dark:bg-white/10 text-foreground',
              sidebarCollapsed ? 'justify-center px-0' : ''
            )
          }
        >
          {({ isActive }) => (
            <>
              <div className={cn("flex shrink-0 items-center justify-center", isActive ? "text-foreground" : "text-muted-foreground")}>
                <SettingsIcon className="h-[18px] w-[18px]" strokeWidth={2} />
              </div>
              {!sidebarCollapsed && <span className="flex-1 overflow-hidden text-ellipsis whitespace-nowrap">Settings</span>}
            </>
          )}
        </NavLink>
      </div>
    </aside>
  );
}
