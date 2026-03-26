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

interface CronState {
  workspaces: string[];
  skills: CronSkill[];
  tasks: CronTask[];
  loading: boolean;
  error: string | null;

  fetchWorkspaces: () => Promise<void>;
  fetchSkills: (workspace: string) => Promise<void>;
  fetchTasks: () => Promise<void>;
  createTask: (workspace: string, skillName: string, intervalMinutes: number) => Promise<CronTask>;
  updateTask: (taskId: string, updates: { workspace?: string; skillName?: string; intervalMinutes?: number; enabled?: boolean }) => Promise<void>;
  deleteTask: (taskId: string) => Promise<void>;
  fetchRuns: (taskId: string, limit?: number) => Promise<CronRun[]>;
  executeNow: (taskId: string) => Promise<void>;
  clearError: () => void;
}

export const useCronStore = create<CronState>((set) => ({
  workspaces: [],
  skills: [],
  tasks: [],
  loading: false,
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

  createTask: async (workspace: string, skillName: string, intervalMinutes: number) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<CronTask>((resolve, reject) => {
      const unsub = wrapHandler(client, 'cron.created', (data) => {
        unsub();
        unsubErr();
        const task = data.task as CronTask;
        set((state) => ({ tasks: [task, ...state.tasks] }));
        resolve(task);
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'cron.create', workspace, skillName, intervalMinutes });
    });
  },

  updateTask: async (taskId: string, updates: { workspace?: string; skillName?: string; intervalMinutes?: number; enabled?: boolean }) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'cron.updated', (data) => {
        unsub();
        unsubErr();
        const task = data.task as CronTask;
        set((state) => ({
          tasks: state.tasks.map((t) => (t.id === taskId ? { ...t, ...task } : t)),
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'cron.update', taskId, ...updates });
    });
  },

  deleteTask: async (taskId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'cron.deleted', (data) => {
        unsub();
        unsubErr();
        set((state) => ({
          tasks: state.tasks.filter((t) => t.id !== data.taskId),
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'cron.delete', taskId });
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

  executeNow: async (taskId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'cron.executed', () => {
        unsub();
        unsubErr();
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'cron.execute', taskId });
    });
  },

  clearError: () => set({ error: null }),
}));
