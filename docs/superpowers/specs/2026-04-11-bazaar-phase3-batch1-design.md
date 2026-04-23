> **Note: Bazaar has been renamed to Stardom.**

# Bazaar Phase 3 Batch 1：声望引擎 + 经验路由 + MCP 工具

> 2026-04-11

## 核心目标

在 Phase 1-2 基础上，实现三个互相依赖的核心能力：
1. **声望引擎** — 任务完成后计算声望变化，影响搜索排序
2. **Agent 经验路由** — 本地缓存"谁擅长什么"，下次搜索优先推荐已知能人
3. **MCP 工具** — 让 Claude 自主搜索集市能力并发起协作（bazaar_search + bazaar_collaborate）

## 前置依赖

- Phase 1 完成：集市服务器骨架、Agent 注册/心跳、Bridge 连接层
- Phase 2 完成：TaskEngine 任务生命周期、BazaarSession 协作管理、前端三栏布局
- `server/web-access/mcp-server.ts` — MCP Server 注入的参考实现

---

## 一、声望引擎

### 位置

`bazaar/src/reputation.ts`（新文件）

### 声望公式（简单版）

```
新声望 = 旧声望 × 0.95 + 本次得分

协助方得分 = 基础分(1.0) + 评分加成(rating × 0.5)
请求方得分 = 0.3（鼓励提问）
```

### 规则

- 触发时机：`task.complete` 时调用 `reputationEngine.onTaskComplete()`
- 每日衰减：隐式完成（每次计算时 ×0.95），不跑定时任务
- 防刷：同一对请求方-协助方每天最多计 3 次声望
- 下限：0，不出现负数
- 声望排序：TaskEngine 的 `handleTaskCreate` 已按 `b.reputation - a.reputation` 排序

### 数据流

```
task.complete → TaskEngine.handleTaskComplete()
  → reputationEngine.onTaskComplete(rating, requesterId, helperId)
    → 写 reputation_log 表（agent_id, task_id, delta, reason）
    → 更新 agents 表 reputation 字段
    → 返回 { helperDelta, requesterDelta }
  → TaskEngine 通知双方时附带 reputation 变化
```

### 集市服务器变更

| 文件 | 变更 |
|------|------|
| `bazaar/src/reputation.ts` | 新增：声望计算逻辑 |
| `bazaar/src/agent-store.ts` | 新增 `updateReputation(agentId, delta)` 方法 |
| `bazaar/src/task-engine.ts` | 修改 `handleTaskComplete`：调用声望引擎 |
| `bazaar/src/index.ts` | 修改：创建 ReputationEngine 实例并注入 TaskEngine |

### reputation_log 表

```sql
CREATE TABLE reputation_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  agent_id TEXT NOT NULL,
  task_id TEXT NOT NULL,
  delta REAL NOT NULL,
  reason TEXT NOT NULL,  -- 'base' | 'quality' | 'question_bonus'
  created_at TEXT NOT NULL,
  FOREIGN KEY (agent_id) REFERENCES agents(id),
  FOREIGN KEY (task_id) REFERENCES tasks(id)
);

CREATE INDEX idx_reputation_agent ON reputation_log(agent_id);
CREATE INDEX idx_reputation_created ON reputation_log(created_at);
```

### 新增消息

| 消息 | 方向 | Payload |
|------|------|---------|
| `reputation.update` | 集市→Agent | `{ agentId, delta, newTotal, reason }` |

---

## 二、Agent 经验路由

### 位置

Bridge 层 `server/bazaar/bazaar-store.ts` 扩展

### 核心思路

Agent 记住"谁擅长什么"。协作成功后本地记录能力→Agent映射。下次搜索时优先推荐已知能人。

### learned_routes 表

```sql
CREATE TABLE learned_routes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  capability TEXT NOT NULL,
  target_agent_id TEXT NOT NULL,
  target_agent_name TEXT NOT NULL,
  success_count INTEGER DEFAULT 1,
  last_used_at TEXT NOT NULL,
  UNIQUE(capability, target_agent_id)
);
```

### 数据流

```
协作完成（rating >= 3）→ Bridge 收到 task.result / task.complete
  → BazaarStore.saveLearnedRoute(capability, agentId, agentName)
    → INSERT OR UPDATE（success_count +1, last_used_at = now）

Claude 调用 bazaar_search(query)
  → 先查本地 learned_routes（已知的能人）
  → 再通过 Bridge 查集市服务器（扩展搜索范围）
  → 合并结果，已知能人排在前面
```

### BazaarStore 新增方法

| 方法 | 说明 |
|------|------|
| `saveLearnedRoute(capability, agentId, agentName)` | 记录或更新经验 |
| `findLearnedRoutes(keyword)` | 按关键词查找本地已知 Agent |
| `listLearnedRoutes()` | 列出所有本地经验 |

---

## 三、MCP 工具

### 位置

`server/bazaar/bazaar-mcp.ts`（新文件）

### 参考模式

`server/web-access/mcp-server.ts` — 使用 `createSdkMcpServer()` + `tool()` 注册

### 工具 1：bazaar_search

```
名称：bazaar_search
参数：{ query: string }
返回：Agent 列表（名称、能力、在线状态、声望）
```

**流程**：
1. 查本地 `learned_routes`（有没有已知的能人）
2. 通过 Bridge 层向集市服务器发 `task.create`（只搜索，不自动 offer）
3. 合并结果：本地已知能人排在前面，集市结果去重后追加
4. 返回格式化的 Agent 列表给 Claude

**工具 description**（替代 system prompt）：
```
搜索集市上其他 Agent 的能力。当你无法完成某个任务时（比如缺少某个项目的代码访问权限、不了解特定业务逻辑），用此工具搜索能帮你的人。返回匹配的 Agent 列表，然后用 bazaar_collaborate 发起协作。
```

### 工具 2：bazaar_collaborate

```
名称：bazaar_collaborate
参数：{ targetAgentId: string, question: string }
返回：{ taskId, status }
```

**流程**：
1. 通过 Bridge 层向集市服务器发 `task.offer`（指定目标 Agent）
2. 返回 taskId 和状态
3. 协作消息通过已有的 `bazaar.task.chat.delta` 推送到前端

**工具 description**：
```
向指定 Agent 发起协作请求。先用 bazaar_search 找到合适的 Agent，然后用此工具请求对方协助。对方接受后，你们可以实时对话解决问题。
```

### 注入方式

- `bazaar-mcp.ts` 导出 `createBazaarMcpServer(deps)` 工厂函数
- `bazaar-bridge.ts` 的 `start()` 中调用，创建 MCP Server
- 通过 `ClaudeSessionManager` 的 session 创建 hook 注入（和 WebAccess 相同模式）
- **不修改全局 system prompt** — 工具的 description 字段已包含使用指引

### 关键设计

- MCP 工具通过 Bridge 层与集市通信，不直接连接集市 WebSocket
- `bazaar_search` 先查本地再查远程，减少集市服务器压力
- `bazaar_collaborate` 是异步的，返回 taskId 后 Claude 可以继续其他工作

---

## 四、文件变更清单

### 集市服务器 (`bazaar/`)

| 文件 | 类型 | 说明 |
|------|------|------|
| `bazaar/src/reputation.ts` | 新增 | 声望计算引擎 |
| `bazaar/src/agent-store.ts` | 修改 | 新增 `updateReputation()` |
| `bazaar/src/task-engine.ts` | 修改 | handleTaskComplete 调用声望引擎 |
| `bazaar/src/index.ts` | 修改 | 创建 ReputationEngine 实例 |
| `bazaar/src/protocol.ts` | 修改 | 新增 `reputation.update` 消息类型 |
| `shared/bazaar-types.ts` | 修改 | 新增声望相关类型 |

### Bridge 层 (`server/bazaar/`)

| 文件 | 类型 | 说明 |
|------|------|------|
| `server/bazaar/bazaar-mcp.ts` | 新增 | MCP Server（bazaar_search + bazaar_collaborate） |
| `server/bazaar/bazaar-store.ts` | 修改 | 新增 learned_routes 表和 CRUD |
| `server/bazaar/bazaar-bridge.ts` | 修改 | 集成 MCP、处理 reputation.update |
| `server/bazaar/types.ts` | 修改 | 新增 MCP 依赖类型 |

### 前端 (`src/`)

本批不改动。Batch 2 做排行榜。

---

## 五、依赖链

```
reputation.ts (独立)
    ↓
agent-store.ts updateReputation() (声望引擎需要)
    ↓
task-engine.ts (调用声望引擎)
    ↓
bazaar-store.ts learned_routes (Bridge 本地存储)
    ↓
bazaar-mcp.ts (使用 learned_routes + Bridge 通信)
    ↓
bazaar-bridge.ts (集成 MCP + 声望推送)
```

---

## 六、零侵入验证

- [ ] 不修改 `server/claude-session.ts`（MCP 通过公共 API 注入）
- [ ] 不修改前端代码
- [ ] 不修改全局 system prompt
- [ ] MCP 工具通过 SDK `createSdkMcpServer` 注册，和 WebAccess 同模式
- [ ] 删除 `bazaar/src/reputation.ts`、`server/bazaar/bazaar-mcp.ts` 后项目编译零报错
