// src/features/stardom/TaskPanel.tsx
import { useStardomStore } from '@/stores/stardom';
import { TaskCard } from './TaskCard';
import { ListTodo } from 'lucide-react';
import { t } from '@/locales';


export function TaskPanel() {
  const { tasks, setActiveChat, cancelTask } = useStardomStore();

  const activeTasks = tasks.filter(t =>
    t.status === 'searching' || t.status === 'offered' || t.status === 'matched' || t.status === 'chatting'
  );
  const pastTasks = tasks.filter(t => t.status === 'completed' || t.status === 'timeout' || t.status === 'cancelled');

  return (
    <div>
      <div className="flex items-center gap-2 mb-3">
        <ListTodo className="h-4 w-4 text-muted-foreground" />
        <h3 className="font-medium text-sm">{t('stardom.task.list')}</h3>
      </div>

      {tasks.length === 0 ? (
        <p className="text-sm text-muted-foreground py-8 text-center">
          {t('stardom.task.noTasks')}<br />
          <span className="text-xs">{t('stardom.task.autoHint')}</span>
        </p>
      ) : (
        <>
          {activeTasks.length > 0 && (
            <div className="mb-4">
              <div className="text-xs text-muted-foreground mb-2">{t('stardom.task.active')} ({activeTasks.length})</div>
              <div className="space-y-2">
                {activeTasks.map(task => (
                  <TaskCard key={task.taskId} task={task} onClick={setActiveChat} onCancel={cancelTask} />
                ))}
              </div>
            </div>
          )}

          {pastTasks.length > 0 && (
            <div>
              <div className="text-xs text-muted-foreground mb-2">{t('stardom.task.history')} ({pastTasks.length})</div>
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
