import { useEffect, useRef, useCallback, useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronLeft, FolderOpen } from 'lucide-react';
import { cn } from '@/lib/utils';
import { CronTaskSettings } from '@/features/settings/CronTaskSettings';
import { useCronStore } from '@/stores/cron';
import { useWsConnection } from '@/stores/ws-connection';

export function CronTasksPage() {
  const navigate = useNavigate();
  const scrollRef = useRef<HTMLDivElement>(null);
  const [activeWorkspace, setActiveWorkspace] = useState<string | null>(null);
  const isScrollingRef = useRef(false);

  const tasks = useCronStore((s) => s.tasks);
  const fetchTasks = useCronStore((s) => s.fetchTasks);
  const { status: wsStatus } = useWsConnection();

  useEffect(() => {
    if (wsStatus === 'connected') fetchTasks();
  }, [wsStatus, fetchTasks]);

  // Group tasks by workspace, sorted by name
  const groups = useMemo(() => {
    const map = new Map<string, string[]>();
    for (const t of tasks) {
      const name = t.workspace.split(/[/\\]/).pop() || t.workspace;
      if (!map.has(name)) map.set(name, []);
      map.get(name)!.push(t.id);
    }
    return Array.from(map.entries()).sort((a, b) => a[0].localeCompare(b[0]));
  }, [tasks]);

  // Set initial active workspace
  useEffect(() => {
    if (groups.length > 0 && activeWorkspace === null) {
      setActiveWorkspace(groups[0][0]);
    }
  }, [groups, activeWorkspace]);

  // Intersection observer
  useEffect(() => {
    const container = scrollRef.current;
    if (!container || groups.length === 0) return;

    let ready = false;
    const timer = setTimeout(() => { ready = true; }, 200);
    const observers: IntersectionObserver[] = [];

    groups.forEach(([name]) => {
      const el = document.getElementById(`cron-ws-${name}`);
      if (!el) return;

      const observer = new IntersectionObserver(
        (entries) => {
          entries.forEach((entry) => {
            if (ready && entry.isIntersecting && !isScrollingRef.current) {
              setActiveWorkspace(name);
            }
          });
        },
        { root: container, threshold: 0.2 },
      );
      observer.observe(el);
      observers.push(observer);
    });

    return () => {
      clearTimeout(timer);
      observers.forEach((o) => o.disconnect());
    };
  }, [groups]);

  const scrollToWorkspace = useCallback((name: string) => {
    const el = document.getElementById(`cron-ws-${name}`);
    if (el) {
      isScrollingRef.current = true;
      setActiveWorkspace(name);
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      setTimeout(() => { isScrollingRef.current = false; }, 800);
    }
  }, []);

  return (
    <div className="flex h-full">
      {/* Left nav */}
      <nav className="w-64 shrink-0 p-4 space-y-1">
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground mb-4 px-2"
        >
          <ChevronLeft className="h-4 w-4" />
          返回
        </button>
        {groups.length === 0 && (
          <div className="text-xs text-muted-foreground px-3 py-2">暂无业务系统</div>
        )}
        {groups.map(([name]) => (
          <button
            key={name}
            onClick={() => scrollToWorkspace(name)}
            className={cn(
              'flex items-center gap-2.5 w-full rounded-lg px-3 py-2 text-sm font-medium transition-colors',
              activeWorkspace === name
                ? 'bg-primary/10 text-primary'
                : 'text-muted-foreground hover:bg-muted hover:text-foreground',
            )}
          >
            <FolderOpen className="h-4 w-4" />
            {name}
          </button>
        ))}
      </nav>

      {/* Right content */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto">
        <div className="max-w-2xl mx-auto p-6 space-y-6">
          <CronTaskSettings />
        </div>
      </div>
    </div>
  );
}
