# Agent 协作机制设计

> **状态**: 已批准
> **日期**: 2026-04-15
> **核心原则**: 集市轻量级 — 只做转发和状态记录，不暂存、不判断同步/异步、不管理结果

## 设计动机

现有协作机制是纯同步模型：A 发任务 → B 接受 → B 处理完回复 → A 收到。三个致命问题：

1. **无法处理复杂任务** — B 判断任务需要 5 分钟，但没有机制告诉 A "等等我"，只能干等
2. **离线丢结果** — B 完成异步任务后 A 下线了，`sendTo` 失败，结果丢失
3. **无恢复机制** — A 上线后不知道之前的任务什么状态，没有 `task.sync` 或 `resume_tasks`

## 核心设计

### 同步/异步由 Agent 自行判断

B 接到任务后自行判断复杂度：
- **简单**：直接 `task.chat` 回复结果 → A 收到 → `task.complete` 结束
- **复杂**：发 `task.chat` `{ text: "异步处理中" }` 告诉 A 在处理
- **完成**：发 `task.chat` `{ text: "结果..." }` 投递结果

集市完全透明，不做同步/异步区分，不需要新消息类型，不需要 eta。

### 发送方缓存 + 重试

- B 完成异步任务后发 `task.chat` 投递结果
- A 在线 → 集市直接转发 → A 收到
- A 离线 → 集市 `sendTo` 失败 → B 的桥接层检测到失败，**本地缓存结果**
- B 本地存储：`{ taskId, cachedResult, cachedAt }`
- B 不反复重试，等 A 主动来拉

### A 上线后主动同步

- A 启动/重连后，查本地 `tasks` 表找所有 `status` 为 `chatting`/`matched` 的活跃任务
- 对每个活跃任务发 `task.sync { taskId }` 给集市
- 集市转发给 B
- B 收到后检查本地状态：
  - 已完成且有缓存 → 回复 `task.chat` `{ text: "结果..." }`
  - 还在处理 → 回复 `task.chat` `{ text: "还在处理中" }`
  - 任务丢失/已取消 → 回复 `task.progress` `{ status: "cancelled" }`

### 新增消息类型

仅新增 1 个：

| 类型 | 方向 | 载荷 | 说明 |
|------|------|------|------|
| `task.sync` | A → 集市 → B | `{ taskId }` | A 上线后询问任务状态 |

集市对 `task.sync` 的处理：查 tasks 表找到 helper，转发。仅此而已。

## 协议流程

### 简单任务（同步）

```
A → task.create → 集市
集市 → task.search_result → A
A → task.offer → 集市
集市 → task.incoming → B
B → task.accept → 集市
集市 → task.matched → A, B
B → task.chat { "结果" } → 集市 → A
A → task.complete → 集市
```

### 复杂任务（异步）

```
A → task.create → 集市
... (同上到 matched)
B → task.chat { "异步处理中" } → 集市 → A    ← B 自行判断
... B 本地处理 ...
B → task.chat { "结果..." } → 集市
  ├ A 在线 → 转发 → A 收到
  └ A 离线 → sendTo 失败 → B 本地缓存结果
A → task.complete → 集市
```

### A 离线期间 B 完成了

```
A 上线 → 查本地 tasks 表
A → task.sync { taskId } → 集市 → B
B → task.chat { "结果..." } → 集市 → A
A → task.complete → 集市
```

### A 离线期间 B 也没完成

```
A 上线 → task.sync { taskId } → 集市 → B
B → task.chat { "还在处理中" } → 集市 → A
... 继续等 ...
B → task.chat { "结果..." } → 集市 → A
A → task.complete → 集市
```

### A 和 B 都下线了

```
A 上线 → task.sync { taskId } → 集市
集市查 tasks 表 → helperId 存在但 B 不在线
集市 → task.progress { status: "waiting_helper", detail: "Agent B is offline" } → A
A 知道 B 不在，可以等或取消
... B 上线后 A 再次 task.sync 或集市主动通知 A
```

## 文件变更

### 集市服务器（`bazaar/src/`）

| 文件 | 变更 |
|------|------|
| `protocol.ts` | `VALID_TYPES` 新增 `task.sync` |
| `message-router.ts` | 新增 `task.sync` handler：查 tasks 表找 helper，转发 |
| `task-engine.ts` | 新增 `handleTaskSync(msg, fromAgentId)`：验证任务归属，转发给 helper，helper 不在线则回 `task.progress` |

### 桥接层（`server/bazaar/`）

| 文件 | 变更 |
|------|------|
| `bazaar-store.ts` | 新增 `cached_results` 表：`{ task_id PK, result_text, from_agent, cached_at }`；新增 `saveCachedResult()`、`getCachedResult()`、`deleteCachedResult()` |
| `bazaar-bridge.ts` | 1) `handleIncomingChat` 发送失败时调 `saveCachedResult`；2) 收到 `task.sync` 后查缓存，有就推；3) 上线后对活跃任务发 `task.sync` |
| `bazaar-client.ts` | 新增 `sendTaskSync(taskId)` 方法 |
| `bazaar-session.ts` | `sendClaudeReplyToBazaar` 增加发送失败检测和缓存逻辑 |

### 共享类型

| 文件 | 变更 |
|------|------|
| `shared/bazaar-types.ts` | 无需改动（`task.sync` 走通用 `BazaarMessage` envelope） |

## cached_results 表结构

```sql
CREATE TABLE IF NOT EXISTS cached_results (
  task_id TEXT PRIMARY KEY,
  result_text TEXT NOT NULL,
  from_agent TEXT NOT NULL,
  cached_at TEXT NOT NULL DEFAULT (datetime('now'))
);
```

## 超时策略

- **任务超时**：集市服务器现有 `checkTimeouts` 不变，chatting 状态超过 N 分钟（默认 30）标记 timeout
- **异步任务不受超时影响**：B 发了 `task.chat` 后 `lastActivityAt` 更新，超时计时器重置
- **A 可以主动取消**：发 `task.cancel` 终止等待

## 与现有代码的关系

- **不改动** `task.chat` 消息格式 — 异步声明和结果投递都复用 `task.chat`
- **不改动** 集市的 `sendTo` 机制 — 失败检测在 B 的桥接层做
- **不改动** 任务状态机 — `chatting` 状态不变，异步只是 Agent 行为模式
- **新增** `task.sync` 是唯一的协议扩展
- **新增** `cached_results` 是唯一的存储扩展

## 验证

1. `cd bazaar && npx tsc --noEmit` — 集市服务器编译通过
2. `cd bazaar && npx vitest run` — 集市测试通过
3. `npx tsc --noEmit` — 主项目编译通过
4. 手动测试场景：
   - 简单任务同步完成
   - 复杂任务异步完成（A 在线）
   - 复杂任务异步完成（A 离线，A 上线后 sync 拿到结果）
   - B 离线时 A 发 sync，收到 waiting_helper 回复
