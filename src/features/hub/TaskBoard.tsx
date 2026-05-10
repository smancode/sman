import { useState } from 'react';
import { t } from '@/locales';
import { FeedbackState } from '@/components/common/FeedbackState';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { useRoomTasks, useCreateTask, useCancelTask } from '@/queries/use-hub';
import { useHubTaskProgress } from '@/stores/hub-task-progress';
import type { Task, TaskStatusType } from '@/schemas/hub';
import { Plus, X } from 'lucide-react';
import { cn } from '@/lib/utils';

const STATUS_COLUMNS: { status: TaskStatusType; labelKey: string; color: string; badgeClass: string }[] = [
  { status: 'queued', labelKey: 'hub.task.queued', color: 'border-t-yellow-400 dark:border-t-yellow-600', badgeClass: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400' },
  { status: 'dispatched', labelKey: 'hub.task.dispatched', color: 'border-t-blue-400 dark:border-t-blue-600', badgeClass: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400' },
  { status: 'running', labelKey: 'hub.task.running', color: 'border-t-green-400 dark:border-t-green-600', badgeClass: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400' },
  { status: 'completed', labelKey: 'hub.task.completed', color: 'border-t-gray-300 dark:border-t-gray-600', badgeClass: 'bg-gray-100 text-gray-600 dark:bg-gray-800/40 dark:text-gray-400' },
  { status: 'failed', labelKey: 'hub.task.failed', color: 'border-t-red-400 dark:border-t-red-600', badgeClass: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400' },
];

interface TaskBoardProps {
  roomId: string;
}

export function TaskBoard({ roomId }: TaskBoardProps) {
  const { data: tasks, isLoading } = useRoomTasks(roomId);
  const createTask = useCreateTask();
  const cancelTask = useCancelTask();
  const progressMap = useHubTaskProgress((s) => s.progressMap);
  const [showCreate, setShowCreate] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newDesc, setNewDesc] = useState('');

  const handleCreate = () => {
    if (!newTitle.trim()) return;
    createTask.mutate({
      roomId,
      title: newTitle.trim(),
      description: newDesc.trim() || undefined,
    }, {
      onSuccess: () => { setNewTitle(''); setNewDesc(''); setShowCreate(false); },
    });
  };

  if (isLoading) return <FeedbackState state="loading" title={t('common.loading')} />;

  return (
    <div className="flex h-full flex-col">
      {/* Create bar */}
      {showCreate ? (
        <div className="border-b px-4 py-2.5 space-y-2 bg-muted/30">
          <div className="flex gap-2">
            <Input
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
              placeholder={t('hub.task.titlePlaceholder')}
              className="h-8 text-sm flex-1"
              autoFocus
              onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
            />
            <Button size="sm" className="h-8" onClick={handleCreate} disabled={!newTitle.trim()}>
              {t('common.confirm')}
            </Button>
            <Button variant="ghost" size="sm" className="h-8 px-2" onClick={() => setShowCreate(false)}>
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>
          <Textarea
            value={newDesc}
            onChange={(e) => setNewDesc(e.target.value)}
            placeholder={t('hub.task.descPlaceholder')}
            className="text-sm min-h-[52px] resize-none"
            rows={2}
          />
        </div>
      ) : (
        <div className="border-b px-4 py-2">
          <Button variant="ghost" size="sm" className="h-7 text-xs text-muted-foreground" onClick={() => setShowCreate(true)}>
            <Plus className="h-3 w-3 mr-1" />
            {t('hub.task.create')}
          </Button>
        </div>
      )}

      {/* Kanban columns */}
      <div className="flex flex-1 overflow-x-auto">
        {STATUS_COLUMNS.map((col) => {
          const colTasks = (tasks || []).filter((task: Task) => task.status === col.status);
          return (
            <div key={col.status} className={cn('flex min-w-[200px] flex-1 flex-col border-r border-t-2', col.color)}>
              <div className="flex items-center gap-2 px-3 py-2">
                <span className="text-xs font-medium text-muted-foreground">{t(col.labelKey)}</span>
                <Badge variant="secondary" className="h-4 px-1.5 text-[10px]">{colTasks.length}</Badge>
              </div>
              <ScrollArea className="flex-1">
                <div className="p-2 space-y-2">
                  {colTasks.map((task: Task) => (
                    <TaskCard
                      key={task.id}
                      task={task}
                      statusBadgeClass={col.badgeClass}
                      progress={progressMap[task.id]?.progress}
                      onCancel={() => cancelTask.mutate({ taskId: task.id })}
                    />
                  ))}
                </div>
              </ScrollArea>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function TaskCard({ task, statusBadgeClass, progress, onCancel }: {
  task: Task;
  statusBadgeClass: string;
  progress?: string;
  onCancel: () => void;
}) {
  return (
    <div className="group rounded-md border bg-card p-2.5 space-y-1.5 hover:shadow-sm transition-shadow">
      <div className="flex items-start justify-between gap-1">
        <span className="text-sm font-medium leading-tight">{task.title}</span>
        {(task.status === 'queued' || task.status === 'dispatched') && (
          <button
            onClick={onCancel}
            className="shrink-0 rounded p-0.5 text-muted-foreground opacity-0 group-hover:opacity-100 hover:text-destructive transition-all"
          >
            <X className="h-3 w-3" />
          </button>
        )}
      </div>
      {task.description && (
        <p className="text-xs text-muted-foreground line-clamp-2">{task.description}</p>
      )}
      <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
        {task.assigned_to && (
          <span className="truncate">{task.assigned_to.slice(0, 12)}</span>
        )}
        {task.retry_count > 0 && (
          <Badge variant="warning" className="h-3.5 px-1 text-[9px]">
            {t('hub.task.retryCount')}: {task.retry_count}
          </Badge>
        )}
      </div>
      {progress && task.status === 'running' && (
        <p className="text-[11px] text-muted-foreground truncate">{progress}</p>
      )}
    </div>
  );
}
