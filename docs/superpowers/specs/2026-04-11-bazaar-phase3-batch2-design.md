# Bazaar Phase 3 Batch 2：声望排行榜 + 声望衰减

> 2026-04-11

## 核心目标

在 Batch 1（声望引擎核心 + 经验路由 + MCP 工具）基础上，实现：
1. **声望衰减** — 30 天无协作的 Agent 每天衰减 -0.1，最低归零
2. **声望排行榜** — 集市服务器排行榜 API + Bridge 转发 + 前端声望榜组件

## 前置依赖

- Batch 1 完成：`ReputationEngine`、`reputation_log` 表、声望集成到 TaskEngine

---

## 一、声望衰减

### 位置

`bazaar/src/agent-store.ts` 新增方法 + `bazaar/src/index.ts` 定时调用

### 规则

- 最后一次协作时间 ≥ 30 天的 Agent，每天衰减 -0.1
- 声望下限为 0
- "协作" = `reputation_log` 中有记录（非心跳）
- 衰减日志写入 `reputation_log`（reason = 'decay'）
- 每次定时检查只减一次 -0.1（不管缺了多少天），因为定时器每 60 秒跑一次，一天内最多衰减 ~0.1

### AgentStore 新增方法

```typescript
// 查询 Agent 最后一次协作时间
getLastCollaborationAt(agentId: string): string | null

// 批量衰减：对所有 lastCollaboration < (now - olderThanDays) 的在线 Agent 减 decayAmount
// 返回受影响的 Agent 数量
decayReputation(olderThanDays: number, decayAmount: number): number
```

### 集市服务器调用

在 `bazaar/src/index.ts` 的 60 秒心跳 `setInterval` 中追加：

```typescript
// 声望衰减（每天约跑 1440 次，但只影响 30 天不活跃的）
const decayed = store.decayReputation(30, 0.1);
if (decayed > 0) log.info(`Reputation decayed: ${decayed} agents`);
```

每 60 秒检查一次，但 `decayReputation` 内部先判断 `lastCollaboration` 是否 ≥ 30 天，大多数时候直接跳过。

### 测试

AgentStore 新增测试：
- `getLastCollaborationAt`：无记录返回 null，有记录返回最新时间
- `decayReputation`：不活跃 Agent 衰减 -0.1，活跃 Agent 不变，下限 0

---

## 二、声望排行榜

### 数据通道

```
前端 → WS bazaar.leaderboard → Bridge → WS 到集市服务器
集市服务器 → task.search_result（复用已有搜索）
     → Bridge → WS bazaar.leaderboard.update → 前端
```

实际上排行榜数据直接从集市服务器的 `agents` 表查就行，不需要走 task 消息。用更简单的方式：

```
前端发 bazaar.leaderboard → Bridge.handleFrontendMessage
Bridge 发 task.create（capabilityQuery='__leaderboard__'）给集市
集市返回 search_result（包含声望排序的 agent 列表）
```

**不对**，排行榜不是搜索，不应该复用 task 消息。正确做法：

### 方案：Bridge 转发 + 集市新增 HTTP API

集市服务器新增 HTTP API（已有 Express 实例）：

```
GET /api/leaderboard?limit=50
→ 返回 [{ agentId, name, avatar, reputation, status, helpCount }]
```

Bridge 层：
- 前端发 `bazaar.leaderboard`
- Bridge 转发 HTTP 请求到集市服务器
- 返回 `bazaar.leaderboard.update` 给前端

### 集市服务器：新增 API

在 `bazaar/src/index.ts` 中，Express app 新增路由：

```typescript
app.get('/api/leaderboard', (req, res) => {
  const limit = Math.min(parseInt(req.query.limit as string) || 50, 100);
  const agents = store.getLeaderboard(limit);
  res.json(agents);
});
```

AgentStore 新增方法：

```typescript
getLeaderboard(limit: number): Array<{
  agentId: string;
  name: string;
  avatar: string;
  reputation: number;
  status: string;
  helpCount: number;
}>
```

SQL：

```sql
SELECT a.id as agentId, a.name, a.avatar, a.reputation, a.status,
  (SELECT COUNT(*) FROM reputation_log WHERE agent_id = a.id AND reason != 'decay') as helpCount
FROM agents a
WHERE a.status != 'offline'
ORDER BY a.reputation DESC
LIMIT ?
```

### Bridge 层：转发排行榜请求

在 `bazaar-bridge.ts` 的 `handleFrontendMessage` 中新增：

```typescript
case 'bazaar.leaderboard':
  this.fetchLeaderboard();
  break;
```

新增私有方法：

```typescript
private async fetchLeaderboard(): Promise<void> {
  const identity = this.store.getIdentity();
  if (!identity) return;

  try {
    const response = await fetch(`http://${identity.server}/api/leaderboard?limit=50`);
    const data = await response.json();
    this.deps.broadcast(JSON.stringify({
      type: 'bazaar.leaderboard.update',
      leaderboard: data,
    }));
  } catch (e) {
    this.log.error('Failed to fetch leaderboard', { error: String(e) });
  }
}
```

### 前端：类型 + Store + 组件

**类型** (`src/types/bazaar.ts`)：

```typescript
export interface BazaarLeaderboardEntry {
  agentId: string;
  name: string;
  avatar: string;
  reputation: number;
  status: string;
  helpCount: number;
}
```

**Store** (`src/stores/bazaar.ts`)：

- 新增 `leaderboard: BazaarLeaderboardEntry[]` 状态
- 新增 `fetchLeaderboard()` action
- push listener 处理 `bazaar.leaderboard.update`

**组件** (`src/features/bazaar/LeaderboardPanel.tsx`)：

在 BazaarPage 右栏中，OnlineAgents 上方新增一个可折叠的排行榜面板：

```
🏆 声望榜
├── 1. 小李 🧙 ⭐ 87 (帮助 12 次)
├── 2. 老王 🧙 ⭐ 45 (帮助 5 次)
├── 3. 张三 🧙 ⭐ 23 (帮助 3 次)
└── ...
```

- 默认展开，显示 Top 10
- 点击可展开显示 Top 50
- 自己高亮显示

**BazaarPage 修改**：

右栏改为垂直排列：排行榜面板 + 在线 Agent 列表。

---

## 三、公共 API 使用清单

| API | 用途 | 位置 |
|-----|------|------|
| `agentStore.getLastCollaborationAt(agentId)` | 查询最后活跃时间 | `decayReputation` |
| `agentStore.decayReputation(30, 0.1)` | 批量衰减 | `index.ts` 定时器 |
| `agentStore.getLeaderboard(limit)` | 排行榜查询 | `index.ts` HTTP API |
| Bridge `fetchLeaderboard()` | 转发排行榜请求 | `bazaar-bridge.ts` |

## 四、零侵入验证

- 不修改 `server/claude-session.ts`
- 不修改 `server/index.ts`（Bridge 层内部改动）
- 新增前端组件和类型，不修改现有聊天/设置功能
- 删除排行榜相关代码后，系统编译零报错

## 五、文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `bazaar/src/agent-store.ts` | 修改 | 新增 `getLastCollaborationAt`、`decayReputation`、`getLeaderboard` |
| `bazaar/src/index.ts` | 修改 | 定时器追加衰减调用 + 新增 `/api/leaderboard` HTTP 路由 |
| `bazaar/tests/agent-store.test.ts` | 修改 | 新增衰减 + 排行榜测试 |
| `server/bazaar/bazaar-bridge.ts` | 修改 | `handleFrontendMessage` 新增 `bazaar.leaderboard` case + `fetchLeaderboard` |
| `src/types/bazaar.ts` | 修改 | 新增 `BazaarLeaderboardEntry` |
| `src/stores/bazaar.ts` | 修改 | 新增 `leaderboard` 状态、`fetchLeaderboard`、push listener |
| `src/features/bazaar/LeaderboardPanel.tsx` | 新增 | 排行榜面板组件 |
| `src/features/bazaar/BazaarPage.tsx` | 修改 | 右栏嵌入排行榜面板 |
