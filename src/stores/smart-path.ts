/**
 * Smart Path Store
 * Manages smart path execution via WebSocket.
 */
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type { SmartPath, SmartPathRun, SmartPathStatus } from '@/types/settings';

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

interface SmartPathState {
  paths: SmartPath[];
  runs: SmartPathRun[];
  currentPath: SmartPath | null;
  loading: boolean;
  running: boolean;
  error: string | null;

  fetchPaths: () => Promise<void>;
  createPath: (input: {
    name: string;
    workspace: string;
    steps: string;
    status?: SmartPathStatus;
  }) => Promise<SmartPath>;
  updatePath: (pathId: string, updates: Partial<SmartPath>) => Promise<void>;
  deletePath: (pathId: string) => Promise<void>;
  runPath: (pathId: string) => Promise<void>;
  fetchRuns: (pathId: string) => Promise<void>;
  generatePython: (description: string, workspace: string) => Promise<string>;
  generatePlan: (description: string, workspace: string) => Promise<any>;
  saveFile: (pathId: string, filePath: string) => Promise<void>;
  setCurrentPath: (path: SmartPath | null) => void;
  clearError: () => void;
}

export const useSmartPathStore = create<SmartPathState>((set, get) => ({
  paths: [],
  runs: [],
  currentPath: null,
  loading: false,
  running: false,
  error: null,

  fetchPaths: async () => {
    const client = getWsClient();
    if (!client) return;

    set({ loading: true, error: null });
    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'smartpath.list', (data) => {
        unsub();
        set({ paths: data.paths as SmartPath[], loading: false });
        resolve();
      });
      client.send({ type: 'smartpath.list' });
    });
  },

  createPath: async (input) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<SmartPath>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.created', (data) => {
        unsub();
        unsubErr();
        const path = data.path as SmartPath;
        set((state) => ({ paths: [path, ...state.paths] }));
        resolve(path);
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'smartpath.create', ...input });
    });
  },

  updatePath: async (pathId, updates) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.updated', (data) => {
        unsub();
        unsubErr();
        const path = data.path as SmartPath;
        set((state) => ({
          paths: state.paths.map((p) => (p.id === pathId ? { ...p, ...path } : p)),
          currentPath: state.currentPath?.id === pathId ? { ...state.currentPath, ...path } : state.currentPath,
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'smartpath.update', pathId, ...updates });
    });
  },

  deletePath: async (pathId) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.deleted', (data) => {
        unsub();
        unsubErr();
        set((state) => ({
          paths: state.paths.filter((p) => p.id !== data.pathId),
          currentPath: state.currentPath?.id === pathId ? null : state.currentPath,
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'smartpath.delete', pathId });
    });
  },

  runPath: async (pathId) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    set({ running: true, error: null });

    const unsubProgress = wrapHandler(client, 'smartpath.progress', (data) => {
      if (data.pathId === pathId) {
        set((state) => ({
          paths: state.paths.map((p) =>
            p.id === pathId ? { ...p, status: 'running' as SmartPathStatus } : p,
          ),
        }));
      }
    });

    const unsubComplete = wrapHandler(client, 'smartpath.completed', (data) => {
      if (data.pathId === pathId) {
        unsubProgress();
        unsubComplete();
        const path = data.path as SmartPath;
        set((state) => ({
          running: false,
          paths: state.paths.map((p) => (p.id === pathId ? { ...p, ...path } : p)),
        }));
      }
    });

    const unsubFailed = wrapHandler(client, 'smartpath.failed', (data) => {
      if (data.pathId === pathId) {
        unsubProgress();
        unsubComplete();
        unsubFailed();
        set((state) => ({
          running: false,
          paths: state.paths.map((p) =>
            p.id === pathId ? { ...p, status: 'failed' as SmartPathStatus } : p,
          ),
          error: String(data.error),
        }));
      }
    });

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.running', () => {
        unsub();
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubProgress();
        unsubComplete();
        unsubFailed();
        set({ running: false, error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'smartpath.run', pathId });
    });
  },

  fetchRuns: async (pathId) => {
    const client = getWsClient();
    if (!client) return;

    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'smartpath.runs', (data) => {
        unsub();
        set({ runs: data.runs as SmartPathRun[] });
        resolve();
      });
      client.send({ type: 'smartpath.runs', pathId });
    });
  },

  generatePython: async (description, workspace) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<string>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.pythonGenerated', (data) => {
        unsub();
        unsubErr();
        resolve(String(data.code));
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'smartpath.generatePython', description, workspace });
    });
  },

  generatePlan: async (description, workspace) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<any>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.planGenerated', (data) => {
        unsub();
        unsubErr();
        resolve(data.plan);
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'smartpath.generatePlan', description, workspace });
    });
  },

  saveFile: async (pathId, filePath) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.fileSaved', (data) => {
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
      client.send({ type: 'smartpath.saveFile', pathId, filePath });
    });
  },

  setCurrentPath: (path) => set({ currentPath: path }),
  clearError: () => set({ error: null }),
}));
