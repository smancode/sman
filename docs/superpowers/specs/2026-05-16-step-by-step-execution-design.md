# 地球路径逐步执行模式设计

## 目标

为地球路径（SmartPath）新增逐步执行模式，用户可逐步执行每个步骤，每步完成后暂停，允许编辑结果和描述，然后选择重新执行或继续下一步。

同时强化 tmp 目录规范：临时文件放 tmp/，可复用资源放 references/，run.md 必须记录可复用资源路径。

## 方案

混合方案：后端拆分引擎为协调 + 单步执行 + 收尾三个阶段 API，前端控制逐步执行流程。

## 流程

```
用户点"逐步执行"
  → 调用 smartpath.orchestrate → 返回蓝图 + runId
  → 清空 tmp/ 目录
  → 逐步循环:
    for step[0..N]:
      调用 smartpath.runStep（传入蓝图 + 步骤索引 + 前序结果）
      流式显示执行结果
      步骤完成 → 暂停
        - 用户可编辑结果文本
        - 用户可修改步骤描述
        - 点"重新执行" → 重新调用 runStep
        - 点"继续" → 进入下一步
  → 调用 smartpath.finalize → 生成报告 + 更新 run.md
```

## 后端 API

### smartpath.orchestrate

**请求：** `{ type: 'smartpath.orchestrate', pathId, workspace, args }`

**响应：** `{ type: 'smartpath.orchestrated', pathId, blueprint: PathBlueprint, runId }`

- 调用引擎的 `orchestrateOnly()` 方法
- 创建 run 记录（status: 'running'）
- 清空 tmp/ 目录
- 返回蓝图

### smartpath.runStep

**请求：**
```ts
{
  type: 'smartpath.runStep', pathId, workspace, runId,
  blueprint: PathBlueprint,
  stepIndex: number,
  priorResults: string[],
  args?: string,
}
```

**响应：** 复用 `smartpath.stepExecutionProgress` 和 `smartpath.stepExecutionResult`

- 调用引擎的 `runSingleStep()` 方法
- 流式返回执行过程
- 提取 [REFERENCE:...] 保存到 references/
- 返回步骤完整结果

### smartpath.finalize

**请求：**
```ts
{
  type: 'smartpath.finalize', pathId, workspace, runId,
  blueprint: PathBlueprint,
  stepResults: string[],
}
```

**响应：** `{ type: 'smartpath.completed', pathId, path, references }`

- 调用引擎的 `finalize()` 方法
- 生成执行报告
- 更新 run.md（强调记录可复用资源路径）
- 更新 path 状态为 completed
- 返回更新后的 path 和 references

## 后端代码改动

### server/smart-path-engine.ts

从 `runWithResults()` 拆出 3 个公开方法：

1. `orchestrateOnly(pathId, workspace, args)` → `{ blueprint, runId }`
2. `runSingleStep(pathId, workspace, runId, blueprint, stepIndex, priorResults, args, onStepProgress)` → `string`
3. `finalize(pathId, workspace, runId, blueprint, stepResults)` → `void`

导出 `PathBlueprint` 和 `StepPlan` 类型。

`STEP_SYSTEM_PROMPT` 新增 tmp 规则：

```
临时文件规则：
1. 每个步骤的临时输出必须放在 tmp/ 目录（相对于工作区根目录）
2. tmp/ 里的文件不在步骤间复用，每次运行开始时自动清空
3. 要跨步骤复用的文件必须保存到 references/ 目录，用 [REFERENCE:filename] 标记
4. 脚本、配置文件等可复用资源必须放到 references/，禁止放 tmp/
```

`updateRunGuide` 的 prompt 增加要求：

```
必须在「可复用资源」部分列出所有 references/ 中文件的完整路径和用途说明。
包括脚本路径、配置文件路径、文档路径等。
```

### server/smart-path-store.ts

新增 `clearTmpDir(workspace, pathId)` 方法，删除并重建 `{workspace}/.sman/paths/{pathId}/tmp/` 目录。

### server/index.ts

新增 3 个 WebSocket handler：`smartpath.orchestrate`、`smartpath.runStep`、`smartpath.finalize`。

## 前端代码改动

### src/stores/smart-path.ts

新增状态：

```ts
stepping: boolean;
stepBlueprint: PathBlueprint | null;
stepRunId: string | null;
stepResults: string[];
stepDescriptions: string[];
```

新增方法：

```ts
startStepping(pathId, workspace, args): Promise<void>
runStepContinue(pathId, workspace): Promise<void>
runStepRedo(pathId, workspace, stepIndex): Promise<void>
updateStepResult(index, value): void
updateStepDescription(index, value): void
finalizeStepping(pathId, workspace): Promise<void>
cancelStepping(): void
```

### src/features/smart-paths/index.tsx

PathDetail：
- 新增"逐步执行"按钮（与"执行"并列）
- stepping 状态下显示逐步执行 UI

StepViewCard 增强（逐步模式）：
- 步骤完成后显示可编辑的结果 Textarea
- 显示可编辑的描述 Input
- 底部操作栏："重新执行" + "继续"按钮
- 当前执行中的步骤显示流式输出 + loading

### src/locales/zh-CN.json + en-US.json

新增约 10 个翻译 key。

### src/types/settings.ts

导入 `PathBlueprint` 类型定义。

## dev-workflow Skill 改动

### .claude/skills/dev-workflow/SKILL.md

新增 tmp/references 规则段落到 Step 3 之前：

```markdown
## 临时文件与可复用资源规范

- 每个步骤的临时输出必须放在 tmp/ 目录
- tmp/ 里的文件不在步骤间复用，每次运行自动清空
- 要跨步骤复用的文件必须保存到 references/ 目录
- 脚本、配置文件等可复用资源必须放到 references/，禁止放 tmp/
- run.md 必须记录所有可复用资源的完整路径和用途
```

## 关键文件索引

| 文件 | 改动 |
|------|------|
| `server/smart-path-engine.ts` | 拆出 3 个公开方法 + 导出类型 + STEP_SYSTEM_PROMPT 增加规则 |
| `server/smart-path-store.ts` | 新增 clearTmpDir 方法 |
| `server/index.ts` | 新增 3 个 WS handler |
| `server/types.ts` | 导出 PathBlueprint 类型 |
| `src/stores/smart-path.ts` | 新增逐步执行状态和方法 |
| `src/features/smart-paths/index.tsx` | UI 变更：按钮 + 可编辑步骤 |
| `src/locales/zh-CN.json` | 新增翻译 |
| `src/locales/en-US.json` | 新增翻译 |
| `src/types/settings.ts` | 新增类型 |
| `.claude/skills/dev-workflow/SKILL.md` | 新增 tmp 规则 |
