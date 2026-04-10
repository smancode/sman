// src/stores/bazaar.ts
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type {
  BazaarTask,
  BazaarConnectionStatus,
  BazaarChatMessage,
  BazaarAgentInfo,
  BazaarMode,
  BazaarDigest,
} from '@/types/bazaar';

function getWsClient() {
  return useWsConnection.getState().client;
}

interface BazaarState {
  connection: BazaarConnectionStatus;
  tasks: BazaarTask[];
  onlineAgents: BazaarAgentInfo[];
  activeChat: {
    taskId: string;
    messages: BazaarChatMessage[];
  } | null;
  digest: BazaarDigest | null;
  loading: boolean;
  error: string | null;

  // Actions
  fetchTasks: () => void;
  fetchOnlineAgents: () => void;
  cancelTask: (taskId: string) => void;
  setActiveChat: (taskId: string) => void;
  clearActiveChat: () => void;
  setMode: (mode: BazaarMode) => void;
  clearError: () => void;
}

let set: (partial: Partial<BazaarState> | ((state: BazaarState) => Partial<BazaarState>)) => void;

export const useBazaarStore = create<BazaarState>((storeSet) => {
  set = storeSet;

  return {
    connection: {
      connected: false,
      activeSlots: 0,
      maxSlots: 3,
    },
    tasks: [],
    onlineAgents: [],
    activeChat: null,
    digest: null,
    loading: false,
    error: null,

    fetchTasks: () => {
      const client = getWsClient();
      if (!client) return;
      set({ loading: true });
      client.send({ type: 'bazaar.task.list' });
    },

    fetchOnlineAgents: () => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'bazaar.agent.list' });
    },

    cancelTask: (taskId: string) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'bazaar.task.cancel', payload: { taskId } });
    },

    setActiveChat: (taskId: string) => {
      set({ activeChat: { taskId, messages: [] } });
    },

    clearActiveChat: () => {
      set({ activeChat: null });
    },

    setMode: (mode: BazaarMode) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'bazaar.config.update', payload: { mode } });
    },

    clearError: () => set({ error: null }),
  };
});

// ── WebSocket push listener ──

let pushListenerRegistered = false;

function registerPushListeners() {
  if (pushListenerRegistered) return;
  pushListenerRegistered = true;

  const handle = (msg: Record<string, unknown>) => {
    if (!msg.type?.toString().startsWith('bazaar.')) return;

    const type = msg.type as string;

    if (type === 'bazaar.status') {
      const event = msg.event as string;
      if (event === 'connected') {
        set((s) => ({
          connection: {
            ...s.connection,
            connected: true,
            agentId: msg.agentId as string,
            agentName: msg.agentName as string,
            reputation: (msg.reputation as number) ?? 0,
            activeSlots: (msg.activeSlots as number) ?? 0,
            maxSlots: (msg.maxSlots as number) ?? 3,
          },
        }));
      } else if (event === 'disconnected') {
        set((s) => ({ connection: { ...s.connection, connected: false } }));
      }
    } else if (type === 'bazaar.task.list.update') {
      set({ tasks: msg.tasks as BazaarTask[], loading: false });
    } else if (type === 'bazaar.agent.list.update') {
      set({ onlineAgents: msg.agents as BazaarAgentInfo[] });
    } else if (type === 'bazaar.task.chat.delta') {
      set((s) => {
        if (!s.activeChat || s.activeChat.taskId !== msg.taskId) return s;
        return {
          activeChat: {
            ...s.activeChat,
            messages: [...s.activeChat.messages, {
              taskId: msg.taskId as string,
              from: msg.from as string,
              text: msg.text as string,
              timestamp: new Date().toISOString(),
            }],
          },
        };
      });
    } else if (type === 'bazaar.notify') {
      // 协作请求通知 — 不在 store 中处理，由组件直接监听
    } else if (type === 'bazaar.digest') {
      set({ digest: msg as unknown as BazaarDigest });
    }
  };

  const unsub = useWsConnection.subscribe((state, prev) => {
    if (state.client && state.client !== prev.client) {
      state.client.on('message', handle as (...a: unknown[]) => void);
    }
  });

  const currentClient = useWsConnection.getState().client;
  if (currentClient) {
    currentClient.on('message', handle as (...a: unknown[]) => void);
  }
}

registerPushListeners();
