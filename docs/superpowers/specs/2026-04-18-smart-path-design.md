# Smart Path 多步骤能力系统设计文档

## 背景

改造现有 Batch 任务系统为多步骤能力编排系统。用户可自定义 Path（路径），每个 Path 包含多个 Step，每个 Step 可配置串行/并行模式，Step 内可拼接多个 Action（执行 skill 或 Python 脚本）。

## 核心概念

| 术语 | 说明 |
|------|------|
| **Path** | 一组步骤的编排配置，用户可配置多个 |
| **Step** | 一个执行单元，含 `mode: serial/parallel` + `actions[]` |
| **Action** | 最小执行单元：`{ type: 'skill', skillId: 'xxx' }` 或 `{ type: 'python', code: '...' }` |
| **Run** | 一次 Path 的执行记录 |
| **Context** | 共享状态池，`ctx.steps[i]` 存第 i 步的输出，供后续 step 读取 |

## 数据流示例

```
step0: skill1 → 结果写入 ctx.steps[0]
         ↓
step1: skill2 + skill3(parallel) → 结果合并写入 ctx.steps[1]
         ↓
step2: python(ctx.steps[0], ctx.steps[1]) → 最终结果
```

## 约束

- 参数严格校验（`CODING_RULES.md`）：path 名、step 配置缺失直接抛异常
- 单一职责：store 只做存储，engine 只做执行
- 测试放 `tests/server/smart-path-*.test.ts`
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
前端: SmartPathPage (列表/配置/执行)
         ↓ WebSocket
后端: SmartPathStore (SQLite) + SmartPathEngine (执行)
         ↓
     ClaudeSessionManager (sendMessageForCron 执行 skill)
         ↓
     child_process (execFile 执行 python)
```

## 数据库 Schema

```sql
CREATE TABLE smart_paths (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  workspace TEXT NOT NULL,
  steps TEXT NOT NULL,         -- JSON
  status TEXT NOT NULL DEFAULT 'draft',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE smart_path_runs (
  id TEXT PRIMARY KEY,
  path_id TEXT NOT NULL,
  status TEXT NOT NULL,
  step_results TEXT NOT NULL DEFAULT '{}',
  started_at TEXT NOT NULL,
  finished_at TEXT,
  error_message TEXT,
  FOREIGN KEY (path_id) REFERENCES smart_paths(id) ON DELETE CASCADE
);
```

## WebSocket API

| 类型 | 方向 | 说明 |
|------|------|------|
| `smartpath.create` | C→S | 创建 Path |
| `smartpath.update` | C→S | 更新 Path |
| `smartpath.delete` | C→S | 删除 Path |
| `smartpath.list` | C→S | 列出 Paths |
| `smartpath.run` | C→S | 执行 Path |
| `smartpath.run.status` | S→C | 运行状态更新 |
| `smartpath.run.completed` | S→C | 运行完成 |

## 执行引擎逻辑

1. 校验 Path 配置（steps 非空，每个 action 参数完整）
2. 创建 Run 记录，状态 `running`
3. 遍历 steps：
   - serial：按顺序执行 actions，前一个结果作为后一个的输入
   - parallel：并发执行 actions，结果合并为数组
   - 每步结果写入 context：`ctx.steps[index] = result`
4. 最终 context 作为 Run 的 `stepResults`
5. 更新 Run 状态为 `completed` 或 `failed`

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

## 与现有 Batch 的关系

Batch 系统保留不动，新增 Smart Path 模块并行共存。路由 `/smart-paths`。
