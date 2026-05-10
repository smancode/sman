import { queryClient } from './query-client';
import { useWsConnection } from '@/stores/ws-connection';

let registered = false;

// ── Debounce map: per-prefix, 100ms window ──
// Borrowed from Multica's useRealtimeSync pattern.
// Multiple WS events within 100ms for the same prefix (room./agent./task.)
// get batched into a single invalidateQueries call.
const pendingFlush = new Map<string, ReturnType<typeof setTimeout>>();
const DEBOUNCE_MS = 100;

function scheduleFlush(prefix: string, keys: Array<readonly unknown[]>): void {
  if (pendingFlush.has(prefix)) return;

  const timer = setTimeout(() => {
    pendingFlush.delete(prefix);
    for (const key of keys) {
      queryClient.invalidateQueries({ queryKey: key });
    }
  }, DEBOUNCE_MS);

  pendingFlush.set(prefix, timer);
}

function flushAll(): void {
  for (const [prefix, timer] of pendingFlush) {
    clearTimeout(timer);
    pendingFlush.delete(prefix);
  }

  // Bulk invalidate all hub queries on reconnect
  queryClient.invalidateQueries({ queryKey: ['hub'] });
}

// ── Prefix → query keys mapping ──

function getKeysForPrefix(prefix: string, data: { roomId?: string; taskId?: string }): Array<readonly unknown[]> {
  switch (prefix) {
    case 'room':
      return [
        ['hub', 'rooms'],
        ...(data.roomId ? [['hub', 'rooms', data.roomId] as const] : []),
      ];
    case 'agent':
      return [
        ...(data.roomId ? [['hub', 'rooms', data.roomId, 'agents'] as const] : []),
      ];
    case 'task':
      return [
        ...(data.roomId ? [['hub', 'rooms', data.roomId, 'tasks'] as const] : []),
        ...(data.taskId ? [['hub', 'tasks', data.taskId] as const] : []),
      ];
    default:
      return [];
  }
}

export function registerHubEventHandlers(): void {
  if (registered) return;
  registered = true;

  let lastStatus = '';

  useWsConnection.subscribe((state) => {
    const client = state.client;

    // On reconnect: bulk invalidate all hub data
    if (state.status === 'connected' && lastStatus === 'connecting') {
      flushAll();
    }
    lastStatus = state.status;

    if (!client) return;

    const handler = (msg: unknown) => {
      const data = msg as { type: string; roomId?: string; taskId?: string };
      const type = data.type;

      if (!type.startsWith('room.') && !type.startsWith('agent.') && !type.startsWith('task.')) return;

      // Extract prefix (e.g., "task.claimed" -> "task")
      const prefix = type.split('.')[0];
      const keys = getKeysForPrefix(prefix, data);
      if (keys.length > 0) {
        scheduleFlush(prefix, keys);
      }
    };

    client.on('message', handler);
  });
}
