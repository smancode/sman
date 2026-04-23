// src/stores/stardom.ts
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type {
  StardomTask,
  StardomConnectionStatus,
  StardomChatMessage,
  StardomAgentInfo,
  StardomMode,
  StardomDigest,
  StardomNotification,
  StardomTaskChat,
  StardomLeaderboardEntry,
  ActivityEntry,
  StardomCapability,
} from '@/types/stardom';

function getWsClient() {
  return useWsConnection.getState().client;
}

// notify 模式倒计时：30 秒
const NOTIFY_COUNTDOWN_MS = 30_000;

// 多任务对话存储：key = taskId
const taskChatMap = new Map<string, StardomChatMessage[]>();

interface StardomState {
  connection: StardomConnectionStatus;
  tasks: StardomTask[];
  onlineAgents: StardomAgentInfo[];
  leaderboard: StardomLeaderboardEntry[];
  capabilities: StardomCapability[];
  activeChat: StardomTaskChat | null;
  notifications: StardomNotification[];
  digest: StardomDigest | null;
  loading: boolean;
  error: string | null;
  worldPositions: Map<string, { agentId: string; x: number; y: number; state: string; facing: string }>;
  sendWorldMove: (x: number, y: number, state: string, facing: string) => void;
  activityLog: ActivityEntry[];
  addActivity: (entry: Omit<ActivityEntry, 'id' | 'timestamp'>) => void;

  // Actions
  fetchTasks: () => void;
  fetchOnlineAgents: () => void;
  fetchLeaderboard: () => void;
  fetchCapabilities: () => void;
  cancelTask: (taskId: string) => void;
  setActiveChat: (taskId: string) => void;
  clearActiveChat: () => void;
  setMode: (mode: StardomMode) => void;
  clearError: () => void;
  acceptTask: (taskId: string) => void;
  rejectTask: (taskId: string) => void;
  dismissNotification: (notificationId: string) => void;
  getTaskChat: (taskId: string) => StardomChatMessage[];
}

let set: (partial: Partial<StardomState> | ((state: StardomState) => Partial<StardomState>)) => void;
let get: () => StardomState;

// 存储 notify 模式的倒计时 timer，key = notificationId
const countdownTimers = new Map<string, ReturnType<typeof setTimeout>>();

export const useStardomStore = create<StardomState>((storeSet, storeGet) => {
  set = storeSet;
  get = storeGet;

  return {
    connection: {
      connected: false,
      activeSlots: 0,
      maxSlots: 3,
    },
    tasks: [],
    onlineAgents: [],
    leaderboard: [],
    capabilities: [],
    activeChat: null,
    notifications: [],
    digest: null,
    loading: false,
    error: null,
    worldPositions: new Map(),
    activityLog: [],

    fetchTasks: () => {
      const client = getWsClient();
      if (!client) return;
      set({ loading: true });
      client.send({ type: 'stardom.task.list' });
    },

    fetchOnlineAgents: () => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'stardom.agent.list' });
    },

    fetchLeaderboard: () => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'stardom.leaderboard' });
    },

    fetchCapabilities: () => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'stardom.capabilities.list' });
    },

    cancelTask: (taskId: string) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'stardom.task.cancel', payload: { taskId } });
    },

    setActiveChat: (taskId: string) => {
      const existingMessages = taskChatMap.get(taskId) ?? [];
      set({ activeChat: { taskId, messages: existingMessages } });
    },

    clearActiveChat: () => {
      set({ activeChat: null });
    },

    setMode: (mode: StardomMode) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'stardom.config.update', payload: { mode } });
    },

    clearError: () => set({ error: null }),

    addActivity: (entry) => {
      const fullEntry: ActivityEntry = {
        ...entry,
        id: `act-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        timestamp: Date.now(),
      };
      set((s) => ({
        activityLog: [fullEntry, ...s.activityLog].slice(0, 200),
      }));
    },

    sendWorldMove: (x: number, y: number, state: string, facing: string) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'stardom.world.move', payload: { x, y, state, facing } });
    },

    acceptTask: (taskId: string) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'stardom.task.accept', payload: { taskId } });
      set((s) => ({
        notifications: s.notifications.filter((n) => n.taskId !== taskId),
      }));
    },

    rejectTask: (taskId: string) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'stardom.task.reject', payload: { taskId } });
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
    if (!msg.type?.toString().startsWith('stardom.')) return;

    const type = msg.type as string;

    if (type === 'stardom.status') {
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
            collabMode: (msg.collabMode as StardomMode) ?? 'notify',
          },
        }));
      } else if (event === 'disconnected') {
        set((s) => ({ connection: { ...s.connection, connected: false } }));
      }
    } else if (type === 'stardom.task.list.update') {
      const prevTasks = get().tasks;
      set({ tasks: msg.tasks as StardomTask[], loading: false });
      // Log new tasks
      const newTasks = (msg.tasks as StardomTask[]).filter(
        (t) => !prevTasks.some((p) => p.taskId === t.taskId)
      );
      for (const t of newTasks) {
        get().addActivity({
          type: 'task_event',
          agentId: t.direction === 'outgoing' ? get().connection.agentId : t.helperAgentId,
          agentName: t.direction === 'outgoing' ? get().connection.agentName : t.helperName,
          description: t.direction === 'outgoing'
            ? `发出协作请求: ${t.question}`
            : `收到协作请求: ${t.question}`,
          metadata: { taskId: t.taskId, direction: t.direction, status: t.status },
        });
      }
    } else if (type === 'stardom.agent.list.update') {
      set({ onlineAgents: msg.agents as StardomAgentInfo[] });
    } else if (type === 'stardom.leaderboard.update') {
      const prevReputation = get().connection.reputation ?? 0;
      set({ leaderboard: msg.leaderboard as StardomLeaderboardEntry[] });
      // Log reputation change
      const myId = get().connection.agentId;
      const myEntry = (msg.leaderboard as StardomLeaderboardEntry[]).find(
        (e: StardomLeaderboardEntry) => e.agentId === myId
      );
      if (myEntry && myEntry.reputation !== prevReputation) {
        const delta = myEntry.reputation - prevReputation;
        get().addActivity({
          type: 'reputation_change',
          agentId: myId,
          agentName: get().connection.agentName,
          description: `声望 ${delta > 0 ? '+' : ''}${delta}（当前 ${myEntry.reputation}）`,
          metadata: { reputation: myEntry.reputation, delta },
        });
      }
    } else if (type === 'stardom.task.chat.delta') {
      const taskId = msg.taskId as string;
      const from = msg.from as string;
      const text = msg.text as string;
      const chatMsg: StardomChatMessage = {
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
    } else if (type === 'stardom.notify') {
      const mode = msg.mode as 'auto' | 'notify' | 'manual';
      const notificationId = (msg.notificationId as string) ?? `notif-${Date.now()}`;
      const taskId = msg.taskId as string;
      const now = new Date();

      const notification: StardomNotification = {
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
            client.send({ type: 'stardom.task.accept', payload: { taskId } });
          }
          set((s) => ({
            notifications: s.notifications.filter((n) => n.notificationId !== notificationId),
          }));
          countdownTimers.delete(notificationId);
        }, NOTIFY_COUNTDOWN_MS);
        countdownTimers.set(notificationId, timer);
      }

      get().addActivity({
        type: 'task_event',
        agentId: msg.from as string,
        agentName: msg.from as string,
        description: `${msg.from} 请求协作: ${(msg.question as string).slice(0, 80)}`,
        metadata: { taskId: msg.taskId, mode: msg.mode },
      });
    } else if (type === 'stardom.digest') {
      set({ digest: msg as unknown as StardomDigest });
    } else if (type === 'stardom.capabilities.update') {
      set({ capabilities: (msg.capabilities ?? []) as StardomCapability[] });
    } else if (type === 'stardom.world.agent_update') {
      // World position tracking — kept for future use but not rendered
    } else if (type === 'stardom.world.zone_snapshot') {
      // World position tracking — kept for future use but not rendered
    } else if (type === 'stardom.world.agent_leave') {
      // World position tracking — kept for future use but not rendered
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
