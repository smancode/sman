// src/features/bazaar/TaskPanel.tsx
import { useBazaarStore } from '@/stores/bazaar';
import { TaskCard } from './TaskCard';
import { ListTodo } from 'lucide-react';

export function TaskPanel() {
  const { tasks, setActiveChat, cancelTask } = useBazaarStore();

  const activeTasks = tasks.filter(t => t.status === 'offered' || t.status === 'chatting');
  const pastTasks = tasks.filter(t => t.status === 'completed' || t.status === 'timeout' || t.status === 'cancelled');

  return (
    <div>
      <div className="flex items-center gap-2 mb-3">
        <ListTodo className="h-4 w-4 text-muted-foreground" />
        <h3 className="font-medium text-sm">任务列表</h3>
      </div>

      {tasks.length === 0 ? (
        <p className="text-sm text-muted-foreground py-8 text-center">
          暂无协作任务<br />
          <span className="text-xs">Agent 会自动搜索能力并协助你</span>
        </p>
      ) : (
        <>
          {activeTasks.length > 0 && (
            <div className="mb-4">
              <div className="text-xs text-muted-foreground mb-2">进行中 ({activeTasks.length})</div>
              <div className="space-y-2">
                {activeTasks.map(task => (
                  <TaskCard key={task.taskId} task={task} onClick={setActiveChat} onCancel={cancelTask} />
                ))}
              </div>
            </div>
          )}

          {pastTasks.length > 0 && (
            <div>
              <div className="text-xs text-muted-foreground mb-2">历史 ({pastTasks.length})</div>
              <div className="space-y-2">
                {pastTasks.map(task => (
                  <TaskCard key={task.taskId} task={task} onClick={setActiveChat} onCancel={cancelTask} />
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
