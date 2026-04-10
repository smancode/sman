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
  BazaarNotification,
  BazaarTaskChat,
} from '@/types/bazaar';

function getWsClient() {
  return useWsConnection.getState().client;
}

// notify 模式倒计时：30 秒
const NOTIFY_COUNTDOWN_MS = 30_000;

// 多任务对话存储：key = taskId
const taskChatMap = new Map<string, BazaarChatMessage[]>();

interface BazaarState {
  connection: BazaarConnectionStatus;
  tasks: BazaarTask[];
  onlineAgents: BazaarAgentInfo[];
  activeChat: BazaarTaskChat | null;
  notifications: BazaarNotification[];
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
  acceptTask: (taskId: string) => void;
  rejectTask: (taskId: string) => void;
  dismissNotification: (notificationId: string) => void;
  getTaskChat: (taskId: string) => BazaarChatMessage[];
}

let set: (partial: Partial<BazaarState> | ((state: BazaarState) => Partial<BazaarState>)) => void;

// 存储 notify 模式的倒计时 timer，key = notificationId
const countdownTimers = new Map<string, ReturnType<typeof setTimeout>>();

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
    notifications: [],
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
      const existingMessages = taskChatMap.get(taskId) ?? [];
      set({ activeChat: { taskId, messages: existingMessages } });
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

    acceptTask: (taskId: string) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'bazaar.task.accept', payload: { taskId } });
      set((s) => ({
        notifications: s.notifications.filter((n) => n.taskId !== taskId),
      }));
    },

    rejectTask: (taskId: string) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'bazaar.task.reject', payload: { taskId } });
      set((s) => ({
        notifications: s.notifications.filter((n) => n.taskId !== taskId),
      }));
    },

    dismissNotification: (notificationId: string) => {
      const timer = countdownTimers.get(notificationId);
      if (timer) {
        clearTimeout(timer);
        countdownTimers.delete(notificationId);
      }
      set((s) => ({
        notifications: s.notifications.filter((n) => n.notificationId !== notificationId),
      }));
    },

    getTaskChat: (taskId: string) => {
      return taskChatMap.get(taskId) ?? [];
    },
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
      const taskId = msg.taskId as string;
      const from = msg.from as string;
      const text = msg.text as string;
      const chatMsg: BazaarChatMessage = {
        taskId,
        from,
        text,
        timestamp: new Date().toISOString(),
      };

      // 存入 taskChatMap（多任务对话缓存）
      const existing = taskChatMap.get(taskId) ?? [];
      taskChatMap.set(taskId, [...existing, chatMsg]);

      // 如果是当前 activeChat 的任务，同步更新 activeChat
      set((s) => {
        if (!s.activeChat || s.activeChat.taskId !== taskId) return s;
        return {
          activeChat: {
            ...s.activeChat,
            messages: [...s.activeChat.messages, chatMsg],
          },
        };
      });
    } else if (type === 'bazaar.notify') {
      const mode = msg.mode as 'auto' | 'notify' | 'manual';
      const notificationId = (msg.notificationId as string) ?? `notif-${Date.now()}`;
      const taskId = msg.taskId as string;
      const now = new Date();

      const notification: BazaarNotification = {
        notificationId,
        taskId,
        from: msg.from as string,
        question: msg.question as string,
        mode,
        receivedAt: now.toISOString(),
        countdownEndsAt: mode === 'notify'
          ? new Date(now.getTime() + NOTIFY_COUNTDOWN_MS).toISOString()
          : null,
      };

      set((s) => ({
        notifications: [...s.notifications, notification],
      }));

      // notify 模式：启动倒计时，到期自动 accept
      if (mode === 'notify') {
        const timer = setTimeout(() => {
          const client = getWsClient();
          if (client) {
            client.send({ type: 'bazaar.task.accept', payload: { taskId } });
          }
          set((s) => ({
            notifications: s.notifications.filter((n) => n.notificationId !== notificationId),
          }));
          countdownTimers.delete(notificationId);
        }, NOTIFY_COUNTDOWN_MS);
        countdownTimers.set(notificationId, timer);
      }
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
