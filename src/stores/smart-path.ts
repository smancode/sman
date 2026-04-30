/**
 * Smart Path Store — 地球路径
 * 所有操作都传 workspace，后端直接定位文件，不依赖遍历。
 */
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type { SmartPath, SmartPathRun, SmartPathStatus, SmartPathStep, SmartPathReference } from '@/types/settings';

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

type StepExecStatus = 'idle' | 'running' | 'completed' | 'failed';

interface SmartPathState {
  paths: SmartPath[];
  runs: SmartPathRun[];
  reports: Array<{ fileName: string; createdAt: string }>;
  currentReport: string | null;
  currentPath: SmartPath | null;
  loading: boolean;
  running: boolean;
  error: string | null;

  // 步骤流式执行状态
  stepExecutionStream: Record<number, string>;
  stepExecutionStatus: Record<number, StepExecStatus>;

  // References
  references: SmartPathReference[];
  currentReference: string | null;

  fetchPaths: (workspaces: string[]) => Promise<void>;
  createPath: (input: { name: string; workspace: string; steps: string }) => Promise<SmartPath>;
  updatePath: (pathId: string, workspace: string, updates: Partial<SmartPath>) => Promise<void>;
  deletePath: (pathId: string, workspace: string) => Promise<void>;
  runPath: (pathId: string, workspace: string) => Promise<void>;
  fetchRuns: (pathId: string, workspace: string) => Promise<void>;
  fetchReport: (pathId: string, workspace: string, fileName: string) => Promise<void>;
  generateStep: (userInput: string, workspace: string, previousSteps: SmartPathStep[]) => Promise<string>;
  executeStep: (pathId: string, workspace: string, stepIndex: number, step: SmartPathStep, previousSteps: SmartPathStep[]) => Promise<string>;
  clearStepExecutionState: () => void;
  setCurrentPath: (path: SmartPath | null) => void;
  clearError: () => void;
  fetchReferences: (pathId: string, workspace: string) => Promise<void>;
  fetchReference: (pathId: string, workspace: string, fileName: string) => Promise<void>;
}

export const useSmartPathStore = create<SmartPathState>((set) => ({
  paths: [],
  runs: [],
  reports: [],
  currentReport: null,
  currentPath: null,
  loading: false,
  running: false,
  error: null,

  stepExecutionStream: {},
  stepExecutionStatus: {},

  references: [],
  currentReference: null,

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
    set({ running: true, error: null, stepExecutionStream: {}, stepExecutionStatus: {} });

    const unsubStepProgress = wrapHandler(client, 'smartpath.stepExecutionProgress', (data) => {
      if (data.pathId === pathId && typeof data.stepIndex === 'number') {
        const idx = data.stepIndex as number;
        const delta = String(data.delta || '');
        set((s) => ({
          stepExecutionStatus: { ...s.stepExecutionStatus, [idx]: 'running' },
          stepExecutionStream: {
            ...s.stepExecutionStream,
            [idx]: (s.stepExecutionStream[idx] || '') + delta,
          },
        }));
      }
    });
    const unsubStepResult = wrapHandler(client, 'smartpath.stepExecutionResult', (data) => {
      if (data.pathId === pathId && typeof data.stepIndex === 'number') {
        const idx = data.stepIndex as number;
        set((s) => ({
          stepExecutionStatus: { ...s.stepExecutionStatus, [idx]: 'completed' },
        }));
      }
    });
    const unsubProgress = wrapHandler(client, 'smartpath.progress', (data) => {
      if (data.pathId === pathId) set((s) => ({ paths: s.paths.map((p) => p.id === pathId ? { ...p, status: 'running' as SmartPathStatus } : p) }));
    });
    const unsubComplete = wrapHandler(client, 'smartpath.completed', (data) => {
      if (data.pathId === pathId) {
        unsubStepProgress(); unsubStepResult(); unsubProgress(); unsubComplete(); unsubFailed();
        const p = data.path as SmartPath;
        const refs = (data.references as SmartPathReference[]) || [];
        set((s) => ({ running: false, paths: s.paths.map((x) => x.id === pathId ? { ...x, ...p } : x), references: refs }));
      }
    });
    const unsubFailed = wrapHandler(client, 'smartpath.failed', (data) => {
      if (data.pathId === pathId) {
        unsubStepProgress(); unsubStepResult(); unsubProgress(); unsubComplete(); unsubFailed();
        set((s) => ({ running: false, paths: s.paths.map((p) => p.id === pathId ? { ...p, status: 'failed' as SmartPathStatus } : p), error: String(data.error) }));
      }
    });

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.running', () => { unsub(); resolve(); });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub(); unsubStepProgress(); unsubStepResult(); unsubProgress(); unsubComplete(); unsubFailed();
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
        set({
          runs: data.runs as SmartPathRun[],
          reports: (data.reports as Array<{ fileName: string; createdAt: string }>) || [],
        });
        resolve();
      });
      client.send({ type: 'smartpath.runs', pathId, workspace });
    });
  },

  fetchReport: async (pathId, workspace, fileName) => {
    const client = getWsClient();
    if (!client) return;
    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'smartpath.report', (data) => {
        unsub();
        set({ currentReport: (data.content as string) || null });
        resolve();
      });
      client.send({ type: 'smartpath.report', pathId, workspace, fileName });
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

  executeStep: async (pathId, workspace, stepIndex, step, previousSteps) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    set((s) => ({
      stepExecutionStream: { ...s.stepExecutionStream, [stepIndex]: '' },
      stepExecutionStatus: { ...s.stepExecutionStatus, [stepIndex]: 'running' },
    }));

    return new Promise<string>((resolve, reject) => {
      const unsubProgress = wrapHandler(client, 'smartpath.stepExecutionProgress', (data) => {
        if (typeof (data as any).stepIndex === 'number' && (data as any).stepIndex !== stepIndex) return;
        const delta = String(data.delta || '');
        if (delta) {
          set((s) => ({
            stepExecutionStream: {
              ...s.stepExecutionStream,
              [stepIndex]: (s.stepExecutionStream[stepIndex] || '') + delta,
            },
          }));
        }
      });
      const unsubComplete = wrapHandler(client, 'smartpath.stepExecutionCompleted', (data) => {
        const payload = data.payload as Record<string, unknown> | undefined;
        const resultStr = String(payload?.result || '');
        unsubProgress(); unsubComplete(); unsubFailed();
        set((s) => ({
          stepExecutionStatus: { ...s.stepExecutionStatus, [stepIndex]: 'completed' },
        }));
        resolve(resultStr);
      });
      const unsubFailed = wrapHandler(client, 'chat.error', (data) => {
        unsubProgress(); unsubComplete(); unsubFailed();
        set((s) => ({
          stepExecutionStatus: { ...s.stepExecutionStatus, [stepIndex]: 'failed' },
          error: String(data.error),
        }));
        reject(new Error(String(data.error)));
      });

      client.send({
        type: 'smartpath.generateStep',
        pathId,
        workspace,
        stepIndex,
        userInput: step.userInput,
        previousSteps,
        execute: true,
      });
    });
  },

  clearStepExecutionState: () => set({ stepExecutionStream: {}, stepExecutionStatus: {} }),

  setCurrentPath: (path) => set({ currentPath: path, runs: [], reports: [], currentReport: null, stepExecutionStream: {}, stepExecutionStatus: {}, references: [], currentReference: null }),
  clearError: () => set({ error: null }),

  fetchReferences: async (pathId, workspace) => {
    const client = getWsClient();
    if (!client) return;
    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'smartpath.references', (data) => {
        unsub();
        set({ references: (data.references as SmartPathReference[]) || [] });
        resolve();
      });
      client.send({ type: 'smartpath.references', pathId, workspace });
    });
  },

  fetchReference: async (pathId, workspace, fileName) => {
    const client = getWsClient();
    if (!client) return;
    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'smartpath.reference.content', (data) => {
        unsub();
        set({ currentReference: (data.content as string) || null });
        resolve();
      });
      client.send({ type: 'smartpath.reference.read', pathId, workspace, fileName });
    });
  },
}));
