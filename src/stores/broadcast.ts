import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';

export interface BroadcastMessage {
  id: string;
  title: string;
  body: string;
  createdAt: string;
}

interface BroadcastState {
  queue: BroadcastMessage[];
  shown: Set<string>;
  subscribe: () => () => void;
  dismiss: (id: string) => void;
}

function getWsClient() {
  return useWsConnection.getState().client;
}

export const useBroadcastStore = create<BroadcastState>((set, get) => ({
  queue: [],
  shown: new Set<string>(),

  subscribe: () => {
    const client = getWsClient();
    if (!client) return () => {};

    const handler = (msg: Record<string, unknown>) => {
      if (msg?.type === 'hub:broadcast' && msg?.data) {
        const data = msg.data as BroadcastMessage;
        const { shown } = get();
        if (!shown.has(data.id)) {
          set(state => ({
            queue: [...state.queue, data],
            shown: new Set([...state.shown, data.id]),
          }));
        }
      }
    };

    client.on('hub:broadcast', handler);
    // Trigger a broadcast fetch on subscribe (WS just connected)
    client.send({ type: 'hub:fetch' });
    return () => client.off('hub:broadcast', handler);
  },

  dismiss: (id: string) => {
    set(state => ({
      queue: state.queue.filter(m => m.id !== id),
    }));
    const client = getWsClient();
    client?.send({ type: 'hub:ack', broadcastIds: [id] });
  },
}));
