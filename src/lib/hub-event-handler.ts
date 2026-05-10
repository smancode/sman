import { queryClient } from './query-client';
import { useWsConnection } from '@/stores/ws-connection';

let registered = false;

export function registerHubEventHandlers(): void {
  if (registered) return;
  registered = true;

  useWsConnection.subscribe((state) => {
    const client = state.client;
    if (!client) return;

    const handler = (msg: unknown) => {
      const data = msg as { type: string; roomId?: string; taskId?: string };
      const type = data.type;

      if (!type.startsWith('room.') && !type.startsWith('agent.') && !type.startsWith('task.')) return;

      if (type.startsWith('room.')) {
        queryClient.invalidateQueries({ queryKey: ['hub', 'rooms'] });
        if (data.roomId) {
          queryClient.invalidateQueries({ queryKey: ['hub', 'rooms', data.roomId] });
        }
      }

      if (type.startsWith('agent.')) {
        if (data.roomId) {
          queryClient.invalidateQueries({ queryKey: ['hub', 'rooms', data.roomId, 'agents'] });
        }
      }

      if (type.startsWith('task.')) {
        if (data.roomId) {
          queryClient.invalidateQueries({ queryKey: ['hub', 'rooms', data.roomId, 'tasks'] });
        }
        if (data.taskId) {
          queryClient.invalidateQueries({ queryKey: ['hub', 'tasks', data.taskId] });
        }
      }
    };

    client.on('message', handler);
  });
}
