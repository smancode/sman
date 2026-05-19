# Group Task Session Design

> 组合任务 = 普通会话 + 特殊工作目录 + 预设 prompt

## 目标

新建组合后，用户可以在组合里新建任务。新建任务等同于新建一个会话（session），唯一区别是：
- workspace 指向组合的专用目录（`~/.sman/group/{groupId}/`）
- 自动发送第一条消息（任务分析指导 prompt）
- 顶部显示任务卡片，可切换 workspace 上下文查看代码/Git

用户体验上，任务对话和普通会话完全一致——多轮对话、流式响应、代码查看器、Git 面板全部复用。

## 1. Group 目录 + CLAUDE.md 模板

创建组合时：

1. 保持现有 groupId 格式（`group-{Date.now()}-{random}`），不改
2. 创建目录 `{SMAN_HOME}/group/{groupId}/`
3. 写入 `CLAUDE.md`（从模板文件替换占位符生成）

模板文件存放在 `server/templates/group-claude.md`，内容：

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

占位符：
- `{workspace_list}` → 每个目录名一行 `- dirname`
- `{workspace_details}` → 每个路径一行 `/full/path/to/workspace`

## 2. Task = Session 数据模型

Task 本质就是一个普通 session。

### group_tasks 表（简化）

| 列 | 类型 | 说明 |
|---|------|------|
| id | TEXT PK | = 关联的 session ID |
| group_id | TEXT FK | 所属组合 |
| title | TEXT | 任务标题 |
| description | TEXT | 任务描述（可为空） |
| auto_dispatch | INTEGER DEFAULT 0 | AUTO 模式：0=等用户确认，1=自动执行 |
| status | TEXT DEFAULT 'draft' | draft / active / completed / failed |
| created_at | TEXT | 创建时间 |
| updated_at | TEXT | 更新时间 |

### sessions 表

无结构改动。Task 的 session 是一个普通 session，workspace = `{SMAN_HOME}/group/{groupId}/`。

### session.list 过滤

`session.list` 返回时**排除** workspace 以 `{SMAN_HOME}/group/` 为前缀的 session，避免 group task session 出现在普通会话列表中。在 `session-store.ts` 的 `listSessions` SQL 中加 WHERE 条件。

### 标识 group task session

前端通过 group_tasks 表建立 `sessionId → groupId` 的映射（`group-task.list` 返回）。chat 组件通过这个映射判断当前 session 是否为 group task，不依赖路径前缀匹配。

### 删除

- 删除 `workspace_tasks` 表 — 不再需要独立的工作区任务追踪
- 删除 `GroupTaskPage` 组件和 `/group-task/:taskId` 路由
- 删除 `group-task-analyzer.ts` — 分析功能由 AI 多轮对话完成

## 3. 新建任务流程

### 服务端流程

```
用户填写标题+描述 → 前端发 group-task.create {groupId, title, description, autoDispatch}
  → 服务端：
     1. 查 group，获取 workspaceIds 和 groupDir
     2. 确保 groupDir 存在（group.create 时已创建）
     3. sessionManager.createSession(groupDir) → sessionId
     4. INSERT group_tasks (id=sessionId, group_id, title, description, auto_dispatch, status='active')
     5. 构建 task prompt（从模板替换占位符）
     6. 将 task prompt 作为第一条用户消息发送到 session（fire-and-forget，不阻塞响应）
     7. 回复前端：group-task.created {taskId, sessionId}
  → 前端：
     收到 group-task.created → switchSession(sessionId) + navigate('/chat')
     → session.history 加载 → 进入正常多轮对话
```

### 前端 session 加载

task 点击时 `switchSession(sessionId)`。由于 `session.list` 不返回 group task session，需要在 switchSession 时处理 session 不在本地列表的情况：
- 发送 `session.history` 获取消息历史
- 创建本地 ChatSession 记录（标记 source='group'）

### Task Prompt 模板

自动发送的第一条用户消息（发给 AI，指导其如何分析任务）：

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
```
# 任务：{title}
## 执行计划
- [ ] **{系统A}**: 子任务1
- [ ] **{系统B}**: 子任务2
```

## 第五步：逐个执行
按 task.md 中的 TODO 顺序执行，每完成一个更新 [x]。

## 业务系统路径
{workspace_paths}
```

`{confirm_instruction}` 根据模式替换：
- `auto_dispatch = 0`：`将方案展示给用户，等待用户确认后再执行。如果用户没有明确指示，不要自动开始执行。`
- `auto_dispatch = 1`：`方案确定后直接开始执行，不需要等待用户确认。`

### task.md 文件

AI 在对话中将执行计划写入 `{groupDir}/task.md`。这个文件是 AI 自己写入的工作文档，用于追踪 TODO 进度。文件名固定为 `task.md`，不需要加 timestamp。任务完成后保留作为记录。

### 错误处理

- session 创建失败 → 不插入 group_tasks，返回错误
- 第一条消息发送失败 → task status 保持 `active`，用户可以在对话中重新发送
- group 目录不存在 → 自动创建（幂等）

## 4. 侧栏 UI 改造

### 组合展开区域

- 只显示任务列表（TaskItem），不显示 workspace 列表
- workspace 信息折叠：组合名右侧显示 workspace 数量标签，hover 时 tooltip 显示 workspace 目录名
- 移除编辑按钮，保留新建任务、删除

### Task 点击

- 点击 task → `switchSession(task.id)` + `navigate('/chat')`（task.id 就是 sessionId）
- 不再导航到 `/group-task/:taskId`
- TaskItem 的 `isActive` 判断 = `task.id === currentSessionId`（复用 session 高亮逻辑）

### Task 显示

TaskItem 在侧栏的显示格式与 SessionItem 一致，图标用 Layers 区分。

## 5. Chat 顶部栏改造

### 判断是否为 group task session

前端通过 group store 的 `tasks` 映射（sessionId → groupId）判断当前 session 是否为 group task。group store 在 `group-task.list` 响应中维护这个映射。

### 普通会话（现有行为不变）

顶部显示 workspace 目录名 + git 分支。

### Task 会话

- 默认显示任务卡片（标题 + 展开箭头）
- 卡片展开后显示：
  - 任务描述
  - 关联 workspace 列表（可点击）
- 点击某个 workspace：
  - 代码查看器切换到该 workspace 上下文
  - Git 面板切换到该 workspace 上下文
  - 顶部显示该 workspace 的目录名 + git 分支
- 卡片始终在顶部（sticky），不随滚动消失
- 卡片下方是正常的对话区域

## 6. 级联清理

### 删除组合

1. 查询 group 下所有 tasks
2. 对每个 task：清理关联 session（abort + closeV2Session + deleteSession）
3. 删除 group 目录 `rm -rf {SMAN_HOME}/group/{groupId}/`
4. 删除数据库记录（group_tasks 通过 FK CASCADE 自动删除，然后删除 groups）

### 删除任务

1. 清理关联 session（完整流程：abort + closeV2Session + deleteSession + 取消订阅）
2. 删除 group_tasks 记录
3. 删除 group 目录下的 task.md（如果有）
4. 广播更新后的 group task 列表

## 7. 服务端 API 变更

### 新增/修改的 WebSocket 消息

| 消息 | 方向 | 说明 |
|------|------|------|
| `group.create` | C→S | 改造：额外创建 group 目录 + CLAUDE.md |
| `group.delete` | C→S | 改造：级联清理 sessions + 目录 |
| `group-task.create` | C→S | 改造：创建 session + 发送 task prompt |
| `group-task.list` | C→S | 简化：只返回 group_tasks 表数据 |
| `group-task.delete` | C→S | 改造：清理关联 session + 文件 |
| `group-task.created` | S→C | 新增：返回 {taskId, sessionId} |

### 删除的消息

- `group-task.analyze` — 分析功能由 AI 多轮对话完成
- `group-task.dispatch` — 不再需要分发

## 8. 文件变更清单

### 新增

- `server/templates/group-claude.md` — CLAUDE.md 模板
- `server/templates/group-task-prompt.md` — 任务 prompt 模板

### 修改

- `server/group-store.ts` — 简化 group_tasks 表，删除 workspace_tasks 表
- `server/session-store.ts` — session.list 过滤 group task sessions
- `server/index.ts` — 改造 group.create / group.delete / group-task.create / group-task.delete
- `src/components/GroupItem.tsx` — 简化展开区域，移除编辑按钮，workspace 折叠
- `src/components/SessionTree.tsx` — task 点击改为 switchSession
- `src/features/chat/index.tsx` — 顶部任务卡片（group task session 时显示）
- `src/stores/group.ts` — 适配新数据模型，维护 sessionId 映射
- `src/stores/chat.ts` — 处理 group task session 的 switchSession 加载
- `src/schemas/group.ts` — 更新 GroupTask schema，删除 WorkspaceTask
- `src/locales/zh-CN.json` + `en-US.json` — 新增翻译
- `src/app/routes.tsx` — 删除 group-task 路由

### 删除

- `server/group-task-analyzer.ts` — 不再需要
- `src/features/group-tasks/GroupTaskPage.tsx` — 不再需要独立页面
- `src/components/CreateTaskDialog.tsx` — 保留但简化（只需 title + description）
