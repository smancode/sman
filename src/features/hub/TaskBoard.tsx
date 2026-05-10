import { useState } from 'react';
import { t } from '@/locales';
import { useRoomTasks, useCreateTask, useCancelTask } from '@/queries/use-hub';
import { useHubTaskProgress } from '@/stores/hub-task-progress';
import type { Task, TaskStatusType } from '@/schemas/hub';

const STATUS_COLUMNS: { status: TaskStatusType; labelKey: string; color: string }[] = [
  { status: 'queued', labelKey: 'hub.task.queued', color: 'bg-yellow-100 dark:bg-yellow-900/30' },
  { status: 'dispatched', labelKey: 'hub.task.dispatched', color: 'bg-blue-100 dark:bg-blue-900/30' },
  { status: 'running', labelKey: 'hub.task.running', color: 'bg-green-100 dark:bg-green-900/30' },
  { status: 'completed', labelKey: 'hub.task.completed', color: 'bg-gray-100 dark:bg-gray-800/30' },
  { status: 'failed', labelKey: 'hub.task.failed', color: 'bg-red-100 dark:bg-red-900/30' },
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

  if (isLoading) return <div className="p-4 text-muted-foreground">{t('common.loading')}</div>;

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2 border-b px-4 py-2">
        <button
          onClick={() => setShowCreate(!showCreate)}
          className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground hover:bg-primary/90"
        >
          {t('hub.task.create')}
        </button>
      </div>

      {showCreate && (
        <div className="border-b px-4 py-2 space-y-2">
          <input
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
            placeholder={t('hub.task.titlePlaceholder')}
            className="w-full rounded-md border px-2 py-1.5 text-sm"
            autoFocus
          />
          <textarea
            value={newDesc}
            onChange={(e) => setNewDesc(e.target.value)}
            placeholder={t('hub.task.descPlaceholder')}
            className="w-full rounded-md border px-2 py-1.5 text-sm"
            rows={2}
          />
          <button onClick={handleCreate} disabled={!newTitle.trim()} className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground disabled:opacity-50">
            {t('common.confirm')}
          </button>
        </div>
      )}

      <div className="flex flex-1 overflow-x-auto">
        {STATUS_COLUMNS.map((col) => {
          const colTasks = (tasks || []).filter((task: Task) => task.status === col.status);
          return (
            <div key={col.status} className="flex min-w-[220px] flex-1 flex-col border-r">
              <div className={`px-3 py-2 text-sm font-medium ${col.color}`}>
                {t(col.labelKey)} ({colTasks.length})
              </div>
              <div className="flex-1 overflow-auto p-2 space-y-2">
                {colTasks.map((task: Task) => (
                  <TaskCard
                    key={task.id}
                    task={task}
                    progress={progressMap[task.id]?.progress}
                    onCancel={() => cancelTask.mutate({ taskId: task.id })}
                  />
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function TaskCard({ task, progress, onCancel }: { task: Task; progress?: string; onCancel: () => void }) {
  return (
    <div className="rounded-lg border bg-card p-3 space-y-1">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium">{task.title}</span>
        {(task.status === 'queued' || task.status === 'dispatched') && (
          <button onClick={onCancel} className="text-xs text-muted-foreground hover:text-destructive">
            {t('common.cancel')}
          </button>
        )}
      </div>
      {task.description && (
        <p className="text-xs text-muted-foreground line-clamp-2">{task.description}</p>
      )}
      {task.assigned_to && (
        <div className="text-xs text-muted-foreground">
          {t('hub.task.assignee')}: {task.assigned_to.slice(0, 12)}
        </div>
      )}
      {progress && task.status === 'running' && (
        <p className="text-xs text-muted-foreground truncate">{progress}</p>
      )}
      {task.retry_count > 0 && (
        <div className="text-xs text-yellow-600">{t('hub.task.retryCount')}: {task.retry_count}</div>
      )}
    </div>
  );
}
