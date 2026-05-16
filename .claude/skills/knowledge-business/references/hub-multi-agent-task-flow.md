# 协作星图多 Agent 任务编排流

> 验证时间: 2026-05-17 | 相关文件: server/hub/index.ts, server/hub/task-worker.ts, src/features/hub/TaskBoard.tsx

## 用户工作流（Step-by-Step）

1. **创建任务**：用户在 TaskBoard 创建任务，填写标题、描述、验收标准、子任务列表
2. **Agent 评估阶段**：
   - 多个 Agent 自动评估任务
   - 每个 Agent 提交评估报告（claimed_subtasks、complexity、approach）
   - 状态：`evaluating`
3. **用户确认分派**：
   - 用户查看评估报告，手动批准合适的 Agent
   - 为每个子任务选择执行 Agent
   - 可为每个 Agent 添加特定指令
   - 状态：`confirmed` → `dispatched`
4. **Agent 并发执行**：
   - TaskWorker 接收 `task.dispatched_to` 消息
   - 构建 prompt（含全局目标 + 子任务列表 + Agent 的特定指令）
   - 用独立的 session 执行（`hub-task-{taskId}-{assignmentId}`）
   - 实时回传进度（`task.progress`，每次取最后 200 字符）
   - 状态：`running`
5. **完成或失败**：
   - 成功：发送 `task.complete`，包含结果（截取前 10000 字符）
   - 失败：发送 `task.fail`，包含错误信息
   - 状态：`completed` / `failed`

## 业务规则

### 并发限制
- **全局并发限制**：同一时间最多 2 个 Bot 会话在处理请求（MAX_CONCURRENT=2）
- **单用户限制**：同一用户同时只能有 1 个活跃请求
- **队列机制**：超出排队的请求不会丢失，等待前序任务完成后自动执行

### 任务状态机
```
evaluating → confirmed → dispatched → running → completed
                                  ↓           ↓
                              rejected    failed
```

### Agent 能力声明
- Agent 注册时提供：skills（技能列表）、techStack（技术栈）、projectType（项目类型）、summary（项目简介）、description（详细描述）
- 评估时 Agent 声明能处理的子任务 ID 列表
- 分派时只能将子任务分配给声明了能力的 Agent

### 任务停止机制
- 用户可主动停止正在运行的任务（`task.stopping_to` 消息）
- Agent 立即回复 ACK（`task.stop.ack`）并中止执行
- 最后发送 `task.fail`，error 为 "stopped_by_user"

## 领域术语

| 术语 | 定义 |
|------|------|
| **task** | 任务主体，含 title、description、acceptance_criteria、subtasks |
| **subtask** | 子任务，含 id、name、description |
| **evaluation** | 评估报告，Agent 提交的执行方案，含 claimed_subtasks、complexity、approach、status（pending/approved/rejected） |
| **assignment** | 分派记录，含 agent_id、workspace、subtask_ids、instructions、status（assigned/running/completed/failed） |
| **room** | 协作房间，Agent 加入房间后可接收任务通知 |
| **agentId** | Agent 标识，格式 `{hostname}:{hash}`，例如 `MacBook-Pro:abc123def456` |

## 解决的痛点

1. **任务分配不透明**：传统模式下不知道谁在做什么、能力如何
2. **执行进度不可控**：无法实时看到任务执行进度和中间结果
3. **结果难以追溯**：没有完整的执行记录和评估报告
4. **资源浪费**：多个 Agent 同时接任务导致资源竞争
