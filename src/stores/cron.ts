/**
 * Cron Task Store
 * Manages scheduled tasks via WebSocket.
 */
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
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

      set({ loading: true, error: null });
      return new Promise<void>((resolve) => {
        const unsub = wrapHandler(client, 'cron.list', (data) => {
          unsub();
          set({ tasks: data.tasks as CronTask[], loading: false });
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

      return sendWithAck<void>(client, 'cron.executed', { type: 'cron.execute', taskId }, () => {});
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
