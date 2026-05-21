/**
 * Smart Path Store — 地球路径
 * 所有操作都传 workspace，后端直接定位文件，不依赖遍历。
 */
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type { SmartPath, SmartPathRun, SmartPathRunLog, SmartPathStatus, SmartPathStep, SmartPathReference, PathBlueprint } from '@/types/settings';

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

function applyStepEdits(blueprint: PathBlueprint, stepEdits: Record<number, Partial<SmartPathStep>>): PathBlueprint {
  if (Object.keys(stepEdits).length === 0) return blueprint;
  const newBlueprint = { ...blueprint, stepPlans: [...blueprint.stepPlans] };
  for (const [idxStr, edits] of Object.entries(stepEdits)) {
    const idx = Number(idxStr);
    if (idx >= 0 && idx < newBlueprint.stepPlans.length && edits.userInput !== undefined) {
      const origPlan = newBlueprint.stepPlans[idx];
      newBlueprint.stepPlans[idx] = { ...origPlan, revisedInput: edits.userInput };
    }
  }
  return newBlueprint;
}

interface SmartPathState {
  paths: SmartPath[];
  runs: SmartPathRun[];
  reports: Array<{ fileName: string; createdAt: string }>;
  currentReport: string | null;
  runLogs: SmartPathRunLog[];
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
  createPath: (input: { name: string; description?: string; workspace: string; steps: string }) => Promise<SmartPath>;
  updatePath: (pathId: string, workspace: string, updates: Partial<SmartPath>) => Promise<void>;
  deletePath: (pathId: string, workspace: string) => Promise<void>;
  runPath: (pathId: string, workspace: string, args?: string, useRefs?: boolean) => Promise<void>;
  abortPath: (pathId: string) => void;
  fetchRuns: (pathId: string, workspace: string) => Promise<void>;
  fetchReport: (pathId: string, workspace: string, fileName: string) => Promise<void>;
  generateStep: (userInput: string, workspace: string, previousSteps: SmartPathStep[]) => Promise<string>;
  executeStep: (pathId: string, workspace: string, stepIndex: number, step: SmartPathStep, previousSteps: SmartPathStep[]) => Promise<string>;
  clearStepExecutionState: () => void;
  setCurrentPath: (path: SmartPath | null) => void;
  clearError: () => void;
  fetchReferences: (pathId: string, workspace: string) => Promise<void>;
  fetchReference: (pathId: string, workspace: string, fileName: string) => Promise<void>;
  fetchRunLogs: (pathId: string, workspace: string) => Promise<void>;

  // 逐步执行模式
  stepping: boolean;
  finalizing: boolean;
  stepBlueprint: PathBlueprint | null;
  stepRunId: string | null;
  stepResults: string[];
  stepDescriptions: string[];
  stepDeliveryChecks: Array<{ passed?: boolean; reason?: string; retried?: boolean }>;
  stepEdits: Record<number, Partial<SmartPathStep>>;
  currentStepIndex: number;
  stepUseRefs: boolean;
  pathUseRefsMap: Record<string, boolean>;

  startStepping: (pathId: string, workspace: string, args?: string, useRefs?: boolean) => Promise<void>;
  runStepContinue: (pathId: string, workspace: string, args?: string, useRefs?: boolean) => Promise<void>;
  runStepRedo: (pathId: string, workspace: string, stepIndex: number, args?: string) => Promise<void>;
  updateStepResult: (index: number, value: string) => void;
  updateStepDescription: (index: number, value: string) => void;
  updateStepEdit: (index: number, field: keyof SmartPathStep, value: string | string[]) => void;
  finalizeStepping: (pathId: string, workspace: string) => Promise<void>;
  cancelStepping: (pathId: string) => void;
  setPathUseRefs: (pathId: string, value: boolean) => void;

  // 指南对话
  guideChatOpen: Record<number, boolean>;
  guideChatMessages: Record<number, Array<{ role: 'user' | 'assistant'; content: string }>>;
  guideChatSessionIds: Record<number, string>;
  guideChatLoading: Record<number, boolean>;
  guideChatStream: Record<number, string>;

  startGuideChat: (pathId: string, workspace: string, stepIndex: number, stepResult: string) => Promise<void>;
  sendGuideMessage: (pathId: string, workspace: string, stepIndex: number, message: string) => Promise<void>;
  saveGuide: (pathId: string, workspace: string, stepIndex: number) => Promise<void>;
  closeGuideChat: (stepIndex: number) => void;
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
  runLogs: [],

  guideChatOpen: {},
  guideChatMessages: {},
  guideChatSessionIds: {},
  guideChatLoading: {},
  guideChatStream: {},

  stepping: false,
  finalizing: false,
  stepBlueprint: null,
  stepRunId: null,
  stepResults: [],
  stepDescriptions: [],
  stepDeliveryChecks: [],
  stepEdits: {},
  currentStepIndex: -1,
  stepUseRefs: false,
  pathUseRefsMap: {},

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

  runPath: async (pathId, workspace, args, useRefs = false) => {
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
      client.send({ type: 'smartpath.run', pathId, workspace, args, useRefs });
    });
  },

  abortPath: (pathId) => {
    const client = getWsClient();
    if (!client) return;
    client.send({ type: 'smartpath.abort', pathId });
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

  fetchRunLogs: async (pathId, workspace) => {
    const client = getWsClient();
    if (!client) return;
    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'smartpath.runLogs', (data) => {
        unsub();
        set({ runLogs: (data.runLogs as SmartPathRunLog[]) || [] });
        resolve();
      });
      client.send({ type: 'smartpath.runLogs', pathId, workspace });
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
        skills: step.skills,
      });
    });
  },

  clearStepExecutionState: () => set({ stepExecutionStream: {}, stepExecutionStatus: {} }),

  setCurrentPath: (path) => set({
    currentPath: path, runs: [], reports: [], currentReport: null,
    stepExecutionStream: {}, stepExecutionStatus: {},
    references: [], currentReference: null, runLogs: [],
    stepping: false, finalizing: false, stepBlueprint: null, stepRunId: null,
    stepResults: [], stepDescriptions: [], stepDeliveryChecks: [], stepEdits: {},
    currentStepIndex: -1,
  }),
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

  startStepping: async (pathId, workspace, args, useRefs = false) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    set({
      stepping: true, error: null,
      stepExecutionStream: {}, stepExecutionStatus: {},
      stepResults: [], stepDescriptions: [], stepDeliveryChecks: [],
      stepEdits: {},
      currentStepIndex: -1, stepUseRefs: useRefs,
    });

    return new Promise<void>((resolve, reject) => {
      const unsubOrchestrated = wrapHandler(client, 'smartpath.orchestrated', (data) => {
        unsubOrchestrated(); unsubErr(); unsubProgress();
        set({
          stepBlueprint: data.blueprint as PathBlueprint,
          stepRunId: data.runId as string,
          stepExecutionStatus: { [-1]: 'completed' } as Record<number, StepExecStatus>,
        });
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsubOrchestrated(); unsubErr(); unsubProgress();
        set({ stepping: false, error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      const unsubProgress = wrapHandler(client, 'smartpath.stepExecutionProgress', (data) => {
        if (data.pathId === pathId && (data as any).stepIndex === -1) {
          const delta = String(data.delta || '');
          set((s) => ({
            stepExecutionStatus: { ...s.stepExecutionStatus, [-1]: 'running' as StepExecStatus },
            stepExecutionStream: {
              ...s.stepExecutionStream,
              [-1]: (s.stepExecutionStream[-1] || '') + delta,
            },
          }));
        }
      });

      client.send({ type: 'smartpath.orchestrate', pathId, workspace, args, useRefs });
    });
  },

  runStepContinue: async (pathId, workspace, args, useRefs = false) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    const { stepBlueprint, stepRunId, stepResults, stepEdits } = useSmartPathStore.getState();
    if (!stepBlueprint || !stepRunId) throw new Error('No active stepping session');

    const nextIndex = stepResults.length;
    const editedBlueprint = applyStepEdits(stepBlueprint, stepEdits);
    set((s) => ({
      currentStepIndex: nextIndex,
      stepExecutionStream: { ...s.stepExecutionStream, [nextIndex]: '' },
      stepExecutionStatus: { ...s.stepExecutionStatus, [nextIndex]: 'running' as StepExecStatus },
    }));

    return new Promise<void>((resolve, reject) => {
      const unsubProgress = wrapHandler(client, 'smartpath.stepExecutionProgress', (data) => {
        if (data.pathId === pathId && typeof data.stepIndex === 'number') {
          const idx = data.stepIndex as number;
          const delta = String(data.delta || '');
          set((s) => ({
            stepExecutionStream: {
              ...s.stepExecutionStream,
              [idx]: (s.stepExecutionStream[idx] || '') + delta,
            },
          }));
        }
      });
      const unsubResult = wrapHandler(client, 'smartpath.stepExecutionResult', (data) => {
        if (data.pathId === pathId && typeof data.stepIndex === 'number') {
          unsubProgress(); unsubResult(); unsubErr();
          const idx = data.stepIndex as number;
          const result = String(data.result || '');
          const checkPassed = data.deliveryCheckPassed as boolean | undefined;
          const checkReason = data.deliveryCheckReason as string | undefined;
          const retried = data.retried as boolean | undefined;
          set((s) => ({
            stepExecutionStatus: { ...s.stepExecutionStatus, [idx]: 'completed' as StepExecStatus },
            stepResults: [...s.stepResults, result],
            stepDescriptions: [...s.stepDescriptions, ''],
            stepDeliveryChecks: [...s.stepDeliveryChecks, { passed: checkPassed, reason: checkReason, retried }],
          }));
          resolve();
        }
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsubProgress(); unsubResult(); unsubErr();
        set((s) => ({
          stepExecutionStatus: { ...s.stepExecutionStatus, [nextIndex]: 'failed' as StepExecStatus },
          error: String(data.error),
        }));
        reject(new Error(String(data.error)));
      });

      client.send({
        type: 'smartpath.runStep', pathId, workspace, runId: stepRunId,
        blueprint: editedBlueprint, stepIndex: nextIndex,
        priorResults: stepResults, args, useRefs,
        stepEdits: stepEdits[nextIndex],
      });
    });
  },

  runStepRedo: async (pathId, workspace, stepIndex, args) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    const { stepBlueprint, stepRunId, stepResults, stepUseRefs, stepEdits } = useSmartPathStore.getState();
    if (!stepBlueprint || !stepRunId) throw new Error('No active stepping session');

    const priorResults = stepResults.slice(0, stepIndex);
    const editedBlueprint = applyStepEdits(stepBlueprint, stepEdits);
    set((s) => ({
      stepResults: priorResults,
      stepDescriptions: s.stepDescriptions.slice(0, stepIndex),
      stepDeliveryChecks: s.stepDeliveryChecks.slice(0, stepIndex),
      stepExecutionStream: { ...s.stepExecutionStream, [stepIndex]: '' },
      stepExecutionStatus: { ...s.stepExecutionStatus, [stepIndex]: 'running' as StepExecStatus },
      currentStepIndex: stepIndex,
    }));

    return new Promise<void>((resolve, reject) => {
      const unsubProgress = wrapHandler(client, 'smartpath.stepExecutionProgress', (data) => {
        if (data.pathId === pathId && typeof data.stepIndex === 'number') {
          const idx = data.stepIndex as number;
          const delta = String(data.delta || '');
          set((s) => ({
            stepExecutionStream: {
              ...s.stepExecutionStream,
              [idx]: (s.stepExecutionStream[idx] || '') + delta,
            },
          }));
        }
      });
      const unsubResult = wrapHandler(client, 'smartpath.stepExecutionResult', (data) => {
        if (data.pathId === pathId && typeof data.stepIndex === 'number') {
          unsubProgress(); unsubResult(); unsubErr();
          const idx = data.stepIndex as number;
          const result = String(data.result || '');
          const checkPassed = data.deliveryCheckPassed as boolean | undefined;
          const checkReason = data.deliveryCheckReason as string | undefined;
          const retried = data.retried as boolean | undefined;
          set((s) => ({
            stepExecutionStatus: { ...s.stepExecutionStatus, [idx]: 'completed' as StepExecStatus },
            stepResults: [...priorResults, result],
            stepDescriptions: [...s.stepDescriptions.slice(0, stepIndex), ''],
            stepDeliveryChecks: [...s.stepDeliveryChecks.slice(0, stepIndex), { passed: checkPassed, reason: checkReason, retried }],
          }));
          resolve();
        }
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsubProgress(); unsubResult(); unsubErr();
        set((s) => ({
          stepExecutionStatus: { ...s.stepExecutionStatus, [stepIndex]: 'failed' as StepExecStatus },
          error: String(data.error),
        }));
        reject(new Error(String(data.error)));
      });

      client.send({
        type: 'smartpath.runStep', pathId, workspace, runId: stepRunId,
        blueprint: editedBlueprint, stepIndex,
        priorResults, args, useRefs: stepUseRefs,
        stepEdits: stepEdits[stepIndex],
      });
    });
  },

  updateStepResult: (index, value) => {
    set((s) => {
      const results = [...s.stepResults];
      results[index] = value;
      return { stepResults: results };
    });
  },

  updateStepDescription: (index, value) => {
    set((s) => {
      const descs = [...s.stepDescriptions];
      descs[index] = value;
      return { stepDescriptions: descs };
    });
  },

  updateStepEdit: (index, field, value) => {
    set((s) => ({
      stepEdits: {
        ...s.stepEdits,
        [index]: { ...s.stepEdits[index], [field]: value },
      },
    }));
  },

  finalizeStepping: async (pathId, workspace) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    const { stepBlueprint, stepRunId, stepResults } = useSmartPathStore.getState();
    if (!stepBlueprint || !stepRunId) throw new Error('No active stepping session');

    set({ finalizing: true });
    return new Promise<void>((resolve, reject) => {
      const unsubComplete = wrapHandler(client, 'smartpath.completed', (data) => {
        if (data.pathId === pathId) {
          unsubComplete(); unsubErr();
          const p = data.path as SmartPath;
          const refs = (data.references as SmartPathReference[]) || [];
          set((s) => ({
            stepping: false,
            finalizing: false,
            stepBlueprint: null,
            stepRunId: null,
            running: false,
            paths: s.paths.map((x) => x.id === pathId ? { ...x, ...p } : x),
            references: refs,
          }));
          resolve();
        }
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsubComplete(); unsubErr();
        set({ stepping: false, finalizing: false, error: String(data.error) });
        reject(new Error(String(data.error)));
      });

      client.send({
        type: 'smartpath.finalize', pathId, workspace, runId: stepRunId,
        blueprint: stepBlueprint, stepResults,
      });
    });
  },

  cancelStepping: (pathId) => {
    const client = getWsClient();
    if (client && pathId) client.send({ type: 'smartpath.abort', pathId });
    set({
      stepping: false,
      finalizing: false,
      stepBlueprint: null,
      stepRunId: null,
      stepResults: [],
      stepDescriptions: [],
      stepDeliveryChecks: [],
      currentStepIndex: -1,
    });
  },

  setPathUseRefs: (pathId, value) => {
    set((s) => ({ pathUseRefsMap: { ...s.pathUseRefsMap, [pathId]: value } }));
  },

  // ── 指南对话 ──

  startGuideChat: async (pathId, workspace, stepIndex, stepResult) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    set((s) => ({
      guideChatOpen: { ...s.guideChatOpen, [stepIndex]: true },
      guideChatMessages: { ...s.guideChatMessages, [stepIndex]: [] },
      guideChatStream: { ...s.guideChatStream, [stepIndex]: '' },
      guideChatLoading: { ...s.guideChatLoading, [stepIndex]: true },
    }));

    return new Promise<void>((resolve, reject) => {
      const unsubDelta = wrapHandler(client, 'smartpath.guideChat.delta', (data) => {
        if (typeof (data as any).stepIndex === 'number' && (data as any).stepIndex !== stepIndex) return;
        const delta = String(data.delta || '');
        if (delta) {
          set((s) => ({
            guideChatStream: { ...s.guideChatStream, [stepIndex]: (s.guideChatStream[stepIndex] || '') + delta },
          }));
        }
      });
      const unsubComplete = wrapHandler(client, 'smartpath.guideChat.completed', (data) => {
        if (typeof (data as any).stepIndex === 'number' && (data as any).stepIndex !== stepIndex) return;
        unsubDelta(); unsubComplete(); unsubErr();
        const response = String(data.response || '');
        const sessionId = data.sessionId as string;
        set((s) => ({
          guideChatLoading: { ...s.guideChatLoading, [stepIndex]: false },
          guideChatSessionIds: { ...s.guideChatSessionIds, [stepIndex]: sessionId },
          guideChatStream: { ...s.guideChatStream, [stepIndex]: '' },
          guideChatMessages: {
            ...s.guideChatMessages,
            [stepIndex]: [...(s.guideChatMessages[stepIndex] || []), { role: 'assistant' as const, content: response }],
          },
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsubDelta(); unsubComplete(); unsubErr();
        set((s) => ({
          guideChatLoading: { ...s.guideChatLoading, [stepIndex]: false },
          error: String(data.error),
        }));
        reject(new Error(String(data.error)));
      });

      client.send({ type: 'smartpath.guideChat', pathId, workspace, stepIndex, stepResult });
    });
  },

  sendGuideMessage: async (pathId, workspace, stepIndex, message) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    const { guideChatSessionIds } = useSmartPathStore.getState();
    const sessionId = guideChatSessionIds[stepIndex];

    set((s) => ({
      guideChatLoading: { ...s.guideChatLoading, [stepIndex]: true },
      guideChatStream: { ...s.guideChatStream, [stepIndex]: '' },
      guideChatMessages: {
        ...s.guideChatMessages,
        [stepIndex]: [...(s.guideChatMessages[stepIndex] || []), { role: 'user' as const, content: message }],
      },
    }));

    return new Promise<void>((resolve, reject) => {
      const unsubDelta = wrapHandler(client, 'smartpath.guideChat.delta', (data) => {
        if (typeof (data as any).stepIndex === 'number' && (data as any).stepIndex !== stepIndex) return;
        const delta = String(data.delta || '');
        if (delta) {
          set((s) => ({
            guideChatStream: { ...s.guideChatStream, [stepIndex]: (s.guideChatStream[stepIndex] || '') + delta },
          }));
        }
      });
      const unsubComplete = wrapHandler(client, 'smartpath.guideChat.completed', (data) => {
        if (typeof (data as any).stepIndex === 'number' && (data as any).stepIndex !== stepIndex) return;
        unsubDelta(); unsubComplete(); unsubErr();
        const response = String(data.response || '');
        set((s) => ({
          guideChatLoading: { ...s.guideChatLoading, [stepIndex]: false },
          guideChatStream: { ...s.guideChatStream, [stepIndex]: '' },
          guideChatMessages: {
            ...s.guideChatMessages,
            [stepIndex]: [...(s.guideChatMessages[stepIndex] || []), { role: 'assistant' as const, content: response }],
          },
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsubDelta(); unsubComplete(); unsubErr();
        set((s) => ({
          guideChatLoading: { ...s.guideChatLoading, [stepIndex]: false },
          error: String(data.error),
        }));
        reject(new Error(String(data.error)));
      });

      client.send({ type: 'smartpath.guideChat', pathId, workspace, stepIndex, stepResult: '', message, sessionId });
    });
  },

  saveGuide: async (pathId, workspace, stepIndex) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    const { guideChatMessages, guideChatSessionIds } = useSmartPathStore.getState();
    const messages = guideChatMessages[stepIndex] || [];
    const lastAssistantMsg = [...messages].reverse().find(m => m.role === 'assistant');
    if (!lastAssistantMsg) throw new Error('No guide content to save');
    const sessionId = guideChatSessionIds[stepIndex];

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.guideSaved', (data) => {
        unsub(); unsubErr();
        const refs = (data.references as SmartPathReference[]) || [];
        set((s) => ({
          guideChatOpen: { ...s.guideChatOpen, [stepIndex]: false },
          references: refs,
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub(); unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });

      client.send({ type: 'smartpath.guideSave', pathId, workspace, stepIndex, content: lastAssistantMsg.content, sessionId });
    });
  },

  closeGuideChat: (stepIndex) => {
    set((s) => ({
      guideChatOpen: { ...s.guideChatOpen, [stepIndex]: false },
      guideChatMessages: { ...s.guideChatMessages, [stepIndex]: [] },
      guideChatStream: { ...s.guideChatStream, [stepIndex]: '' },
      guideChatLoading: { ...s.guideChatLoading, [stepIndex]: false },
    }));
  },
}));
