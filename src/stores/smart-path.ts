/**
 * Smart Path Store — 地球路径
 * 所有操作都传 workspace，后端直接定位文件，不依赖遍历。
 */
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type { SmartPath, SmartPathRun, SmartPathStatus, SmartPathStep } from '@/types/settings';

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

  fetchPaths: (workspaces: string[]) => Promise<void>;
  createPath: (input: { name: string; workspace: string; steps: string }) => Promise<SmartPath>;
  updatePath: (pathId: string, workspace: string, updates: Partial<SmartPath>) => Promise<void>;
  deletePath: (pathId: string, workspace: string) => Promise<void>;
  runPath: (pathId: string, workspace: string) => Promise<void>;
  fetchRuns: (pathId: string, workspace: string) => Promise<void>;
  generateStep: (userInput: string, workspace: string, previousSteps: SmartPathStep[]) => Promise<string>;
  setCurrentPath: (path: SmartPath | null) => void;
  clearError: () => void;
}

export const useSmartPathStore = create<SmartPathState>((set) => ({
  paths: [],
  runs: [],
  currentPath: null,
  loading: false,
  running: false,
  error: null,

  fetchPaths: async (workspaces) => {
    const client = getWsClient();
    if (!client) return;
    set({ loading: true, error: null });
    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'smartpath.list', (data) => {
        unsub();
        set({ paths: data.paths as SmartPath[], loading: false });
        resolve();
      });
      client.send({ type: 'smartpath.list', workspaces });
    });
  },

  createPath: async (input) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    return new Promise<SmartPath>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.created', (data) => {
        unsub(); unsubErr();
        const p = data.path as SmartPath;
        set((s) => ({ paths: [p, ...s.paths] }));
        resolve(p);
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub(); unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'smartpath.create', ...input });
    });
  },

  updatePath: async (pathId, workspace, updates) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.updated', (data) => {
        unsub(); unsubErr();
        const p = data.path as SmartPath;
        set((s) => ({
          paths: s.paths.map((x) => (x.id === pathId ? { ...x, ...p } : x)),
          currentPath: s.currentPath?.id === pathId ? { ...s.currentPath, ...p } : s.currentPath,
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub(); unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'smartpath.update', pathId, workspace, ...updates });
    });
  },

  deletePath: async (pathId, workspace) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.deleted', (data) => {
        unsub(); unsubErr();
        set((s) => ({
          paths: s.paths.filter((p) => p.id !== data.pathId),
          currentPath: s.currentPath?.id === pathId ? null : s.currentPath,
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub(); unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'smartpath.delete', pathId, workspace });
    });
  },

  runPath: async (pathId, workspace) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    set({ running: true, error: null });

    const unsubProgress = wrapHandler(client, 'smartpath.progress', (data) => {
      if (data.pathId === pathId) set((s) => ({ paths: s.paths.map((p) => p.id === pathId ? { ...p, status: 'running' as SmartPathStatus } : p) }));
    });
    const unsubComplete = wrapHandler(client, 'smartpath.completed', (data) => {
      if (data.pathId === pathId) {
        unsubProgress(); unsubComplete(); unsubFailed();
        const p = data.path as SmartPath;
        set((s) => ({ running: false, paths: s.paths.map((x) => x.id === pathId ? { ...x, ...p } : x) }));
      }
    });
    const unsubFailed = wrapHandler(client, 'smartpath.failed', (data) => {
      if (data.pathId === pathId) {
        unsubProgress(); unsubComplete(); unsubFailed();
        set((s) => ({ running: false, paths: s.paths.map((p) => p.id === pathId ? { ...p, status: 'failed' as SmartPathStatus } : p), error: String(data.error) }));
      }
    });

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.running', () => { unsub(); resolve(); });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub(); unsubProgress(); unsubComplete(); unsubFailed();
        set({ running: false, error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'smartpath.run', pathId, workspace });
    });
  },

  fetchRuns: async (pathId, workspace) => {
    const client = getWsClient();
    if (!client) return;
    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'smartpath.runs', (data) => {
        unsub();
        set({ runs: data.runs as SmartPathRun[] });
        resolve();
      });
      client.send({ type: 'smartpath.runs', pathId, workspace });
    });
  },

  generateStep: async (userInput, workspace, previousSteps) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    return new Promise<string>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.stepGenerated', (data) => {
        unsub(); unsubErr();
        resolve(String((data.payload as Record<string, unknown>)?.generatedContent || ''));
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub(); unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'smartpath.generateStep', userInput, workspace, previousSteps });
    });
  },

  setCurrentPath: (path) => set({ currentPath: path, runs: [] }),
  clearError: () => set({ error: null }),
}));
