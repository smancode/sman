# Smart Path 重构实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Smart Path 从手动配置 skill/python action 的复杂界面，重构为自然语言输入 → 可视化编辑器 → 执行的极简流程

**Architecture:**
- 前端：对话式输入 + 可视化步骤编辑器（左侧卡片，右侧 JSON）
- 后端：新增自然语言生成计划接口，存储从 SQLite 改为文件系统（.sman/paths/*.md）
- 执行：复用现有 SmartPathEngine，增加 .md 文件解析

**Tech Stack:** React 19, TypeScript, Node.js, Express, WebSocket, gray-matter (Markdown frontmatter)

**Project Constraints (from .claude/rules/*.md and CLAUDE.md):**
- 参数严格校验：自然语言非空、步骤非空，不满足直接抛异常 (CODING_RULES.md §2.2)
- 单一职责：一个文件只做一件事，UI/存储/执行分离 (CLAUDE.md §2)
- 行数限制：动态语言 300 行/文件 (CLAUDE.md §2)
- 测试放 `tests/` (CLAUDE.md §2)
- 交付前必须编译和测试通过 (CLAUDE.md §1)

---

## Chunk 1: 后端存储层改造

### Task 1: 重写 SmartPathStore 为文件系统操作

**Files:**
- Modify: `server/smart-path-store.ts` (完全重写，约 150 行)
- Test: `tests/server/smart-path-store.test.ts`

**Applicable Constraints:**
- 单一职责：store 只做文件系统读写，不涉及业务逻辑 (CLAUDE.md §2)
- 参数严格校验：path 非空、workspace 非空 (CODING_RULES.md §2.2)
- 行数限制：150 行以内 (CLAUDE.md §2)

- [ ] **Step 1: 先写测试 - 列出计划文件**

```typescript
// tests/server/smart-path-store.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { SmartPathStore } from '../../server/smart-path-store.js';

describe('SmartPathStore - FileSystem', () => {
  let tempDir: string;
  let store: SmartPathStore;

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    store = new SmartPathStore(tempDir);
  });

  afterEach(() => {
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  it('should create and save plan to .md file', () => {
    const plan = {
      id: 'test-plan-1',
      name: '测试计划',
      description: '测试描述',
      workspace: '/tmp/test',
      steps: [{ mode: 'serial', actions: [] }],
      status: 'draft' as const,
      createdAt: new Date().toISOString(),
    };

    const savedPath = store.savePlan(plan);
    expect(fs.existsSync(savedPath)).toBe(true);

    const content = fs.readFileSync(savedPath, 'utf-8');
    expect(content).toContain('name: 测试计划');
    expect(content).toContain('status: draft');
  });

  it('should list all plans in workspace', () => {
    const plan1 = { id: 'plan1', name: '计划1', workspace: tempDir, steps: [], status: 'draft' as const, createdAt: new Date().toISOString() };
    const plan2 = { id: 'plan2', name: '计划2', workspace: tempDir, steps: [], status: 'draft' as const, createdAt: new Date().toISOString() };

    store.savePlan(plan1);
    store.savePlan(plan2);

    const plans = store.listPlans(tempDir);
    expect(plans).toHaveLength(2);
    expect(plans[0].name).toBe('计划1');
  });

  it('should load plan from .md file', () => {
    const plan = { id: 'plan1', name: '计划1', workspace: tempDir, steps: [], status: 'draft' as const, createdAt: new Date().toISOString() };
    const savedPath = store.savePlan(plan);

    const loaded = store.loadPlan(savedPath);
    expect(loaded.name).toBe('计划1');
    expect(loaded.id).toBe('plan1');
  });

  it('should delete plan file', () => {
    const plan = { id: 'plan1', name: '计划1', workspace: tempDir, steps: [], status: 'draft' as const, createdAt: new Date().toISOString() };
    const savedPath = store.savePlan(plan);

    store.deletePlan(savedPath);
    expect(fs.existsSync(savedPath)).toBe(false);
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

```bash
pnpm test tests/server/smart-path-store.test.ts
```
Expected: FAIL - SmartPathStore 方法不存在

- [ ] **Step 3: 实现 SmartPathStore 文件系统版本**

```typescript
// server/smart-path-store.ts
import fs from 'fs';
import path from 'path';
import { parse, stringify } from 'gray-matter';
import { createLogger, type Logger } from './utils/logger.js';
import type { SmartPath, SmartPathStep } from './types.js';

export class SmartPathStore {
  private log: Logger;

  constructor(private basePath: string) {
    this.log = createLogger('SmartPathStore');
    if (!fs.existsSync(basePath)) {
      fs.mkdirSync(basePath, { recursive: true });
    }
  }

  getPathsDir(workspace: string): string {
    const dir = path.join(workspace, '.sman', 'paths');
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    return dir;
  }

  savePlan(plan: Omit<SmartPath, 'steps'> & { steps: SmartPathStep[] }): string {
    if (!plan.name || !plan.name.trim()) {
      throw new Error('Plan name is required');
    }
    if (!plan.workspace) {
      throw new Error('Workspace is required');
    }

    const dir = this.getPathsDir(plan.workspace);
    const filename = `${plan.id}.md`;
    const filePath = path.join(dir, filename);

    const stepsJson = JSON.stringify(plan.steps);
    const content = `---
name: ${plan.name}
description: ${plan.description || ''}
workspace: ${plan.workspace}
created_at: ${plan.createdAt}
status: ${plan.status}
steps: ${stepsJson}
---

# ${plan.name}

${plan.description || '无描述'}
`;

    fs.writeFileSync(filePath, content, 'utf-8');
    this.log.info(`Saved plan to ${filePath}`);
    return filePath;
  }

  listPlans(workspace: string): SmartPath[] {
    const dir = this.getPathsDir(workspace);
    if (!fs.existsSync(dir)) {
      return [];
    }

    const files = fs.readdirSync(dir).filter(f => f.endsWith('.md'));
    const plans: SmartPath[] = [];

    for (const file of files) {
      try {
        const filePath = path.join(dir, file);
        const plan = this.loadPlan(filePath);
        plans.push(plan);
      } catch (err) {
        this.log.error(`Failed to load plan ${file}: ${err}`);
      }
    }

    return plans.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  }

  loadPlan(filePath: string): SmartPath {
    if (!fs.existsSync(filePath)) {
      throw new Error(`Plan file not found: ${filePath}`);
    }

    const content = fs.readFileSync(filePath, 'utf-8');
    const { data } = parse(content);

    return {
      id: path.basename(filePath, '.md'),
      name: data.name || '',
      workspace: data.workspace || '',
      steps: data.steps || [],
      status: data.status || 'draft',
      createdAt: data.created_at || new Date().toISOString(),
      updatedAt: data.updated_at || data.created_at || new Date().toISOString(),
    };
  }

  deletePlan(filePath: string): void {
    if (!fs.existsSync(filePath)) {
      throw new Error(`Plan file not found: ${filePath}`);
    }
    fs.unlinkSync(filePath);
    this.log.info(`Deleted plan: ${filePath}`);
  }

  updatePlan(filePath: string, updates: Partial<SmartPath>): void {
    const existing = this.loadPlan(filePath);
    const merged = { ...existing, ...updates };
    this.savePlan(merged);
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
pnpm test tests/server/smart-path-store.test.ts
```
Expected: PASS

- [ ] **Step 5: 编译检查**

```bash
pnpm build
```
Expected: 编译通过，无 TypeScript 错误

- [ ] **Step 6: 提交**

```bash
git add server/smart-path-store.ts tests/server/smart-path-store.test.ts
git commit -m "refactor(smart-path): rewrite store to use filesystem instead of SQLite"
```

---

### Task 2: 修改 SmartPathEngine 支持 .md 文件解析

**Files:**
- Modify: `server/smart-path-engine.ts` (约 50 行改动)
- Test: `tests/server/smart-path-engine.test.ts` (新增测试用例)

**Applicable Constraints:**
- 单一职责：engine 只做执行，不做存储 (CLAUDE.md §2)

- [ ] **Step 1: 写测试 - 从文件路径加载计划并执行**

```typescript
// tests/server/smart-path-engine.test.ts
it('should execute plan from .md file path', async () => {
  const planPath = store.savePlan({
    id: 'test-plan',
    name: '测试计划',
    workspace: tempDir,
    steps: [{
      mode: 'serial',
      actions: [{
        type: 'python',
        code: 'import json\nprint(json.dumps({"result": 42}))',
      }],
    }],
    status: 'draft',
    createdAt: new Date().toISOString(),
  });

  await engine.runPath(planPath);

  const runs = store.listRuns('test-plan');
  expect(runs).toHaveLength(1);
  expect(runs[0].status).toBe('completed');
});
```

- [ ] **Step 2: 运行测试验证失败**

```bash
pnpm test tests/server/smart-path-engine.test.ts
```
Expected: FAIL - engine.runPath 不支持文件路径

- [ ] **Step 3: 修改 SmartPathEngine.runPath 方法**

```typescript
// server/smart-path-engine.ts
export class SmartPathEngine {
  constructor(
    private store: SmartPathStore,
    private skillsRegistry: SkillsRegistry,
    private sessionManager: ClaudeSessionManager,
  ) {}

  async runPath(pathOrId: string, onProgress?: (data: {
    stepIndex: number;
    totalSteps: number;
    status: string;
  }) => void): Promise<void> {
    // 判断是文件路径还是 ID
    const isFilePath = pathOrId.endsWith('.md');
    const plan = isFilePath
      ? this.store.loadPlan(pathOrId)
      : this.store.listPlans(process.cwd()).find(p => p.id === pathOrId);

    if (!plan) {
      throw new Error(`Plan not found: ${pathOrId}`);
    }

    const steps = plan.steps;
    if (!steps || steps.length === 0) {
      throw new Error('Plan has no steps');
    }

    const planId = plan.id;
    const run = this.store.createRun(planId);
    this.store.updatePlan(pathOrId, { status: 'running' });

    const ctx: Record<number, unknown> = {};

    try {
      for (let i = 0; i < steps.length; i++) {
        const step = steps[i];
        const result = await this.executeStep(step, ctx, plan.workspace, `${run.id}-${i}`);
        ctx[i] = result;
        if (onProgress) {
          onProgress({ stepIndex: i, totalSteps: steps.length, status: 'stepComplete' });
        }
      }

      this.store.updateRun(run.id, {
        status: 'completed',
        stepResults: JSON.stringify(ctx),
        finishedAt: isoNow(),
      });
      this.store.updatePlan(pathOrId, { status: 'completed' });
      this.log.info(`Plan ${planId} completed`);
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      this.store.updateRun(run.id, {
        status: 'failed',
        errorMessage,
        stepResults: JSON.stringify(ctx),
        finishedAt: isoNow(),
      });
      this.store.updatePlan(pathOrId, { status: 'failed' });
      this.log.error(`Plan ${planId} failed: ${errorMessage}`);
      throw err;
    }
  }

  // ... 其他方法保持不变
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
pnpm test tests/server/smart-path-engine.test.ts
```
Expected: PASS

- [ ] **Step 5: 编译检查**

```bash
pnpm build
```
Expected: 编译通过

- [ ] **Step 6: 提交**

```bash
git add server/smart-path-engine.ts tests/server/smart-path-engine.test.ts
git commit -m "feat(smart-path): support loading plans from .md file paths"
```

---

## Chunk 2: 后端 API 层

### Task 3: 新增 WebSocket Handler - 自然语言生成计划

**Files:**
- Modify: `server/index.ts` (新增约 80 行)

**Applicable Constraints:**
- 参数严格校验：description 非空，workspace 非空 (CODING_RULES.md §2.2)

- [ ] **Step 1: 在 server/index.ts 添加 smartpath.generateFromNL handler**

在现有的 WebSocket 消息处理中添加（找到 `case 'smartpath.generatePython':` 附近）：

```typescript
// server/index.ts - WebSocket handler
case 'smartpath.generateFromNL': {
  const description = msg.description as string;
  const workspace = msg.workspace as string;

  // 严格校验
  if (!description || !description.trim()) {
    throw new Error('Missing description parameter');
  }
  if (!workspace || !workspace.trim()) {
    throw new Error('Missing workspace parameter');
  }

  try {
    const { query } = await import('@anthropic-ai/claude-agent-sdk');
    const llmConfig = settingsManager.getConfig().llm;

    const env: Record<string, string | undefined> = { ...process.env as Record<string, string | undefined> };
    if (llmConfig.apiKey) env['ANTHROPIC_API_KEY'] = llmConfig.apiKey;
    if (llmConfig.baseUrl) env['ANTHROPIC_BASE_URL'] = llmConfig.baseUrl;

    // 获取可用 skills
    const availableSkills = skillsRegistry.listSkills();
    const skillsList = availableSkills.map(s => `- ${s.id}: ${s.description || s.name}`).join('\n');

    const prompt = [
      'Generate an execution plan based on the following description.',
      '',
      'Context:',
      `- Workspace: ${workspace}`,
      `- Available Skills:\n${skillsList || '(none)'}`,
      '',
      'Skills Definition:',
      'A skill is a predefined capability that can be invoked to perform specific tasks.',
      'Skills are identified by their ID (e.g., "test-driven-development", "systematic-debugging").',
      '',
      'Description:',
      description,
      '',
      'Output Format:',
      'Return a JSON object with this structure:',
      '```json',
      '{',
      '  "name": "Plan Name",',
      '  "description": "Original description",',
      '  "steps": [',
      '    {',
      '      "mode": "serial",',
      '      "actions": [',
      '        { "type": "python", "code": "..." },',
      '        { "type": "skill", "skillId": "..." }',
      '      ]',
      '    }',
      '  ]',
      '}',
      '```',
    ].join('\n');

    const configModel = llmConfig.model;
    const resolvedModel = configModel.toLowerCase().startsWith('claude-') || configModel.toLowerCase().startsWith('anthropic-')
      ? configModel
      : 'claude-sonnet-4-6';

    const q = query({
      prompt,
      options: {
        cwd: workspace,
        model: resolvedModel,
        permissionMode: 'bypassPermissions' as const,
        allowDangerouslySkipPermissions: true,
        env,
        systemPrompt: {
          type: 'preset' as const,
          preset: 'claude_code' as const,
          append: 'You are an execution plan generator. Generate ONLY the JSON plan, wrapped in a code block.',
        },
      } as any,
    });

    let fullText = '';
    for await (const msg of q) {
      fullText += extractTextFromMessage(msg);
    }

    const jsonMatch = fullText.match(/```json\n([\s\S]*?)\n```/);
    const jsonStr = jsonMatch ? jsonMatch[1].trim() : fullText.trim();

    const plan = JSON.parse(jsonStr);

    // 验证生成的计划
    if (!plan.steps || !Array.isArray(plan.steps) || plan.steps.length === 0) {
      throw new Error('Generated plan has no steps');
    }

    ws.send(JSON.stringify({ type: 'smartpath.planGenerated', plan }));
  } catch (err) {
    const errorMessage = err instanceof Error ? err.message : String(err);
    ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
  }
  break;
}
```

- [ ] **Step 2: 添加 smartpath.listFiles handler**

```typescript
case 'smartpath.listFiles': {
  const workspace = msg.workspace as string;
  if (!workspace) throw new Error('Missing workspace');

  const store = new SmartPathStore(workspace);
  const plans = store.listPlans(workspace);
  ws.send(JSON.stringify({ type: 'smartpath.fileList', plans }));
  break;
}
```

- [ ] **Step 3: 添加 smartpath.loadFile handler**

```typescript
case 'smartpath.loadFile': {
  const filePath = msg.filePath as string;
  if (!filePath) throw new Error('Missing filePath');

  const store = new SmartPathStore(path.dirname(filePath));
  const plan = store.loadPlan(filePath);
  const content = fs.readFileSync(filePath, 'utf-8');
  ws.send(JSON.stringify({ type: 'smartpath.fileLoaded', plan, content }));
  break;
}
```

- [ ] **Step 4: 添加 smartpath.saveFile handler**

```typescript
case 'smartpath.saveFile': {
  const { filePath, plan } = msg as { filePath: string; plan: SmartPath };
  if (!filePath) throw new Error('Missing filePath');
  if (!plan) throw new Error('Missing plan');

  const store = new SmartPathStore(path.dirname(filePath));
  store.savePlan(plan);
  ws.send(JSON.stringify({ type: 'smartpath.fileSaved', filePath }));
  break;
}
```

- [ ] **Step 5: 添加 smartpath.deleteFile handler**

```typescript
case 'smartpath.deleteFile': {
  const filePath = msg.filePath as string;
  if (!filePath) throw new Error('Missing filePath');

  const store = new SmartPathStore(path.dirname(filePath));
  store.deletePlan(filePath);
  ws.send(JSON.stringify({ type: 'smartpath.fileDeleted', filePath }));
  break;
}
```

- [ ] **Step 6: 编译检查**

```bash
pnpm build
```
Expected: 编译通过，确保导入正确

- [ ] **Step 7: 提交**

```bash
git add server/index.ts
git commit -m "feat(smart-path): add WebSocket handlers for NL-based plan generation"
```

---

## Chunk 3: 前端 UI 重构

### Task 4: 创建前端组件 - PlanInput

**Files:**
- Create: `src/features/smart-paths/PlanInput.tsx` (约 80 行)

**Applicable Constraints:**
- 单一职责：只负责输入和生成 (CLAUDE.md §2)
- 行数限制：80 行以内 (CLAUDE.md §2)

- [ ] **Step 1: 写组件 - PlanInput**

```tsx
// src/features/smart-paths/PlanInput.tsx
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Loader2, Wand2 } from 'lucide-react';
import { useSmartPathStore } from '@/stores/smart-path';

interface PlanInputProps {
  workspace: string;
  onPlanGenerated: (plan: any) => void;
}

export function PlanInput({ workspace, onPlanGenerated }: PlanInputProps) {
  const [description, setDescription] = useState('');
  const [generating, setGenerating] = useState(false);
  const generatePlan = useSmartPathStore((s) => s.generatePlan);

  const handleGenerate = async () => {
    if (!description.trim() || !workspace) return;

    setGenerating(true);
    try {
      const plan = await generatePlan(description, workspace);
      onPlanGenerated(plan);
      setDescription('');
    } catch (err) {
      console.error('Failed to generate plan:', err);
    } finally {
      setGenerating(false);
    }
  };

  return (
    <div className="space-y-2">
      <label className="text-sm font-medium">输入你想做什么：</label>
      <div className="flex gap-2">
        <Textarea
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="用自然语言描述你想完成的任务..."
          className="flex-1 min-h-[80px]"
          disabled={generating}
        />
        <Button
          onClick={handleGenerate}
          disabled={!description.trim() || generating}
          className="shrink-0"
        >
          {generating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Wand2 className="h-4 w-4" />}
          生成计划
        </Button>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 编译检查**

```bash
pnpm build
```
Expected: 编译通过

- [ ] **Step 3: 提交**

```bash
git add src/features/smart-paths/PlanInput.tsx
git commit -m "feat(smart-path): add PlanInput component"
```

---

### Task 5: 创建前端组件 - StepCardsEditor

**Files:**
- Create: `src/features/smart-paths/StepCardsEditor.tsx` (约 200 行)

**Applicable Constraints:**
- 行数限制：200 行以内，可能需要拆分子组件 (CLAUDE.md §2)

- [ ] **Step 1: 写组件 - StepCardsEditor**

```tsx
// src/features/smart-paths/StepCardsEditor.tsx
import { useState } from 'react';
import { Plus, Trash2, GripVertical } from 'lucide-react';
import { Button } from '@/components/ui/button';
import type { SmartPathStep } from '@/types/settings';

interface StepCardsEditorProps {
  steps: SmartPathStep[];
  onChange: (steps: SmartPathStep[]) => void;
}

export function StepCardsEditor({ steps, onChange }: StepCardsEditorProps) {
  const [draggedIndex, setDraggedIndex] = useState<number | null>(null);

  const moveStep = (from: number, to: number) => {
    const newSteps = [...steps];
    const [removed] = newSteps.splice(from, 1);
    newSteps.splice(to, 0, removed);
    onChange(newSteps);
  };

  const addStep = () => {
    onChange([...steps, { mode: 'serial', actions: [] }]);
  };

  const removeStep = (index: number) => {
    onChange(steps.filter((_, i) => i !== index));
  };

  const updateStep = (index: number, step: SmartPathStep) => {
    const newSteps = [...steps];
    newSteps[index] = step;
    onChange(newSteps);
  };

  return (
    <div className="space-y-2">
      {steps.map((step, index) => (
        <div
          key={index}
          className="border rounded-md p-3 space-y-2"
          draggable
          onDragStart={() => setDraggedIndex(index)}
          onDragOver={(e) => {
            e.preventDefault();
            if (draggedIndex !== null && draggedIndex !== index) {
              moveStep(draggedIndex, index);
              setDraggedIndex(index);
            }
          }}
          onDragEnd={() => setDraggedIndex(null)}
        >
          <div className="flex items-center gap-2">
            <GripVertical className="h-4 w-4 text-muted-foreground cursor-move" />
            <span className="text-sm font-medium">步骤 {index + 1}</span>
            <span className="text-xs text-muted-foreground">
              {step.mode === 'serial' ? '串行' : '并行'}
            </span>
            <Button
              variant="ghost"
              size="icon"
              className="ml-auto h-6 w-6"
              onClick={() => removeStep(index)}
            >
              <Trash2 className="h-3 w-3" />
            </Button>
          </div>

          <div className="text-sm space-y-1">
            {step.actions.map((action, actionIndex) => (
              <div key={actionIndex} className="flex items-center gap-2 text-xs">
                <span className="px-2 py-0.5 rounded bg-muted">
                  {action.type === 'python' ? 'Python' : action.skillId || 'skill'}
                </span>
                <span className="text-muted-foreground">
                  {action.type === 'python' ? '执行 Python 代码' : `调用 ${action.skillId} skill`}
                </span>
              </div>
            ))}
          </div>
        </div>
      ))}

      <Button variant="outline" className="w-full" onClick={addStep}>
        <Plus className="h-4 w-4 mr-1" />
        添加步骤
      </Button>
    </div>
  );
}
```

- [ ] **Step 2: 编译检查**

```bash
pnpm build
```
Expected: 编译通过

- [ ] **Step 3: 提交**

```bash
git add src/features/smart-paths/StepCardsEditor.tsx
git commit -m "feat(smart-path): add StepCardsEditor component"
```

---

### Task 6: 创建前端组件 - JsonEditor

**Files:**
- Create: `src/features/smart-paths/JsonEditor.tsx` (约 60 行)

- [ ] **Step 1: 写组件 - JsonEditor**

```tsx
// src/features/smart-paths/JsonEditor.tsx
import { Textarea } from '@/components/ui/textarea';
import type { SmartPathStep } from '@/types/settings';

interface JsonEditorProps {
  steps: SmartPathStep[];
  onChange: (steps: SmartPathStep[]) => void;
}

export function JsonEditor({ steps, onChange }: JsonEditorProps) {
  const handleChange = (value: string) => {
    try {
      const parsed = JSON.parse(value);
      if (Array.isArray(parsed)) {
        onChange(parsed);
      }
    } catch {
      // Invalid JSON, ignore
    }
  };

  return (
    <div className="h-full">
      <Textarea
        value={JSON.stringify(steps, null, 2)}
        onChange={(e) => handleChange(e.target.value)}
        className="h-full font-mono text-xs resize-none"
        placeholder="Steps JSON..."
      />
    </div>
  );
}
```

- [ ] **Step 2: 编译检查**

```bash
pnpm build
```
Expected: 编译通过

- [ ] **Step 3: 提交**

```bash
git add src/features/smart-paths/JsonEditor.tsx
git commit -m "feat(smart-path): add JsonEditor component"
```

---

### Task 7: 重写主页面 - SmartPathPage

**Files:**
- Modify: `src/features/smart-paths/index.tsx` (重写，约 250 行)

**Applicable Constraints:**
- 行数限制：250 行以内，否则需要拆分 (CLAUDE.md §2)

- [ ] **Step 1: 备份现有文件**

```bash
cp src/features/smart-paths/index.tsx src/features/smart-paths/index.tsx.bak
```

- [ ] **Step 2: 重写 SmartPathPage**

```tsx
// src/features/smart-paths/index.tsx
import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronLeft, Save, Play, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useSmartPathStore } from '@/stores/smart-path';
import { useWsConnection } from '@/stores/ws-connection';
import { PlanInput } from './PlanInput';
import { StepCardsEditor } from './StepCardsEditor';
import { JsonEditor } from './JsonEditor';
import type { SmartPath, SmartPathStep } from '@/types/settings';

export function SmartPathPage() {
  const navigate = useNavigate();
  const { status: wsStatus } = useWsConnection();

  const [currentPlan, setCurrentPlan] = useState<SmartPath | null>(null);
  const [steps, setSteps] = useState<SmartPathStep[]>([]);
  const [loading, setLoading] = useState(false);
  const [workspace, setWorkspace] = useState('');

  const generatePlan = useSmartPathStore((s) => s.generatePlan);
  const saveFile = useSmartPathStore((s) => s.saveFile);
  const runPath = useSmartPathStore((s) => s.runPath);

  useEffect(() => {
    // 默认使用当前会话的 workspace
    const currentWorkspace = sessionStorage.getItem('currentWorkspace') || '';
    setWorkspace(currentWorkspace);
  }, []);

  const handlePlanGenerated = (plan: any) => {
    setCurrentPlan({
      id: `plan-${Date.now()}`,
      name: plan.name,
      workspace,
      steps: '',
      status: 'draft',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    });
    setSteps(plan.steps);
  };

  const handleSave = async () => {
    if (!currentPlan) return;

    setLoading(true);
    try {
      const planToSave = { ...currentPlan, steps };
      await saveFile(currentPlan.id, planToSave, workspace);
    } finally {
      setLoading(false);
    }
  };

  const handleRun = async () => {
    if (!currentPlan) return;
    await runPath(currentPlan.id);
  };

  return (
    <div className="flex h-full">
      {/* Left sidebar - file list */}
      <div className="w-64 shrink-0 border-r p-3">
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground mb-3"
        >
          <ChevronLeft className="h-4 w-4" /> 返回
        </button>
        <div className="text-xs text-muted-foreground">
          计划文件列表
        </div>
      </div>

      {/* Main content */}
      <div className="flex-1 flex overflow-hidden">
        {/* Left: Input + Step Cards */}
        <div className="flex-1 overflow-y-auto p-6 space-y-6">
          {!currentPlan ? (
            <PlanInput workspace={workspace} onPlanGenerated={handlePlanGenerated} />
          ) : (
            <>
              <div className="flex items-center justify-between">
                <Input
                  value={currentPlan.name}
                  onChange={(e) => setCurrentPlan({ ...currentPlan, name: e.target.value })}
                  className="text-lg font-semibold"
                />
                <div className="flex gap-2">
                  <Button variant="outline" onClick={handleSave} disabled={loading}>
                    {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
                    保存
                  </Button>
                  <Button onClick={handleRun}>
                    <Play className="h-4 w-4 mr-1" />
                    执行
                  </Button>
                </div>
              </div>

              <StepCardsEditor steps={steps} onChange={setSteps} />
            </>
          )}
        </div>

        {/* Right: JSON Editor */}
        {currentPlan && (
          <div className="w-1/2 border-l p-6">
            <h3 className="text-sm font-medium mb-2">JSON 编辑器</h3>
            <div className="h-[calc(100vh-200px)]">
              <JsonEditor steps={steps} onChange={setSteps} />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 3: 更新 store 方法**

```typescript
// src/stores/smart-path.ts
export const useSmartPathStore = create<SmartPathState>((set, get) => ({
  // ... 现有方法

  generatePlan: async (description, workspace) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<any>((resolve, reject) => {
      const unsub = wrapHandler(client, 'smartpath.planGenerated', (data) => {
        unsub();
        unsubErr();
        resolve(data.plan as any);
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'smartpath.generateFromNL', description, workspace });
    });
  },

  saveFile: async (planId, plan, workspace) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    const filePath = `${workspace}/.sman/paths/${planId}.md`;

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
      client.send({ type: 'smartpath.saveFile', filePath, plan });
    });
  },
}));
```

- [ ] **Step 4: 编译检查**

```bash
pnpm build
```
Expected: 编译通过

- [ ] **Step 5: 提交**

```bash
git add src/features/smart-paths/index.tsx src/stores/smart-path.ts
git commit -m "refactor(smart-path): rewrite main page for NL-based workflow"
```

---

## Chunk 4: 集成测试

### Task 8: 端到端测试

**Files:**
- Test: 手动测试或新增集成测试

- [ ] **Step 1: 启动开发服务器**

```bash
pnpm dev
```

- [ ] **Step 2: 手动测试流程**

1. 打开 Smart Path 页面
2. 输入自然语言："用python查询用户列表，然后调用 validation skill 处理"
3. 点击"生成计划"
4. 验证：左侧显示步骤卡片，右侧显示 JSON
5. 拖拽步骤排序
6. 点击"保存"
7. 验证：文件保存到 `{workspace}/.sman/paths/{id}.md`
8. 点击"执行"
9. 验证：计划正确执行

- [ ] **Step 3: 编译生产版本**

```bash
pnpm build
pnpm build:electron
```
Expected: 编译通过

- [ ] **Step 4: 清理备份文件**

```bash
rm src/features/smart-paths/index.tsx.bak
```

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "test(smart-path): add e2e testing for NL-based workflow"
```

---

## Chunk 5: 文档和总结

### Task 9: 更新设计文档

- [ ] **Step 1: 更新设计文档**

```bash
# 更新 docs/superpowers/specs/2026-04-18-smart-path-design.md
# 添加新的架构说明和使用流程
```

- [ ] **Step 2: 提交**

```bash
git add docs/superpowers/specs/2026-04-18-smart-path-design.md
git commit -m "docs(smart-path): update design document for NL-based workflow"
```

---

## 验收标准

- [ ] 前端：自然语言输入 → 可视化编辑器 → 保存/执行流程完整
- [ ] 后端：generateFromNL、saveFile、loadFile、deleteFile 接口完整
- [ ] 存储：计划保存到 `{workspace}/.sman/paths/*.md`
- [ ] 执行：SmartPathEngine 正确解析并执行 .md 文件
- [ ] 测试：单元测试 + 手动 E2E 测试通过
- [ ] 编译：生产构建无错误
- [ ] 约束合规：所有代码符合 CODING_RULES.md 和 CLAUDE.md 规范

---

## 下一步

计划完成并保存到 `docs/superpowers/plans/2026-04-20-smart-path-refactor.md`。

准备执行？
