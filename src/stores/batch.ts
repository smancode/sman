/**
 * Batch Task Store
 * Manages batch execution tasks via WebSocket.
 */
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type { BatchTask, BatchItem, BatchTaskStatus, BatchItemStatus } from '@/types/settings';

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

interface BatchState {
  tasks: BatchTask[];
  currentTask: BatchTask | null;
  items: BatchItem[];
  testResult: { items: Record<string, unknown>[]; preview: string } | null;
  loading: boolean;
  generating: boolean;
  testing: boolean;
  executing: boolean;
  error: string | null;

  fetchTasks: () => Promise<void>;
  getTask: (taskId: string) => Promise<BatchTask | null>;
  createTask: (input: {
    workspace: string;
    skillName: string;
    mdContent: string;
    execTemplate: string;
    envVars?: Record<string, string>;
    concurrency?: number;
    retryOnFailure?: number;
  }) => Promise<BatchTask>;
  updateTask: (taskId: string, updates: Partial<BatchTask>) => Promise<void>;
  deleteTask: (taskId: string) => Promise<void>;
  generateCode: (taskId: string) => Promise<string>;
  testCode: (taskId: string) => Promise<{ items: Record<string, unknown>[]; preview: string }>;
  saveTask: (taskId: string) => Promise<void>;
  executeTask: (taskId: string) => Promise<void>;
  pauseTask: (taskId: string) => Promise<void>;
  resumeTask: (taskId: string) => Promise<void>;
  cancelTask: (taskId: string) => Promise<void>;
  fetchItems: (taskId: string, filter?: { status?: BatchItemStatus; offset?: number; limit?: number }) => Promise<void>;
  retryFailed: (taskId: string) => Promise<void>;
  setCurrentTask: (task: BatchTask | null) => void;
  clearError: () => void;
}

export const useBatchStore = create<BatchState>((set, get) => ({
  tasks: [],
  currentTask: null,
  items: [],
  testResult: null,
  loading: false,
  generating: false,
  testing: false,
  executing: false,
  error: null,

  fetchTasks: async () => {
    const client = getWsClient();
    if (!client) return;

    set({ loading: true, error: null });
    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'batch.list', (data) => {
        unsub();
        set({ tasks: data.tasks as BatchTask[], loading: false });
        resolve();
      });
      client.send({ type: 'batch.list' });
    });
  },

  getTask: async (taskId: string) => {
    const client = getWsClient();
    if (!client) return null;

    return new Promise<BatchTask | null>((resolve) => {
      const unsub = wrapHandler(client, 'batch.get', (data) => {
        unsub();
        resolve(data.task as BatchTask | null);
      });
      client.send({ type: 'batch.get', taskId });
    });
  },

  createTask: async (input) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<BatchTask>((resolve, reject) => {
      const unsub = wrapHandler(client, 'batch.created', (data) => {
        unsub();
        unsubErr();
        const task = data.task as BatchTask;
        set((state) => ({ tasks: [task, ...state.tasks] }));
        resolve(task);
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'batch.create', ...input });
    });
  },

  updateTask: async (taskId: string, updates: Partial<BatchTask>) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'batch.updated', (data) => {
        unsub();
        unsubErr();
        const task = data.task as BatchTask;
        set((state) => ({
          tasks: state.tasks.map((t) => (t.id === taskId ? { ...t, ...task } : t)),
          currentTask: state.currentTask?.id === taskId ? { ...state.currentTask, ...task } : state.currentTask,
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'batch.update', taskId, ...updates });
    });
  },

  deleteTask: async (taskId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'batch.deleted', (data) => {
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
      client.send({ type: 'batch.delete', taskId });
    });
  },

  generateCode: async (taskId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    set({ generating: true, error: null });
    return new Promise<string>((resolve, reject) => {
      const unsub = wrapHandler(client, 'batch.generated', (data) => {
        unsub();
        unsubErr();
        set({ generating: false });
        // Update task in local state
        set((state) => ({
          tasks: state.tasks.map((t) =>
            t.id === taskId ? { ...t, status: 'generated' as BatchTaskStatus, generatedCode: data.code as string } : t,
          ),
          currentTask: state.currentTask?.id === taskId
            ? { ...state.currentTask, status: 'generated' as BatchTaskStatus, generatedCode: data.code as string }
            : state.currentTask,
        }));
        resolve(data.code as string);
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ generating: false, error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'batch.generate', taskId });
    });
  },

  testCode: async (taskId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    set({ testing: true, error: null, testResult: null });
    return new Promise<{ items: Record<string, unknown>[]; preview: string }>((resolve, reject) => {
      const unsub = wrapHandler(client, 'batch.tested', (data) => {
        unsub();
        unsubErr();
        const result = { items: data.items as Record<string, unknown>[], preview: data.preview as string };
        set({ testing: false, testResult: result });
        set((state) => ({
          tasks: state.tasks.map((t) =>
            t.id === taskId ? { ...t, status: 'tested' as BatchTaskStatus } : t,
          ),
        }));
        resolve(result);
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ testing: false, error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'batch.test', taskId });
    });
  },

  saveTask: async (taskId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'batch.saved', (data) => {
        unsub();
        unsubErr();
        const task = data.task as BatchTask;
        set((state) => ({
          tasks: state.tasks.map((t) => (t.id === taskId ? { ...t, ...task } : t)),
          currentTask: state.currentTask?.id === taskId ? { ...state.currentTask, ...task } : state.currentTask,
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'batch.save', taskId });
    });
  },

  executeTask: async (taskId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    set({ executing: true, error: null });

    const unsubProgress = wrapHandler(client, 'batch.progress', (data) => {
      if (data.taskId === taskId) {
        set((state) => ({
          tasks: state.tasks.map((t) =>
            t.id === taskId
              ? { ...t, successCount: data.successCount as number, failedCount: data.failedCount as number, totalCost: data.totalCost as number }
              : t,
          ),
        }));
      }
    });

    const unsubComplete = wrapHandler(client, 'batch.completed', (data) => {
      if (data.taskId === taskId) {
        unsubProgress();
        unsubComplete();
        const task = data.task as BatchTask;
        set((state) => ({
          executing: false,
          tasks: state.tasks.map((t) => (t.id === taskId ? { ...t, ...task } : t)),
        }));
      }
    });

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'batch.started', () => {
        unsub();
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubProgress();
        unsubComplete();
        set({ executing: false, error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'batch.execute', taskId });
    });
  },

  pauseTask: async (taskId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'batch.paused', (data) => {
        unsub();
        set((state) => ({
          tasks: state.tasks.map((t) => (t.id === taskId ? { ...t, status: 'paused' as BatchTaskStatus } : t)),
        }));
        resolve();
      });
      client.send({ type: 'batch.pause', taskId });
    });
  },

  resumeTask: async (taskId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'batch.resumed', (data) => {
        unsub();
        set((state) => ({
          tasks: state.tasks.map((t) => (t.id === taskId ? { ...t, status: 'running' as BatchTaskStatus } : t)),
        }));
        resolve();
      });
      client.send({ type: 'batch.resume', taskId });
    });
  },

  cancelTask: async (taskId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'batch.cancelled', () => {
        unsub();
        set((state) => ({
          tasks: state.tasks.map((t) => (t.id === taskId ? { ...t, status: 'failed' as BatchTaskStatus } : t)),
        }));
        resolve();
      });
      client.send({ type: 'batch.cancel', taskId });
    });
  },

  fetchItems: async (taskId: string, filter?: { status?: BatchItemStatus; offset?: number; limit?: number }) => {
    const client = getWsClient();
    if (!client) return;

    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'batch.items', (data) => {
        unsub();
        set({ items: data.items as BatchItem[] });
        resolve();
      });
      client.send({ type: 'batch.items', taskId, ...filter });
    });
  },

  retryFailed: async (taskId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    set({ executing: true, error: null });

    const unsubProgress = wrapHandler(client, 'batch.progress', (data) => {
      if (data.taskId === taskId) {
        set((state) => ({
          tasks: state.tasks.map((t) =>
            t.id === taskId
              ? { ...t, successCount: data.successCount as number, failedCount: data.failedCount as number }
              : t,
          ),
        }));
      }
    });

    const unsubRetried = wrapHandler(client, 'batch.retried', (data) => {
      if (data.taskId === taskId) {
        unsubProgress();
        unsubRetried();
        const task = data.task as BatchTask;
        set((state) => ({
          executing: false,
          tasks: state.tasks.map((t) => (t.id === taskId ? { ...t, ...task } : t)),
        }));
      }
    });

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'batch.retrying', () => {
        unsub();
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubProgress();
        unsubRetried();
        set({ executing: false, error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'batch.retry', taskId });
    });
  },

  setCurrentTask: (task) => set({ currentTask: task, testResult: null }),

  clearError: () => set({ error: null }),
}));
