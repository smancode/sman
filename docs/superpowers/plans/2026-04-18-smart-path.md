# Smart Path 多步骤能力系统实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现多步骤能力编排系统，用户可配置 Path（含串行/并行 Step），每个 Step 可执行 Skill 或 Python 脚本，步骤间通过共享 Context 传递数据。

**Architecture:** 后端新增 SmartPathStore（SQLite）+ SmartPathEngine（执行引擎），前端新增 SmartPathPage + Zustand Store，通过 WebSocket 通信。Skill 执行复用 ClaudeSessionManager.sendMessageForCron，Python 执行用 child_process。

**Tech Stack:** TypeScript, React 19, Zustand, better-sqlite3, WebSocket (ws), Express

**Project Constraints (from .claude/rules/*.md and CLAUDE.md):**
- 参数缺失/不合法 → 抛异常，不返回默认值 (`CODING_RULES.md` §2.2)
- 不擅自添加默认值、不做参数转换 (`CODING_RULES.md` §2.1)
- 命名严格按照用户要求 (`CODING_RULES.md` §5.1)
- 单一职责：一个文件只做一件事 (`CLAUDE.md` §2)
- 行数限制：静态语言 500 行 (`CLAUDE.md` §2)
- 测试放 `tests/` (`CLAUDE.md` §2)

---

## 文件结构

| 文件 | 职责 |
|------|------|
| `server/smart-path-store.ts` | SQLite 存储：Path CRUD + Run CRUD |
| `server/smart-path-engine.ts` | 执行引擎：遍历 steps，执行 skill/python，管理 context |
| `server/types.ts` | 追加 SmartPath / SmartPathRun / SmartPathStep / SmartPathAction 类型 |
| `server/index.ts` | 追加 WebSocket handler 注册 |
| `src/types/settings.ts` | 追加前端类型定义 |
| `src/stores/smart-path.ts` | Zustand Store：WS 通信 + 状态管理 |
| `src/features/smart-paths/index.tsx` | 页面组件：Path 列表 + 配置 + 执行 |
| `src/app/routes.tsx` | 追加 `/smart-paths` 路由 |

---

## Chunk 1: 后端基础设施

### Task 1: 后端类型定义 + SQLite Store

**Files:**
- Modify: `server/types.ts`
- Create: `server/smart-path-store.ts`
- Test: `tests/server/smart-path-store.test.ts`

**Applicable Constraints:**
- 参数缺失抛异常，不返回默认值 (`CODING_RULES.md` §2.2)
- 单一职责 (`CLAUDE.md` §2)

- [ ] **Step 1: 在 `server/types.ts` 追加类型定义**

在文件末尾追加：
```typescript
// === Smart Path Types ===

export type SmartPathStatus = 'draft' | 'ready' | 'running' | 'completed' | 'failed';
export type SmartPathRunStatus = 'running' | 'completed' | 'failed';
export type SmartPathStepMode = 'serial' | 'parallel';
export type SmartPathActionType = 'skill' | 'python';

export interface SmartPathAction {
  type: SmartPathActionType;
  skillId?: string;
  code?: string;
}

export interface SmartPathStep {
  mode: SmartPathStepMode;
  actions: SmartPathAction[];
}

export interface SmartPath {
  id: string;
  name: string;
  workspace: string;
  steps: SmartPathStep[];
  status: SmartPathStatus;
  createdAt: string;
  updatedAt: string;
}

export interface SmartPathRun {
  id: string;
  pathId: string;
  status: SmartPathRunStatus;
  stepResults: string; // JSON
  startedAt: string;
  finishedAt?: string;
  errorMessage?: string;
}
```

- [ ] **Step 2: 创建 `server/smart-path-store.ts`**

参考 `server/batch-store.ts` 模式，实现：
- `init()`: 创建 `smart_paths` 和 `smart_path_runs` 表
- `createPath(input)`: 插入 path，校验 name/steps 非空
- `getPath(id)`, `listPaths()`, `updatePath(id, updates)`, `deletePath(id)`
- `createRun(pathId)`, `getRun(id)`, `updateRun(id, updates)`, `listRuns(pathId)`
- 所有方法参数缺失时抛 `IllegalArgumentException`

- [ ] **Step 3: 创建测试 `tests/server/smart-path-store.test.ts`**

测试：create/list/get/update/delete path，create/update run，参数校验抛异常。

- [ ] **Step 4: 运行测试**

```bash
cd /Users/nasakim/projects/smanbase && pnpm test tests/server/smart-path-store.test.ts
```
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/types.ts server/smart-path-store.ts tests/server/smart-path-store.test.ts
git commit -m "feat: add smart-path-store with types and tests"
```

---

### Task 2: 执行引擎

**Files:**
- Create: `server/smart-path-engine.ts`
- Test: `tests/server/smart-path-engine.test.ts`

**Applicable Constraints:**
- 单一职责 (`CLAUDE.md` §2)
- 参数严格校验 (`CODING_RULES.md` §2)

**Depends on:** Task 1

- [ ] **Step 1: 创建 `server/smart-path-engine.ts`**

核心逻辑：
```typescript
export class SmartPathEngine {
  constructor(
    private store: SmartPathStore,
    private skillsRegistry: SkillsRegistry,
    private sessionManager: ClaudeSessionManager,
  ) {}

  async runPath(pathId: string, onProgress?: (data: object) => void): Promise<void> {
    const path = this.store.getPath(pathId);
    if (!path) throw new Error(`Path not found: ${pathId}`);
    if (!path.steps || path.steps.length === 0) throw new Error('Path has no steps');

    const run = this.store.createRun(pathId);
    this.store.updatePath(pathId, { status: 'running' });

    const ctx: Record<number, unknown> = {};

    try {
      for (let i = 0; i < path.steps.length; i++) {
        const step = path.steps[i];
        const result = await this.executeStep(step, ctx, path.workspace, `${run.id}-${i}`);
        ctx[i] = result;
        if (onProgress) onProgress({ stepIndex: i, totalSteps: path.steps.length, status: 'stepComplete' });
      }

      this.store.updateRun(run.id, {
        status: 'completed',
        stepResults: JSON.stringify(ctx),
        finishedAt: new Date().toISOString(),
      });
      this.store.updatePath(pathId, { status: 'completed' });
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      this.store.updateRun(run.id, {
        status: 'failed',
        errorMessage,
        stepResults: JSON.stringify(ctx),
        finishedAt: new Date().toISOString(),
      });
      this.store.updatePath(pathId, { status: 'failed' });
      throw err;
    }
  }

  private async executeStep(
    step: SmartPathStep,
    ctx: Record<number, unknown>,
    workspace: string,
    runKey: string,
  ): Promise<unknown> {
    if (step.mode === 'serial') {
      let result: unknown = null;
      for (let j = 0; j < step.actions.length; j++) {
        const action = step.actions[j];
        const input = result ?? ctx;
        result = await this.executeAction(action, input, workspace, `${runKey}-${j}`);
      }
      return result;
    } else {
      const results = await Promise.all(
        step.actions.map((action, j) =>
          this.executeAction(action, ctx, workspace, `${runKey}-${j}`),
        ),
      );
      return results;
    }
  }

  private async executeAction(
    action: SmartPathAction,
    input: unknown,
    workspace: string,
    sessionKey: string,
  ): Promise<unknown> {
    if (action.type === 'skill') {
      if (!action.skillId) throw new Error('skillId required for skill action');
      const skillDir = this.skillsRegistry.getSkillDir(action.skillId);
      const skillContent = fs.readFileSync(path.join(skillDir, 'SKILL.md'), 'utf-8');
      const prompt = this.buildSkillPrompt(skillContent, input);
      const sessionId = `smartpath-${sessionKey}`;
      this.sessionManager.createSessionWithId(workspace, sessionId);
      const abortController = new AbortController();
      await this.sessionManager.sendMessageForCron(sessionId, prompt, abortController, () => {});
      // Result: last assistant message text
      const msgs = this.sessionManager.getMessages(sessionId);
      const lastAssistant = msgs.reverse().find(m => m.type === 'assistant');
      return this.extractTextFromMessage(lastAssistant);
    } else if (action.type === 'python') {
      if (!action.code) throw new Error('code required for python action');
      return this.executePython(action.code, input);
    }
    throw new Error(`Unknown action type: ${action.type}`);
  }

  private buildSkillPrompt(skillContent: string, input: unknown): string {
    return `${skillContent}\n\n## Context\nPrevious results: ${JSON.stringify(input, null, 2)}`;
  }

  private async executePython(code: string, input: unknown): Promise<unknown> {
    const ctxJson = JSON.stringify(input);
    const wrappedCode = [
      `import json, sys`,
      `ctx = json.loads(${JSON.stringify(ctxJson)})`,
      code,
    ].join('\n');
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'smartpath-py-'));
    const scriptPath = path.join(tmpDir, 'script.py');
    fs.writeFileSync(scriptPath, wrappedCode);
    try {
      const { stdout } = await execFileAsync('python3', [scriptPath], {
        timeout: 60_000,
        maxBuffer: 10 * 1024 * 1024,
      });
      try {
        return JSON.parse(stdout.trim());
      } catch {
        return stdout.trim();
      }
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  }
}
```

- [ ] **Step 2: 创建测试 `tests/server/smart-path-engine.test.ts`**

Mock store, skillsRegistry, sessionManager。测试：serial step 顺序执行、parallel step 并发执行、python action 执行、skill action prompt 构建。

- [ ] **Step 3: 运行测试**

```bash
cd /Users/nasakim/projects/smanbase && pnpm test tests/server/smart-path-engine.test.ts
```
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add server/smart-path-engine.ts tests/server/smart-path-engine.test.ts
git commit -m "feat: add smart-path-engine with execution logic"
```

---

### Task 3: WebSocket Handler 注册

**Files:**
- Modify: `server/index.ts`

**Applicable Constraints:**
- 参数严格校验 (`CODING_RULES.md` §2)

**Depends on:** Task 1, Task 2

- [ ] **Step 1: 在 `server/index.ts` 中初始化 SmartPath 模块**

在 `batchEngine.start()` 附近追加：
```typescript
import { SmartPathStore } from './smart-path-store.js';
import { SmartPathEngine } from './smart-path-engine.js';

const smartPathStore = new SmartPathStore(dbPath);
const smartPathEngine = new SmartPathEngine(smartPathStore, skillsRegistry, sessionManager);
```

- [ ] **Step 2: 在 WS handler switch 中追加 smartpath 分支**

参考 batch handler 模式，追加：
- `smartpath.list` → listPaths
- `smartpath.create` → createPath（校验 name, workspace, steps）
- `smartpath.update` → updatePath
- `smartpath.delete` → deletePath
- `smartpath.run` → engine.runPath（后台执行，进度通过 `smartpath.progress` 广播）
- `smartpath.runs` → listRuns

- [ ] **Step 3: 编译验证**

```bash
cd /Users/nasakim/projects/smanbase && pnpm build:server
```
Expected: 编译通过，无类型错误

- [ ] **Step 4: 提交**

```bash
git add server/index.ts
git commit -m "feat: wire smartpath websocket handlers"
```

---

## Chunk 2: 前端实现

### Task 4: 前端类型 + Zustand Store

**Files:**
- Modify: `src/types/settings.ts`
- Create: `src/stores/smart-path.ts`

**Applicable Constraints:**
- 参数严格校验 (`CODING_RULES.md` §2)

**Depends on:** Task 3

- [ ] **Step 1: 在 `src/types/settings.ts` 追加类型**

```typescript
export type SmartPathStatus = 'draft' | 'ready' | 'running' | 'completed' | 'failed';
export type SmartPathRunStatus = 'running' | 'completed' | 'failed';
export type SmartPathStepMode = 'serial' | 'parallel';
export type SmartPathActionType = 'skill' | 'python';

export interface SmartPathAction {
  type: SmartPathActionType;
  skillId?: string;
  code?: string;
}

export interface SmartPathStep {
  mode: SmartPathStepMode;
  actions: SmartPathAction[];
}

export interface SmartPath {
  id: string;
  name: string;
  workspace: string;
  steps: SmartPathStep[];
  status: SmartPathStatus;
  createdAt: string;
  updatedAt: string;
}

export interface SmartPathRun {
  id: string;
  pathId: string;
  status: SmartPathRunStatus;
  stepResults: string;
  startedAt: string;
  finishedAt?: string;
  errorMessage?: string;
}
```

- [ ] **Step 2: 创建 `src/stores/smart-path.ts`**

参考 `src/stores/batch.ts` 模式，实现 Zustand Store：
- `paths`, `runs`, `currentPath`
- `fetchPaths`, `createPath`, `updatePath`, `deletePath`
- `runPath`, `fetchRuns`
- WS handler: `smartpath.list`, `smartpath.created`, `smartpath.updated`, `smartpath.deleted`, `smartpath.progress`, `smartpath.completed`, `smartpath.runs`

- [ ] **Step 3: 编译验证**

```bash
cd /Users/nasakim/projects/smanbase && pnpm tsc --noEmit -p src/tsconfig.json 2>/dev/null || pnpm build
```
Expected: 无类型错误

- [ ] **Step 4: 提交**

```bash
git add src/types/settings.ts src/stores/smart-path.ts
git commit -m "feat: add smart-path types and zustand store"
```

---

### Task 5: 前端页面组件

**Files:**
- Create: `src/features/smart-paths/index.tsx`

**Applicable Constraints:**
- 单一职责 (`CLAUDE.md` §2)

**Depends on:** Task 4

- [ ] **Step 1: 创建 `src/features/smart-paths/index.tsx`**

实现：
- Path 列表（按 workspace 分组）
- Path 配置面板：
  - 名称输入
  - Step 列表（可增删）
  - 每个 Step：mode 选择（serial/parallel）+ Action 列表
  - 每个 Action：type 选择 + skillId 输入 / code textarea
- 执行按钮 + 进度显示
- Run 历史列表

UI 参考 `src/features/settings/BatchTaskSettings.tsx` 风格，使用 Radix UI 组件。

- [ ] **Step 2: 编译验证**

```bash
cd /Users/nasakim/projects/smanbase && pnpm build
```
Expected: 编译通过

- [ ] **Step 3: 提交**

```bash
git add src/features/smart-paths/index.tsx
git commit -m "feat: add smart-path page component"
```

---

### Task 6: 路由注册

**Files:**
- Modify: `src/app/routes.tsx`

**Applicable Constraints:**
- 无特殊约束

**Depends on:** Task 5

- [ ] **Step 1: 追加路由**

```typescript
import { SmartPathPage } from '@/features/smart-paths';

// 在 children 中追加
{ path: 'smart-paths', element: <SmartPathPage /> },
```

- [ ] **Step 2: 编译验证**

```bash
cd /Users/nasakim/projects/smanbase && pnpm build
```
Expected: 编译通过

- [ ] **Step 3: 提交**

```bash
git add src/app/routes.tsx
git commit -m "feat: add smart-paths route"
```

---

## Chunk 3: 验证

### Task 7: 集成验证

**Files:**
- 全部文件

**Depends on:** Task 1-6

- [ ] **Step 1: 后端编译**

```bash
cd /Users/nasakim/projects/smanbase && pnpm build:server
```
Expected: 编译通过

- [ ] **Step 2: 前端编译**

```bash
cd /Users/nasakim/projects/smanbase && pnpm build
```
Expected: 编译通过

- [ ] **Step 3: 运行后端测试**

```bash
cd /Users/nasakim/projects/smanbase && pnpm test tests/server/smart-path-store.test.ts tests/server/smart-path-engine.test.ts
```
Expected: PASS

- [ ] **Step 4: 功能走查**

1. 启动后端 + 前端
2. 打开 `/smart-paths`
3. 创建一个 Path：step0=skill1(serial), step1=python 脚本
4. 执行 Path
5. 验证步骤顺序执行、context 传递正确

- [ ] **Step 5: 提交**

```bash
git commit -m "feat: smart path multi-step capability system complete"
```

---

## 风险与回退

- **风险**: `sessionManager.sendMessageForCron` 可能不支持同步获取结果。若不行，改为在 engine 中轮询 session messages。
- **风险**: Python 执行环境可能未安装。在 engine 中捕获 `ENOENT` 错误并给出明确提示。
- **回退**: 所有变更与 Batch 系统隔离，可直接删除 smart-path 相关文件回退。
