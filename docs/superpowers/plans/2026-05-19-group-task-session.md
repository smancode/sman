# Group Task as Session 实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 组合任务等同于普通会话，用特殊工作目录 + 预设 prompt 实现跨多业务系统的任务协作

**Architecture:** Task = Session + group workspace。创建组合时建目录写 CLAUDE.md，新建任务时创建 session 并自动发 task prompt。复用全部 chat 基础设施。

**Tech Stack:** Express WebSocket (backend), React + Zustand (frontend), SQLite (storage), Claude Agent SDK (AI)

**Spec:** `docs/superpowers/specs/2026-05-19-group-task-session-design.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `server/templates/group-claude.md` | Group workspace 的 CLAUDE.md 模板 |
| `server/templates/group-task-prompt.md` | 任务分析指导 prompt 模板 |

### Modified Files
| File | Change |
|------|--------|
| `server/group-store.ts` | 简化 group_tasks 表，删除 workspace_tasks 相关代码 |
| `server/session-store.ts:175-185` | listSessions 过滤 group task sessions |
| `server/index.ts:2613-2899` | 改造 group.create/delete, group-task.create/delete/list |
| `src/schemas/group.ts` | 简化 GroupTask，删除 WorkspaceTask |
| `src/stores/group.ts` | 适配新数据模型，维护 sessionId→groupId 映射 |
| `src/stores/chat.ts:402-502` | switchSession 处理 group task session |
| `src/components/GroupItem.tsx:108-247` | 移除编辑按钮，workspace 折叠，task 点击改为 switchSession |
| `src/components/SessionTree.tsx:457-508` | task 点击进入 chat，简化 task handlers |
| `src/components/layout/Titlebar.tsx:41-116` | group task session 时显示任务卡片 |
| `src/locales/zh-CN.json` | 新增翻译 key |
| `src/locales/en-US.json` | 新增翻译 key |
| `src/app/routes.tsx:10,25` | 删除 GroupTaskPage 路由 |

### Deleted Files
| File | Reason |
|------|--------|
| `server/group-task-analyzer.ts` | 分析由 AI 多轮对话完成 |
| `src/features/group-tasks/GroupTaskPage.tsx` | 不再需要独立页面 |

---

## Chunk 1: 后端数据层 + 模板

### Task 1: 创建 CLAUDE.md 模板

**Files:**
- Create: `server/templates/group-claude.md`

- [ ] **Step 1: 创建模板文件**

```markdown
# 多业务系统协作工作区

## 关联业务系统
{workspace_list}

## 工作规则
1. 收到任务后，先收集信息：读取每个关联业务系统的 CLAUDE.md、扫描项目结构、搜索相关 skill
2. 分析任务涉及哪些业务系统，如果一个功能在多个系统都能实现，给出推荐方案并说明理由
3. 与用户确认方案后，将最终计划写入当前目录的 task.md（[ ] TODO 格式）
4. 按 task.md 中的 TODO 顺序逐个执行子任务，每完成一个更新为 [x]
5. 每个子任务明确标注对应的业务系统目录
6. 需要修改代码时，先 cd 到对应的业务系统目录，理解现有代码再操作
7. 所有修改完成后，给出变更总结

## 业务系统路径
{workspace_details}
```

- [ ] **Step 2: 创建 task prompt 模板**

Create: `server/templates/group-task-prompt.md`

```markdown
# 任务：{title}

{description}

---

请按以下流程处理：

## 第一步：收集信息
- 读取每个关联业务系统的 CLAUDE.md，了解项目技术栈和结构
- 搜索各业务系统的 skill，看是否有可直接使用的实现方案
- 理解各系统的职责边界

## 第二步：分析并拆分
- 确定任务涉及哪些业务系统
- 如果一个功能在多个系统都能实现，给出推荐方案及理由，让用户决策
- 拆分为各系统的子任务，明确依赖关系

## 第三步：确认方案
{confirm_instruction}

## 第四步：写入执行计划
用户确认后，将最终方案写入当前目录的 task.md，格式：

# 任务：{title}
## 执行计划
- [ ] **{系统A}**: 子任务1
- [ ] **{系统B}**: 子任务2

## 第五步：逐个执行
按 task.md 中的 TODO 顺序执行，每完成一个更新 [x]。

## 业务系统路径
{workspace_paths}
```

- [ ] **Step 3: Commit**

```bash
git add server/templates/group-claude.md server/templates/group-task-prompt.md
git commit -m "feat(group): add CLAUDE.md and task prompt templates"
```

---

### Task 2: 简化 group-store 数据模型

**Files:**
- Modify: `server/group-store.ts`

- [ ] **Step 1: 简化 GroupTask 接口，删除 WorkspaceTask**

在 `server/group-store.ts` 中：

替换 `GroupTask` interface（lines 13-24）为：
```typescript
export interface GroupTask {
  id: string;
  groupId: string;
  title: string;
  description: string | null;
  autoDispatch: number;
  status: string;
  createdAt: string;
  updatedAt: string;
}
```

删除 `WorkspaceTask` interface（lines 26-35）。

删除 `CreateGroupTaskInput` interface（lines 43-50），替换为：
```typescript
export interface CreateGroupTaskInput {
  id: string;
  groupId: string;
  title: string;
  description?: string;
  autoDispatch?: number;
}
```

- [ ] **Step 2: 简化 init() 表结构**

替换 `init()` 中的 CREATE TABLE（lines 62-103）：

删除 `workspace_tasks` 表的 CREATE TABLE 和相关索引。

替换 `group_tasks` 表的 CREATE：
```sql
CREATE TABLE IF NOT EXISTS group_tasks (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  auto_dispatch INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'draft',
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE
);
```

删除不再需要的索引（`idx_workspace_tasks_*`）。

- [ ] **Step 3: 更新 CRUD 方法**

替换 `createGroupTask`（lines 178-185）：
```typescript
createGroupTask(input: CreateGroupTaskInput): GroupTask {
  const { id, groupId, title, description, autoDispatch } = input;
  this.db.prepare(
    'INSERT INTO group_tasks (id, group_id, title, description, auto_dispatch) VALUES (?, ?, ?, ?, ?)'
  ).run(id, groupId, title, description || null, autoDispatch ?? 0);
  return this.getGroupTask(id)!;
}
```

更新 `getGroupTask`（lines 187-192）和 `listGroupTasks`（lines 194-198）的 SQL 使用别名映射：
```sql
SELECT id, group_id as groupId, title, description, auto_dispatch as autoDispatch, status, created_at as createdAt, updated_at as updatedAt FROM group_tasks WHERE ...
```

简化 `updateGroupTask`（lines 200-242），只保留 status 和 title 更新。

删除所有 `WorkspaceTask` 相关方法（lines 250-307）：`createWorkspaceTask`、`getWorkspaceTask`、`listWorkspaceTasks`、`updateWorkspaceTask`、`deleteWorkspaceTask`。

- [ ] **Step 4: 新增 group 目录管理方法**

在 `GroupStore` 中添加：
```typescript
private groupBaseDir: string;

constructor(db: Database.Database) {
  this.db = db;
  this.log = createLogger('GroupStore');
  this.groupBaseDir = path.join(os.homedir(), '.sman', 'group');
  this.init();
}

getGroupDir(groupId: string): string {
  return path.join(this.groupBaseDir, groupId);
}

ensureGroupDir(groupId: string, workspaceIds: string[]): void {
  const dir = this.getGroupDir(groupId);
  fs.mkdirSync(dir, { recursive: true });
  // Read template and replace placeholders
  const templatePath = path.join(path.dirname(fileURLToPath(import.meta.url)), 'templates', 'group-claude.md');
  if (!fs.existsSync(path.join(dir, 'CLAUDE.md'))) {
    let template = fs.readFileSync(templatePath, 'utf-8');
    const names = workspaceIds.map(ws => ws.split(/[/\\]/).pop() || ws);
    template = template.replace('{workspace_list}', names.map(n => `- ${n}`).join('\n'));
    template = template.replace('{workspace_details}', workspaceIds.join('\n'));
    fs.writeFileSync(path.join(dir, 'CLAUDE.md'), template);
  }
}

deleteGroupDir(groupId: string): void {
  const dir = this.getGroupDir(groupId);
  if (fs.existsSync(dir)) {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}
```

需要在文件顶部添加 `import fs from 'fs'; import path from 'path'; import os from 'os'; import { fileURLToPath } from 'url';`

- [ ] **Step 5: Commit**

```bash
git add server/group-store.ts
git commit -m "refactor(group-store): simplify data model, add group dir management"
```

---

### Task 3: session.list 过滤 group task sessions

**Files:**
- Modify: `server/session-store.ts:175-185`

- [ ] **Step 1: 修改 listSessions SQL 添加 workspace 过滤**

在 `session-store.ts` 的 `listSessions` 方法中，SQL WHERE 条件追加排除 group 目录：

找到 `listSessions` 方法（约 line 175），修改 SQL 的 WHERE 子句，在现有的 `is_cron = 0 AND deleted_at IS NULL` 之后追加：
```sql
AND workspace NOT LIKE '{groupBaseDir}%'
```

需要在 `SessionStore` 构造函数中计算 `groupBaseDir`：
```typescript
private groupBaseDir: string;
// In constructor:
this.groupBaseDir = path.join(os.homedir(), '.sman', 'group').replace(/'/g, "''") + '/%';
```

在 SQL 中使用参数化或直接拼接（因为是内部常量，不含用户输入）。

- [ ] **Step 2: Commit**

```bash
git add server/session-store.ts
git commit -m "feat(session): exclude group task sessions from session list"
```

---

## Chunk 2: 后端 API 改造

### Task 4: 改造 group.create 处理器

**Files:**
- Modify: `server/index.ts:2613-2640`

- [ ] **Step 1: 在 group.create 中添加目录创建**

在 `server/index.ts` 的 `group.create` handler（line 2613）中，在 `groupStore.createGroup(...)` 之后添加：

```typescript
const group = groupStore.createGroup({
  id: String(msg.groupId),
  name: String(msg.name),
  workspaceIds: msg.workspaceIds.map(String),
});
// Create group workspace directory + CLAUDE.md
groupStore.ensureGroupDir(String(msg.groupId), msg.workspaceIds.map(String));
```

- [ ] **Step 2: Commit**

```bash
git add server/index.ts
git commit -m "feat(group): create group dir on group.create"
```

---

### Task 5: 改造 group.delete 添加级联清理

**Files:**
- Modify: `server/index.ts:2687-2713`

- [ ] **Step 1: 在 group.delete 中添加级联清理**

替换 `group.delete` handler（lines 2687-2713）：

```typescript
case 'group.delete': {
  if (!msg.groupId) {
    ws.send(JSON.stringify({ type: 'error', error: 'Missing groupId' }));
    break;
  }
  try {
    const groupId = String(msg.groupId);
    // Clean up all task sessions first
    const tasks = groupStore.listGroupTasks(groupId);
    for (const task of tasks) {
      try {
        sessionManager.abort(task.id);
        sessionManager.closeV2Session(task.id);
        sessionStore.deleteSession(task.id);
      } catch { /* session may not exist */ }
    }
    // Delete group directory
    groupStore.deleteGroupDir(groupId);
    // Delete from database
    const deleted = groupStore.deleteGroup(groupId);
    if (!deleted) {
      ws.send(JSON.stringify({ type: 'error', error: 'Group not found' }));
      break;
    }
    // Broadcast updated lists
    const parsedGroups = groupStore.listGroups().map(g => ({
      ...g,
      workspaceIds: JSON.parse(g.workspaceIds || '[]')
    }));
    broadcastToAllAuthenticated(JSON.stringify({
      type: 'group.list',
      groups: parsedGroups,
    }));
    // Send updated task list to clear deleted tasks
    ws.send(JSON.stringify({
      type: 'group-task.list',
      groupId,
      groupTasks: [],
    }));
    ws.send(JSON.stringify({ type: 'group.deleted', groupId }));
  } catch (err) {
    ws.send(JSON.stringify({ type: 'error', error: err instanceof Error ? err.message : String(err) }));
  }
  break;
}
```

- [ ] **Step 2: Commit**

```bash
git add server/index.ts
git commit -m "feat(group): cascade cleanup on group delete"
```

---

### Task 6: 改造 group-task.create — 创建 session + 发送 prompt

**Files:**
- Modify: `server/index.ts:2716-2739`

- [ ] **Step 1: 读取 task prompt 模板**

在 `server/index.ts` 顶部添加模板加载函数（在其他 import 之后）：

```typescript
import { readFileSync } from 'fs';
import { dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));

function loadGroupTaskPrompt(title: string, description: string, workspaceIds: string[], autoDispatch: boolean): string {
  const templatePath = path.join(__dirname, 'templates', 'group-task-prompt.md');
  let prompt = readFileSync(templatePath, 'utf-8');
  prompt = prompt.replace(/\{title\}/g, title);
  prompt = prompt.replace(/\{description\}/g, description || '');
  const confirmInstruction = autoDispatch
    ? '方案确定后直接开始执行，不需要等待用户确认。'
    : '将方案展示给用户，等待用户确认后再执行。如果用户没有明确指示，不要自动开始执行。';
  prompt = prompt.replace('{confirm_instruction}', confirmInstruction);
  prompt = prompt.replace('{workspace_paths}', workspaceIds.join('\n'));
  return prompt;
}
```

- [ ] **Step 2: 替换 group-task.create handler**

替换 `group-task.create` handler（lines 2716-2739）：

```typescript
case 'group-task.create': {
  if (!msg.groupId || !msg.title) {
    ws.send(JSON.stringify({ type: 'error', error: 'Missing groupId or title' }));
    break;
  }
  try {
    const groupId = String(msg.groupId);
    const title = String(msg.title);
    const description = msg.description ? String(msg.description) : '';
    const autoDispatch = msg.autoDispatch ? 1 : 0;

    // Get group info
    const group = groupStore.getGroup(groupId);
    if (!group) {
      ws.send(JSON.stringify({ type: 'error', error: 'Group not found' }));
      break;
    }
    const workspaceIds = JSON.parse(group.workspaceIds || '[]');
    const groupDir = groupStore.getGroupDir(groupId);

    // Ensure group dir exists
    groupStore.ensureGroupDir(groupId, workspaceIds);

    // Create session with group dir as workspace
    const sessionId = sessionManager.createSession(groupDir);

    // Create task record (id = sessionId)
    groupStore.createGroupTask({
      id: sessionId,
      groupId,
      title,
      description,
      autoDispatch,
    });

    // Subscribe client to this session
    subscribeClientToSession(ws, sessionId);

    // Build and send task prompt as first user message (fire-and-forget)
    const taskPrompt = loadGroupTaskPrompt(title, description, workspaceIds, autoDispatch === 1);
    sessionManager.sendMessage(sessionId, taskPrompt, ws).catch((err: Error) => {
      log.error(`[group-task.create] Failed to send initial prompt: ${err.message}`);
    });

    // Reply with created task info
    ws.send(JSON.stringify({
      type: 'group-task.created',
      task: { id: sessionId, groupId, title, description, status: 'active' },
      sessionId,
    }));

    // Broadcast updated task list
    const groupTasks = groupStore.listGroupTasks(groupId);
    broadcastToAllAuthenticated(JSON.stringify({
      type: 'group-task.list',
      groupId,
      groupTasks,
    }));
  } catch (err) {
    log.error(`[group-task.create] Error: ${err}`);
    ws.send(JSON.stringify({ type: 'error', error: err instanceof Error ? err.message : String(err) }));
  }
  break;
}
```

**Note:** `sessionManager.sendMessage` 的签名需要确认，可能需要调整参数。查看 `server/claude-session.ts` 中 `sendMessage` 方法的确切签名来适配。

- [ ] **Step 3: Commit**

```bash
git add server/index.ts
git commit -m "feat(group-task): create session and send task prompt on task create"
```

---

### Task 7: 改造 group-task.delete + 清理 analyze/dispatch

**Files:**
- Modify: `server/index.ts:2751-2899`

- [ ] **Step 1: 替换 group-task.delete handler**

```typescript
case 'group-task.delete': {
  if (!msg.taskId) {
    ws.send(JSON.stringify({ type: 'error', error: 'Missing taskId' }));
    break;
  }
  try {
    const taskId = String(msg.taskId);
    const task = groupStore.getGroupTask(taskId);
    if (!task) {
      ws.send(JSON.stringify({ type: 'error', error: 'Task not found' }));
      break;
    }
    // Clean up associated session
    try {
      sessionManager.abort(taskId);
      sessionManager.closeV2Session(taskId);
      sessionStore.deleteSession(taskId);
    } catch { /* session may not exist */ }
    // Delete task record
    groupStore.deleteGroupTask(taskId);
    // Broadcast updated task list
    const groupTasks = groupStore.listGroupTasks(task.groupId);
    ws.send(JSON.stringify({
      type: 'group-task.list',
      groupId: task.groupId,
      groupTasks,
    }));
    ws.send(JSON.stringify({ type: 'group-task.deleted', taskId }));
  } catch (err) {
    ws.send(JSON.stringify({ type: 'error', error: err instanceof Error ? err.message : String(err) }));
  }
  break;
}
```

- [ ] **Step 2: 删除 group-task.analyze 和 group-task.dispatch handlers**

删除 `server/index.ts` 中 `group-task.analyze`（lines 2772-2843）和 `group-task.dispatch`（lines 2845-2899）的整个 case 块。

- [ ] **Step 3: 删除 group-task-analyzer import 和实例化**

删除 line 27: `import { GroupTaskAnalyzer } from './group-task-analyzer.js';`
删除 line 144: `const groupTaskAnalyzer = new GroupTaskAnalyzer(sessionManager, groupStore);`

- [ ] **Step 4: 简化 group-task.list handler**

替换 `group-task.list` handler（lines 2741-2749），确保返回简化后的字段：

```typescript
case 'group-task.list': {
  if (!msg.groupId) {
    ws.send(JSON.stringify({ type: 'error', error: 'Missing groupId' }));
    break;
  }
  const groupTasks = groupStore.listGroupTasks(String(msg.groupId));
  ws.send(JSON.stringify({
    type: 'group-task.list',
    groupId: String(msg.groupId),
    groupTasks,
  }));
  break;
}
```

- [ ] **Step 5: Commit**

```bash
git add server/index.ts
git commit -m "feat(group-task): cascade cleanup on delete, remove analyze/dispatch"
```

---

## Chunk 3: 前端 Schema + Store

### Task 8: 更新前端 Schema

**Files:**
- Modify: `src/schemas/group.ts`

- [ ] **Step 1: 简化 GroupTaskSchema，删除 WorkspaceTask**

替换 `GroupTaskSchema`（lines 27-38）：
```typescript
export const GroupTaskSchema = z.object({
  id: z.string(),
  groupId: z.string(),
  title: z.string(),
  description: z.string().nullable().default(null),
  autoDispatch: z.number().default(0),
  status: z.string(),
  createdAt: z.string(),
  updatedAt: z.string(),
}).passthrough();
```

删除 `WorkspaceTaskSchema`（lines 40-49）、`WorkspaceTaskStatusValues`（lines 13-14）。

简化 `TaskStatusValues`（line 10）：
```typescript
export const TaskStatusValues = ['draft', 'active', 'completed', 'failed'] as const;
```

删除 `GroupDetailUpdateSchema` 和 `GroupTaskDetailUpdateSchema`（lines 58-68），不再需要。

删除 `WorkspaceTask` type export（line 74）。

删除 `EMPTY_WORKSPACE_TASKS`（line 100）。

- [ ] **Step 2: Commit**

```bash
git add src/schemas/group.ts
git commit -m "refactor(schema): simplify GroupTask, remove WorkspaceTask"
```

---

### Task 9: 改造 group store

**Files:**
- Modify: `src/stores/group.ts`

- [ ] **Step 1: 添加 taskSessionMap 状态**

在 store state 中添加映射：
```typescript
interface GroupState {
  groups: Group[];
  tasks: Record<string, GroupTask[]>; // groupId -> tasks
  taskSessionMap: Record<string, string>; // sessionId -> groupId
  loading: boolean;
}
```

- [ ] **Step 2: 更新 messageHandler**

更新 `messageHandler`（lines 40-55）中 `group-task.list` 的处理，同时维护 `taskSessionMap`：

```typescript
if (data.type === 'group-task.list' && data.groupId) {
  const groupTasks = (data.groupTasks || []) as GroupTask[];
  set((state) => {
    const newMap = { ...state.taskSessionMap };
    groupTasks.forEach(t => { newMap[t.id] = t.groupId; });
    return {
      tasks: { ...state.tasks, [data.groupId!]: groupTasks },
      taskSessionMap: newMap,
    };
  });
}
```

添加 `group-task.created` 的处理：
```typescript
if (data.type === 'group-task.created' && data.task && data.sessionId) {
  const task = data.task as GroupTask;
  set((state) => {
    const groupTasks = [...(state.tasks[task.groupId] || []), task];
    return {
      tasks: { ...state.tasks, [task.groupId]: groupTasks },
      taskSessionMap: { ...state.taskSessionMap, [task.id]: task.groupId },
    };
  });
}
```

- [ ] **Step 3: 添加 helper 方法**

添加 `isGroupTaskSession` 方法：
```typescript
isGroupTaskSession: (sessionId: string) => {
  return !!get().taskSessionMap[sessionId];
},

getGroupForSession: (sessionId: string) => {
  const groupId = get().taskSessionMap[sessionId];
  if (!groupId) return null;
  return get().groups.find(g => g.id === groupId) || null;
},
```

- [ ] **Step 4: 简化 createTask**

更新 `createTask` 方法，使用新的 payload 格式：
```typescript
createTask: async (groupId, task) => {
  const client = useWsConnection.getState().client;
  if (!client) throw new Error('No WebSocket client available');
  client.send({
    type: 'group-task.create',
    groupId,
    title: task.title,
    description: task.description || '',
    autoDispatch: task.autoDispatch || false,
  });
},
```

- [ ] **Step 5: Commit**

```bash
git add src/stores/group.ts
git commit -m "refactor(group-store): add session mapping, adapt to new task model"
```

---

### Task 10: chat store — 处理 group task session 加载

**Files:**
- Modify: `src/stores/chat.ts:402-502`

- [ ] **Step 1: 在 switchSession 中处理 session 不在列表的情况**

在 `switchSession` 方法（line 402）中，在设置 `currentSessionId` 之后，如果新 session 不在 `sessions` 列表中，发送 `session.history` 来获取信息：

在现有 `switchSession` 的 `set({ currentSessionId: newId, ... })` 逻辑之后，添加：
```typescript
// Handle group task sessions not in local session list
const newSession = get().sessions.find(s => s.key === newId);
if (!newId.startsWith('chatbot-') && !newSession) {
  // This may be a group task session - fetch history directly
  const client = getWsClient();
  if (client) {
    client.send({ type: 'session.history', sessionId: newId });
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/stores/chat.ts
git commit -m "feat(chat): handle group task session loading in switchSession"
```

---

## Chunk 4: 前端 UI

### Task 11: 侧栏 — GroupItem 改造

**Files:**
- Modify: `src/components/GroupItem.tsx`

- [ ] **Step 1: 移除 onEdit prop 和编辑按钮**

删除 props 中的 `onEdit`（line 26），删除 `handleEdit` 函数（lines 125-128），删除编辑 Button（lines 185-193）。

- [ ] **Step 2: workspace 信息折叠显示**

替换 workspace 列表区域（lines 211-227），改为在 group header 中显示 workspace 数量标签：

在 group header 的 `</span>` （workspace 数量标签，约 line 170）后面添加：
```tsx
{group.workspaceIds && group.workspaceIds.length > 0 && (
  <span className="text-[10px] text-muted-foreground/40" title={group.workspaceIds.join('\n')}>
    ({group.workspaceIds.length})
  </span>
)}
```

删除展开区域中的 workspace 列表渲染代码。

- [ ] **Step 3: Commit**

```bash
git add src/components/GroupItem.tsx
git commit -m "refactor(GroupItem): remove edit button, collapse workspace display"
```

---

### Task 12: 侧栏 — SessionTree task 点击改造

**Files:**
- Modify: `src/components/SessionTree.tsx`

- [ ] **Step 1: 改造 handleTaskSelect**

替换 `handleTaskSelect`（lines 492-495）：

```typescript
const handleTaskSelect = (taskId: string) => {
  switchSession(taskId);
  navigate('/chat');
};
```

- [ ] **Step 2: 改造 handleTaskCreated**

替换 `handleTaskCreated`（lines 477-490）：

```typescript
const handleTaskCreated = (task: { id: string; groupId: string; title: string }) => {
  // Task is now a session - switch to it
  switchSession(task.id);
  navigate('/chat');
};
```

- [ ] **Step 3: 简化 handleGroupEdit 和 handleGroupDelete**

删除 `handleGroupEdit`（lines 457-460）。

从 `GroupItem` 的 props 中移除 `onEdit` 和 `handleGroupEdit` 的传递。

- [ ] **Step 4: 简化 handleTaskDelete**

替换 `handleTaskDelete`（lines 497-508）：

```typescript
const handleTaskDelete = async (taskId: string) => {
  try {
    await deleteTask(taskId);
    // If currently viewing this task session, switch away
    if (currentSessionId === taskId) {
      switchSession('');
    }
  } catch (err) {
    console.error('[SessionTree] Failed to delete task:', err);
    alert(`${t('task.deleteFail')}: ${err instanceof Error ? err.message : String(err)}`);
  }
};
```

- [ ] **Step 5: Commit**

```bash
git add src/components/SessionTree.tsx
git commit -m "refactor(SessionTree): task click opens chat, simplify handlers"
```

---

### Task 13: Chat 顶部任务卡片

**Files:**
- Modify: `src/components/layout/Titlebar.tsx:41-116`

- [ ] **Step 1: 添加 group task session 检测和任务卡片**

在 `Titlebar.tsx` 中，添加对 group task session 的判断。当 currentSession 是 group task 时，显示任务卡片替代普通的 workspace 路径。

在 Titlebar 组件中添加 group store 引用：
```typescript
import { useGroupStore } from '@/stores/group';
```

在 workspace 显示区域（约 lines 91-116），添加条件分支：
- 如果是 group task session（通过 `useGroupStore.isGroupTaskSession(currentSessionId)` 判断），显示任务卡片
- 否则保持现有 workspace 路径 + git 分支

任务卡片组件：
```tsx
function GroupTaskCard({ sessionId }: { sessionId: string }) {
  const [expanded, setExpanded] = useState(false);
  const group = useGroupStore(s => s.getGroupForSession(sessionId));
  const [selectedWorkspace, setSelectedWorkspace] = useState<string | null>(null);

  if (!group) return null;

  return (
    <div className="flex items-center gap-2">
      <div
        className="flex items-center gap-1 cursor-pointer text-sm text-foreground/80 hover:text-foreground"
        onClick={() => setExpanded(!expanded)}
      >
        <Layers className="h-3.5 w-3.5" />
        <span className="truncate max-w-[200px]">{group.name}</span>
        <ChevronDown className={cn("h-3 w-3 transition-transform", expanded && "rotate-180")} />
      </div>
      {expanded && (
        <div className="flex items-center gap-1">
          {group.workspaceIds.map((ws) => (
            <button
              key={ws}
              className={cn(
                "text-xs px-1.5 py-0.5 rounded",
                selectedWorkspace === ws ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground"
              )}
              onClick={() => setSelectedWorkspace(ws)}
              title={ws}
            >
              {ws.split(/[/\\]/).pop()}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add src/components/layout/Titlebar.tsx
git commit -m "feat(titlebar): add group task card for task sessions"
```

---

### Task 14: 路由和翻译

**Files:**
- Modify: `src/app/routes.tsx`
- Modify: `src/locales/zh-CN.json`, `src/locales/en-US.json`

- [ ] **Step 1: 删除 group-task 路由**

从 `routes.tsx` 中删除：
- Line 10: `import { GroupTaskPage } from '@/features/group-tasks';`
- Line 25: `{ path: 'group-task/:taskId', element: <GroupTaskPage /> },`

- [ ] **Step 2: 添加新翻译 key**

在 `zh-CN.json` 和 `en-US.json` 中添加/更新：
```json
"group.workspaces": { "text": "工作区", "context": "组合工作区" },
"group.taskCount": { "text": "${count} 个任务", "context": "任务计数" }
```

- [ ] **Step 3: Commit**

```bash
git add src/app/routes.tsx src/locales/zh-CN.json src/locales/en-US.json
git commit -m "refactor(routes): remove group-task page, add translations"
```

---

## Chunk 5: 清理

### Task 15: 删除废弃文件

**Files:**
- Delete: `server/group-task-analyzer.ts`
- Delete: `src/features/group-tasks/GroupTaskPage.tsx`

- [ ] **Step 1: 删除文件**

```bash
rm server/group-task-analyzer.ts
rm src/features/group-tasks/GroupTaskPage.tsx
# Check if group-tasks directory is now empty
ls src/features/group-tasks/
```

如果 `src/features/group-tasks/` 目录为空或只剩无关文件，删除整个目录。

- [ ] **Step 2: 清理相关 import**

确认没有其他文件引用被删除的文件。运行：
```bash
grep -rn "group-task-analyzer\|GroupTaskPage" src/ server/ --include="*.ts" --include="*.tsx"
```

清理所有引用。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: remove deprecated group-task-analyzer and GroupTaskPage"
```

---

### Task 16: 编译验证 + 测试

- [ ] **Step 1: TypeScript 编译**

```bash
npx tsc --noEmit
```

修复所有编译错误。

- [ ] **Step 2: 运行测试**

```bash
pnpm test
```

修复所有失败测试。

- [ ] **Step 3: 手动测试清单**

```
[ ] 新建组合 → 确认 ~/.sman/group/{id}/ 目录和 CLAUDE.md 创建
[ ] 组合展开 → workspace 数量标签显示正确，无编辑按钮
[ ] 新建任务 → 自动创建 session，自动发送 task prompt，进入 chat 视图
[ ] 任务对话 → 多轮对话正常，AI 收集信息并拆分任务
[ ] 点击任务 → switchSession 到 /chat，消息历史加载
[ ] 顶部任务卡片 → 显示组合名，展开显示 workspace 列表
[ ] 删除任务 → session 清理，侧栏更新
[ ] 删除组合 → 所有 task session 清理，目录删除
[ ] 普通 session.list → 不包含 group task session
```

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "feat(group-task): task as session implementation complete"
```
