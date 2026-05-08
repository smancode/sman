import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type { WsClient } from '@/lib/ws-client';

export interface BroadcastMessage {
  id: string;
  title: string;
  body: string;
  createdAt: string;
}

const STORAGE_KEY = 'sman:broadcast:read';

function loadReadIds(): Set<string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return new Set(JSON.parse(raw) as string[]);
  } catch {}
  return new Set<string>();
}

function saveReadIds(ids: Set<string>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...ids]));
  } catch {}
}

function handleBroadcastsResponse(...args: unknown[]) {
  const msg = args[0] as Record<string, unknown>;
  if (msg?.type !== 'hub:broadcasts' || !Array.isArray(msg?.data)) return;
  const { readIds } = useBroadcastStore.getState();
  const unread = (msg.data as BroadcastMessage[]).filter(m => !readIds.has(m.id));
  if (unread.length > 0) {
    useBroadcastStore.setState({ queue: unread });
  }
}

function registerOnClient(client: WsClient) {
  client.on('hub:broadcasts', handleBroadcastsResponse);
  return () => client.off('hub:broadcasts', handleBroadcastsResponse);
}

interface BroadcastState {
  queue: BroadcastMessage[];
  readIds: Set<string>;
  subscribe: () => () => void;
  dismiss: (id: string) => void;
}

export const useBroadcastStore = create<BroadcastState>((set, get) => ({
  queue: [],
  readIds: loadReadIds(),

  subscribe: () => {
    let offClient: (() => void) | null = null;
    let prevClient: WsClient | null = null;

    const unsubscribe = useWsConnection.subscribe((state) => {
      const client = state.client;

      // Register handler when client appears or changes
      if (client && client !== prevClient) {
        if (offClient) offClient();
        prevClient = client;
        offClient = registerOnClient(client);
      }

      // On connected, query broadcasts
      if (state.status === 'connected' && client) {
        try { client.send({ type: 'hub:query' }); } catch {}
      }
    });

    // Check current state immediately
    const { client, status } = useWsConnection.getState();
    if (client) {
      prevClient = client;
      offClient = registerOnClient(client);
    }
    if (status === 'connected' && client) {
      try { client.send({ type: 'hub:query' }); } catch {}
    }

    return () => {
      unsubscribe();
      if (offClient) offClient();
    };
  },

  dismiss: (id: string) => {
    const { readIds } = get();
    const next = new Set(readIds);
    next.add(id);
    saveReadIds(next);
    set(state => ({
      queue: state.queue.filter(m => m.id !== id),
      readIds: next,
    }));
  },
}));
