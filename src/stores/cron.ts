/**
 * Cron Task Store
 * Manages scheduled tasks via WebSocket.
 */
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import { cronCache } from '@/lib/cron-cache';
import type { CronTask, CronRun, CronSkill } from '@/types/settings';

type MsgHandler = (msg: Record<string, unknown>) => void;

function getWsClient() {
  return useWsConnection.getState().client;
}

function wrapHandler(
  client: { on: (e: string, h: (...a: unknown[]) => void) => void; off: (e: string, h: (...a: unknown[]) => void) => void },
  event: string,
  handler: MsgHandler,
) {
  const wrapped = (...args: unknown[]) => handler(args[0] as Record<string, unknown>);
  client.on(event, wrapped);
  return () => client.off(event, wrapped);
}

type WsClient = NonNullable<ReturnType<typeof useWsConnection.getState>['client']>;

/**
 * Register a success-event handler and a shared error handler, then send the message.
 * Returns a promise that resolves when the success event fires.
 */
function sendWithAck<T>(
  client: WsClient,
  successEvent: string,
  message: Record<string, unknown>,
  onSuccess: (data: Record<string, unknown>) => T,
  extraErrorState?: Record<string, unknown>,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const unsub = wrapHandler(client, successEvent, (data) => {
      cleanup();
      resolve(onSuccess(data));
    });
    const unsubErr = wrapHandler(client, 'chat.error', (data) => {
      cleanup();
      const errorMsg = String(data.error);
      set({ error: errorMsg, ...extraErrorState });
      reject(new Error(errorMsg));
    });
    const cleanup = () => {
      unsub();
      unsubErr();
    };
    client.send(message);
  });
}

interface CronState {
  workspaces: string[];
  skills: CronSkill[];
  tasks: CronTask[];
  loading: boolean;
  scanning: boolean;
  error: string | null;

  fetchWorkspaces: () => Promise<void>;
  fetchSkills: (workspace: string) => Promise<void>;
  fetchTasks: () => Promise<void>;
  createTask: (workspace: string, skillName: string, cronExpression: string) => Promise<CronTask>;
  updateTask: (taskId: string, updates: { workspace?: string; skillName?: string; cronExpression?: string; enabled?: boolean }) => Promise<void>;
  deleteTask: (taskId: string) => Promise<void>;
  fetchRuns: (taskId: string, limit?: number) => Promise<CronRun[]>;
  executeNow: (taskId: string) => Promise<void>;
  scanCronTasks: () => Promise<{ created: number; updated: number; skipped: number; disabled: number }>;
  clearError: () => void;
}

// Zustand `set` is hoisted into module scope so sendWithAck can use it.
// This is safe because create() runs synchronously and set is assigned before any async action fires.
let set: (partial: Partial<CronState> | ((state: CronState) => Partial<CronState>)) => void;

export const useCronStore = create<CronState>((storeSet) => {
  set = storeSet;

  return {
    workspaces: [],
    skills: [],
    tasks: [],
    loading: false,
    scanning: false,
    error: null,

    fetchWorkspaces: async () => {
      const client = getWsClient();
      if (!client) return;

      return new Promise<void>((resolve) => {
        const unsub = wrapHandler(client, 'cron.workspaces', (data) => {
          unsub();
          set({ workspaces: data.workspaces as string[] });
          resolve();
        });
        client.send({ type: 'cron.workspaces' });
      });
    },

    fetchSkills: async (workspace: string) => {
      const client = getWsClient();
      if (!client) return;

      return new Promise<void>((resolve) => {
        const unsub = wrapHandler(client, 'cron.skills', (data) => {
          unsub();
          set({ skills: data.skills as CronSkill[] });
          resolve();
        });
        client.send({ type: 'cron.skills', workspace });
      });
    },

    fetchTasks: async () => {
      const client = getWsClient();
      if (!client) return;

      // Read from cache: memory first, then IndexedDB
      let cached = cronCache.get();
      if (!cached) {
        cached = await cronCache.getAsync();
      }

      if (cached && cached.length > 0) {
        // Show cached data immediately
        set({ tasks: cached, loading: false, error: null });
      } else {
        set({ loading: true, error: null });
      }

      // Always sync from backend
      return new Promise<void>((resolve) => {
        const unsub = wrapHandler(client, 'cron.list', (data) => {
          unsub();
          const tasks = data.tasks as CronTask[];
          cronCache.set(tasks);
          set({ tasks, loading: false });
          resolve();
        });
        client.send({ type: 'cron.list' });
      });
    },

    createTask: (workspace: string, skillName: string, cronExpression: string) => {
      const client = getWsClient();
      if (!client) throw new Error('Not connected');

      return sendWithAck<CronTask>(client, 'cron.created', { type: 'cron.create', workspace, skillName, cronExpression }, (data) => {
        const task = data.task as CronTask;
        set((state) => ({ tasks: [task, ...state.tasks] }));
        return task;
      });
    },

    updateTask: (taskId: string, updates: { workspace?: string; skillName?: string; cronExpression?: string; enabled?: boolean }) => {
      const client = getWsClient();
      if (!client) throw new Error('Not connected');

      return sendWithAck<void>(client, 'cron.updated', { type: 'cron.update', taskId, ...updates }, (data) => {
        const task = data.task as CronTask;
        set((state) => ({
          tasks: state.tasks.map((t) => (t.id === taskId ? { ...t, ...task } : t)),
        }));
      });
    },

    deleteTask: (taskId: string) => {
      const client = getWsClient();
      if (!client) throw new Error('Not connected');

      return sendWithAck<void>(client, 'cron.deleted', { type: 'cron.delete', taskId }, (data) => {
        set((state) => ({
          tasks: state.tasks.filter((t) => t.id !== data.taskId),
        }));
      });
    },

    fetchRuns: async (taskId: string, limit = 20) => {
      const client = getWsClient();
      if (!client) return [];

      return new Promise<CronRun[]>((resolve) => {
        const unsub = wrapHandler(client, 'cron.runs', (data) => {
          unsub();
          resolve(data.runs as CronRun[]);
        });
        client.send({ type: 'cron.runs', taskId, limit });
      });
    },

    executeNow: (taskId: string) => {
      const client = getWsClient();
      if (!client) throw new Error('Not connected');

      return sendWithAck<void>(client, 'cron.executed', { type: 'cron.execute', taskId }, () => {
        // ack 表示后端已接受执行请求，立即更新 UI 为"执行中"
        const now = new Date().toISOString();
        set((state) => ({
          tasks: state.tasks.map(t =>
            t.id === taskId
              ? { ...t, latestRun: { id: -1, taskId, sessionId: '', status: 'running' as const, startedAt: now, finishedAt: null, lastActivityAt: null, errorMessage: null } }
              : t
          ),
        }));
      });
    },

    scanCronTasks: () => {
      const client = getWsClient();
      if (!client) throw new Error('Not connected');

      set({ scanning: true, error: null });
      return sendWithAck<{ created: number; updated: number; skipped: number; disabled: number }>(
        client,
        'cron.scanned',
        { type: 'cron.scan' },
        (data) => {
          const tasks = data.tasks as CronTask[];
          set({ tasks, scanning: false });
          return {
            created: (data.created as number) || 0,
            updated: (data.updated as number) || 0,
            skipped: (data.skipped as number) || 0,
            disabled: (data.disabled as number) || 0,
          };
        },
        { scanning: false },
      );
    },

    clearError: () => set({ error: null }),
  };
});

// ── WebSocket push listener: sync cron state across clients ──
// Registered once on first store access, auto-cleans on disconnect.

let pushListenerRegistered = false;

function registerPushListeners() {
  if (pushListenerRegistered) return;
  pushListenerRegistered = true;

  const handle = (msg: Record<string, unknown>) => {
    if (msg.type === 'cron.changed') {
      const action = msg.action as string;
      if (action === 'created') {
        const task = msg.task as CronTask;
        set((state) => {
          if (state.tasks.some(t => t.id === task.id)) return state;
          return { tasks: [task, ...state.tasks] };
        });
      } else if (action === 'updated') {
        const task = msg.task as CronTask;
        set((state) => ({
          tasks: state.tasks.map(t => (t.id === task.id ? { ...t, ...task } : t)),
        }));
      } else if (action === 'deleted') {
        const taskId = msg.taskId as string;
        set((state) => ({
          tasks: state.tasks.filter(t => t.id !== taskId),
        }));
      } else if (action === 'scanned') {
        const tasks = msg.tasks as CronTask[];
        if (tasks) set({ tasks });
      }
    } else if (msg.type === 'cron.runStatusChanged') {
      const taskId = msg.taskId as string;
      const latestRun = msg.latestRun as CronRun | undefined;
      if (latestRun) {
        set((state) => ({
          tasks: state.tasks.map(t =>
            t.id === taskId ? { ...t, latestRun } : t
          ),
        }));
      }
    }
  };

  // Subscribe to all WS messages via the connection store
  const unsub = useWsConnection.subscribe((state, prev) => {
    if (state.client && state.client !== prev.client) {
      state.client.on('message', handle as (...a: unknown[]) => void);
    }
  });

  // Also attach to current client if already connected
  const currentClient = useWsConnection.getState().client;
  if (currentClient) {
    currentClient.on('message', handle as (...a: unknown[]) => void);
  }
}

registerPushListeners();

// Auto-sync cache whenever tasks change
useCronStore.subscribe((state) => {
  if (state.tasks.length > 0) {
    cronCache.set(state.tasks);
  }
});
