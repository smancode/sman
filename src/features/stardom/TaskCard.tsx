// src/features/stardom/TaskCard.tsx
import type { StardomTask } from '@/types/stardom';
import { Button } from '@/components/ui/button';
import { ArrowRight, ArrowLeft, Clock, Star } from 'lucide-react';

interface TaskCardProps {
  task: StardomTask;
  onClick: (taskId: string) => void;
  onCancel: (taskId: string) => void;
}

const statusLabels: Record<string, { label: string; color: string }> = {
  searching: { label: '搜索中', color: 'bg-yellow-100 text-yellow-700' },
  offered: { label: '待接受', color: 'bg-blue-100 text-blue-700' },
  matched: { label: '已匹配', color: 'bg-indigo-100 text-indigo-700' },
  chatting: { label: '协作中', color: 'bg-green-100 text-green-700' },
  completed: { label: '已完成', color: 'bg-gray-100 text-gray-500' },
  timeout: { label: '超时', color: 'bg-red-100 text-red-700' },
  cancelled: { label: '已取消', color: 'bg-gray-100 text-gray-500' },
};

export function TaskCard({ task, onClick, onCancel }: TaskCardProps) {
  const status = statusLabels[task.status] ?? { label: task.status, color: 'bg-gray-100 text-gray-500' };
  const isActive = task.status === 'searching' || task.status === 'offered' || task.status === 'matched' || task.status === 'chatting';

  return (
    <div
      className={`p-3 rounded-lg border ${isActive ? 'border-primary/30 bg-primary/5' : 'border-border'} cursor-pointer hover:bg-muted/50 transition-colors`}
      onClick={() => onClick(task.taskId)}
    >
      <div className="flex items-center justify-between mb-1.5">
        <div className="flex items-center gap-1.5">
          {task.direction === 'outgoing' ? (
            <ArrowRight className="h-3.5 w-3.5 text-blue-500" />
          ) : (
            <ArrowLeft className="h-3.5 w-3.5 text-green-500" />
          )}
          <span className="text-xs text-muted-foreground">
            {task.direction === 'outgoing' ? '我求助' : '帮我'}
          </span>
        </div>
        <span className={`text-xs px-2 py-0.5 rounded-full ${status.color}`}>
          {status.label}
        </span>
      </div>

      <p className="text-sm mb-1.5 line-clamp-2">{task.question}</p>

      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <span>{task.direction === 'outgoing' ? task.helperName : task.requesterName}</span>
          <span className="flex items-center gap-0.5"><Clock className="h-3 w-3" />{new Date(task.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}</span>
          {task.rating && <span className="flex items-center gap-0.5"><Star className="h-3 w-3" />{task.rating}</span>}
        </div>

        {isActive && (
          <Button
            variant="ghost"
            size="sm"
            className="h-6 text-xs text-red-500 hover:text-red-600"
            onClick={(e) => { e.stopPropagation(); onCancel(task.taskId); }}
          >
            终止
          </Button>
        )}
      </div>
    </div>
  );
}
