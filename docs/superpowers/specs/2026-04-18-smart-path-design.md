# Smart Path 多步骤能力系统设计文档 v2

## 背景

改造现有 Smart Path 系统，从复杂的 skill/python 手动配置简化为自然语言输入 + 可视化编辑。用户输入需求描述，Claude SDK 自动生成执行计划，支持可视化卡片拖拽编辑和 JSON 代码编辑双模式。

## 核心概念

| 术语 | 说明 |
|------|------|
| **Path (计划)** | 一组步骤的编排配置，以 Markdown 文件存储（YAML frontmatter） |
| **Step (步骤)** | 一个执行单元，含 `mode: serial/parallel` + `actions[]` |
| **Action (动作)** | 最小执行单元：`{ type: 'skill', skillId: 'xxx' }` 或 `{ type: 'python', code: '...' }` |
| **Run (执行)** | 一次 Path 的执行记录 |
| **NL Generation (自然语言生成)** | 用户输入需求，Claude SDK 自动生成执行计划 |

## 数据流示例

```
用户输入: "分析用户登录日志，找出异常登录行为"
         ↓
Claude SDK 生成执行计划 (JSON)
         ↓
用户可视化编辑 (拖拽卡片 / JSON 编辑)
         ↓
保存为 .md 文件 ({workspace}/.sman/paths/{planId}.md)
         ↓
执行引擎运行计划
```

## 约束

- 参数严格校验（`CODING_RULES.md`）：path 名、step 配置缺失直接抛异常
- 单一职责：store 只做文件 I/O，engine 只做执行，不混业务逻辑
- 测试放 `tests/e2e-smartpath.test.js`
- 不返回默认值，不满足条件直接抛异常
- 命名严格按用户要求

## 类型定义

```typescript
// Action 类型
interface SmartPathAction {
  type: 'skill' | 'python';
  skillId?: string;        // type='skill' 时必填
  code?: string;           // type='python' 时必填
}

// Step 类型
interface SmartPathStep {
  mode: 'serial' | 'parallel';
  actions: SmartPathAction[];
}

// Path 配置
interface SmartPath {
  id: string;
  name: string;
  description: string;
  workspace: string;
  steps: SmartPathStep[];
  status: 'draft' | 'ready' | 'running' | 'completed' | 'failed';
  createdAt: string;
  updatedAt: string;
}

// Run 记录
interface SmartPathRun {
  id: string;
  pathId: string;
  status: 'running' | 'completed' | 'failed';
  stepResults: Record<number, unknown>;
  startedAt: string;
  finishedAt?: string;
  errorMessage?: string;
}
```

## 架构

```
前端: SmartPathPage
  - PlanInput (自然语言输入)
  - StepCardsEditor (可视化卡片编辑)
  - JsonEditor (JSON 代码编辑)
         ↓ WebSocket
后端: SmartPathStore (文件系统) + SmartPathEngine (执行)
  - 存储: {workspace}/.sman/paths/{planId}.md (Markdown + YAML frontmatter)
  - 生成: ClaudeSessionManager.sendMessageForCron (支持 Anthropic API)
         ↓
     ClaudeSessionManager (执行 skill)
     child_process (执行 python)
```

## 文件存储格式

每个 Path 存储为独立的 Markdown 文件：

```
{workspace}/.sman/paths/{planId}.md
---
name: "分析用户登录日志"
description: "找出异常登录行为"
workspace: "/path/to/workspace"
created_at: "2026-04-20T12:00:00.000Z"
updated_at: "2026-04-20T12:00:00.000Z"
status: "draft"
steps:
  - mode: "serial"
    actions:
      - type: "python"
        code: "print('分析日志')"
---
# 分析用户登录日志

找出异常登录行为
```

## WebSocket API

### 计划生成

| 类型 | 方向 | 说明 |
|------|------|------|
| `smartpath.generateFromNL` | C→S | 自然语言生成计划 |
| `smartpath.generated` | S→C | 返回生成的计划 |

**请求参数：**
```typescript
{
  description: string;  // 需求描述
  workspace: string;    // 工作区路径
}
```

**响应：**
```typescript
{
  type: 'smartpath.generated';
  payload: {
    plan: SmartPath;
  }
}
```

**限制：** 目前仅支持 Anthropic API，暂不支持 Zhipu AI 等其他提供商

### 文件操作

| 类型 | 方向 | 说明 |
|------|------|------|
| `smartpath.listFiles` | C→S | 列出工作区所有计划 |
| `smartpath.fileList` | S→C | 返回计划列表 |
| `smartpath.loadFile` | C→S | 加载指定计划 |
| `smartpath.fileLoaded` | S→C | 返回计划内容 |
| `smartpath.saveFile` | C→S | 保存计划到文件 |
| `smartpath.fileSaved` | S→C | 保存成功确认 |
| `smartpath.deleteFile` | C→S | 删除计划文件 |
| `smartpath.fileDeleted` | S→C | 删除成功确认 |

**smartpath.listFiles 请求参数：**
```typescript
{
  workspace: string;
}
```

**smartpath.loadFile 请求参数：**
```typescript
{
  workspace: string;
  planId: string;
}
```

**smartpath.saveFile 请求参数：**
```typescript
{
  planId: string;
  workspace: string;
  plan: {
    name: string;
    description: string;
    steps: SmartPathStep[];
  }
}
```

**smartpath.deleteFile 请求参数：**
```typescript
{
  workspace: string;
  planId: string;
}
```

### 执行操作

| 类型 | 方向 | 说明 |
|------|------|------|
| `smartpath.run` | C→S | 执行计划 |
| `smartpath.running` | S→C | 执行开始 |
| `smartpath.progress` | S→C | 执行进度更新 |
| `smartpath.completed` | S→C | 执行完成 |
| `smartpath.failed` | S→C | 执行失败 |

## 执行引擎逻辑

1. 加载 Path 配置（支持 .md 文件路径或 planId）
2. 校验配置（steps 非空，每个 action 参数完整）
3. 创建 Run 记录（`{workspace}/.sman/paths/{pathId}/runs/{runId}.json`）
4. 遍历 steps：
   - **serial 模式**：按顺序执行 actions，前一个结果作为后一个的输入
   - **parallel 模式**：并发执行 actions，结果合并为数组
   - 每步结果写入 context：`ctx.steps[index] = result`
5. 最终 context 作为 Run 的 `stepResults`
6. 更新 Run 状态为 `completed` 或 `failed`

## Skill 执行方式

复用 `sessionManager.sendMessageForCron`：
- 创建临时 session（`smartpath-{runId}-{stepIdx}-{actionIdx}`）
- 将 skill 内容 + 上下文（前序 step 结果）拼接为 prompt
- 发送给 Claude，获取文本回复作为 action 结果

## Python 执行方式

- 将 code 写入临时文件
- `execFile('python3', [script])` 执行
- stdout 作为结果（尝试 JSON.parse，失败则原样返回）
- 注入 `ctx` 变量供脚本读取前序结果

## 与旧版本的关系

- **Batch 系统**：保留不动，并行共存
- **Smart Path v1**：完全替换为新的文件系统存储
- **路由**：`/smart-paths` 前端路由，后端通过 WebSocket 通信

## 前端组件

### 核心组件

| 组件 | 路径 | 职责 |
|------|------|------|
| `SmartPathPage` | `src/features/smart-paths/index.tsx` | 主页面，集成所有子组件 |
| `PlanInput` | `src/features/smart-paths/PlanInput.tsx` | 自然语言输入框 |
| `StepCardsEditor` | `src/features/smart-paths/StepCardsEditor.tsx` | 可视化卡片编辑器 |
| `JsonEditor` | `src/features/smart-paths/JsonEditor.tsx` | JSON 代码编辑器 |

### 编辑器双模式

- **可视化卡片模式**：拖拽排序、添加/删除步骤、切换串行/并行
- **JSON 编辑模式**：直接编辑 JSON，支持实时校验
- **双向同步**：两种模式数据实时同步

## 后端模块

### 核心模块

| 模块 | 路径 | 职责 |
|------|------|------|
| `SmartPathStore` | `server/smart-path-store.ts` | 文件系统 I/O（.md 文件读写） |
| `SmartPathEngine` | `server/smart-path-engine.ts` | 执行引擎（调用 skill/python） |
| WebSocket Handlers | `server/index.ts` | 消息处理（生成、保存、加载、删除） |

### SmartPathStore 方法

- `savePlan(plan)` - 保存计划到 .md 文件
- `listPlans(workspace)` - 列出工作区所有计划
- `loadPlan(filePath)` - 加载指定计划
- `deletePlan(filePath)` - 删除计划文件
- `updatePlan(filePath, updates)` - 更新计划
- `createRun(pathId)` - 创建执行记录
- `updateRun(runId, updates)` - 更新执行记录
- `listRuns(pathId)` - 列出执行历史

## 已知限制

1. **LLM 提供商支持**：计划生成功能目前仅支持 Anthropic API，暂不支持 Zhipu AI、OpenAI 等其他提供商
2. **执行引擎**：支持所有 LLM 提供商（通过 sessionManager 统一接口）

## 测试

运行 E2E 测试：
```bash
node tests/e2e-smartpath.test.js
```

测试覆盖：
- ✅ 文件保存（`smartpath.saveFile`）
- ✅ 文件列表（`smartpath.listFiles`）
- ✅ 文件加载（`smartpath.loadFile`）
- ✅ 文件删除（`smartpath.deleteFile`）
- ⚠️  自然语言生成（`smartpath.generateFromNL` - 需要 Anthropic API）

## Python 执行方式

- 将 code 写入临时文件
- `execFile('python3', [script])` 执行
- stdout 作为结果（尝试 JSON.parse，失败则原样返回）
- 注入 `ctx` 变量供脚本读取前序结果

## 与现有 Batch 的关系

Batch 系统保留不动，新增 Smart Path 模块并行共存。路由 `/smart-paths`。
