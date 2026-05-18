# Hub 任务分配详细机制

> 贡献者: nasakim | 验证时间: 2026-05-19 | 来源: business-nasakim.md

## 任务分配的人工干预机制

### 评估审批
- 每个 Agent 提交评估后，用户可逐个 Approve/Reject
- 只 approved 的参与分配
- AUTO 模式（`auto_execute=true`）：跳过审批（全部 approved）

### 手动分配
- 确认任务后进入 DispatchPanel
- 可下拉覆盖 Agent 投标结果
- 某子任务可不分配
- AUTO 模式：跳过手动分配（Hub 按投标自动分配）

### 补充指令
- 可为每个 Agent 添加额外执行指令
- AUTO 模式：无法添加指令

### AUTO 模式限制
- 仍可拒绝整个任务
- 核心场景：批量执行、信任 Agent 投标结果

---

## Private 房间任务分配机制

### 评估阶段
- Hub 广播 `task.created`
- 所有 workspace 的 Agent 并行评估并投标（方案、复杂度、依赖）

### 分配阶段
- Hub 根据评估结果决策
- 一个子任务只分配给唯一 Agent
- 发送 `task.dispatched_to`

### 执行阶段
- TaskWorker 收到任务后检查槽位
- 执行、实时上报进度
- 提交结果

### 关键约束
- 单 Agent 最大并发 `MAX_CONCURRENT = 2`
- 子任务归属唯一
- 所有活跃 workspace 全员参与评估

---

## 无 Agent 可处理任务时的挂起行为

### 挂起条件
- 所有 agent 返回空投标（`claimedSubtasks: []`）
- 无 agent 在线

### 挂起表现
- 任务永久停留在 **evaluating** 状态
- 无超时机制
- Confirm 按钮被禁用

### 用户操作
- 只能手动 Reject 使状态变为 `rejected`
- 部分 agent 投标时仍可确认

### 建议
- 开启 `auto_execute` 跳过确认
- 服务端增加超时自动拒绝机制

---

**验证状态**: ✅ [已验证]
**代码位置**:
- server/hub/evaluation-handler.ts
- server/hub/task-worker.ts
- src/features/hub/TaskBoard.tsx
- src/features/hub/TaskDetail.tsx
