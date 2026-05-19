import { memo, useState } from 'react';
import {
  ChevronDown,
  ChevronRight,
  Layers,
  Plus,
  Trash,
  FolderOpen,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { t, useLocale } from '@/locales';
import type { GroupTask, GroupSubtask } from '@/schemas/group';

interface GroupItemProps {
  name: string;
  workspaceCount: number;
  workspaceNames: string[];
  tasks: GroupTask[];
  subtasks: Record<string, GroupSubtask[]>; // taskId -> subtasks
  expanded: boolean;
  onToggle: () => void;
  onDelete: () => void;
  onNewTask: () => void;
  onTaskSelect: (taskId: string) => void;
  onTaskDelete: (taskId: string) => void;
  onSubtaskSelect: (sessionId: string) => void;
  activeTaskId: string | null;
  activeSessionId: string | null;
}

const TaskItem = memo(function TaskItem({
  task,
  subtasks,
  isActive,
  onSelect,
  onDelete,
  onSubtaskSelect,
  activeSessionId,
}: {
  task: GroupTask;
  subtasks: GroupSubtask[];
  isActive: boolean;
  onSelect: () => void;
  onDelete: () => void;
  onSubtaskSelect: (sessionId: string) => void;
  activeSessionId: string | null;
}) {
  const [hovered, setHovered] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [expanded, setExpanded] = useState(subtasks.length > 0);

  const handleDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (deleting) return;
    if (!confirm(t('task.deleteConfirm'))) return;
    setDeleting(true);
    try {
      onDelete();
    } catch (err) {
      console.error('[TaskItem] Failed to delete task:', err);
      setDeleting(false);
    }
  };

  return (
    <div>
      <div
        className={cn(
          'flex items-center gap-2 pl-3 pr-1 py-2 rounded-lg cursor-pointer text-[13px] transition-all duration-200',
          isActive
            ? 'bg-[hsl(var(--muted))] text-foreground font-semibold'
            : 'hover:bg-[hsl(var(--muted))] text-foreground/60 hover:text-foreground',
        )}
        onClick={() => { onSelect(); if (subtasks.length > 0) setExpanded(!expanded); }}
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
      >
        <Layers className="h-3.5 w-3.5 shrink-0 text-muted-foreground/60" />
        <span className="truncate flex-1 min-w-0">{task.title}</span>
        {subtasks.length > 0 && (
          <span className="text-[10px] text-muted-foreground/50 bg-muted/50 rounded px-1">
            {subtasks.length}
          </span>
        )}
        <div className={cn('flex items-center gap-0.5 shrink-0', !hovered && !deleting && 'hidden')}>
          <button
            className={cn(
              'shrink-0 p-0.5 rounded transition-all',
              hovered || deleting ? 'opacity-100' : 'opacity-0 pointer-events-none',
              deleting && 'opacity-40 cursor-not-allowed',
              'text-muted-foreground hover:text-destructive',
            )}
            onClick={handleDelete}
            disabled={deleting}
            title={t('task.delete')}
          >
            <Trash className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>
      {/* Subtasks */}
      {expanded && subtasks.map((sub) => {
        const wsName = sub.workspace.split(/[/\\]/).pop() || sub.workspace;
        const isSubActive = sub.sessionId === activeSessionId;
        return (
          <div
            key={sub.id}
            className={cn(
              'flex items-center gap-2 pl-7 pr-1 py-1.5 rounded-lg cursor-pointer text-[12px] transition-all duration-200',
              isSubActive
                ? 'bg-[hsl(var(--muted))] text-foreground font-medium'
                : 'hover:bg-[hsl(var(--muted))] text-foreground/40 hover:text-foreground/60',
            )}
            onClick={() => onSubtaskSelect(sub.sessionId)}
          >
            <FolderOpen className="h-3 w-3 shrink-0 text-muted-foreground/40" />
            <span className="truncate min-w-0">{wsName}: {sub.title}</span>
          </div>
        );
      })}
    </div>
  );
}, (prev, next) => {
  return prev.task.id === next.task.id
    && prev.isActive === next.isActive
    && prev.task.status === next.task.status
    && prev.subtasks === next.subtasks
    && prev.activeSessionId === next.activeSessionId;
});

export const GroupItem = memo(function GroupItem({
  name,
  workspaceCount,
  workspaceNames,
  tasks,
  subtasks,
  expanded,
  onToggle,
  onDelete,
  onNewTask,
  onTaskSelect,
  onTaskDelete,
  onSubtaskSelect,
  activeTaskId,
  activeSessionId,
}: GroupItemProps) {
  useLocale();
  const [hovered, setHovered] = useState(false);
  const taskCount = tasks.length;
  const hasActiveTask = tasks.some((task) => task.id === activeTaskId);

  const handleDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!confirm(t('group.deleteConfirm'))) return;
    onDelete();
  };

  const handleNewTask = (e: React.MouseEvent) => {
    e.stopPropagation();
    onNewTask();
  };

  return (
    <div className="mb-0.5">
      {/* Group header */}
      <div
        className={cn(
          'flex items-center gap-1.5 px-2 py-2 rounded-lg cursor-pointer transition-all duration-200 relative',
          expanded || hasActiveTask
            ? 'text-foreground'
            : 'text-foreground/60 hover:text-foreground/80',
          'hover:bg-[hsl(var(--muted))]/50',
        )}
        onClick={onToggle}
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
      >
        <div className="flex items-center justify-center w-4 h-4">
          {expanded ? (
            <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5 text-muted-foreground" />
          )}
        </div>
        <Layers
          className={cn(
            'h-4 w-4 shrink-0',
            expanded || hasActiveTask ? 'text-foreground/70' : 'text-muted-foreground',
          )}
        />
        <span className="text-[13px] font-medium truncate flex-1">{name}</span>
        <span
          className="text-[10px] text-muted-foreground/50 bg-muted/50 rounded px-1"
          title={workspaceNames.join(', ')}
        >
          {workspaceCount}
        </span>
        <span className="text-[11px] text-muted-foreground/60 tabular-nums">{taskCount}</span>

        {hovered && (
          <div className="flex items-center gap-0.5 absolute right-2 bg-background/95 backdrop-blur-sm rounded-lg pl-2 shadow-sm">
            <Button
              variant="ghost"
              size="icon"
              className="h-5 w-5 p-0.5 text-muted-foreground hover:text-primary"
              onClick={handleNewTask}
              title={t('task.new')}
            >
              <Plus className="h-3 w-3" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="h-5 w-5 p-0.5 text-muted-foreground hover:text-destructive"
              onClick={handleDelete}
              title={t('group.delete')}
            >
              <Trash className="h-3 w-3" />
            </Button>
          </div>
        )}
      </div>

      {/* Tasks with subtasks */}
      {expanded && (
        <div className="ml-6 mt-0.5 space-y-0.5">
          {tasks.map((task) => (
            <TaskItem
              key={task.id}
              task={task}
              subtasks={subtasks[task.id] || []}
              isActive={task.id === activeTaskId}
              onSelect={() => onTaskSelect(task.id)}
              onDelete={() => onTaskDelete(task.id)}
              onSubtaskSelect={onSubtaskSelect}
              activeSessionId={activeSessionId}
            />
          ))}
        </div>
      )}
    </div>
  );
}, (prev, next) => {
  return prev.name === next.name
    && prev.workspaceCount === next.workspaceCount
    && prev.expanded === next.expanded
    && prev.activeTaskId === next.activeTaskId
    && prev.activeSessionId === next.activeSessionId
    && prev.tasks === next.tasks
    && prev.subtasks === next.subtasks;
});
