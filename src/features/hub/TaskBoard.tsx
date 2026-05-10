import { useState } from 'react';
import { t } from '@/locales';
import { FeedbackState } from '@/components/common/FeedbackState';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { useRoomTasks, useCreateTask, useCancelTask, useStopTask } from '@/queries/use-hub';
import type { CreateTaskParams } from '@/queries/use-hub';
import { useHubTaskProgress } from '@/stores/hub-task-progress';
import type { Task, TaskStatusType, Subtask } from '@/schemas/hub';
import { Plus, X, ChevronDown, ChevronUp, StopCircle } from 'lucide-react';
import { cn } from '@/lib/utils';

const STATUS_COLUMNS: { status: TaskStatusType; labelKey: string; color: string; badgeClass: string }[] = [
  { status: 'evaluating', labelKey: 'hub.task.evaluating', color: 'border-t-purple-400 dark:border-t-purple-600', badgeClass: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400' },
  { status: 'confirmed', labelKey: 'hub.task.confirmed', color: 'border-t-amber-400 dark:border-t-amber-600', badgeClass: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400' },
  { status: 'dispatched', labelKey: 'hub.task.dispatched', color: 'border-t-blue-400 dark:border-t-blue-600', badgeClass: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400' },
  { status: 'running', labelKey: 'hub.task.running', color: 'border-t-green-400 dark:border-t-green-600', badgeClass: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400' },
  { status: 'stopping', labelKey: 'hub.task.stopping', color: 'border-t-orange-400 dark:border-t-orange-600', badgeClass: 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400' },
  { status: 'completed', labelKey: 'hub.task.completed', color: 'border-t-gray-300 dark:border-t-gray-600', badgeClass: 'bg-gray-100 text-gray-600 dark:bg-gray-800/40 dark:text-gray-400' },
  { status: 'failed', labelKey: 'hub.task.failed', color: 'border-t-red-400 dark:border-t-red-600', badgeClass: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400' },
];

interface TaskBoardProps {
  roomId: string;
  onSelectTask?: (taskId: string) => void;
}

export function TaskBoard({ roomId, onSelectTask }: TaskBoardProps) {
  const { data: tasks, isLoading } = useRoomTasks(roomId);
  const createTask = useCreateTask();
  const cancelTask = useCancelTask();
  const stopTask = useStopTask();
  const progressMap = useHubTaskProgress((s) => s.progressMap);
  const [showCreate, setShowCreate] = useState(false);

  const handleCreate = (params: CreateTaskParams) => {
    createTask.mutate(params, {
      onSuccess: () => setShowCreate(false),
    });
  };

  if (isLoading) return <FeedbackState state="loading" title={t('common.loading')} />;

  return (
    <div className="flex h-full flex-col">
      {showCreate ? (
        <TaskCreateForm
          roomId={roomId}
          onSubmit={handleCreate}
          onCancel={() => setShowCreate(false)}
        />
      ) : (
        <div className="border-b px-4 py-2">
          <Button variant="ghost" size="sm" className="h-7 text-xs text-muted-foreground" onClick={() => setShowCreate(true)}>
            <Plus className="h-3 w-3 mr-1" />
            {t('hub.task.create')}
          </Button>
        </div>
      )}

      <div className="flex flex-1 overflow-x-auto">
        {STATUS_COLUMNS.map((col) => {
          const colTasks = (tasks || []).filter((task: Task) => task.status === col.status);
          return (
            <div key={col.status} className={cn('flex min-w-[180px] flex-1 flex-col border-r border-t-2', col.color)}>
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
                      onStop={() => stopTask.mutate({ taskId: task.id })}
                      onClick={() => onSelectTask?.(task.id)}
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

function TaskCreateForm({ roomId, onSubmit, onCancel }: {
  roomId: string;
  onSubmit: (params: CreateTaskParams) => void;
  onCancel: () => void;
}) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [acceptanceCriteria, setAcceptanceCriteria] = useState('');
  const [gitBranch, setGitBranch] = useState('');
  const [subtasks, setSubtasks] = useState<{ id: string; name: string; description: string }[]>([]);
  const [showAdvanced, setShowAdvanced] = useState(false);

  const addSubtask = () => {
    setSubtasks([...subtasks, { id: crypto.randomUUID().slice(0, 8), name: '', description: '' }]);
  };

  const removeSubtask = (idx: number) => {
    setSubtasks(subtasks.filter((_, i) => i !== idx));
  };

  const updateSubtask = (idx: number, field: 'name' | 'description', value: string) => {
    const updated = [...subtasks];
    updated[idx] = { ...updated[idx], [field]: value };
    setSubtasks(updated);
  };

  const handleSubmit = () => {
    if (!title.trim()) return;
    onSubmit({
      roomId,
      title: title.trim(),
      description: description.trim() || undefined,
      acceptanceCriteria: acceptanceCriteria.trim() || undefined,
      gitBranch: gitBranch.trim() || undefined,
      subtasks: subtasks.filter(st => st.name.trim()).length > 0
        ? subtasks.filter(st => st.name.trim()).map(st => ({ id: st.id, name: st.name.trim(), description: st.description.trim() || undefined }))
        : undefined,
    });
  };

  return (
    <div className="border-b px-4 py-2.5 space-y-2 bg-muted/30">
      <div className="flex gap-2">
        <Input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder={t('hub.task.titlePlaceholder')}
          className="h-8 text-sm flex-1"
          autoFocus
          onKeyDown={(e) => e.key === 'Enter' && handleSubmit()}
        />
        <Button size="sm" className="h-8" onClick={handleSubmit} disabled={!title.trim()}>
          {t('common.confirm')}
        </Button>
        <Button variant="ghost" size="sm" className="h-8 px-2" onClick={onCancel}>
          <X className="h-3.5 w-3.5" />
        </Button>
      </div>
      <Textarea
        value={description}
        onChange={(e) => setDescription(e.target.value)}
        placeholder={t('hub.task.descPlaceholder')}
        className="text-sm min-h-[40px] resize-none"
        rows={2}
      />

      <button
        onClick={() => setShowAdvanced(!showAdvanced)}
        className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
      >
        {showAdvanced ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
        {t('hub.task.advanced')}
      </button>

      {showAdvanced && (
        <div className="space-y-2">
          <Textarea
            value={acceptanceCriteria}
            onChange={(e) => setAcceptanceCriteria(e.target.value)}
            placeholder={t('hub.task.acceptancePlaceholder')}
            className="text-sm min-h-[40px] resize-none"
            rows={2}
          />
          <Input
            value={gitBranch}
            onChange={(e) => setGitBranch(e.target.value)}
            placeholder={t('hub.task.gitBranchPlaceholder')}
            className="h-7 text-xs"
          />

          <div className="space-y-1">
            <div className="flex items-center justify-between">
              <span className="text-xs text-muted-foreground">{t('hub.task.subtasks')}</span>
              <Button variant="ghost" size="sm" className="h-5 px-1.5 text-[11px]" onClick={addSubtask}>
                <Plus className="h-2.5 w-2.5 mr-0.5" />
                {t('hub.task.addSubtask')}
              </Button>
            </div>
            {subtasks.map((st, idx) => (
              <div key={st.id} className="flex gap-1.5">
                <Input
                  value={st.name}
                  onChange={(e) => updateSubtask(idx, 'name', e.target.value)}
                  placeholder={t('hub.task.subtaskName')}
                  className="h-6 text-xs flex-1"
                />
                <Button variant="ghost" size="sm" className="h-6 px-1" onClick={() => removeSubtask(idx)}>
                  <X className="h-2.5 w-2.5" />
                </Button>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function TaskCard({ task, statusBadgeClass, progress, onCancel, onStop, onClick }: {
  task: Task;
  statusBadgeClass: string;
  progress?: string;
  onCancel: () => void;
  onStop: () => void;
  onClick?: () => void;
}) {
  const subtaskCount = parseSubtaskCount(task.subtasks);

  return (
    <div
      onClick={onClick}
      className={cn(
        'group rounded-md border bg-card p-2.5 space-y-1.5 hover:shadow-sm transition-shadow',
        onClick && 'cursor-pointer',
      )}
    >
      <div className="flex items-start justify-between gap-1">
        <span className="text-sm font-medium leading-tight">{task.title}</span>
        <div className="flex items-center shrink-0">
          {canStop(task.status) && (
            <button
              onClick={(e) => { e.stopPropagation(); onStop(); }}
              className="rounded p-0.5 text-muted-foreground opacity-0 group-hover:opacity-100 hover:text-orange-500 transition-all"
              title={t('hub.task.stop')}
            >
              <StopCircle className="h-3.5 w-3.5" />
            </button>
          )}
          {canCancel(task.status) && (
            <button
              onClick={(e) => { e.stopPropagation(); onCancel(); }}
              className="rounded p-0.5 text-muted-foreground opacity-0 group-hover:opacity-100 hover:text-destructive transition-all"
            >
              <X className="h-3 w-3" />
            </button>
          )}
        </div>
      </div>
      {task.description && (
        <p className="text-xs text-muted-foreground line-clamp-2">{task.description}</p>
      )}
      <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
        {task.git_branch && (
          <span className="font-mono truncate max-w-[100px]">{task.git_branch}</span>
        )}
        {subtaskCount > 0 && (
          <Badge variant="secondary" className="h-3.5 px-1 text-[9px]">
            {subtaskCount} {t('hub.task.subtaskUnit')}
          </Badge>
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

function canCancel(status: string): boolean {
  return ['evaluating', 'confirmed', 'draft', 'rejected', 'queued'].includes(status);
}

function canStop(status: string): boolean {
  return ['dispatched', 'running'].includes(status);
}

function parseSubtaskCount(subtasksJson: string): number {
  try {
    const arr = JSON.parse(subtasksJson);
    return Array.isArray(arr) ? arr.length : 0;
  } catch {
    return 0;
  }
}
