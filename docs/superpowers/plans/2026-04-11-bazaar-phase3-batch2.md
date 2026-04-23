> **Note: Bazaar has been renamed to Stardom.**

# Bazaar Phase 3 Batch 2 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add reputation decay (30-day inactivity) and a reputation leaderboard (server API + Bridge forwarding + frontend component)

**Architecture:** Server-side decay runs in the existing 60s heartbeat interval. Leaderboard uses a new HTTP API on the bazaar server, forwarded through the Bridge layer via WebSocket. Frontend gets a collapsible leaderboard panel in the right column of BazaarPage.

**Tech Stack:** SQLite (better-sqlite3), Express, WebSocket, React, Zustand, Vitest

---

## Chunk 1: 声望衰减

### Task 1: AgentStore 新增衰减相关方法

**Files:**
- Modify: `bazaar/src/agent-store.ts`
- Modify: `bazaar/tests/agent-store.test.ts`

- [ ] **Step 1: Write failing tests**

在 `bazaar/tests/agent-store.test.ts` 的 `describe('reputation')` 块末尾追加：

```typescript
it('should return last collaboration date', () => {
  store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
  expect(store.getLastCollaborationAt('a1')).toBeNull();

  store.logReputation('a1', 't1', 1.5, 'base', 'req-1');
  const lastDate = store.getLastCollaborationAt('a1');
  expect(lastDate).not.toBeNull();
});

it('should decay reputation for inactive agents', () => {
  store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
  store.updateReputation('a1', 10);

  // 手动设置一个 31 天前的 reputation_log 记录来模拟不活跃
  const oldDate = new Date(Date.now() - 31 * 24 * 60 * 60 * 1000).toISOString();
  store.db.prepare(`
    UPDATE reputation_log SET created_at = ? WHERE agent_id = ?
  `).run(oldDate, 'a1');

  const decayed = store.decayReputation(30, 0.1);
  expect(decayed).toBe(1);
  const agent = store.getAgent('a1');
  expect(agent!.reputation).toBeCloseTo(9.9);
});

it('should not decay reputation for active agents', () => {
  store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
  store.updateReputation('a1', 10);
  store.logReputation('a1', 't1', 1.5, 'base', 'req-1'); // 今天活跃

  const decayed = store.decayReputation(30, 0.1);
  expect(decayed).toBe(0);
  const agent = store.getAgent('a1');
  expect(agent!.reputation).toBe(10);
});

it('should not decay reputation below 0', () => {
  store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
  store.updateReputation('a1', 0.05);

  const oldDate = new Date(Date.now() - 31 * 24 * 60 * 60 * 1000).toISOString();
  store.db.prepare(`
    UPDATE reputation_log SET created_at = ? WHERE agent_id = ?
  `).run(oldDate, 'a1');

  store.decayReputation(30, 0.1);
  const agent = store.getAgent('a1');
  expect(agent!.reputation).toBe(0);
});
```

- [ ] **Step 2: Run tests to verify failure**

Run: `cd bazaar && npx vitest run tests/agent-store.test.ts`
Expected: FAIL with "store.getLastCollaborationAt is not a function"

- [ ] **Step 3: Implement methods in AgentStore**

在 `bazaar/src/agent-store.ts` 的 `close()` 前追加：

```typescript
getLastCollaborationAt(agentId: string): string | null {
    const row = this.db.prepare(`
      SELECT MAX(created_at) as lastAt FROM reputation_log
      WHERE agent_id = ? AND reason != 'decay'
    `).get(agentId) as { lastAt: string | null } | undefined;
    return row?.lastAt ?? null;
  }

  decayReputation(olderThanDays: number, decayAmount: number): number {
    const cutoff = new Date(Date.now() - olderThanDays * 24 * 60 * 60 * 1000).toISOString();

    // 找到所有不活跃的 Agent（无 reputation_log 记录或最后记录早于 cutoff）
    const inactiveAgents = this.db.prepare(`
      SELECT a.id, a.reputation
      FROM agents a
      WHERE a.status != 'offline'
        AND (
          NOT EXISTS (SELECT 1 FROM reputation_log rl WHERE rl.agent_id = a.id AND rl.reason != 'decay')
          OR (SELECT MAX(rl2.created_at) FROM reputation_log rl2 WHERE rl2.agent_id = a.id AND rl2.reason != 'decay') < ?
        )
        AND a.reputation > 0
    `).all(cutoff) as Array<{ id: string; reputation: number }>;

    if (inactiveAgents.length === 0) return 0;

    const updateStmt = this.db.prepare(`
      UPDATE agents SET reputation = MAX(0, reputation - ?) WHERE id = ?
    `);
    const logStmt = this.db.prepare(`
      INSERT INTO reputation_log (agent_id, task_id, delta, reason, created_at)
      VALUES (?, '__decay__', ?, 'decay', ?)
    `);

    const now = new Date().toISOString();
    const tx = this.db.transaction(() => {
      for (const agent of inactiveAgents) {
        const actualDecay = Math.min(decayAmount, agent.reputation);
        updateStmt.run(actualDecay, agent.id);
        logStmt.run(agent.id, -actualDecay, now);
      }
    });
    tx();

    return inactiveAgents.length;
  }
```

注意：需要在 `AgentStore` 类中暴露 `db` 的访问权限给测试。当前 `db` 是 `private`。测试中直接访问 `store.db` 需要改为包级别可见。更好的做法是在测试中通过公共方法来模拟旧数据。

实际上测试中用了 `store.db.prepare(...)`, 这在 TypeScript 中会报错因为 `db` 是 private。改为在 AgentStore 中新增一个测试辅助方法，或者直接在注册后用 `logReputation` 创建记录然后通过 SQL 更新时间戳。

**替代方案**：在 AgentStore 中新增 `setReputationLogCreatedAt(agentId: string, newCreatedAt: string)` 测试辅助方法，或者将测试改为通过 `db` 属性（用 `(store as any).db` 绕过）。

用 `(store as any).db` 更简洁：

```typescript
// 测试中
(store as any).db.prepare(`
  UPDATE reputation_log SET created_at = ? WHERE agent_id = ?
`).run(oldDate, 'a1');
```

- [ ] **Step 4: Run tests to verify pass**

Run: `cd bazaar && npx vitest run tests/agent-store.test.ts`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add bazaar/src/agent-store.ts bazaar/tests/agent-store.test.ts
git commit -m "feat(bazaar): add reputation decay methods to AgentStore"
```

---

### Task 2: 集市服务器集成衰减定时器

**Files:**
- Modify: `bazaar/src/index.ts`

- [ ] **Step 1: 在心跳 setInterval 中追加衰减调用**

在 `bazaar/src/index.ts` 的 `setInterval` 回调中，`taskEngine.checkTimeouts(5);` 之后追加：

```typescript
  // 声望衰减（30 天不活跃每天 -0.1）
  const decayed = store.decayReputation(30, 0.1);
  if (decayed > 0) {
    log.info(`Reputation decayed: ${decayed} agents`);
  }
```

- [ ] **Step 2: 验证编译**

Run: `cd bazaar && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: 运行全部 bazaar 测试**

Run: `cd bazaar && npx vitest run`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add bazaar/src/index.ts
git commit -m "feat(bazaar): integrate reputation decay into heartbeat timer"
```

---

## Chunk 2: 声望排行榜

### Task 3: AgentStore 新增 getLeaderboard 方法

**Files:**
- Modify: `bazaar/src/agent-store.ts`
- Modify: `bazaar/tests/agent-store.test.ts`

- [ ] **Step 1: Write failing test**

在 `bazaar/tests/agent-store.test.ts` 的 `describe('reputation')` 块末尾追加：

```typescript
it('should return leaderboard ordered by reputation DESC', () => {
  store.registerAgent({ id: 'a1', username: 'u1', hostname: 'h1', name: '小李', avatar: '🧙' });
  store.registerAgent({ id: 'a2', username: 'u2', hostname: 'h2', name: '老王', avatar: '🧑‍💻' });
  store.registerAgent({ id: 'a3', username: 'u3', hostname: 'h3', name: '张三', avatar: '🧑‍🎓' });

  store.updateReputation('a1', 87);
  store.updateReputation('a2', 45);
  store.updateReputation('a3', 23);

  // a2 设为离线，应被排除
  store.setAgentOffline('a2');

  const board = store.getLeaderboard(10);
  expect(board).toHaveLength(2);
  expect(board[0].name).toBe('小李');
  expect(board[0].reputation).toBe(87);
  expect(board[0].helpCount).toBe(0);
  expect(board[1].name).toBe('张三');
});

it('should count helpCount from reputation_log', () => {
  store.registerAgent({ id: 'a1', username: 'u1', hostname: 'h1', name: '小李' });
  store.updateReputation('a1', 10);
  store.logReputation('a1', 't1', 1.0, 'base', 'req');
  store.logReputation('a1', 't2', 0.5, 'quality', 'req');
  store.logReputation('a1', '__decay__', -0.1, 'decay'); // decay 不算帮助次数

  const board = store.getLeaderboard(10);
  expect(board[0].helpCount).toBe(2); // base + quality，不算 decay
});
```

- [ ] **Step 2: Run tests to verify failure**

Run: `cd bazaar && npx vitest run tests/agent-store.test.ts`
Expected: FAIL

- [ ] **Step 3: Implement getLeaderboard**

在 `bazaar/src/agent-store.ts` 的 `close()` 前追加：

```typescript
  getLeaderboard(limit: number): Array<{
    agentId: string;
    name: string;
    avatar: string;
    reputation: number;
    status: string;
    helpCount: number;
  }> {
    return this.db.prepare(`
      SELECT a.id as agentId, a.name, a.avatar, a.reputation, a.status,
        (SELECT COUNT(*) FROM reputation_log rl WHERE rl.agent_id = a.id AND rl.reason != 'decay') as helpCount
      FROM agents a
      WHERE a.status != 'offline'
      ORDER BY a.reputation DESC
      LIMIT ?
    `).all(limit) as Array<{
      agentId: string;
      name: string;
      avatar: string;
      reputation: number;
      status: string;
      helpCount: number;
    }>;
  }
```

- [ ] **Step 4: Run tests to verify pass**

Run: `cd bazaar && npx vitest run tests/agent-store.test.ts`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add bazaar/src/agent-store.ts bazaar/tests/agent-store.test.ts
git commit -m "feat(bazaar): add getLeaderboard method to AgentStore"
```

---

### Task 4: 集市服务器新增 /api/leaderboard HTTP 路由

**Files:**
- Modify: `bazaar/src/index.ts`

- [ ] **Step 1: 在 Express app 中新增路由**

在 `bazaar/src/index.ts` 的健康检查路由之后追加：

```typescript
// 声望排行榜
app.get('/api/leaderboard', (req, res) => {
  const limit = Math.min(parseInt(req.query.limit as string) || 50, 100);
  const board = store.getLeaderboard(limit);
  res.json(board);
});
```

- [ ] **Step 2: 验证编译**

Run: `cd bazaar && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: 运行全部测试**

Run: `cd bazaar && npx vitest run`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add bazaar/src/index.ts
git commit -m "feat(bazaar): add /api/leaderboard HTTP endpoint"
```

---

### Task 5: Bridge 层转发排行榜请求

**Files:**
- Modify: `server/bazaar/bazaar-bridge.ts`

- [ ] **Step 1: 在 handleFrontendMessage 新增 bazaar.leaderboard case**

在 `server/bazaar/bazaar-bridge.ts` 的 `handleFrontendMessage` switch 中，`case 'bazaar.config.update'` 之前追加：

```typescript
      case 'bazaar.leaderboard':
        this.fetchLeaderboard();
        break;
```

- [ ] **Step 2: 新增 fetchLeaderboard 私有方法**

在 `bazaar-bridge.ts` 类中追加：

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

- [ ] **Step 3: 验证编译**

Run: `npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 4: 运行 server bazaar 测试**

Run: `npx vitest run tests/server/bazaar/`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add server/bazaar/bazaar-bridge.ts
git commit -m "feat(bazaar): bridge forwards leaderboard requests to bazaar server"
```

---

### Task 6: 前端类型 + Store + 排行榜组件

**Files:**
- Modify: `src/types/bazaar.ts`
- Modify: `src/stores/bazaar.ts`
- Create: `src/features/bazaar/LeaderboardPanel.tsx`
- Modify: `src/features/bazaar/BazaarPage.tsx`

- [ ] **Step 1: 新增前端类型**

在 `src/types/bazaar.ts` 末尾追加：

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

- [ ] **Step 2: 扩展 Zustand Store**

在 `src/stores/bazaar.ts` 中：

1. 在 import 中追加 `BazaarLeaderboardEntry`
2. 在 `BazaarState` interface 中追加：
```typescript
leaderboard: BazaarLeaderboardEntry[];
fetchLeaderboard: () => void;
```
3. 在初始 state 中追加：`leaderboard: [],`
4. 新增 action：
```typescript
fetchLeaderboard: () => {
  const client = getWsClient();
  if (!client) return;
  client.send({ type: 'bazaar.leaderboard' });
},
```
5. 在 `registerPushListeners` 的 handle 函数中追加：
```typescript
} else if (type === 'bazaar.leaderboard.update') {
  set({ leaderboard: msg.leaderboard as BazaarLeaderboardEntry[] });
}
```

- [ ] **Step 3: 创建排行榜组件**

创建 `src/features/bazaar/LeaderboardPanel.tsx`：

```tsx
import { useBazaarStore } from '@/stores/bazaar';
import { Trophy } from 'lucide-react';

export function LeaderboardPanel() {
  const { leaderboard } = useBazaarStore();

  return (
    <div>
      <div className="flex items-center gap-2 mb-3">
        <Trophy className="h-4 w-4 text-yellow-500" />
        <h3 className="font-medium text-sm">声望榜</h3>
      </div>

      {leaderboard.length === 0 ? (
        <p className="text-sm text-muted-foreground py-2 text-center">暂无排行数据</p>
      ) : (
        <div className="space-y-1">
          {leaderboard.slice(0, 10).map((entry, index) => {
            const medal = index === 0 ? '🥇' : index === 1 ? '🥈' : index === 2 ? '🥉' : `${index + 1}.`;
            return (
              <div
                key={entry.agentId}
                className="flex items-center justify-between py-1.5 px-2 rounded hover:bg-muted/50"
              >
                <div className="flex items-center gap-2">
                  <span className="text-sm w-6 text-center">{medal}</span>
                  <span className="text-xl">{entry.avatar}</span>
                  <span className="text-sm font-medium">{entry.name}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted-foreground">{entry.helpCount} 次帮助</span>
                  <span className="text-sm font-medium">⭐ {Math.round(entry.reputation)}</span>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 4: 在 BazaarPage 右栏嵌入排行榜**

修改 `src/features/bazaar/BazaarPage.tsx`：

1. 新增 import：
```typescript
import { LeaderboardPanel } from './LeaderboardPanel';
```

2. 在 `useEffect` 中追加 `fetchLeaderboard`：
```typescript
const { connection, fetchTasks, fetchOnlineAgents, fetchLeaderboard, loading } = useBazaarStore();

useEffect(() => {
  fetchTasks();
  fetchOnlineAgents();
  fetchLeaderboard();
}, [fetchTasks, fetchOnlineAgents, fetchLeaderboard]);
```

3. 修改右栏布局，在 OnlineAgents 上方加入排行榜：
```tsx
{/* 右栏：排行榜 + 在线 Agent + 控制栏 */}
<div className="w-1/3 flex flex-col overflow-hidden">
  <div className="flex-1 overflow-y-auto p-4 space-y-4">
    <LeaderboardPanel />
    <OnlineAgents />
  </div>
  <ControlBar />
</div>
```

- [ ] **Step 5: 验证编译**

Run: `pnpm build`
Expected: 构建成功

- [ ] **Step 6: Commit**

```bash
git add src/types/bazaar.ts src/stores/bazaar.ts src/features/bazaar/LeaderboardPanel.tsx src/features/bazaar/BazaarPage.tsx
git commit -m "feat(bazaar): add reputation leaderboard frontend component"
```

---

### Task 7: 最终验证

- [ ] **Step 1: 运行全量测试**

Run: `cd bazaar && npx vitest run && cd .. && npx vitest run tests/server/bazaar/`
Expected: ALL PASS

- [ ] **Step 2: 编译检查**

Run: `cd bazaar && npx tsc --noEmit && cd .. && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: 主项目构建**

Run: `pnpm build`
Expected: 构建成功

---

## 文件依赖关系

```
agent-store.ts (Task 1: 衰减方法 + Task 3: 排行榜方法)
    ↓
index.ts (Task 2: 衰减定时器 + Task 4: HTTP API)
    ↓
bazaar-bridge.ts (Task 5: 转发排行榜)
    ↓
前端类型 + Store + 组件 (Task 6)
    ↓
最终验证 (Task 7)
```
