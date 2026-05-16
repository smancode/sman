# 地球路径逐步执行模式 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为地球路径新增逐步执行模式，用户可逐步执行每个步骤，每步完成后暂停，允许编辑结果和描述，选择重新执行或继续。同时强化 tmp 目录规范。

**Architecture:** 后端拆分引擎为协调 + 单步执行 + 收尾三个阶段 API，前端控制逐步执行流程。保留现有的一键执行模式，两者并存。

**Tech Stack:** TypeScript (Node.js/Express + React 19), WebSocket (ws), Zustand, TailwindCSS, Radix UI

---

## Chunk 1: 后端 — 类型导出与 Store 改动

### Task 1: 导出 PathBlueprint 类型 + Store 新增 clearTmpDir

**Files:**
- Modify: `server/smart-path-engine.ts:20-32` (导出类型)
- Modify: `server/smart-path-store.ts` (新增 clearTmpDir)
- Modify: `server/types.ts:156-199` (新增 PathBlueprint 类型)

- [ ] **Step 1: 在 server/types.ts 中新增 PathBlueprint 和 StepPlan 类型**

在 `SmartPathReference` 接口之后添加：

```ts
export interface StepPlan {
  revisedInput: string;
  roleDescription: string;
  expectedOutputs: string;
  dependenciesOnPrior: string;
}

export interface PathBlueprint {
  goal: string;
  stepPlans: StepPlan[];
  modifications: Array<{ step: number; original: string; revised: string; reason: string }>;
}
```

- [ ] **Step 2: 修改 server/smart-path-engine.ts — 删除内部类型定义，改为从 types.ts 导入**

删除 `smart-path-engine.ts` 第 20-32 行的 `StepPlan` 和 `PathBlueprint` 接口定义，替换为：

```ts
import type { SmartPathStep, StepPlan, PathBlueprint } from './types.js';
```

- [ ] **Step 3: 在 server/smart-path-store.ts 新增 clearTmpDir 方法**

在 `SmartPathStore` 类中，`cleanupOldRuns` 方法之前添加：

```ts
/** 清空并重建 tmp/ 目录 */
clearTmpDir(ws: string, pathId: string): void {
  const tmpDir = path.join(this.pathDir(ws, pathId), 'tmp');
  if (fs.existsSync(tmpDir)) {
    fs.rmSync(tmpDir, { recursive: true });
  }
  fs.mkdirSync(tmpDir, { recursive: true });
  this.log.info(`Cleared tmp dir: ${tmpDir}`);
}
```

- [ ] **Step 4: 编译验证**

Run: `npx tsc --noEmit --project tsconfig.server.json 2>&1 | head -20`
Expected: 无类型错误

- [ ] **Step 5: Commit**

```bash
git add server/types.ts server/smart-path-engine.ts server/smart-path-store.ts
git commit -m "refactor(smartpath): export PathBlueprint type and add clearTmpDir to store"
```

---

## Chunk 2: 后端 — 引擎拆分（3 个公开方法）

### Task 2: 拆分 SmartPathEngine 为 orchestrateOnly + runSingleStep + finalize

**Files:**
- Modify: `server/smart-path-engine.ts`

- [ ] **Step 1: 新增 orchestrateOnly 方法**

在 `SmartPathEngine` 类中，`run` 方法之后添加：

```ts
/** 协调阶段：主编分析 → 返回蓝图 + 创建 run + 清空 tmp */
async orchestrateOnly(
  pathId: string,
  workspace: string,
  args: string | undefined,
  onStepProgress: (stepIndex: number, delta: string) => void,
): Promise<{ blueprint: PathBlueprint; runId: string }> {
  const smartPath = this.store.get(pathId, workspace);
  if (!smartPath) throw new Error(`Path not found: ${pathId}`);

  let steps: SmartPathStep[];
  try { steps = JSON.parse(smartPath.steps); } catch { throw new Error('Invalid steps JSON'); }
  if (!Array.isArray(steps) || steps.length === 0) throw new Error('Path has no steps');

  const run = this.store.createRun(pathId, workspace);
  this.store.update(pathId, workspace, { status: 'running' });
  this.store.clearTmpDir(workspace, pathId);

  const abortController = new AbortController();
  const sessionIds: string[] = [];
  this.activeRuns.set(pathId, { abortController, sessionIds });

  const referencesContext = this.buildReferencesContext(workspace, smartPath.id);

  let blueprint: PathBlueprint;
  try {
    blueprint = await this.orchestrate(smartPath, steps, referencesContext, workspace, args, abortController, sessionIds, onStepProgress);
  } catch (err) {
    this.log.warn(`Orchestration failed, using default blueprint: ${err}`);
    blueprint = buildDefaultBlueprint(steps);
  }

  return { blueprint, runId: run.id };
}
```

- [ ] **Step 2: 新增 runSingleStep 方法**

```ts
/** 单步执行：接收蓝图、步骤索引、前序结果，返回步骤结果 */
async runSingleStep(
  pathId: string,
  workspace: string,
  runId: string,
  blueprint: PathBlueprint,
  stepIndex: number,
  totalSteps: number,
  priorResults: string[],
  args: string | undefined,
  onStepProgress: (stepIndex: number, delta: string) => void,
): Promise<string> {
  const smartPath = this.store.get(pathId, workspace);
  if (!smartPath) throw new Error(`Path not found: ${pathId}`);

  let steps: SmartPathStep[];
  try { steps = JSON.parse(smartPath.steps); } catch { throw new Error('Invalid steps JSON'); }

  const plan = blueprint.stepPlans[stepIndex] || blueprint.stepPlans[0];
  const referencesContext = this.buildReferencesContext(workspace, smartPath.id);

  // 用 priorResults 构建 keyOutputs
  const keyOutputs: string[] = priorResults.slice();

  const prompt = buildStepPrompt(
    plan, blueprint.goal, stepIndex, totalSteps,
    keyOutputs, referencesContext,
    stepIndex === 0 ? args : undefined,
  );

  const abortController = this.activeRuns.get(pathId)?.abortController || new AbortController();
  const sessionIds = this.activeRuns.get(pathId)?.sessionIds || [];

  const sessionId = `smartpath-ephemeral-${runId}-step-${stepIndex}-${Date.now()}`;
  this.sessionManager.createEphemeralSessionWithId(workspace, sessionId);
  sessionIds.push(sessionId);

  let stepFullContent = '';
  try {
    const stepReturned = await this.sessionManager.sendMessageForStep(
      sessionId, prompt, abortController,
      (delta) => {
        stepFullContent += delta;
        onStepProgress(stepIndex, delta);
      },
      STEP_SYSTEM_PROMPT,
    );

    const stepResult = (stepReturned || stepFullContent).trim();

    // 提取 reference 文件
    const refs = extractReferences(stepResult);
    for (const ref of refs) {
      this.store.saveReference(workspace, pathId, ref.fileName, ref.content);
    }

    return stepResult;
  } finally {
    this.sessionManager.closeV2Session(sessionId);
    this.sessionManager.removeEphemeralSession(sessionId);
  }
}
```

- [ ] **Step 3: 新增 finalize 方法**

```ts
/** 收尾：生成报告 + 更新 run.md + 更新状态 */
async finalize(
  pathId: string,
  workspace: string,
  runId: string,
  blueprint: PathBlueprint,
  stepResults: string[],
): Promise<void> {
  const smartPath = this.store.get(pathId, workspace);
  if (!smartPath) throw new Error(`Path not found: ${pathId}`);

  let steps: SmartPathStep[];
  try { steps = JSON.parse(smartPath.steps); } catch { throw new Error('Invalid steps JSON'); }

  this.store.update(pathId, workspace, { status: 'completed' });
  const reportFileName = this.store.createReport(
    workspace, pathId, smartPath.name, steps, stepResults, runId,
    'completed', isoNow(), isoNow(),
  );
  this.store.updateRun(runId, workspace, pathId, {
    status: 'completed',
    stepResults: JSON.stringify(stepResults),
    finishedAt: isoNow(),
    reportFileName: path.basename(reportFileName),
  });

  await this.updateRunGuide(workspace, pathId, steps, stepResults, blueprint);

  this.activeRuns.delete(pathId);
}
```

- [ ] **Step 4: 更新 STEP_SYSTEM_PROMPT — 新增 tmp 规则**

将现有的 `STEP_SYSTEM_PROMPT` 常量替换为：

```ts
const STEP_SYSTEM_PROMPT = [
  '[步骤执行模式]',
  '你正在执行自动化工作流的一个步骤。规则：',
  '1. 直接执行，给出简洁结果。不要询问用户。',
  '2. 专注于本步骤目标，不要越界。',
  '3. 能用 skill/tool 完成就用，不能的直接实现。',
  '4. 最后用「执行结果：」开头总结。',
  '5. 可复用文件用 [REFERENCE:filename.ext] 包裹内容标注。',
  '',
  '临时文件规则：',
  '1. 每个步骤的临时输出必须放在 tmp/ 目录（相对于工作区根目录）',
  '2. tmp/ 里的文件不在步骤间复用，每次运行开始时自动清空',
  '3. 要跨步骤复用的文件必须保存到 references/ 目录，用 [REFERENCE:filename] 标记',
  '4. 脚本、配置文件等可复用资源必须放到 references/，禁止放 tmp/',
].join('\n');
```

- [ ] **Step 5: 更新 updateRunGuide 的 prompt — 要求记录资源路径**

在 `updateRunGuide` 方法中，prompt 数组末尾的 `'输出 run.md 完整内容...'` 行替换为：

```ts
'输出 run.md 完整内容，包含：# 复用指南与经验沉淀 → 路径概述 → 执行策略 → 步骤修正记录 → 可复用资源（必须列出每个文件的完整路径 .sman/paths/{pathId}/references/{filename} 和用途说明）→ 注意事项 → 最佳实践。简洁实用。',
```

- [ ] **Step 6: 编译验证**

Run: `npx tsc --noEmit --project tsconfig.server.json 2>&1 | head -20`
Expected: 无类型错误

- [ ] **Step 7: Commit**

```bash
git add server/smart-path-engine.ts
git commit -m "feat(smartpath): split engine into orchestrate/runStep/finalize methods"
```

---

## Chunk 3: 后端 — WebSocket Handler

### Task 3: 新增 3 个 WS handler (orchestrate / runStep / finalize)

**Files:**
- Modify: `server/index.ts:2125` (在 `smartpath.generateStep` case 之后)

- [ ] **Step 1: 在 server/index.ts 的 smartpath handler 区域，`smartpath.generateStep` case 之后添加 3 个新 case**

在 `case 'smartpath.generateStep'` 的 break 之后（约第 2125 行之后），`case 'chatbot.weixin.qr.request'` 之前，插入：

```ts
        case 'smartpath.orchestrate': {
          if (!msg.pathId || !msg.workspace) throw new Error('Missing pathId or workspace');
          try {
            const oPathId = msg.pathId as string;
            const oWorkspace = msg.workspace as string;
            const oAllWs = [...new Set(store.listSessions().map(s => s.workspace))];
            const oPath = smartPathStore.get(oPathId, oWorkspace, oAllWs);
            const oActualWs = oPath?.workspace || oWorkspace;
            const oArgs = (msg.args as string) || oPath?.defaultArgs || '';

            const { blueprint, runId } = await smartPathEngine.orchestrateOnly(
              oPathId, oActualWs, oArgs,
              (stepIndex, delta) => {
                broadcast(JSON.stringify({ type: 'smartpath.stepExecutionProgress', pathId: oPathId, stepIndex, delta }));
              },
            );

            ws.send(JSON.stringify({ type: 'smartpath.orchestrated', pathId: oPathId, blueprint, runId }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'chat.error', error: err instanceof Error ? err.message : String(err) }));
          }
          break;
        }

        case 'smartpath.runStep': {
          if (!msg.pathId || !msg.workspace || !msg.runId || !msg.blueprint || msg.stepIndex === undefined) {
            throw new Error('Missing required: pathId, workspace, runId, blueprint, stepIndex');
          }
          try {
            const rsPathId = msg.pathId as string;
            const rsWorkspace = msg.workspace as string;
            const rsRunId = msg.runId as string;
            const rsBlueprint = msg.blueprint as import('./types.js').PathBlueprint;
            const rsStepIndex = msg.stepIndex as number;
            const rsPriorResults = (msg.priorResults as string[]) || [];
            const rsArgs = msg.args as string | undefined;

            const rsAllWs = [...new Set(store.listSessions().map(s => s.workspace))];
            const rsPath = smartPathStore.get(rsPathId, rsWorkspace, rsAllWs);
            const rsActualWs = rsPath?.workspace || rsWorkspace;

            const rsResult = await smartPathEngine.runSingleStep(
              rsPathId, rsActualWs, rsRunId, rsBlueprint,
              rsStepIndex, rsBlueprint.stepPlans.length,
              rsPriorResults, rsArgs,
              (stepIndex, delta) => {
                broadcast(JSON.stringify({ type: 'smartpath.stepExecutionProgress', pathId: rsPathId, stepIndex, delta }));
              },
            );

            broadcast(JSON.stringify({ type: 'smartpath.stepExecutionResult', pathId: rsPathId, stepIndex: rsStepIndex, result: rsResult }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'chat.error', error: err instanceof Error ? err.message : String(err) }));
          }
          break;
        }

        case 'smartpath.finalize': {
          if (!msg.pathId || !msg.workspace || !msg.runId || !msg.blueprint || !msg.stepResults) {
            throw new Error('Missing required: pathId, workspace, runId, blueprint, stepResults');
          }
          try {
            const fPathId = msg.pathId as string;
            const fWorkspace = msg.workspace as string;
            const fRunId = msg.runId as string;
            const fBlueprint = msg.blueprint as import('./types.js').PathBlueprint;
            const fStepResults = msg.stepResults as string[];

            const fAllWs = [...new Set(store.listSessions().map(s => s.workspace))];
            const fPath = smartPathStore.get(fPathId, fWorkspace, fAllWs);
            const fActualWs = fPath?.workspace || fWorkspace;

            await smartPathEngine.finalize(fPathId, fActualWs, fRunId, fBlueprint, fStepResults);

            const p = smartPathStore.get(fPathId, fActualWs);
            const refs = smartPathStore.listReferences(fActualWs, fPathId);
            broadcast(JSON.stringify({ type: 'smartpath.completed', pathId: fPathId, path: p, references: refs }));
          } catch (err) {
            ws.send(JSON.stringify({ type: 'chat.error', error: err instanceof Error ? err.message : String(err) }));
          }
          break;
        }
```

- [ ] **Step 2: 编译验证**

Run: `npx tsc --noEmit --project tsconfig.server.json 2>&1 | head -20`
Expected: 无类型错误

- [ ] **Step 3: Commit**

```bash
git add server/index.ts
git commit -m "feat(smartpath): add WS handlers for orchestrate/runStep/finalize"
```

---

## Chunk 4: 前端 — 类型 + 翻译 + Store

### Task 4: 前端类型定义 + 翻译 key

**Files:**
- Modify: `src/types/settings.ts:193-231` (新增 PathBlueprint)
- Modify: `src/locales/zh-CN.json` (新增翻译)
- Modify: `src/locales/en-US.json` (新增翻译)

- [ ] **Step 1: 在 src/types/settings.ts 中新增 PathBlueprint 类型**

在 `SmartPathReference` 之后添加：

```ts
export interface StepPlan {
  revisedInput: string;
  roleDescription: string;
  expectedOutputs: string;
  dependenciesOnPrior: string;
}

export interface PathBlueprint {
  goal: string;
  stepPlans: StepPlan[];
  modifications: Array<{ step: number; original: string; revised: string; reason: string }>;
}
```

- [ ] **Step 2: 在 src/locales/zh-CN.json 中添加逐步执行相关翻译 key**

在文件末尾 `"hub.settings.save"` 条目之后、闭合 `}` 之前添加（注意前一个 key 末尾不要逗号）：

```json
  "smartpath.stepExec": { "text": "逐步执行", "context": "逐步执行按钮" },
  "smartpath.redoStep": { "text": "重新执行", "context": "重新执行当前步骤按钮" },
  "smartpath.continueStep": { "text": "继续", "context": "继续下一步按钮" },
  "smartpath.editResult": { "text": "编辑结果", "context": "编辑步骤结果按钮" },
  "smartpath.editDesc": { "text": "编辑描述", "context": "编辑步骤描述按钮" },
  "smartpath.stepResultPlaceholder": { "text": "步骤执行结果（可编辑）...", "context": "步骤结果编辑区占位符" },
  "smartpath.stepDescPlaceholder": { "text": "步骤描述（可编辑）...", "context": "步骤描述编辑区占位符" },
  "smartpath.finalizeStep": { "text": "完成", "context": "完成最后一步按钮" },
  "smartpath.cancelStepExec": { "text": "取消", "context": "取消逐步执行按钮" },
  "smartpath.stepCompleted": { "text": "已完成", "context": "步骤已完成状态" },
  "smartpath.orchestrating": { "text": "主编分析中...", "context": "主编分析阶段提示" }
```

- [ ] **Step 3: 在 src/locales/en-US.json 中添加对应的英文翻译**

同样位置添加对应的英文翻译条目。

- [ ] **Step 4: Commit**

```bash
git add src/types/settings.ts src/locales/zh-CN.json src/locales/en-US.json
git commit -m "feat(smartpath): add PathBlueprint type and i18n keys for step-by-step execution"
```

### Task 5: Store 新增逐步执行状态和方法

**Files:**
- Modify: `src/stores/smart-path.ts`

- [ ] **Step 1: 在 SmartPathState 接口中新增逐步执行状态字段和方法签名**

在 `fetchReference` 方法签名之后添加：

```ts
  // 逐步执行模式
  stepping: boolean;
  stepBlueprint: PathBlueprint | null;
  stepRunId: string | null;
  stepResults: string[];
  stepDescriptions: string[];
  currentStepIndex: number;

  startStepping: (pathId: string, workspace: string, args?: string) => Promise<void>;
  runStepContinue: (pathId: string, workspace: string, args?: string) => Promise<void>;
  runStepRedo: (pathId: string, workspace: string, stepIndex: number, args?: string) => Promise<void>;
  updateStepResult: (index: number, value: string) => void;
  updateStepDescription: (index: number, value: string) => void;
  finalizeStepping: (pathId: string, workspace: string) => Promise<void>;
  cancelStepping: () => void;
```

- [ ] **Step 2: 在 store 的 import 行添加 PathBlueprint 导入**

```ts
import type { SmartPath, SmartPathRun, SmartPathStatus, SmartPathStep, SmartPathReference, PathBlueprint } from '@/types/settings';
```

- [ ] **Step 3: 在 store 初始状态中添加默认值**

```ts
  stepping: false,
  stepBlueprint: null,
  stepRunId: null,
  stepResults: [],
  stepDescriptions: [],
  currentStepIndex: -1,
```

- [ ] **Step 4: 实现 startStepping 方法**

```ts
  startStepping: async (pathId, workspace, args) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    set({
      stepping: true, error: null,
      stepExecutionStream: {}, stepExecutionStatus: {},
      stepResults: [], stepDescriptions: [],
      currentStepIndex: -1,
    });

    return new Promise<void>((resolve, reject) => {
      const unsubOrchestrated = wrapHandler(client, 'smartpath.orchestrated', (data) => {
        unsubOrchestrated(); unsubErr();
        set({
          stepBlueprint: data.blueprint as PathBlueprint,
          stepRunId: data.runId as string,
          stepExecutionStatus: { [-1]: 'completed' } as Record<number, StepExecStatus>,
        });
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsubOrchestrated(); unsubErr();
        set({ stepping: false, error: String(data.error) });
        reject(new Error(String(data.error)));
      });

      // 监听主编分析的流式进度
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
      // unsubProgress will be cleaned up when stepping ends

      client.send({ type: 'smartpath.orchestrate', pathId, workspace, args });
    });
  },
```

- [ ] **Step 5: 实现 runStepContinue 和 runStepRedo 方法**

```ts
  runStepContinue: async (pathId, workspace, args) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    const { stepBlueprint, stepRunId, stepResults } = useSmartPathStore.getState();
    if (!stepBlueprint || !stepRunId) throw new Error('No active stepping session');

    const nextIndex = stepResults.length;
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
          set((s) => ({
            stepExecutionStatus: { ...s.stepExecutionStatus, [idx]: 'completed' as StepExecStatus },
            stepResults: [...s.stepResults, result],
            stepDescriptions: [...s.stepDescriptions, ''],
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
        blueprint: stepBlueprint, stepIndex: nextIndex,
        priorResults: stepResults, args,
      });
    });
  },

  runStepRedo: async (pathId, workspace, stepIndex, args) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    const { stepBlueprint, stepRunId, stepResults } = useSmartPathStore.getState();
    if (!stepBlueprint || !stepRunId) throw new Error('No active stepping session');

    // 清空当前步骤及之后的结果
    const priorResults = stepResults.slice(0, stepIndex);
    set((s) => ({
      stepResults: priorResults,
      stepDescriptions: s.stepDescriptions.slice(0, stepIndex),
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
          set((s) => ({
            stepExecutionStatus: { ...s.stepExecutionStatus, [idx]: 'completed' as StepExecStatus },
            stepResults: [...priorResults, result],
            stepDescriptions: [...s.stepDescriptions.slice(0, stepIndex), ''],
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
        blueprint: stepBlueprint, stepIndex,
        priorResults, args,
      });
    });
  },
```

- [ ] **Step 6: 实现 updateStepResult / updateStepDescription / finalizeStepping / cancelStepping**

```ts
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

  finalizeStepping: async (pathId, workspace) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');
    const { stepBlueprint, stepRunId, stepResults } = useSmartPathStore.getState();
    if (!stepBlueprint || !stepRunId) throw new Error('No active stepping session');

    return new Promise<void>((resolve, reject) => {
      const unsubComplete = wrapHandler(client, 'smartpath.completed', (data) => {
        if (data.pathId === pathId) {
          unsubComplete(); unsubErr();
          const p = data.path as SmartPath;
          const refs = (data.references as SmartPathReference[]) || [];
          set((s) => ({
            stepping: false,
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
        set({ stepping: false, error: String(data.error) });
        reject(new Error(String(data.error)));
      });

      client.send({
        type: 'smartpath.finalize', pathId, workspace, runId: stepRunId,
        blueprint: stepBlueprint, stepResults,
      });
    });
  },

  cancelStepping: () => {
    set({
      stepping: false,
      stepBlueprint: null,
      stepRunId: null,
      stepResults: [],
      stepDescriptions: [],
      currentStepIndex: -1,
    });
  },
```

- [ ] **Step 7: 编译验证**

Run: `npx tsc --noEmit 2>&1 | head -20`
Expected: 无类型错误（可能有未使用的导入警告，可以忽略）

- [ ] **Step 8: Commit**

```bash
git add src/stores/smart-path.ts
git commit -m "feat(smartpath): add step-by-step execution state and methods to store"
```

---

## Chunk 5: 前端 — UI 组件改动

### Task 6: 修改 StepViewCard + PathDetail 支持逐步执行 UI

**Files:**
- Modify: `src/features/smart-paths/index.tsx`

- [ ] **Step 1: 新增 StepControlBar 组件**

在 `StepViewCard` 组件之前添加：

```tsx
function StepControlBar({ stepIndex, isLastStep, onRedo, onContinue, onFinalize }: {
  stepIndex: number; isLastStep: boolean;
  onRedo: () => void; onContinue: () => void; onFinalize: () => void;
}) {
  return (
    <div className="flex items-center gap-2 mt-2">
      <Button variant="outline" size="sm" className="h-7 text-xs gap-1" onClick={onRedo}>
        <Play className="h-3 w-3" /> {t('smartpath.redoStep')}
      </Button>
      {isLastStep ? (
        <Button size="sm" className="h-7 text-xs gap-1" onClick={onFinalize}>
          <CheckCircle className="h-3 w-3" /> {t('smartpath.finalizeStep')}
        </Button>
      ) : (
        <Button size="sm" className="h-7 text-xs gap-1" onClick={onContinue}>
          {t('smartpath.continueStep')}
        </Button>
      )}
    </div>
  );
}
```

- [ ] **Step 2: 修改 StepViewCard — 增加逐步模式下的可编辑区域**

StepViewCard 组件需要接收新的 props：
```tsx
function StepViewCard({ step, index, total, executionStream, executing, stepping, stepResult, stepDesc, onResultChange, onDescChange, onRedo, onContinue, onFinalize }: {
  step: SmartPathStep; index: number; total: number;
  executionStream?: string; executing?: boolean;
  stepping?: boolean;
  stepResult?: string; stepDesc?: string;
  onResultChange?: (v: string) => void;
  onDescChange?: (v: string) => void;
  onRedo?: () => void; onContinue?: () => void; onFinalize?: () => void;
})
```

在步骤结果渲染之后、`</div>` 闭合之前添加逐步执行的可编辑区域：

```tsx
      {/* 逐步执行模式：可编辑区域 + 控制按钮 */}
      {stepping && stepResult && !executing && (
        <div className="mt-2 space-y-2">
          <div className="space-y-1">
            <Label className="text-xs text-muted-foreground">{t('smartpath.editDesc')}</Label>
            <Input
              value={stepDesc ?? step.userInput}
              onChange={(e) => onDescChange?.(e.target.value)}
              className="h-6 text-xs"
              placeholder={t('smartpath.stepDescPlaceholder')}
            />
          </div>
          <div className="space-y-1">
            <Label className="text-xs text-muted-foreground">{t('smartpath.editResult')}</Label>
            <Textarea
              value={stepResult}
              onChange={(e) => onResultChange?.(e.target.value)}
              className="min-h-[80px] text-xs resize-none"
              placeholder={t('smartpath.stepResultPlaceholder')}
            />
          </div>
          <StepControlBar
            stepIndex={index}
            isLastStep={index === total - 1}
            onRedo={() => onRedo?.()}
            onContinue={() => onContinue?.()}
            onFinalize={() => onFinalize?.()}
          />
        </div>
      )}
```

- [ ] **Step 3: 修改 PathDetail — 新增"逐步执行"按钮**

在 PathDetail 组件中：

1. 从 store 获取逐步执行状态：
```tsx
const stepping = useSmartPathStore((s) => s.stepping);
const stepBlueprint = useSmartPathStore((s) => s.stepBlueprint);
const stepResults = useSmartPathStore((s) => s.stepResults);
const stepDescriptions = useSmartPathStore((s) => s.stepDescriptions);
const currentStepIndex = useSmartPathStore((s) => s.currentStepIndex);
const startStepping = useSmartPathStore((s) => s.startStepping);
const runStepContinue = useSmartPathStore((s) => s.runStepContinue);
const runStepRedo = useSmartPathStore((s) => s.runStepRedo);
const updateStepResult = useSmartPathStore((s) => s.updateStepResult);
const updateStepDescription = useSmartPathStore((s) => s.updateStepDescription);
const finalizeStepping = useSmartPathStore((s) => s.finalizeStepping);
const cancelStepping = useSmartPathStore((s) => s.cancelStepping);
```

2. 在按钮区域，在 `{running ? (...中止...) : (...执行...)}` 之前添加逐步执行按钮：

```tsx
{stepping ? (
  <Button variant="outline" size="sm" onClick={cancelStepping}>
    {t('smartpath.cancelStepExec')}
  </Button>
) : (
  !running && (
    <Button variant="outline" size="sm" onClick={() => startStepping(path.id, path.workspace, path.defaultArgs)}>
      <Play className="h-3.5 w-3.5 mr-1" /> {t('smartpath.stepExec')}
    </Button>
  )
)}
```

3. 修改步骤渲染区域，在 stepping 模式下传递额外的 props 给 StepViewCard：

```tsx
{steps.map((s, i) => {
  const isStepCompleted = stepping && i < stepResults.length;
  const isCurrentStep = stepping && i === stepResults.length;
  return (
    <StepViewCard key={i} step={s} index={i} total={steps.length}
      executionStream={stepExecutionStream[i] || ''}
      executing={stepExecutionStatus[i] === 'running'}
      stepping={stepping && (isStepCompleted || isCurrentStep)}
      stepResult={stepping ? stepResults[i] : undefined}
      stepDesc={stepping ? stepDescriptions[i] : undefined}
      onResultChange={stepping ? (v) => updateStepResult(i, v) : undefined}
      onDescChange={stepping ? (v) => updateStepDescription(i, v) : undefined}
      onRedo={stepping ? () => runStepRedo(path.id, path.workspace, i, path.defaultArgs) : undefined}
      onContinue={stepping ? () => runStepContinue(path.id, path.workspace, path.defaultArgs) : undefined}
      onFinalize={stepping ? () => finalizeStepping(path.id, path.workspace) : undefined}
    />
  );
})}
```

- [ ] **Step 4: 修改 PathDetail 的 onRun prop 传入方式**

SmartPathPage 中 `handleRunStepping` 不需要了，`PathDetail` 直接从 store 获取方法。但 `PathDetail` 仍需保留 `onRun` prop 用于一键执行。确保 stepping 状态下 onRun 按钮被禁用。

- [ ] **Step 5: 编译验证**

Run: `npx tsc --noEmit 2>&1 | head -30`
Expected: 无类型错误

- [ ] **Step 6: Commit**

```bash
git add src/features/smart-paths/index.tsx
git commit -m "feat(smartpath): add step-by-step execution UI with editable results"
```

---

## Chunk 6: dev-workflow Skill 改动

### Task 7: 更新 dev-workflow Skill — 新增 tmp 规范

**Files:**
- Modify: `.claude/skills/dev-workflow/SKILL.md`

- [ ] **Step 1: 在 Step 3 之前（约第 143 行）插入 tmp 规范段落**

在 `## Step 3: 逐任务执行` 之前添加：

```markdown
## 临时文件与可复用资源规范

在执行过程中，所有步骤必须遵守以下文件存放规则：

| 文件类型 | 存放位置 | 说明 |
|---------|---------|------|
| 临时输出 | `tmp/` | 每次运行自动清空，不在步骤间复用 |
| 可复用脚本 | `references/` | 用 `[REFERENCE:filename]` 标记，跨运行保留 |
| 可复用文档 | `references/` | 同上 |
| 可复用配置 | `references/` | 同上 |

**规则：**
- 临时文件放 `tmp/`，禁止放 `references/`
- 要跨步骤复用的文件必须放 `references/`，禁止放 `tmp/`
- `tmp/` 中的文件每次运行开始时自动清空，不保证存在
- `references/` 中的文件跨运行保留，可复用
- `run.md` 必须记录所有可复用资源的完整路径和用途说明

---
```

- [ ] **Step 2: Commit**

```bash
git add .claude/skills/dev-workflow/SKILL.md
git commit -m "docs(dev-workflow): add tmp/references file rules"
```

---

## Chunk 7: 集成验证

### Task 8: 编译 + 构建验证

**Files:**
- 无新改动

- [ ] **Step 1: 全量编译检查**

Run: `npx tsc --noEmit 2>&1 | head -30`
Expected: 无错误

- [ ] **Step 2: 前端构建**

Run: `pnpm build 2>&1 | tail -10`
Expected: 构建成功

- [ ] **Step 3: 后端构建**

Run: `pnpm build:server 2>&1 | tail -10`
Expected: 构建成功

- [ ] **Step 4: 最终 Commit（如有构建修复）**

如果构建过程中发现并修复了问题：
```bash
git add -A
git commit -m "fix(smartpath): fix build issues from step-by-step execution feature"
```
