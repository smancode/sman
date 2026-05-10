import { create } from 'zustand';
import { useWsConnection } from './ws-connection';

interface TaskProgress {
  taskId: string;
  agentId: string;
  progress: string;
  updatedAt: number;
}

interface HubTaskProgressState {
  progressMap: Record<string, TaskProgress>;
}

let listenerRegistered = false;

export const useHubTaskProgress = create<HubTaskProgressState>(() => {
  if (!listenerRegistered) {
    listenerRegistered = true;
    useWsConnection.subscribe((state) => {
      const client = state.client;
      if (!client) return;

      const handler = (msg: unknown) => {
        const data = msg as { type: string; taskId?: string; agentId?: string; progress?: string };
        if (data.type === 'task.progress' && data.taskId) {
          useHubTaskProgress.setState((s) => ({
            progressMap: {
              ...s.progressMap,
              [data.taskId!]: {
                taskId: data.taskId!,
                agentId: data.agentId || '',
                progress: data.progress || '',
                updatedAt: Date.now(),
              },
            },
          }));
        }

        if (data.type === 'task.completed' || data.type === 'task.failed' || data.type === 'task.cancelled') {
          const taskId = (data as { taskId?: string }).taskId;
          if (taskId) {
            useHubTaskProgress.setState((s) => {
              const { [taskId]: _, ...rest } = s.progressMap;
              return { progressMap: rest };
            });
          }
        }
      };

      client.on('message', handler);
    });
  }

  return { progressMap: {} };
});
