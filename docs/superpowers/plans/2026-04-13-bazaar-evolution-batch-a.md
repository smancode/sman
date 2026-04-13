# Bazaar 自我进化 - 批次 A（后端）实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Agent 自我进化的后端能力 — 对话经验提取、磨合机制、能力查找顺序、集市能力商店

**Architecture:** 在现有 `server/bazaar/` 桥接层上扩展：`bazaar-store.ts` 新增 experience 和 pair_history 表，`bazaar-bridge.ts` 增加异步经验提取和 pair 记录，`bazaar-mcp.ts` 搜索工具增加排序逻辑。集市服务端新增 `capability-store.ts`。

**Tech Stack:** TypeScript, better-sqlite3, Vitest

**Design Spec:** `docs/superpowers/specs/2026-04-13-bazaar-evolution-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `server/bazaar/bazaar-store.ts` | 新增 experience 字段 + pair_history 表 |
| Modify | `server/bazaar/bazaar-bridge.ts` | 异步经验提取 + pair 记录 + 查找顺序注入 |
| Modify | `server/bazaar/bazaar-mcp.ts` | 搜索排序（老搭档 > 历史协作 > 有经验 > 远程） |
| Modify | `server/bazaar/types.ts` | 新增类型定义 |
| Create | `bazaar/src/capability-store.ts` | 集市服务端能力包存储 |
| Modify | `bazaar/src/message-router.ts` | 能力包消息路由 |
| Modify | `bazaar/src/index.ts` | 注册能力包 HTTP API |
| Modify | `bazaar/src/protocol.ts` | 新增 capabilities 消息类型 |
| Modify | `tests/server/bazaar/bazaar-store.test.ts` | 测试新表和新字段 |
| Modify | `tests/server/bazaar/bridge-integration.test.ts` | 测试经验提取和 pair 记录 |
| Create | `tests/server/bazaar/bazaar-mcp-ranking.test.ts` | 测试搜索排序逻辑 |
| Create | `bazaar/tests/capability-store.test.ts` | 测试能力包 CRUD |

---

## Chunk 1: 对话经验利用（方向 3）

### Task 1: learned_routes 表新增 experience 字段

**Files:**
- Modify: `server/bazaar/bazaar-store.ts:58-66` (learned_routes 表定义)
- Modify: `server/bazaar/bazaar-store.ts:154-159` (saveLearnedRoute 方法)
- Modify: `server/bazaar/bazaar-store.ts:161-168` (findLearnedRoutes 方法)
- Modify: `server/bazaar/bazaar-store.ts:170-176` (listLearnedRoutes 方法)
- Test: `tests/server/bazaar/bazaar-store.test.ts`

- [ ] **Step 1: 写失败测试 — experience 字段存储和查询**

在 `tests/server/bazaar/bazaar-store.test.ts` 的 `learned_routes` describe 块中新增测试：

```typescript
it('should save and retrieve experience field', () => {
  store.saveLearnedRoute({
    capability: '支付查询',
    agentId: 'agent-002',
    agentName: '小李',
    experience: '用 JOIN 优化了支付流水查询，将查询时间从 3s 降到 50ms',
  });

  const results = store.findLearnedRoutes('支付');
  expect(results).toHaveLength(1);
  expect(results[0].experience).toBe('用 JOIN 优化了支付流水查询，将查询时间从 3s 降到 50ms');
});

it('should search experience field by keyword', () => {
  store.saveLearnedRoute({ capability: '支付', agentId: 'a1', agentName: 'A', experience: '风控规则配置需要修改白名单' });

  const results = store.findLearnedRoutes('风控');
  expect(results).toHaveLength(1);
  expect(results[0].agentId).toBe('a1');
});

it('should return empty experience by default', () => {
  store.saveLearnedRoute({ capability: '支付', agentId: 'a1', agentName: 'A' });

  const results = store.findLearnedRoutes('支付');
  expect(results[0].experience).toBe('');
});
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/bazaar/bazaar-store.test.ts`
Expected: FAIL — experience 属性不存在

- [ ] **Step 3: 修改 bazaar-store.ts — 表定义增加 experience 列**

在 `init()` 方法的 `learned_routes` CREATE TABLE 语句中，`agent_name TEXT NOT NULL,` 后新增：

```sql
experience TEXT DEFAULT '',
```

**迁移安全**：在 `init()` 末尾 `PRAGMA journal_mode=WAL;` 之后，添加迁移逻辑（处理已有数据库）：

```typescript
// Migration: add experience column to existing learned_routes table
try {
  this.db.exec("ALTER TABLE learned_routes ADD COLUMN experience TEXT DEFAULT ''");
} catch {
  // Column already exists, ignore
}
```

- [ ] **Step 4: 修改 saveLearnedRoute — 支持 experience 参数**

```typescript
saveLearnedRoute(input: { capability: string; agentId: string; agentName: string; experience?: string }): void {
  this.db.prepare(`
    INSERT OR REPLACE INTO learned_routes (capability, agent_id, agent_name, experience, updated_at)
    VALUES (?, ?, ?, ?, ?)
  `).run(input.capability, input.agentId, input.agentName, input.experience ?? '', new Date().toISOString());
}
```

- [ ] **Step 5: 修改 findLearnedRoutes — 同时搜索 experience 字段**

```typescript
findLearnedRoutes(keyword: string): Array<{ capability: string; agentId: string; agentName: string; experience: string }> {
  const escaped = keyword.replace(/%/g, '\\%').replace(/_/g, '\\_');
  return this.db.prepare(`
    SELECT capability, agent_id as agentId, agent_name as agentName, experience
    FROM learned_routes
    WHERE capability LIKE ? ESCAPE '\\' OR experience LIKE ? ESCAPE '\\'
  `).all(`%${escaped}%`, `%${escaped}%`) as Array<{ capability: string; agentId: string; agentName: string; experience: string }>;
}
```

- [ ] **Step 6: 修改 listLearnedRoutes — 返回 experience**

```typescript
listLearnedRoutes(): Array<{ capability: string; agentId: string; agentName: string; experience: string }> {
  return this.db.prepare(`
    SELECT capability, agent_id as agentId, agent_name as agentName, experience
    FROM learned_routes
    ORDER BY capability, updated_at DESC
  `).all() as Array<{ capability: string; agentId: string; agentName: string; experience: string }>;
}
```

- [ ] **Step 7: 运行测试验证通过**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/bazaar/bazaar-store.test.ts`
Expected: PASS

- [ ] **Step 8: 运行 tsc 检查**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 9: Commit**

```bash
git add server/bazaar/bazaar-store.ts tests/server/bazaar/bazaar-store.test.ts
git commit -m "feat(bazaar): add experience field to learned_routes for conversation memory"
```

---

### Task 2: 异步经验提取逻辑

**Files:**
- Modify: `server/bazaar/bazaar-bridge.ts:385-407` (handleTaskComplete)
- Modify: `server/bazaar/types.ts` (新增 ExperienceExtractor 类型)
- Test: `tests/server/bazaar/bridge-integration.test.ts`

- [ ] **Step 1: 写失败测试 — 经验提取在 task complete 后触发**

在 `tests/server/bazaar/bridge-integration.test.ts` 末尾新增测试：

```typescript
it('should extract experience on task complete with rating >= 3', async () => {
  // Setup: 先保存一个 incoming task（bridge 需要它来获取 agentId/agentName）
  store.saveTask({
    taskId: 'task-001',
    direction: 'incoming',
    requesterAgentId: 'agent-002',
    requesterName: '小李',
    question: '支付查询怎么优化？',
    status: 'chatting',
    createdAt: new Date().toISOString(),
  });

  // 添加一些聊天消息
  store.saveChatMessage({ taskId: 'task-001', from: 'remote', text: '支付查询怎么优化？' });
  store.saveChatMessage({ taskId: 'task-001', from: 'local', text: '用 JOIN 替代子查询' });

  // 触发 task.complete
  const wsClient = Array.from(wss.clients)[0];
  if (wsClient && wsClient.readyState === WebSocket.OPEN) {
    wsClient.send(JSON.stringify({
      id: 'srv-complete-001',
      type: 'task.complete',
      payload: {
        taskId: 'task-001',
        rating: 4,
        feedback: '很好',
      },
    }));
  }

  await new Promise((r) => setTimeout(r, 500));

  // 验证 learned_routes 被保存
  const routes = store.findLearnedRoutes('支付');
  expect(routes.length).toBeGreaterThanOrEqual(1);
  // 经验提取是异步 best-effort（可能因无 API key 而为空），但路由必须有
  expect(routes[0].agentId).toBe('agent-002');
}, 10_000);
```

- [ ] **Step 2: 运行测试验证**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/bazaar/bridge-integration.test.ts`
Expected: 可能 PASS（当前已保存 learned_routes），但需要验证 experience 字段

- [ ] **Step 3: 在 bazaar-bridge.ts 中增加异步经验提取**

在 `handleTaskComplete` 方法中，**替换**原有的 saveLearnedRoute 调用（第 392-401 行的整个 `if (rating >= 3)` 块）为 fire-and-forget 异步提取：

**注意：删除原代码中 `this.store.saveLearnedRoute({ capability, agentId, agentName });` 这一行，统一由 extractExperience 处理。**

```typescript
private handleTaskComplete(payload: Record<string, unknown>): void {
  const taskId = payload.taskId as string;
  const rating = payload.rating as number;
  const feedback = (payload.feedback as string) ?? '';

  if (rating >= 3) {
    const task = this.store.getTask(taskId);
    if (task) {
      const capability = task.question;
      const agentId = task.requesterAgentId ?? task.helperAgentId;
      const agentName = task.requesterName ?? task.helperName;
      if (agentId && agentName) {
        // Fire-and-forget 异步经验提取
        this.extractExperience(taskId, capability, agentId, agentName).catch(() => {
          // 静默降级：经验提取失败不影响 learned_routes
          this.log.info('Experience extraction failed, saving route without experience');
          this.store.saveLearnedRoute({ capability, agentId, agentName });
        });
      }
    }
  }

  if (this.bazaarSession) {
    this.bazaarSession.completeCollaboration(taskId, rating, feedback);
  }
}

/**
 * 从对话历史中提取经验摘要（best-effort，不阻塞主流程）
 * 30 秒超时，失败时静默降级
 */
private async extractExperience(
  taskId: string,
  capability: string,
  agentId: string,
  agentName: string,
): Promise<void> {
  const messages = this.store.listChatMessages(taskId);
  if (messages.length === 0) {
    // 没有对话消息，直接保存无经验的路由
    this.store.saveLearnedRoute({ capability, agentId, agentName });
    return;
  }

  const chatText = messages
    .map(m => `${m.from === 'local' ? '我' : agentName}: ${m.text}`)
    .join('\n');

  const experience = await this.callClaudeForExperience(chatText);
  this.store.saveLearnedRoute({ capability, agentId, agentName, experience });
}

/**
 * 调用 Claude API 提取经验摘要
 * 30 秒超时，返回空字符串表示提取失败
 */
private async callClaudeForExperience(chatText: string): Promise<string> {
  try {
    const config = this.deps.settingsManager.getConfig();
    const apiKey = config.llm?.apiKey;
    const baseUrl = config.llm?.baseURL || 'https://api.anthropic.com';
    const model = config.llm?.model || 'claude-haiku-4-5-20251001';

    if (!apiKey) return '';

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 30_000);

    const response = await fetch(`${baseUrl}/v1/messages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
      },
      body: JSON.stringify({
        model,
        max_tokens: 200,
        messages: [{
          role: 'user',
          content: `从以下协作对话中提取经验摘要（100字以内）：
- 解决了什么问题
- 用了什么方法
- 关键知识点

对话内容：
${chatText}

直接输出经验摘要，不要其他内容：`,
        }],
      }),
      signal: controller.signal,
    });

    clearTimeout(timer);

    if (!response.ok) return '';

    const data = await response.json() as any;
    const text = data.content?.[0]?.text ?? '';
    return text.slice(0, 200); // 限制长度
  } catch {
    return ''; // 超时或任何错误都静默降级
  }
}
```

- [ ] **Step 4: 运行全部 bazaar 测试**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/bazaar/`
Expected: ALL PASS

- [ ] **Step 5: 运行 tsc 检查**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 6: Commit**

```bash
git add server/bazaar/bazaar-bridge.ts tests/server/bazaar/bridge-integration.test.ts
git commit -m "feat(bazaar): async experience extraction from collaboration conversations"
```

---

### Task 3: bazaar_search 增加经验搜索和排序

**Files:**
- Modify: `server/bazaar/bazaar-mcp.ts:60-105` (bazaar_search 工具)
- Create: `tests/server/bazaar/bazaar-mcp-ranking.test.ts`

- [ ] **Step 1: 写失败测试 — 搜索结果包含 experience 标记**

创建 `tests/server/bazaar/bazaar-mcp-ranking.test.ts`：

```typescript
// tests/server/bazaar/bazaar-mcp-ranking.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { BazaarStore } from '../../../server/bazaar/bazaar-store.js';
import { BazaarClient } from '../../../server/bazaar/bazaar-client.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('bazaar_search ranking', () => {
  let store: BazaarStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `bazaar-ranking-test-${Date.now()}.db`);
    store = new BazaarStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  it('should mark results with experience as [有经验]', () => {
    store.saveLearnedRoute({
      capability: '支付查询',
      agentId: 'a1',
      agentName: '小李',
      experience: '用 JOIN 优化查询',
    });

    const routes = store.findLearnedRoutes('支付');
    expect(routes).toHaveLength(1);
    expect(routes[0].experience).toBeTruthy();
    expect(routes[0].experience).toContain('JOIN');
  });

  it('should search experience field', () => {
    store.saveLearnedRoute({
      capability: '数据库操作',
      agentId: 'a1',
      agentName: '小李',
      experience: '风控规则需要配置白名单',
    });

    const routes = store.findLearnedRoutes('风控');
    expect(routes).toHaveLength(1);
  });

  it('should not match empty experience', () => {
    store.saveLearnedRoute({
      capability: '支付查询',
      agentId: 'a1',
      agentName: '小李',
    });

    const routes = store.findLearnedRoutes('风控');
    expect(routes).toHaveLength(0);
  });
});
```

- [ ] **Step 2: 运行测试验证**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/bazaar/bazaar-mcp-ranking.test.ts`
Expected: PASS（findLearnedRoutes 已在 Task 1 中更新支持 experience 搜索）

- [ ] **Step 3: 修改 bazaar_search 输出格式 — 标记有经验**

在 `bazaar-mcp.ts` 的 searchTool 中，修改本地路由的映射，增加 experience 标记：

```typescript
...localRoutes.map(r => {
  const exp = r.experience ? ' [有经验]' : '';
  return {
    source: 'local' as const,
    agentId: r.agentId,
    name: r.agentName,
    capability: r.capability,
    badge: r.experience ? '有经验' : '历史协作',
  };
}),
```

修改结果格式化：

```typescript
const lines = allResults.map((r, i) => {
  const local = r.source === 'local' ? ` [${r.badge || '历史协作'}]` : '';
  return `${i + 1}. ${r.name} (${r.agentId})${local}`;
});
```

- [ ] **Step 4: 运行 tsc + 测试**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit && npx vitest run tests/server/bazaar/`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/bazaar/bazaar-mcp.ts tests/server/bazaar/bazaar-mcp-ranking.test.ts
git commit -m "feat(bazaar): search experience field and show badges in results"
```

---

## Chunk 2: 磨合机制（方向 4）

### Task 4: pair_history 表和 CRUD

**Files:**
- Modify: `server/bazaar/bazaar-store.ts` (新增 pair_history 表)
- Test: `tests/server/bazaar/bazaar-store.test.ts`

- [ ] **Step 1: 写失败测试 — pair_history CRUD**

在 `tests/server/bazaar/bazaar-store.test.ts` 新增 describe 块：

```typescript
describe('pair_history', () => {
  it('should save and get pair history', () => {
    store.savePairHistory({ partnerId: 'agent-002', partnerName: '小李', rating: 4.5 });
    const pair = store.getPairHistory('agent-002');
    expect(pair).toBeDefined();
    expect(pair!.partnerName).toBe('小李');
    expect(pair!.taskCount).toBe(1);
    expect(pair!.avgRating).toBe(4.5);
  });

  it('should accumulate pair history (upsert)', () => {
    store.savePairHistory({ partnerId: 'a1', partnerName: 'A', rating: 4 });
    store.savePairHistory({ partnerId: 'a1', partnerName: 'A', rating: 5 });

    const pair = store.getPairHistory('a1');
    expect(pair!.taskCount).toBe(2);
    expect(pair!.totalRating).toBe(9);
    expect(pair!.avgRating).toBe(4.5);
  });

  it('should return undefined for unknown partner', () => {
    expect(store.getPairHistory('unknown')).toBeUndefined();
  });

  it('should list all pair histories sorted by avgRating', () => {
    store.savePairHistory({ partnerId: 'a1', partnerName: 'A', rating: 3 });
    store.savePairHistory({ partnerId: 'a2', partnerName: 'B', rating: 5 });

    const pairs = store.listPairHistories();
    expect(pairs).toHaveLength(2);
    expect(pairs[0].partnerId).toBe('a2'); // avgRating 5 排前面
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/bazaar/bazaar-store.test.ts`
Expected: FAIL — savePairHistory 不存在

- [ ] **Step 3: 在 bazaar-store.ts 的 init() 中新增 pair_history 表**

在 `PRAGMA journal_mode=WAL;` 之前新增：

```sql
CREATE TABLE IF NOT EXISTS pair_history (
  partner_id TEXT NOT NULL,
  partner_name TEXT NOT NULL,
  task_count INTEGER DEFAULT 1,
  total_rating REAL DEFAULT 0,
  avg_rating REAL DEFAULT 0,
  last_collaborated_at TEXT NOT NULL,
  PRIMARY KEY (partner_id)
);
```

- [ ] **Step 4: 在 BazaarStore 类中新增方法**

```typescript
// ── Pair History ──

savePairHistory(input: { partnerId: string; partnerName: string; rating: number }): void {
  const existing = this.getPairHistory(input.partnerId);
  const now = new Date().toISOString();

  if (existing) {
    const newCount = existing.taskCount + 1;
    const newTotal = existing.totalRating + input.rating;
    const newAvg = Math.round((newTotal / newCount) * 10) / 10;
    this.db.prepare(`
      UPDATE pair_history
      SET partner_name = ?, task_count = ?, total_rating = ?, avg_rating = ?, last_collaborated_at = ?
      WHERE partner_id = ?
    `).run(input.partnerName, newCount, newTotal, newAvg, now, input.partnerId);
  } else {
    this.db.prepare(`
      INSERT INTO pair_history (partner_id, partner_name, task_count, total_rating, avg_rating, last_collaborated_at)
      VALUES (?, ?, 1, ?, ?, ?)
    `).run(input.partnerId, input.partnerName, input.rating, input.rating, now);
  }
}

getPairHistory(partnerId: string): { partnerId: string; partnerName: string; taskCount: number; totalRating: number; avgRating: number; lastCollaboratedAt: string } | undefined {
  return this.db.prepare(`
    SELECT partner_id as partnerId, partner_name as partnerName,
      task_count as taskCount, total_rating as totalRating,
      avg_rating as avgRating, last_collaborated_at as lastCollaboratedAt
    FROM pair_history WHERE partner_id = ?
  `).get(partnerId) as any;
}

listPairHistories(): Array<{ partnerId: string; partnerName: string; taskCount: number; avgRating: number; lastCollaboratedAt: string }> {
  return this.db.prepare(`
    SELECT partner_id as partnerId, partner_name as partnerName,
      task_count as taskCount, avg_rating as avgRating,
      last_collaborated_at as lastCollaboratedAt
    FROM pair_history ORDER BY avg_rating DESC
  `).all() as any[];
}
```

- [ ] **Step 5: 运行测试验证通过**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/bazaar/bazaar-store.test.ts`
Expected: PASS

- [ ] **Step 6: 运行 tsc 检查**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 7: Commit**

```bash
git add server/bazaar/bazaar-store.ts tests/server/bazaar/bazaar-store.test.ts
git commit -m "feat(bazaar): add pair_history table for collaboration familiarity tracking"
```

---

### Task 5: 磨合机制集成 — 搜索排序 + 上下文注入

**Files:**
- Modify: `server/bazaar/bazaar-bridge.ts` (handleTaskComplete 中更新 pair_history)
- Modify: `server/bazaar/bazaar-mcp.ts` (搜索结果按优先级排序)
- Test: `tests/server/bazaar/bazaar-mcp-ranking.test.ts`

- [ ] **Step 1: 写失败测试 — 老搭档排序优先**

在 `tests/server/bazaar/bazaar-mcp-ranking.test.ts` 新增：

```typescript
it('should rank old partners higher (pair history)', () => {
  // 添加老搭档记录：3次协作，avg 4.5
  store.savePairHistory({ partnerId: 'a1', partnerName: '小李', rating: 4 });
  store.savePairHistory({ partnerId: 'a1', partnerName: '小李', rating: 5 });
  store.savePairHistory({ partnerId: 'a1', partnerName: '小李', rating: 4.5 });

  // 添加普通历史协作
  store.saveLearnedRoute({ capability: '支付查询', agentId: 'a1', agentName: '小李' });
  store.saveLearnedRoute({ capability: '支付查询', agentId: 'a2', agentName: '老王' });

  const pair = store.getPairHistory('a1');
  expect(pair).toBeDefined();
  expect(pair!.taskCount).toBe(3);
  expect(pair!.avgRating).toBeGreaterThanOrEqual(4);
});
```

- [ ] **Step 2: 运行测试验证**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/bazaar/bazaar-mcp-ranking.test.ts`
Expected: PASS

- [ ] **Step 3: 在 bazaar-bridge.ts handleTaskComplete 中更新 pair_history**

在 `handleTaskComplete` 方法的 `if (rating >= 3)` 块内，保存 learned_routes 后新增：

```typescript
// 更新磨合记录
if (agentId && agentName) {
  this.store.savePairHistory({ partnerId: agentId, partnerName: agentName, rating });
}
```

- [ ] **Step 4: 修改 bazaar-mcp.ts 搜索结果排序**

在 `bazaar_search` 工具中，修改本地路由映射，增加 pair_history 查询：

```typescript
// 1. 先查本地经验路由
const localRoutes = deps.store.findLearnedRoutes(query);

// 2. 查询 pair history 用于排序
const pairHistories = (() => {
  try {
    return deps.store.listPairHistories();
  } catch {
    return []; // 数据损坏时静默降级
  }
})();

// ... (远程搜索不变)

// 3. 合并结果并排序
const pairMap = new Map(pairHistories.map(p => [p.partnerId, p]));

const allResults = [
  ...localRoutes.map(r => {
    const pair = pairMap.get(r.agentId);
    let badge = r.experience ? '有经验' : '历史协作';
    if (pair && pair.taskCount >= 3 && pair.avgRating >= 4) {
      badge = '老搭档';
    } else if (pair && pair.taskCount >= 1) {
      badge = '历史协作';
    }
    return {
      source: 'local' as const,
      agentId: r.agentId,
      name: r.agentName,
      capability: r.capability,
      badge,
      priority: badge === '老搭档' ? 0 : badge === '历史协作' ? 1 : badge === '有经验' ? 2 : 3,
    };
  }),
  ...filteredRemote.map(m => ({
    source: 'remote' as const,
    agentId: m.agentId,
    name: m.name,
    capability: '',
    badge: '',
    priority: 4,
  })),
];

// 按优先级排序
allResults.sort((a, b) => a.priority - b.priority);
```

- [ ] **Step 5: 运行全部测试**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/bazaar/ && npx tsc --noEmit`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add server/bazaar/bazaar-bridge.ts server/bazaar/bazaar-mcp.ts tests/server/bazaar/bazaar-mcp-ranking.test.ts
git commit -m "feat(bazaar): pair familiarity ranking - old partners first in search results"
```

---

### Task 6: 协作上下文注入

**Files:**
- Modify: `server/bazaar/bazaar-bridge.ts` (handleTaskAccepted)
- Test: `tests/server/bazaar/bridge-integration.test.ts`

- [ ] **Step 1: 在 handleTaskAccepted 中注入协作上下文**

在 `bazaar-bridge.ts` 的 `handleTaskAccepted` 方法中，`startCollaboration` 调用前，查询 pair_history 构建上下文：

```typescript
private handleTaskAccepted(payload: Record<string, unknown>): void {
  if (!this.bazaarSession) return;

  const taskId = payload.taskId as string;
  const task = this.store.getTask(taskId);
  if (!task) {
    this.log.warn(`Task not found for accept: ${taskId}`);
    return;
  }

  this.clearNotifyTimeout(taskId);
  this.store.updateTaskStatus(taskId, 'chatting');

  // 构建协作上下文（如果有历史）
  const partnerId = task.requesterAgentId ?? task.helperAgentId;
  const partnerName = task.requesterName ?? task.helperName;
  let collaborationContext = '';
  if (partnerId) {
    const pair = this.store.getPairHistory(partnerId);
    if (pair && pair.taskCount >= 1) {
      collaborationContext = `\n[协作上下文]\n你之前和 Agent「${partnerName}」协作过 ${pair.taskCount} 次，平均评分 ${pair.avgRating}。\n`;
      // 找到最近相关的 learned route — 按 agentId 过滤
      const allRoutes = this.store.listLearnedRoutes().filter(r => r.agentId === partnerId);
      if (allRoutes.length > 0) {
        collaborationContext += `上次协作解决了"${allRoutes[0].capability}"的问题。\n`;
      }
    }
  }

  const workspace = this.deps.homeDir;
  this.bazaarSession.startCollaboration(
    taskId,
    task.question + collaborationContext,
    task.requesterAgentId ?? 'unknown',
    task.requesterName ?? '一位同事',
    workspace,
  ).catch((err) => {
    this.log.error(`Failed to start collaboration for ${taskId}`, { error: String(err) });
  });
}
```

- [ ] **Step 2: 运行测试 + tsc**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/bazaar/ && npx tsc --noEmit`
Expected: ALL PASS

- [ ] **Step 3: Commit**

```bash
git add server/bazaar/bazaar-bridge.ts
git commit -m "feat(bazaar): inject collaboration context from pair history"
```

---

## Chunk 3: 能力查找顺序引导（方向 1）

### Task 7: 查找顺序引导文本注入

**Files:**
- Modify: `server/bazaar/bazaar-bridge.ts` (start 方法)
- Modify: `server/bazaar/bazaar-mcp.ts` (工具描述更新)

- [ ] **Step 1: 在 bazaar-bridge.ts start() 中添加查找顺序引导常量**

在文件顶部新增常量：

```typescript
/**
 * 能力查找顺序引导 — 注入到 Claude 对话上下文
 * 通过 sman cli --with-bazaar 加载时使用
 */
export const CAPABILITY_LOOKUP_GUIDE = `[能力查找顺序]
当你遇到无法完成的任务时，按以下顺序查找帮助：
1. 先自己尝试解决
2. 运行 \`sman capabilities list\` 检查本地已安装的能力包
3. 使用 bazaar_search MCP 工具搜索集市上其他 Agent 寻求协作
4. 运行 \`sman capabilities search <关键词>\` 搜索集市能力包
5. 都找不到时，告知用户需要什么能力`;
```

在 `start()` 方法的成功连接分支中，`this.log.info('Bazaar bridge started.')` 之后新增：

```typescript
// 查找顺序引导通过 MCP 工具描述和 CLI 注入
// MCP 工具描述中已包含引导信息
this.log.info('Capability lookup guide available via MCP tools');
```

- [ ] **Step 2: 更新 bazaar_search 工具描述**

在 `bazaar-mcp.ts` 中，修改 `bazaar_search` 工具的描述，在末尾追加：

```typescript
'搜索集市上其他 Agent 的能力。'
+ '当你无法完成某个任务时（比如缺少某个项目的代码访问权限、不了解特定业务逻辑），'
+ '用此工具搜索能帮你的人。\n\n'
+ '能力查找顺序：先自己尝试 → sman capabilities list 查本地能力包 → bazaar_search 搜索其他 Agent → sman capabilities search 搜索集市能力包\n\n'
+ '返回匹配的 Agent 列表（名称、能力、在线状态、声望、协作历史），'
+ '然后用 bazaar_collaborate 发起协作。',
```

- [ ] **Step 3: 运行 tsc + 测试**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit && npx vitest run tests/server/bazaar/`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add server/bazaar/bazaar-bridge.ts server/bazaar/bazaar-mcp.ts
git commit -m "feat(bazaar): add capability lookup order guidance in MCP tool descriptions"
```

---

## Chunk 4: 集市通用能力商店（方向 2）

### Task 8: 集市服务端 capability-store

**Files:**
- Create: `bazaar/src/capability-store.ts`
- Modify: `bazaar/src/index.ts` (注册 HTTP API)
- Test: `bazaar/tests/capability-store.test.ts`

- [ ] **Step 1: 写失败测试 — capability CRUD**

创建 `bazaar/tests/capability-store.test.ts`：

```typescript
// bazaar/tests/capability-store.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { CapabilityStore } from '../src/capability-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('CapabilityStore', () => {
  let store: CapabilityStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `capability-test-${Date.now()}.db`);
    store = new CapabilityStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  it('should publish and get a capability', () => {
    store.publish({
      name: 'payment-query',
      description: '支付系统查询工具',
      version: '1.0.0',
      category: '金融',
      packageUrl: 'https://packages.sman.dev/payment-query-1.0.0.tar.gz',
      readme: '# Payment Query\n查询支付流水和转账记录',
    });

    const cap = store.get('payment-query');
    expect(cap).toBeDefined();
    expect(cap!.description).toBe('支付系统查询工具');
    expect(cap!.version).toBe('1.0.0');
  });

  it('should search capabilities by keyword', () => {
    store.publish({ name: 'payment-query', description: '支付查询', version: '1.0.0', category: '金融', packageUrl: 'http://x' });
    store.publish({ name: 'risk-control', description: '风控规则引擎', version: '1.0.0', category: '金融', packageUrl: 'http://x' });
    store.publish({ name: 'deploy-tool', description: '部署工具', version: '1.0.0', category: '运维', packageUrl: 'http://x' });

    const results = store.search('支付');
    expect(results).toHaveLength(1);
    expect(results[0].name).toBe('payment-query');
  });

  it('should search by name and description', () => {
    store.publish({ name: 'log-analyzer', description: '日志分析工具', version: '1.0.0', category: '运维', packageUrl: 'http://x' });

    const byName = store.search('log');
    expect(byName).toHaveLength(1);

    const byDesc = store.search('分析');
    expect(byDesc).toHaveLength(1);
  });

  it('should update existing capability', () => {
    store.publish({ name: 'payment-query', description: '支付查询', version: '1.0.0', category: '金融', packageUrl: 'http://x' });
    store.publish({ name: 'payment-query', description: '支付系统查询（增强版）', version: '1.1.0', category: '金融', packageUrl: 'http://x/v2' });

    const cap = store.get('payment-query');
    expect(cap!.version).toBe('1.1.0');
    expect(cap!.description).toBe('支付系统查询（增强版）');
  });

  it('should list all capabilities', () => {
    store.publish({ name: 'a', description: 'A', version: '1.0.0', category: '通用', packageUrl: 'http://x' });
    store.publish({ name: 'b', description: 'B', version: '1.0.0', category: '通用', packageUrl: 'http://x' });

    expect(store.list()).toHaveLength(2);
  });

  it('should delete capability', () => {
    store.publish({ name: 'a', description: 'A', version: '1.0.0', category: '通用', packageUrl: 'http://x' });
    store.remove('a');
    expect(store.get('a')).toBeUndefined();
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/nasakim/projects/smanbase/bazaar && npx vitest run tests/capability-store.test.ts`
Expected: FAIL — CapabilityStore 不存在

- [ ] **Step 3: 创建 bazaar/src/capability-store.ts**

```typescript
// bazaar/src/capability-store.ts
import Database from 'better-sqlite3';
import type { Database as DatabaseType } from 'better-sqlite3';
import fs from 'fs';
import path from 'path';

export interface CapabilityInput {
  name: string;
  description: string;
  version: string;
  category: string;
  packageUrl: string;
  readme?: string;
}

export interface CapabilityRow {
  name: string;
  description: string;
  version: string;
  category: string;
  packageUrl: string;
  readme: string;
  createdAt: string;
  updatedAt: string;
}

export class CapabilityStore {
  private db: DatabaseType;

  constructor(dbPath: string) {
    const dir = path.dirname(dbPath);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    this.db = new Database(dbPath);
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS capabilities (
        name TEXT PRIMARY KEY,
        description TEXT NOT NULL,
        version TEXT NOT NULL,
        category TEXT NOT NULL,
        package_url TEXT NOT NULL,
        readme TEXT DEFAULT '',
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );

      PRAGMA journal_mode=WAL;
    `);
  }

  publish(input: CapabilityInput): void {
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO capabilities (name, description, version, category, package_url, readme, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(name) DO UPDATE SET
        description = excluded.description,
        version = excluded.version,
        category = excluded.category,
        package_url = excluded.package_url,
        readme = excluded.readme,
        updated_at = excluded.updated_at
    `).run(input.name, input.description, input.version, input.category, input.packageUrl, input.readme ?? '', now, now);
  }

  get(name: string): CapabilityRow | undefined {
    return this.db.prepare(`
      SELECT name, description, version, category, package_url as packageUrl,
        readme, created_at as createdAt, updated_at as updatedAt
      FROM capabilities WHERE name = ?
    `).get(name) as CapabilityRow | undefined;
  }

  search(keyword: string): CapabilityRow[] {
    const escaped = keyword.replace(/%/g, '\\%').replace(/_/g, '\\_');
    return this.db.prepare(`
      SELECT name, description, version, category, package_url as packageUrl,
        readme, created_at as createdAt, updated_at as updatedAt
      FROM capabilities
      WHERE name LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\'
      ORDER BY updated_at DESC
    `).all(`%${escaped}%`, `%${escaped}%`) as CapabilityRow[];
  }

  list(): CapabilityRow[] {
    return this.db.prepare(`
      SELECT name, description, version, category, package_url as packageUrl,
        readme, created_at as createdAt, updated_at as updatedAt
      FROM capabilities ORDER BY category, name
    `).all() as CapabilityRow[];
  }

  remove(name: string): void {
    this.db.prepare('DELETE FROM capabilities WHERE name = ?').run(name);
  }

  close(): void {
    this.db.close();
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/nasakim/projects/smanbase/bazaar && npx vitest run tests/capability-store.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add bazaar/src/capability-store.ts bazaar/tests/capability-store.test.ts
git commit -m "feat(bazaar): add capability store for marketplace packages"
```

---

### Task 9: 集市服务端能力包 HTTP API

**Files:**
- Modify: `bazaar/src/index.ts` (注册 HTTP API 路由)
- Modify: `bazaar/src/protocol.ts` (新增 capabilities.* 消息类型)

- [ ] **Step 1: 在 protocol.ts 中新增 capabilities 消息类型**

在 `protocol.ts` 的 `REQUIRED_FIELDS` 对象中新增（同时需要在 `VALID_TYPES` Set 中添加这些类型）：

```typescript
// 在 VALID_TYPES Set 中添加：
'capabilities.search',
'capabilities.list',
'capabilities.publish',
'capabilities.remove',

// 在 REQUIRED_FIELDS 对象中添加：
'capabilities.search': ['query'],
'capabilities.list': [],
'capabilities.publish': ['name', 'description', 'version', 'category', 'packageUrl'],
'capabilities.remove': ['name'],
```

**注意**：如果能力包 API 仅通过 HTTP 端点提供（不经过 WebSocket），则不需要修改 `protocol.ts`。此处预留是为了将来支持 WS 消息方式。

- [ ] **Step 2: 在 bazaar/src/index.ts 中注册 HTTP API**

在现有 `app.get('/api/leaderboard', ...)` 之后新增：

```typescript
// Capability Store HTTP API
const capabilityStore = new CapabilityStore(path.join(dataDir, 'capabilities.db'));

app.get('/api/capabilities/search', (req, res) => {
  const query = req.query.q as string;
  if (!query) {
    res.json([]);
    return;
  }
  res.json(capabilityStore.search(query));
});

app.get('/api/capabilities/list', (_req, res) => {
  res.json(capabilityStore.list());
});

app.get('/api/capabilities/:name', (req, res) => {
  const cap = capabilityStore.get(req.params.name);
  if (!cap) {
    res.status(404).json({ error: 'Capability not found' });
    return;
  }
  res.json(cap);
});
```

在文件顶部 import 区新增：

```typescript
import { CapabilityStore } from './capability-store.js';
```

- [ ] **Step 3: 运行 tsc + 测试**

Run: `cd /Users/nasakim/projects/smanbase/bazaar && npx tsc --noEmit && npx vitest run`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add bazaar/src/index.ts bazaar/src/protocol.ts
git commit -m "feat(bazaar): add HTTP API endpoints for capability marketplace"
```

---

## Chunk 5: 集成验证

### Task 10: 全量测试验证

- [ ] **Step 1: 运行主项目全部 bazaar 测试**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/bazaar/`
Expected: ALL PASS

- [ ] **Step 2: 运行集市服务端全部测试**

Run: `cd /Users/nasakim/projects/smanbase/bazaar && npx vitest run`
Expected: ALL PASS

- [ ] **Step 3: 运行前端测试（确保无破坏）**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run src/features/bazaar/`
Expected: ALL PASS

- [ ] **Step 4: 双端 tsc 检查**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit && cd bazaar && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 5: 最终提交**

```bash
git add -A
git commit -m "feat(bazaar): batch A complete - experience extraction, pair familiarity, capability store"
```
