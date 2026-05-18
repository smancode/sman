# Technical — nasakim

> Last extracted: 2026-05-18T06:50:42.446Z

<!-- All items have been aggregated into skill knowledge-technical -->

## Private 房间任务分配机制
<!-- hash: a3f7b2 -->
- **评估阶段**：Hub 广播 `task.created`，所有 workspace 的 Agent 并行评估并投标（方案、复杂度、依赖）
- **分配阶段**：Hub 根据评估结果决策，一个子任务只分配给唯一 Agent，发送 `task.dispatched_to`
- **执行阶段**：TaskWorker 收到任务后检查槽位、执行、实时上报进度、提交结果
- **关键约束**：单 Agent 最大并发 `MAX_CONCURRENT = 2`；子任务归属唯一；所有活跃 workspace 全员参与评估
<!-- end: a3f7b2 -->