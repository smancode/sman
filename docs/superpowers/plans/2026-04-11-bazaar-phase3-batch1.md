# Phase 3 Batch 1: 声望引擎 + 经验路由 + MCP 工具

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在集市服务器实现声望计算引擎，在 Bridge 层实现 Agent 经验路由和 MCP 工具（bazaar_search + bazaar_collaborate），让 Claude 能自主搜索集市能力并发起协作。

**Architecture:** 声望引擎作为独立模块 `reputation.ts` 被 TaskEngine 在任务完成时调用。经验路由存本地 BazaarStore（learned_routes 表），MCP 工具通过 `createSdkMcpServer` 注册（和 WebAccess 同模式），由 Bridge 层注入 Claude Session。

**Tech Stack:** TypeScript, better-sqlite3, Vitest, @anthropic-ai/claude-agent-sdk (MCP), zod

**Spec:** `docs/superpowers/specs/2026-04-11-bazaar-phase3-batch1-design.md`

---

## Chunk 1: 声望引擎

### Task 1: AgentStore 新增 updateReputation 和 reputation_log 表

**Files:**
- Modify: `bazaar/src/agent-store.ts`
- Modify: `bazaar/tests/agent-store.test.ts`

- [ ] **Step 1: 写测试 — updateReputation 和 reputation_log**

在 `bazaar/tests/agent-store.test.ts` 末尾追加：

```typescript
  describe('reputation', () => {
    it('should update agent reputation', () => {
      store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
      store.updateReputation('a1', 2.5);
      const agent = store.getAgent('a1');
      expect(agent!.reputation).toBe(2.5);
    });

    it('should accumulate reputation', () => {
      store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
      store.updateReputation('a1', 1.5);
      store.updateReputation('a1', 2.0);
      const agent = store.getAgent('a1');
      expect(agent!.reputation).toBeCloseTo(3.5);
    });

    it('should not go below 0', () => {
      store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
      store.updateReputation('a1', -5);
      const agent = store.getAgent('a1');
      expect(agent!.reputation).toBe(0);
    });

    it('should log reputation changes', () => {
      store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
      store.logReputation('a1', 'task-001', 1.5, 'base');
      store.logReputation('a1', 'task-002', 2.0, 'quality');
      const logs = store.getReputationLogs('a1');
      expect(logs).toHaveLength(2);
      expect(logs[0].delta).toBe(2.0);
      expect(logs[0].reason).toBe('quality');
    });

    it('should count reputation events between two agents today', () => {
      store.registerAgent({ id: 'a1', username: 'test1', hostname: 'h', name: 'T1' });
      store.logReputation('a1', 't1', 1, 'base', 'req-1');
      store.logReputation('a1', 't2', 1, 'base', 'req-1');
      store.logReputation('a1', 't3', 1, 'base', 'req-1');
      store.logReputation('a1', 't4', 1, 'base', 'req-1');
      expect(store.getReputationCountToday('a1', 'req-1')).toBe(4);
    });
  });
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd bazaar && npx vitest run tests/agent-store.test.ts`
Expected: FAIL — `store.updateReputation is not a function`

- [ ] **Step 3: 在 AgentStore.init() 中添加 reputation_log 表**

在 `bazaar/src/agent-store.ts` 的 `init()` 方法中，`PRAGMA journal_mode=WAL;` 前追加：

```typescript
      CREATE TABLE IF NOT EXISTS reputation_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        agent_id TEXT NOT NULL,
        task_id TEXT NOT NULL,
        delta REAL NOT NULL,
        reason TEXT NOT NULL,
        source_agent_id TEXT,
        created_at TEXT NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_reputation_agent ON reputation_log(agent_id);
      CREATE INDEX IF NOT EXISTS idx_reputation_created ON reputation_log(created_at);
```

- [ ] **Step 4: 在 AgentStore 类末尾 close() 前追加方法**

```typescript
  // ── Reputation ──

  updateReputation(agentId: string, delta: number): void {
    this.db.prepare(`
      UPDATE agents SET reputation = MAX(0, reputation + ?) WHERE id = ?
    `).run(delta, agentId);
  }

  logReputation(agentId: string, taskId: string, delta: number, reason: string, sourceAgentId?: string): void {
    this.db.prepare(`
      INSERT INTO reputation_log (agent_id, task_id, delta, reason, source_agent_id, created_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(agentId, taskId, delta, reason, sourceAgentId ?? null, new Date().toISOString());
  }

  getReputationLogs(agentId: string, limit = 100): Array<{ id: number; taskId: string; delta: number; reason: string; createdAt: string }> {
    return this.db.prepare(`
      SELECT id, task_id as taskId, delta, reason, created_at as createdAt
      FROM reputation_log WHERE agent_id = ?
      ORDER BY created_at DESC LIMIT ?
    `).all(agentId, limit) as Array<{ id: number; taskId: string; delta: number; reason: string; createdAt: string }>;
  }

  getReputationCountToday(agentId: string, sourceAgentId: string): number {
    const today = new Date().toISOString().slice(0, 10);
    const row = this.db.prepare(`
      SELECT COUNT(*) as count FROM reputation_log
      WHERE agent_id = ? AND source_agent_id = ? AND created_at >= ?
    `).get(agentId, sourceAgentId, today) as { count: number } | undefined;
    return row?.count ?? 0;
  }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd bazaar && npx vitest run tests/agent-store.test.ts`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add bazaar/src/agent-store.ts bazaar/tests/agent-store.test.ts
git commit -m "feat(bazaar): add reputation_log table and updateReputation to AgentStore"
```

---

### Task 2: 实现 ReputationEngine

**Files:**
- Create: `bazaar/src/reputation.ts`
- Create: `bazaar/tests/reputation.test.ts`

- [ ] **Step 1: 写测试**

创建 `bazaar/tests/reputation.test.ts`：

```typescript
// bazaar/tests/reputation.test.ts
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ReputationEngine } from '../src/reputation.js';
import type { AgentStore } from '../src/agent-store.js';

function createMockAgentStore(): AgentStore {
  return {
    updateReputation: vi.fn(),
    logReputation: vi.fn(),
    getReputationCountToday: vi.fn(() => 0),
    getAgent: vi.fn(() => ({ reputation: 10 })),
    logAudit: vi.fn(),
  } as unknown as AgentStore;
}

describe('ReputationEngine', () => {
  let engine: ReputationEngine;
  let store: AgentStore;

  beforeEach(() => {
    store = createMockAgentStore();
    engine = new ReputationEngine(store);
  });

  describe('onTaskComplete', () => {
    it('should calculate helper reputation: base + quality', () => {
      const result = engine.onTaskComplete('task-1', 'req-1', 'help-1', 4);

      // helper: base(1.0) + rating*0.5(2.0) = 3.0, decay old: 10*0.95=9.5, new=12.5, delta=2.5
      // Wait — delta is the score added, not the final value
      // helperDelta = 1.0 + 4 * 0.5 = 3.0
      expect(result.helperDelta).toBe(3.0);
      expect(result.requesterDelta).toBe(0.3);
      expect(store.updateReputation).toHaveBeenCalledWith('help-1', 3.0);
      expect(store.updateReputation).toHaveBeenCalledWith('req-1', 0.3);
    });

    it('should give minimum helper score even with rating 1', () => {
      const result = engine.onTaskComplete('task-1', 'req-1', 'help-1', 1);
      // helper: 1.0 + 1*0.5 = 1.5
      expect(result.helperDelta).toBe(1.5);
    });

    it('should cap helper score at rating 5', () => {
      const result = engine.onTaskComplete('task-1', 'req-1', 'help-1', 5);
      // helper: 1.0 + 5*0.5 = 3.5
      expect(result.helperDelta).toBe(3.5);
    });

    it('should skip helper if daily cap reached (3 per pair)', () => {
      (store.getReputationCountToday as any).mockReturnValue(3);
      const result = engine.onTaskComplete('task-1', 'req-1', 'help-1', 5);
      expect(result.helperDelta).toBe(0);
      expect(store.updateReputation).not.toHaveBeenCalledWith('help-1', expect.anything());
    });

    it('should log reputation events', () => {
      engine.onTaskComplete('task-1', 'req-1', 'help-1', 4);
      expect(store.logReputation).toHaveBeenCalledWith('help-1', 'task-1', 3.0, 'base', 'req-1');
      expect(store.logReputation).toHaveBeenCalledWith('help-1', 'task-1', 2.0, 'quality', 'req-1');
      expect(store.logReputation).toHaveBeenCalledWith('req-1', 'task-1', 0.3, 'question_bonus', 'help-1');
    });
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd bazaar && npx vitest run tests/reputation.test.ts`
Expected: FAIL

- [ ] **Step 3: 实现 ReputationEngine**

创建 `bazaar/src/reputation.ts`：

```typescript
// bazaar/src/reputation.ts
import { createLogger, type Logger } from './utils/logger.js';
import type { AgentStore } from './agent-store.js';

const DAILY_CAP_PER_PAIR = 3;

export interface ReputationResult {
  helperDelta: number;
  requesterDelta: number;
}

export class ReputationEngine {
  private log: Logger;
  private store: AgentStore;

  constructor(store: AgentStore) {
    this.store = store;
    this.log = createLogger('ReputationEngine');
  }

  /**
   * 任务完成时计算声望变化
   *
   * 公式（简单版）：
   * - 协助方：基础分(1.0) + 评分加成(rating × 0.5)
   * - 请求方：0.3（鼓励提问）
   * - 防刷：同一对请求方-协助方每天最多计 3 次声望
   */
  onTaskComplete(
    taskId: string,
    requesterId: string,
    helperId: string,
    rating: number,
  ): ReputationResult {
    // 防刷检查
    const countToday = this.store.getReputationCountToday(helperId, requesterId);
    if (countToday >= DAILY_CAP_PER_PAIR) {
      this.log.info(`Daily reputation cap reached for pair ${requesterId}→${helperId}`);
      return { helperDelta: 0, requesterDelta: 0 };
    }

    // 协助方得分：基础分 + 评分加成
    const baseScore = 1.0;
    const qualityBonus = rating * 0.5;
    const helperDelta = baseScore + qualityBonus;

    // 请求方得分
    const requesterDelta = 0.3;

    // 更新声望
    this.store.updateReputation(helperId, helperDelta);
    this.store.updateReputation(requesterId, requesterDelta);

    // 记录日志
    this.store.logReputation(helperId, taskId, baseScore, 'base', requesterId);
    this.store.logReputation(helperId, taskId, qualityBonus, 'quality', requesterId);
    this.store.logReputation(requesterId, taskId, requesterDelta, 'question_bonus', helperId);

    this.log.info(`Reputation updated: helper=${helperId} +${helperDelta}, requester=${requesterId} +${requesterDelta}`);

    return { helperDelta, requesterDelta };
  }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd bazaar && npx vitest run tests/reputation.test.ts`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add bazaar/src/reputation.ts bazaar/tests/reputation.test.ts
git commit -m "feat(bazaar): implement ReputationEngine with simple scoring formula"
```

---

### Task 3: 集成声望引擎到 TaskEngine

**Files:**
- Modify: `bazaar/src/task-engine.ts`
- Modify: `bazaar/src/index.ts`
- Modify: `bazaar/src/protocol.ts`

- [ ] **Step 1: 修改 TaskEngine 构造函数注入 ReputationEngine**

在 `bazaar/src/task-engine.ts` 顶部新增 import：

```typescript
import type { ReputationEngine } from './reputation.js';
```

修改 TaskEngine 类，新增 `reputationEngine` 属性：

```typescript
  private reputationEngine: ReputationEngine | null;

  constructor(
    taskStore: TaskStore,
    agentStore: AgentStore,
    connections: Map<string, WebSocket>,
    sendTo: SendFn,
    reputationEngine?: ReputationEngine,
  ) {
    this.taskStore = taskStore;
    this.agentStore = agentStore;
    this.connections = connections;
    this.sendTo = sendTo;
    this.reputationEngine = reputationEngine ?? null;
    this.log = createLogger('TaskEngine');
  }
```

- [ ] **Step 2: 修改 handleTaskComplete 调用声望引擎**

替换 `bazaar/src/task-engine.ts` 中的 `handleTaskComplete` 方法：

```typescript
  handleTaskComplete(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): void {
    const taskId = msg.payload.taskId as string;
    const rating = msg.payload.rating as number;
    const feedback = msg.payload.feedback as string | undefined;
    const task = this.taskStore.getTask(taskId);
    if (!task) return;

    this.taskStore.updateTaskStatus(taskId, 'completed', { rating, feedback });

    // 声望计算
    let reputationDelta = 0;
    if (this.reputationEngine && task.requesterId && task.helperId) {
      const repResult = this.reputationEngine.onTaskComplete(
        taskId, task.requesterId, task.helperId, rating,
      );
      reputationDelta = repResult.helperDelta;
    }

    // 通知协助方结算
    if (task.helperId) {
      this.sendTo(task.helperId, {
        type: 'task.result',
        id: uuidv4(),
        payload: { taskId, reputationDelta },
      });
    }

    this.agentStore.logAudit('task.completed', fromAgentId, task.helperId ?? undefined, taskId, {
      rating, feedback, reputationDelta,
    });
  }
```

- [ ] **Step 3: 修改 index.ts 创建 ReputationEngine 并注入**

在 `bazaar/src/index.ts` 中：

新增 import：
```typescript
import { ReputationEngine } from './reputation.js';
```

在 `const taskEngine = new TaskEngine(...)` 行前新增：
```typescript
const reputationEngine = new ReputationEngine(store);
```

修改 taskEngine 创建：
```typescript
const taskEngine = new TaskEngine(taskStore, store, connections, sendToAgent, reputationEngine);
```

- [ ] **Step 4: 在 protocol.ts 新增 reputation.update 消息类型**

在 `bazaar/src/protocol.ts` 的 `VALID_TYPES` Set 中，`'server.maintenance'` 后追加：
```typescript
  'reputation.update',
```

- [ ] **Step 5: 在 shared/bazaar-types.ts 新增声望类型**

在 `shared/bazaar-types.ts` 的 `TaskResultPayload` 接口后追加：

```typescript
export interface ReputationUpdatePayload {
  agentId: string;
  delta: number;
  newTotal: number;
  reason: string;
}
```

- [ ] **Step 6: 验证编译**

Run: `cd bazaar && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 7: 运行全部 bazaar 测试**

Run: `cd bazaar && npx vitest run`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```bash
git add bazaar/src/task-engine.ts bazaar/src/index.ts bazaar/src/protocol.ts shared/bazaar-types.ts
git commit -m "feat(bazaar): integrate ReputationEngine into TaskEngine and wire up in index.ts"
```

---

## Chunk 2: 经验路由 + MCP 工具

### Task 4: BazaarStore 新增 learned_routes 表

**Files:**
- Modify: `server/bazaar/bazaar-store.ts`
- Modify: `tests/server/bazaar/bazaar-store.test.ts`

- [ ] **Step 1: 写测试**

在 `tests/server/bazaar/bazaar-store.test.ts` 末尾追加：

```typescript
  describe('learned_routes', () => {
    it('should save and find learned routes', () => {
      store.saveLearnedRoute({ capability: '支付查询', targetAgentId: 'agent-002', targetAgentName: '小李' });
      const routes = store.findLearnedRoutes('支付');
      expect(routes).toHaveLength(1);
      expect(routes[0].targetAgentId).toBe('agent-002');
      expect(routes[0].targetAgentName).toBe('小李');
    });

    it('should increment success_count on duplicate', () => {
      store.saveLearnedRoute({ capability: '支付查询', targetAgentId: 'agent-002', targetAgentName: '小李' });
      store.saveLearnedRoute({ capability: '支付查询', targetAgentId: 'agent-002', targetAgentName: '小李' });
      const routes = store.findLearnedRoutes('支付');
      expect(routes[0].successCount).toBe(2);
    });

    it('should list all learned routes', () => {
      store.saveLearnedRoute({ capability: '支付查询', targetAgentId: 'a1', targetAgentName: 'A1' });
      store.saveLearnedRoute({ capability: '风控规则', targetAgentId: 'a2', targetAgentName: 'A2' });
      expect(store.listLearnedRoutes()).toHaveLength(2);
    });

    it('should return empty array when no routes match', () => {
      store.saveLearnedRoute({ capability: '支付查询', targetAgentId: 'a1', targetAgentName: 'A1' });
      expect(store.findLearnedRoutes('网络')).toHaveLength(0);
    });
  });
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npx vitest run tests/server/bazaar/bazaar-store.test.ts`
Expected: FAIL

- [ ] **Step 3: 在 BazaarStore.init() 添加 learned_routes 表**

在 `server/bazaar/bazaar-store.ts` 的 `init()` 中 `PRAGMA journal_mode=WAL;` 前追加：

```typescript
      CREATE TABLE IF NOT EXISTS learned_routes (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        capability TEXT NOT NULL,
        target_agent_id TEXT NOT NULL,
        target_agent_name TEXT NOT NULL,
        success_count INTEGER DEFAULT 1,
        last_used_at TEXT NOT NULL,
        UNIQUE(capability, target_agent_id)
      );

      CREATE INDEX IF NOT EXISTS idx_routes_capability ON learned_routes(capability);
```

- [ ] **Step 4: 在 BazaarStore 类末尾 close() 前追加方法**

```typescript
  // ── Learned Routes ──

  saveLearnedRoute(input: { capability: string; targetAgentId: string; targetAgentName: string }): void {
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO learned_routes (capability, target_agent_id, target_agent_name, success_count, last_used_at)
      VALUES (?, ?, ?, 1, ?)
      ON CONFLICT(capability, target_agent_id) DO UPDATE SET
        success_count = success_count + 1,
        last_used_at = excluded.last_used_at
    `).run(input.capability, input.targetAgentId, input.targetAgentName, now);
  }

  findLearnedRoutes(keyword: string): Array<{ id: number; capability: string; targetAgentId: string; targetAgentName: string; successCount: number; lastUsedAt: string }> {
    const escaped = keyword.replace(/%/g, '\\%').replace(/_/g, '\\_');
    return this.db.prepare(`
      SELECT id, capability, target_agent_id as targetAgentId,
        target_agent_name as targetAgentName, success_count as successCount, last_used_at as lastUsedAt
      FROM learned_routes
      WHERE capability LIKE ? ESCAPE '\\'
      ORDER BY success_count DESC
    `).all(`%${escaped}%`) as Array<{ id: number; capability: string; targetAgentId: string; targetAgentName: string; successCount: number; lastUsedAt: string }>;
  }

  listLearnedRoutes(): Array<{ id: number; capability: string; targetAgentId: string; targetAgentName: string; successCount: number; lastUsedAt: string }> {
    return this.db.prepare(`
      SELECT id, capability, target_agent_id as targetAgentId,
        target_agent_name as targetAgentName, success_count as successCount, last_used_at as lastUsedAt
      FROM learned_routes
      ORDER BY last_used_at DESC
    `).all() as Array<{ id: number; capability: string; targetAgentId: string; targetAgentName: string; successCount: number; lastUsedAt: string }>;
  }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `npx vitest run tests/server/bazaar/bazaar-store.test.ts`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add server/bazaar/bazaar-store.ts tests/server/bazaar/bazaar-store.test.ts
git commit -m "feat(bazaar): add learned_routes table for agent experience routing"
```

---

### Task 5: 实现 MCP Server (bazaar_search + bazaar_collaborate)

**Files:**
- Create: `server/bazaar/bazaar-mcp.ts`
- Modify: `server/bazaar/types.ts`

- [ ] **Step 1: 在 types.ts 新增 MCP 依赖类型**

在 `server/bazaar/types.ts` 末尾追加：

```typescript
// ── MCP Server 依赖注入 ──

export interface BazaarMcpDeps {
  store: import('./bazaar-store.js').BazaarStore;
  client: import('./bazaar-client.js').BazaarClient;
  broadcast: (data: string) => void;
}
```

- [ ] **Step 2: 实现 bazaar-mcp.ts**

创建 `server/bazaar/bazaar-mcp.ts`：

```typescript
// server/bazaar/bazaar-mcp.ts
/**
 * MCP Server — registers bazaar_search and bazaar_collaborate tools.
 *
 * Uses createSdkMcpServer + tool API for in-process MCP server.
 * Pattern follows server/web-access/mcp-server.ts.
 */
import { z } from 'zod';
import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import type { McpSdkServerConfigWithInstance } from '@anthropic-ai/claude-agent-sdk';
import type { BazaarMcpDeps } from './types.js';

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

function textResult(text: string, isError = false): ToolResult {
  return { content: [{ type: 'text', text }], isError };
}

function errorResult(message: string): ToolResult {
  return textResult(message, true);
}

export function createBazaarMcpServer(deps: BazaarMcpDeps): McpSdkServerConfigWithInstance {
  const searchTool = tool(
    'bazaar_search',
    '搜索集市上其他 Agent 的能力。当你无法完成某个任务时（比如缺少某个项目的代码访问权限、不了解特定业务逻辑），'
      + '用此工具搜索能帮你的人。返回匹配的 Agent 列表（名称、能力、在线状态、声望），然后用 bazaar_collaborate 发起协作。',
    {
      query: z.string().describe('搜索关键词，描述你需要的能力，如 "支付查询" 或 "风控规则"'),
    },
    async (args: any) => {
      try {
        const query = args.query as string;

        // 1. 先查本地经验路由
        const localRoutes = deps.store.findLearnedRoutes(query);

        // 2. 通过 client 发送 task.create 到集市服务器获取远程搜索结果
        // 使用 Promise 等待搜索结果
        const remoteMatches = await searchRemoteAgents(deps, query);

        // 3. 合并结果：本地已知能人排在前面，远程结果去重
        const localAgentIds = new Set(localRoutes.map(r => r.targetAgentId));
        const filteredRemote = remoteMatches.filter((m: any) => !localAgentIds.has(m.agentId));

        const allResults = [
          ...localRoutes.map(r => ({
            source: 'local',
            agentId: r.targetAgentId,
            name: r.targetAgentName,
            capability: r.capability,
            successCount: r.successCount,
          })),
          ...filteredRemote,
        ];

        if (allResults.length === 0) {
          return textResult(`没有找到拥有 "${query}" 能力的 Agent。你可以尝试换个关键词搜索，或者稍后再试。`);
        }

        const lines = allResults.map((r: any, i: number) => {
          const local = r.source === 'local' ? ` [历史协作 ${r.successCount} 次]` : '';
          return `${i + 1}. ${r.name} (${r.agentId})${local} — ${r.capability ?? query}`;
        });

        return textResult(
          `找到 ${allResults.length} 个匹配的 Agent：\n${lines.join('\n')}\n\n`
          + `用 bazaar_collaborate 向其中任何一个发起协作。`,
        );
      } catch (e: any) {
        return errorResult(`搜索失败: ${e.message}`);
      }
    },
  );

  const collaborateTool = tool(
    'bazaar_collaborate',
    '向指定 Agent 发起协作请求。先用 bazaar_search 找到合适的 Agent，然后用此工具请求对方协助。'
      + '对方接受后，你们可以实时对话解决问题。协作过程会在前端传送门页面实时显示。',
    {
      targetAgentId: z.string().describe('目标 Agent 的 ID（从 bazaar_search 结果中获取）'),
      question: z.string().describe('你需要对方帮助解决的问题'),
    },
    async (args: any) => {
      try {
        const { targetAgentId, question } = args;

        // 通过 client 发送 task.create + task.offer
        const taskId = await createAndOfferTask(deps, targetAgentId, question);

        return textResult(
          `协作请求已发送！任务 ID: ${taskId}\n`
          + `正在等待 ${targetAgentId} 接受。对方接受后，你可以直接在这里继续对话。`,
        );
      } catch (e: any) {
        return errorResult(`发起协作失败: ${e.message}`);
      }
    },
  );

  return createSdkMcpServer({
    name: 'bazaar',
    version: '1.0.0',
    tools: [searchTool, collaborateTool],
  });
}

// ── Helper：远程搜索 ──

function searchRemoteAgents(deps: BazaarMcpDeps, query: string): Promise<Array<{ agentId: string; name: string; capability: string }>> {
  return new Promise((resolve) => {
    const timeout = setTimeout(() => resolve([]), 5000);

    const handler = (msg: Record<string, unknown>) => {
      if (msg.type === 'task.search_result') {
        clearTimeout(timeout);
        deps.client.off('message', handler);
        const matches = (msg as any).payload?.matches ?? [];
        resolve(matches.map((m: any) => ({
          agentId: m.agentId,
          name: m.name,
          capability: m.repo,
        })));
      }
    };

    deps.client.on('message', handler as (...a: unknown[]) => void);

    deps.client.send({
      id: `mcp-search-${Date.now()}`,
      type: 'task.create',
      payload: { question: query, capabilityQuery: query },
    });
  });
}

// ── Helper：创建任务并发送 offer ──

function createAndOfferTask(deps: BazaarMcpDeps, targetAgentId: string, question: string): Promise<string> {
  return new Promise((resolve, reject) => {
    let taskId: string | null = null;
    const timeout = setTimeout(() => reject(new Error('协作请求超时')), 10000);

    const handler = (msg: Record<string, unknown>) => {
      if (msg.type === 'task.search_result' && !taskId) {
        taskId = ((msg as any).payload?.taskId) as string;
        // 收到搜索结果后发 offer
        deps.client.send({
          id: `mcp-offer-${Date.now()}`,
          type: 'task.offer',
          payload: { taskId, targetAgent },
        });
      } else if (msg.type === 'task.matched' && taskId) {
        clearTimeout(timeout);
        deps.client.off('message', handler);
        resolve(taskId);
      } else if (msg.type === 'error') {
        clearTimeout(timeout);
        deps.client.off('message', handler);
        reject(new Error(((msg as any).payload?.message as string) ?? 'Unknown error'));
      }
    };

    deps.client.on('message', handler as (...a: unknown[]) => void);

    deps.client.send({
      id: `mcp-create-${Date.now()}`,
      type: 'task.create',
      payload: { question, capabilityQuery: question },
    });
  });
}
```

- [ ] **Step 3: 验证编译**

Run: `npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 4: Commit**

```bash
git add server/bazaar/bazaar-mcp.ts server/bazaar/types.ts
git commit -m "feat(bazaar): add MCP Server with bazaar_search and bazaar_collaborate tools"
```

---

### Task 6: 集成 MCP 和声望推送到 BazaarBridge

**Files:**
- Modify: `server/bazaar/bazaar-bridge.ts`

- [ ] **Step 1: 在 BazaarBridge 中集成 MCP Server**

在 `server/bazaar/bazaar-bridge.ts` 顶部新增 import：

```typescript
import { createBazaarMcpServer } from './bazaar-mcp.js';
```

在 `start()` 方法中，`this.bazaarSession = new BazaarSession(...)` 之后追加：

```typescript
      // 创建 MCP Server（bazaar_search + bazaar_collaborate）
      const mcpServer = createBazaarMcpServer({
        store: this.store,
        client: this.client,
        broadcast: this.deps.broadcast,
      });
      // 注入到 ClaudeSessionManager（和 WebAccess 同模式）
      // TODO: 需要确认 sessionManager 的 MCP 注入 API
      this.log.info('Bazaar MCP server created');
```

- [ ] **Step 2: 在 handleBazaarMessage 中处理 reputation.update**

在 `server/bazaar/bazaar-bridge.ts` 的 `handleBazaarMessage` 方法中，`case 'task.chat':` 之前添加：

```typescript
      case 'reputation.update':
        this.deps.broadcast(JSON.stringify({
          type: 'bazaar.status',
          event: 'reputation_updated',
          agentId: msg.payload.agentId,
          delta: msg.payload.delta,
          newTotal: msg.payload.newTotal,
          reason: msg.payload.reason,
        }));
        break;
```

- [ ] **Step 3: 在 handleTaskComplete 中保存经验路由**

修改 `handleTaskComplete` 方法，在调用 `completeCollaboration` 之前添加经验路由保存：

```typescript
  private handleTaskComplete(payload: Record<string, unknown>): void {
    const taskId = payload.taskId as string;
    const rating = payload.rating as number;
    const feedback = (payload.feedback as string) ?? '';

    // 保存经验路由（rating >= 3 表示成功）
    if (rating >= 3) {
      const task = this.store.getTask(taskId);
      if (task) {
        const capability = task.question.slice(0, 50); // 取问题前 50 字作为能力标签
        const agentId = task.requesterAgentId ?? task.helperAgentId;
        const agentName = task.requesterName ?? task.helperName;
        if (agentId && agentName) {
          this.store.saveLearnedRoute({
            capability,
            targetAgentId: agentId,
            targetAgentName: agentName,
          });
        }
      }
    }

    if (this.bazaarSession) {
      this.bazaarSession.completeCollaboration(taskId, rating, feedback);
    }
  }
```

- [ ] **Step 4: 验证编译**

Run: `npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 5: 运行全部 bazaar 相关测试**

Run: `npx vitest run tests/server/bazaar/ && cd bazaar && npx vitest run`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add server/bazaar/bazaar-bridge.ts
git commit -m "feat(bazaar): integrate MCP server and reputation/experience into BazaarBridge"
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

- [ ] **Step 4: 检查零侵入**

确认以下文件未修改：
- `server/claude-session.ts`
- `src/` 目录下任何文件
- 全局 system prompt

- [ ] **Step 5: 最终 Commit**

```bash
git add -A
git commit -m "chore(bazaar): Phase 3 Batch 1 verification — all tests pass"
```

---

## 文件依赖关系

```
agent-store.ts (Task 1: reputation_log + updateReputation)
    ↓
reputation.ts (Task 2: 声望计算引擎)
    ↓
task-engine.ts + index.ts + protocol.ts (Task 3: 集成声望引擎)
    ↓
bazaar-store.ts (Task 4: learned_routes)
    ↓
bazaar-mcp.ts (Task 5: MCP 工具)
    ↓
bazaar-bridge.ts (Task 6: 集成 MCP + 经验路由)
    ↓
最终验证 (Task 7)
```

## 公共 API 使用清单

| API | 用途 | 调用位置 |
|-----|------|---------|
| `agentStore.updateReputation(agentId, delta)` | 更新 Agent 声望 | `ReputationEngine.onTaskComplete` |
| `agentStore.logReputation(agentId, taskId, delta, reason, sourceAgentId)` | 记录声望日志 | `ReputationEngine.onTaskComplete` |
| `agentStore.getReputationCountToday(agentId, sourceAgentId)` | 防刷检查 | `ReputationEngine.onTaskComplete` |
| `bazaarStore.saveLearnedRoute(...)` | 保存经验路由 | `BazaarBridge.handleTaskComplete` |
| `bazaarStore.findLearnedRoutes(keyword)` | 查找已知能人 | `bazaar-mcp.ts bazaar_search` |
| `bazaarClient.send(msg)` | 发消息到集市 | `bazaar-mcp.ts` |
| `createSdkMcpServer({ name, version, tools })` | 创建 MCP Server | `bazaar-mcp.ts` |

## 零侵入验证

- [ ] 不修改 `server/claude-session.ts`
- [ ] 不修改前端代码（`src/`）
- [ ] 不修改全局 system prompt
- [ ] MCP 工具通过 SDK `createSdkMcpServer` 注册
- [ ] 删除 `bazaar/src/reputation.ts`、`server/bazaar/bazaar-mcp.ts` 后项目编译零报错
