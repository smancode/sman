import { useEffect, useRef, useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronLeft, FolderOpen } from 'lucide-react';
import { cn } from '@/lib/utils';
import { CronTaskSettings } from '@/features/settings/CronTaskSettings';
import { useCronStore } from '@/stores/cron';
import { useWsConnection } from '@/stores/ws-connection';
import { useScrollSpy } from '@/hooks/useScrollSpy';
import { PageLayout } from '@/components/common/PageLayout';
import { t } from '@/locales';

export function CronTasksPage() {
  const navigate = useNavigate();
  const scrollRef = useRef<HTMLDivElement>(null);
  const [activeWorkspace, setActiveWorkspace] = useState<string | null>(null);

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

  const { activeId, setActiveId, scrollTo } = useScrollSpy({
    containerRef: scrollRef,
    items: groups.map(([name]) => ({ id: name })),
    idPrefix: 'cron-ws-',
    threshold: 0.2,
  });

  // Sync scroll spy active id with local state
  useEffect(() => {
    if (activeId) setActiveWorkspace(activeId);
  }, [activeId]);

  return (
    <PageLayout
      scrollRef={scrollRef}
      sidebar={
        <>
          <button
            onClick={() => navigate(-1)}
            className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground mb-4 px-2"
          >
            <ChevronLeft className="h-4 w-4" />
            {t('cron.back')}
          </button>
          {groups.length === 0 && (
            <div className="text-xs text-muted-foreground px-3 py-2">{t('cron.noSystems')}</div>
          )}
          {groups.map(([name]) => (
            <button
              key={name}
              onClick={() => {
                setActiveWorkspace(name);
                scrollTo(name);
              }}
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
        </>
      }
    >
      <CronTaskSettings />
    </PageLayout>
  );
}
