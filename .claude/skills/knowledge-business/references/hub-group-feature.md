# 组合（Group）功能详细设计

> 贡献者: nasakim | 验证时间: 2026-05-19 | 来源: business-nasakim.md

## 功能概述

组合（Group）是多个 workspace 的逻辑分组，用于跨项目的任务协作和分析。

## 用户工作流

### 1. 创建组合
- **入口**：在"新建会话"右侧并排增加"新建组合"按钮
- **创建流程**：
  - 填入组合名称
  - 多选已有 workspace
  - 确认创建

### 2. 组合管理
- **展示**：组合项在左侧栏显示（用独立图标）
- **操作**：悬浮时浮出三个操作按钮
  - "新建任务"
  - "编辑"
  - "删除"

### 3. 任务创建
- **必填字段**：
  - 任务名称
  - 任务描述
  - 任务细节
  - 交付标准
- **自动分析**：确认后自动开 session 分析任务+已有 workspace，输出可行性报告（对话样式）

### 4. Workspace 切换
- **入口**：右侧顶部可切换 workspace
- **联动效果**：切换后顶部栏的代码目录和 git 分支随之联动

### 5. 自动 Session 发起
- **触发条件**：条件充分后
- **行为**：系统自动给有任务的 workspace 发起 session（等同于在该 workspace 里帮用户发起会话）
- **支持 AUTO 模式**：支持自动化场景

---

## 数据结构

### Group Schema
```typescript
{
  id: string
  name: string
  workspaceIds: string[]
  status: 'active' | 'archived'
  createdAt: string
  updatedAt: string
}
```

### GroupTask Schema
```typescript
{
  id: string
  groupId: string
  title: string
  description: string | null
  details: string | null
  acceptanceCriteria: string | null
  status: 'draft' | 'analyzing' | 'analyzed' | 'dispatching' | 'dispatched' | 'in_progress' | 'completed' | 'failed'
  analysisResult: string | null  // JSON string
  createdAt: string
  updatedAt: string
}
```

### WorkspaceTask Schema
```typescript
{
  id: string
  groupTaskId: string
  workspace: string
  sessionId: string | null
  status: 'pending' | 'assigned' | 'in_progress' | 'completed' | 'failed'
  result: string | null
  createdAt: string
  updatedAt: string
}
```

---

## 技术实现

### 数据存储
- **位置**：`server/group-store.ts`
- **表结构**：groups, group_tasks, workspace_tasks 三张表

### 状态管理
- **位置**：`src/stores/group.ts`
- **实现**：Zustand store with WebSocket sync

### Schema 定义
- **位置**：`src/schemas/group.ts`
- **包含**：Group, GroupTask, WorkspaceTask + WS event schemas

### WebSocket API
- `group.create` / `group.list` / `group.update` / `group.delete`
- `group-task.create` / `group-task.list` / `group-task.delete`

---

## 状态

⚠️ **未提交代码** - 文件在 git working directory 但未 commit

**文件列表**：
- server/group-store.ts
- src/schemas/group.ts
- src/stores/group.ts
- src/components/CreateGroupDialog.tsx
- src/components/CreateTaskDialog.tsx
- src/components/GroupItem.tsx
- src/features/group-tasks/GroupTaskPage.tsx

---

**验证状态**: ⚠️ [未提交代码]
**代码位置**: 上述文件均已实现，但未提交到 git
