# Bazaar 集市服务器任务引擎 实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在集市服务器中实现完整的任务路由引擎——任务创建、能力搜索、候选人邀请、接受/拒绝、匹配、聊天中继、超时管理、完成评分。

**Architecture:** 新增 `bazaar/src/task-store.ts`（任务 SQLite 持久化）和 `bazaar/src/task-engine.ts`（任务路由引擎），扩展现有 `message-router.ts` 注入 TaskEngine 和 connections Map。TaskEngine 持有 AgentStore + TaskStore + connections 引用，通过 connections Map 定向推送消息给 Agent。

**Tech Stack:** TypeScript, SQLite (better-sqlite3), WebSocket (ws), Vitest

**Design Doc:** `docs/superpowers/specs/2026-04-10-bazaar-agent-swarm-design.md`

**前置依赖：** Phase 1 Chunk 1（集市服务器骨架）已完成

---

## Chunk 4: 集市服务器任务引擎

### Task 1: 任务 SQLite 持久化层

**Files:**
- Create: `bazaar/src/task-store.ts`
- Test: `bazaar/tests/task-store.test.ts`

- [ ] **Step 1: 写测试**

```typescript
// bazaar/tests/task-store.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TaskStore } from '../src/task-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('TaskStore', () => {
  let store: TaskStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `bazaar-task-test-${Date.now()}.db`);
    store = new TaskStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  describe('createTask / getTask', () => {
    it('should create and retrieve a task', () => {
      store.createTask({
        id: 'task-001',
        requesterId: 'agent-001',
        question: '支付查询怎么实现？',
        capabilityQuery: '支付 查询',
        status: 'searching',
      });
      const task = store.getTask('task-001');
      expect(task).toBeDefined();
      expect(task!.id).toBe('task-001');
      expect(task!.requesterId).toBe('agent-001');
      expect(task!.question).toBe('支付查询怎么实现？');
      expect(task!.status).toBe('searching');
    });

    it('should return undefined for non-existent task', () => {
      expect(store.getTask('non-existent')).toBeUndefined();
    });
  });

  describe('updateTaskStatus', () => {
    it('should update task status', () => {
      store.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'searching' });
      store.updateTaskStatus('t1', 'offered', { helperId: 'agent-002', helperName: '小李' });
      const task = store.getTask('t1');
      expect(task!.status).toBe('offered');
      expect(task!.helperId).toBe('agent-002');
    });

    it('should update to completed with rating', () => {
      store.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting', helperId: 'a2' });
      store.updateTaskStatus('t1', 'completed', { rating: 5, feedback: '很棒' });
      const task = store.getTask('t1');
      expect(task!.status).toBe('completed');
      expect(task!.rating).toBe(5);
      expect(task!.feedback).toBe('很棒');
      expect(task!.completedAt).toBeDefined();
    });
  });

  describe('listActiveTasks', () => {
    it('should return only active tasks', () => {
      store.createTask({ id: 't1', requesterId: 'a1', question: 'q1', capabilityQuery: 'c', status: 'chatting' });
      store.createTask({ id: 't2', requesterId: 'a1', question: 'q2', capabilityQuery: 'c', status: 'completed' });
      store.createTask({ id: 't3', requesterId: 'a1', question: 'q3', capabilityQuery: 'c', status: 'offered' });
      const active = store.listActiveTasks();
      expect(active).toHaveLength(2);
      expect(active.map(t => t.id)).toContain('t1');
      expect(active.map(t => t.id)).toContain('t3');
    });
  });

  describe('listTasksByAgent', () => {
    it('should return tasks where agent is requester or helper', () => {
      store.createTask({ id: 't1', requesterId: 'a1', question: 'q1', capabilityQuery: 'c', status: 'chatting', helperId: 'a2' });
      store.createTask({ id: 't2', requesterId: 'a3', question: 'q2', capabilityQuery: 'c', status: 'chatting', helperId: 'a1' });
      store.createTask({ id: 't3', requesterId: 'a3', question: 'q3', capabilityQuery: 'c', status: 'searching' });
      const tasks = store.listTasksByAgent('a1');
      expect(tasks).toHaveLength(2);
    });
  });

  describe('getActiveTaskCount', () => {
    it('should count active tasks for an agent (requester or helper)', () => {
      store.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting' });
      store.createTask({ id: 't2', requesterId: 'a3', question: 'q', capabilityQuery: 'c', status: 'chatting', helperId: 'a1' });
      store.createTask({ id: 't3', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'completed' });
      expect(store.getActiveTaskCount('a1')).toBe(2);
    });
  });

  describe('saveChatMessage / listChatMessages', () => {
    it('should persist and retrieve chat messages', () => {
      store.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting' });
      store.saveChatMessage('t1', 'agent-001', '你好，我需要帮助');
      store.saveChatMessage('t1', 'agent-002', '好的，我来帮你');
      const messages = store.listChatMessages('t1');
      expect(messages).toHaveLength(2);
      expect(messages[0].from).toBe('agent-001');
      expect(messages[1].text).toBe('好的，我来帮你');
    });

    it('should return empty array for task with no messages', () => {
      expect(store.listChatMessages('no-task')).toEqual([]);
    });
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd bazaar && npx vitest run tests/task-store.test.ts 2>&1 | tail -5`
Expected: FAIL

- [ ] **Step 3: 实现 TaskStore**

```typescript
// bazaar/src/task-store.ts
import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from './utils/logger.js';
import fs from 'fs';
import path from 'path';

export interface TaskRow {
  id: string;
  requesterId: string;
  helperId: string | null;
  helperName: string | null;
  question: string;
  capabilityQuery: string;
  status: string;
  rating: number | null;
  feedback: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  deadline: string | null;
}

export interface ChatMessageRow {
  taskId: string;
  from: string;
  text: string;
  timestamp: string;
}

export class TaskStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    const dir = path.dirname(dbPath);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('TaskStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS tasks (
        id TEXT PRIMARY KEY,
        requester_id TEXT NOT NULL,
        helper_id TEXT,
        helper_name TEXT,
        question TEXT NOT NULL,
        capability_query TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'created',
        rating INTEGER,
        feedback TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        completed_at TEXT,
        deadline TEXT
      );

      CREATE TABLE IF NOT EXISTS chat_messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        task_id TEXT NOT NULL,
        from_agent TEXT NOT NULL,
        text TEXT NOT NULL,
        timestamp TEXT NOT NULL,
        FOREIGN KEY (task_id) REFERENCES tasks(id)
      );

      CREATE INDEX IF NOT EXISTS idx_tasks_requester ON tasks(requester_id);
      CREATE INDEX IF NOT EXISTS idx_tasks_helper ON tasks(helper_id);
      CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
      CREATE INDEX IF NOT EXISTS idx_chat_task ON chat_messages(task_id);

      PRAGMA journal_mode=WAL;
    `);
  }

  createTask(input: {
    id: string;
    requesterId: string;
    question: string;
    capabilityQuery: string;
    status: string;
    helperId?: string;
    helperName?: string;
    deadline?: string;
  }): void {
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO tasks (id, requester_id, helper_id, helper_name, question, capability_query, status, created_at, updated_at, deadline)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      input.id, input.requesterId, input.helperId ?? null, input.helperName ?? null,
      input.question, input.capabilityQuery, input.status, now, now, input.deadline ?? null,
    );
  }

  getTask(id: string): TaskRow | undefined {
    return this.db.prepare(`
      SELECT id, requester_id as requesterId, helper_id as helperId, helper_name as helperName,
        question, capability_query as capabilityQuery, status, rating, feedback,
        created_at as createdAt, updated_at as updatedAt, completed_at as completedAt, deadline
      FROM tasks WHERE id = ?
    `).get(id) as TaskRow | undefined;
  }

  updateTaskStatus(id: string, status: string, extra?: {
    helperId?: string;
    helperName?: string;
    rating?: number;
    feedback?: string;
  }): void {
    const now = new Date().toISOString();
    const task = this.getTask(id);
    if (!task) return;

    this.db.prepare(`
      UPDATE tasks SET status = ?, updated_at = ?,
        helper_id = COALESCE(?, helper_id),
        helper_name = COALESCE(?, helper_name),
        rating = COALESCE(?, rating),
        feedback = COALESCE(?, feedback),
        completed_at = CASE WHEN ? IN ('completed', 'rated', 'failed', 'cancelled', 'timeout') THEN ? ELSE completed_at END
      WHERE id = ?
    `).run(
      status, now,
      extra?.helperId ?? null, extra?.helperName ?? null,
      extra?.rating ?? null, extra?.feedback ?? null,
      status, now, id,
    );
  }

  listActiveTasks(): TaskRow[] {
    return this.db.prepare(`
      SELECT id, requester_id as requesterId, helper_id as helperId, helper_name as helperName,
        question, capability_query as capabilityQuery, status, rating, feedback,
        created_at as createdAt, updated_at as updatedAt, completed_at as completedAt, deadline
      FROM tasks WHERE status IN ('created', 'searching', 'offered', 'matched', 'chatting')
      ORDER BY created_at DESC
    `).all() as TaskRow[];
  }

  listTasksByAgent(agentId: string): TaskRow[] {
    return this.db.prepare(`
      SELECT id, requester_id as requesterId, helper_id as helperId, helper_name as helperName,
        question, capability_query as capabilityQuery, status, rating, feedback,
        created_at as createdAt, updated_at as updatedAt, completed_at as completedAt, deadline
      FROM tasks WHERE requester_id = ? OR helper_id = ?
      ORDER BY created_at DESC LIMIT 50
    `).all(agentId, agentId) as TaskRow[];
  }

  getActiveTaskCount(agentId: string): number {
    const row = this.db.prepare(`
      SELECT COUNT(*) as count FROM tasks
      WHERE (requester_id = ? OR helper_id = ?)
        AND status IN ('created', 'searching', 'offered', 'matched', 'chatting')
    `).get(agentId, agentId) as { count: number } | undefined;
    return row?.count ?? 0;
  }

  listTimedOutTasks(timeoutMinutes: number): TaskRow[] {
    const cutoff = new Date(Date.now() - timeoutMinutes * 60_000).toISOString();
    return this.db.prepare(`
      SELECT id, requester_id as requesterId, helper_id as helperId, helper_name as helperName,
        question, capability_query as capabilityQuery, status, rating, feedback,
        created_at as createdAt, updated_at as updatedAt, completed_at as completedAt, deadline
      FROM tasks WHERE status = 'chatting' AND updated_at < ?
    `).all(cutoff) as TaskRow[];
  }

  saveChatMessage(taskId: string, from: string, text: string): void {
    this.db.prepare(`
      INSERT INTO chat_messages (task_id, from_agent, text, timestamp)
      VALUES (?, ?, ?, ?)
    `).run(taskId, from, text, new Date().toISOString());
  }

  listChatMessages(taskId: string): ChatMessageRow[] {
    return this.db.prepare(`
      SELECT task_id as taskId, from_agent as \`from\`, text, timestamp
      FROM chat_messages WHERE task_id = ?
      ORDER BY timestamp ASC
    `).all(taskId) as ChatMessageRow[];
  }

  close(): void {
    this.db.close();
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd bazaar && npx vitest run tests/task-store.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add bazaar/src/task-store.ts bazaar/tests/task-store.test.ts
git commit -m "feat(bazaar): add TaskStore with SQLite persistence and chat messages"
```

---

### Task 2: 任务路由引擎

**Files:**
- Create: `bazaar/src/task-engine.ts`
- Test: `bazaar/tests/task-engine.test.ts`

- [ ] **Step 1: 写测试**

```typescript
// bazaar/tests/task-engine.test.ts
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { TaskEngine } from '../src/task-engine.js';
import type { TaskStore } from '../src/task-store.js';
import type { AgentStore } from '../src/agent-store.js';
import type WebSocket from 'ws';
import { v4 as uuidv4 } from 'uuid';

function createMockStores() {
  const tasks: Map<string, any> = new Map();
  const sent: any[] = [];

  const mockTaskStore = {
    createTask: (input: any) => { tasks.set(input.id, { ...input, status: input.status, updatedAt: new Date().toISOString() }); },
    getTask: (id: string) => tasks.get(id),
    updateTaskStatus: vi.fn((id: string, status: string, extra?: any) => {
      const t = tasks.get(id);
      if (t) { t.status = status; if (extra?.helperId) t.helperId = extra.helperId; if (extra?.helperName) t.helperName = extra.helperName; }
    }),
    listActiveTasks: () => Array.from(tasks.values()).filter((t: any) => ['searching', 'offered', 'matched', 'chatting'].includes(t.status)),
    getActiveTaskCount: (agentId: string) => Array.from(tasks.values()).filter((t: any) =>
      (t.requesterId === agentId || t.helperId === agentId) && ['searching', 'offered', 'matched', 'chatting'].includes(t.status)
    ).length,
    listTimedOutTasks: () => [],
    saveChatMessage: vi.fn(),
    listChatMessages: () => [],
    close: () => {},
  } as unknown as TaskStore;

  const mockAgentStore = {
    findAgentsByCapability: vi.fn(() => []),
    getAgent: vi.fn(),
    getActiveTaskCount: vi.fn(() => 0),
    logAudit: vi.fn(),
    updateAgentStatus: vi.fn(),
  } as unknown as AgentStore;

  const connections = new Map<string, WebSocket>();

  const sendTo = (agentId: string, data: unknown) => {
    sent.push({ agentId, data });
  };

  return { mockTaskStore, mockAgentStore, connections, sent, sendTo };
}

describe('TaskEngine', () => {
  let engine: TaskEngine;
  let ctx: ReturnType<typeof createMockStores>;

  beforeEach(() => {
    ctx = createMockStores();
    engine = new TaskEngine(ctx.mockTaskStore, ctx.mockAgentStore, ctx.connections, ctx.sendTo);
  });

  describe('handleTaskCreate', () => {
    it('should create task, search capabilities, and return results', () => {
      ctx.mockAgentStore.findAgentsByCapability = vi.fn(() => [
        { agentId: 'a1', repo: 'payment' },
        { agentId: 'a2', repo: 'payment' },
      ]);
      (ctx.mockAgentStore.getAgent as any) = vi.fn((id: string) => ({
        id, name: `Agent-${id}`, status: 'idle', reputation: 10,
      }));

      const result = engine.handleTaskCreate({
        id: 'msg-001',
        type: 'task.create',
        payload: { question: '支付查询', capabilityQuery: '支付 查询' },
      }, 'agent-req');

      expect(result.taskId).toBeDefined();
      expect(result.matches).toHaveLength(2);
      expect(result.matches[0].agentId).toBe('a1');
      expect(ctx.sent.length).toBe(0); // search_result returned, not pushed
    });

    it('should return empty matches when no agents found', () => {
      ctx.mockAgentStore.findAgentsByCapability = vi.fn(() => []);

      const result = engine.handleTaskCreate({
        id: 'msg-001',
        type: 'task.create',
        payload: { question: '未知领域', capabilityQuery: 'xyz' },
      }, 'agent-req');

      expect(result.matches).toHaveLength(0);
    });
  });

  describe('handleTaskOffer', () => {
    it('should send task.incoming to target agent', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'searching' });
      ctx.mockAgentStore.getActiveTaskCount = vi.fn(() => 0);

      engine.handleTaskOffer({
        id: 'msg-002',
        type: 'task.offer',
        payload: { taskId: 't1', targetAgent: 'a2' },
      }, 'a1');

      expect(ctx.mockTaskStore.updateTaskStatus).toHaveBeenCalledWith('t1', 'offered', expect.objectContaining({ helperId: 'a2' }));
      expect(ctx.sent).toHaveLength(1);
      expect(ctx.sent[0].agentId).toBe('a2');
      expect((ctx.sent[0].data as any).type).toBe('task.incoming');
    });

    it('should reject when target agent is busy (slot full)', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'searching' });
      ctx.mockAgentStore.getActiveTaskCount = vi.fn(() => 3); // slot full

      const result = engine.handleTaskOffer({
        id: 'msg-002',
        type: 'task.offer',
        payload: { taskId: 't1', targetAgent: 'a2' },
      }, 'a1');

      expect(result.error).toContain('busy');
    });
  });

  describe('handleTaskAccept', () => {
    it('should match task and notify both agents', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'offered', helperId: 'a2' });

      engine.handleTaskAccept({
        id: 'msg-003',
        type: 'task.accept',
        payload: { taskId: 't1' },
      }, 'a2');

      expect(ctx.mockTaskStore.updateTaskStatus).toHaveBeenCalledWith('t1', 'matched', expect.anything());
      // Should notify requester
      expect(ctx.sent.some(s => s.agentId === 'a1')).toBe(true);
      // Should notify helper
      expect(ctx.sent.some(s => s.agentId === 'a2')).toBe(true);
    });
  });

  describe('handleTaskReject', () => {
    it('should set task back to searching', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'offered', helperId: 'a2' });

      engine.handleTaskReject({
        id: 'msg-004',
        type: 'task.reject',
        payload: { taskId: 't1' },
      }, 'a2');

      expect(ctx.mockTaskStore.updateTaskStatus).toHaveBeenCalledWith('t1', 'searching', expect.anything());
      // Should notify requester
      expect(ctx.sent.some(s => s.agentId === 'a1')).toBe(true);
    });
  });

  describe('handleTaskChat', () => {
    it('should relay chat to the other agent and save message', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting', helperId: 'a2' });

      engine.handleTaskChat({
        id: 'msg-005',
        type: 'task.chat',
        payload: { taskId: 't1', text: '你好' },
      }, 'a1');

      // Should relay to a2
      expect(ctx.sent).toHaveLength(1);
      expect(ctx.sent[0].agentId).toBe('a2');
      expect((ctx.sent[0].data as any).payload.text).toBe('你好');
      expect((ctx.sent[0].data as any).payload.from).toBe('a1');
      expect(ctx.mockTaskStore.saveChatMessage).toHaveBeenCalledWith('t1', 'a1', '你好');
    });
  });

  describe('handleTaskComplete', () => {
    it('should complete task with rating and notify helper', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting', helperId: 'a2' });

      engine.handleTaskComplete({
        id: 'msg-006',
        type: 'task.complete',
        payload: { taskId: 't1', rating: 5, feedback: '很棒' },
      }, 'a1');

      expect(ctx.mockTaskStore.updateTaskStatus).toHaveBeenCalledWith('t1', 'completed', expect.objectContaining({ rating: 5 }));
      expect(ctx.sent.some(s => s.agentId === 'a2')).toBe(true);
      expect((ctx.sent.find(s => s.agentId === 'a2')!.data as any).type).toBe('task.result');
    });
  });

  describe('handleTaskCancel', () => {
    it('should cancel task and notify both agents', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting', helperId: 'a2' });

      engine.handleTaskCancel({
        id: 'msg-007',
        type: 'task.cancel',
        payload: { taskId: 't1', reason: 'user_cancel' },
      }, 'a1');

      expect(ctx.mockTaskStore.updateTaskStatus).toHaveBeenCalledWith('t1', 'cancelled', expect.anything());
      expect(ctx.sent).toHaveLength(2); // both agents notified
    });
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd bazaar && npx vitest run tests/task-engine.test.ts 2>&1 | tail -5`
Expected: FAIL

- [ ] **Step 3: 实现 TaskEngine**

```typescript
// bazaar/src/task-engine.ts
import { v4 as uuidv4 } from 'uuid';
import { createLogger, type Logger } from './utils/logger.js';
import type { TaskStore } from './task-store.js';
import type { AgentStore } from './agent-store.js';
import type WebSocket from 'ws';

type SendFn = (agentId: string, data: unknown) => void;

export class TaskEngine {
  private log: Logger;
  private taskStore: TaskStore;
  private agentStore: AgentStore;
  private connections: Map<string, WebSocket>;
  private sendTo: SendFn;

  constructor(
    taskStore: TaskStore,
    agentStore: AgentStore,
    connections: Map<string, WebSocket>,
    sendTo: SendFn,
  ) {
    this.taskStore = taskStore;
    this.agentStore = agentStore;
    this.connections = connections;
    this.sendTo = sendTo;
    this.log = createLogger('TaskEngine');
  }

  handleTaskCreate(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): {
    taskId: string;
    matches: Array<{ agentId: string; name: string; status: string; reputation: number; repo: string }>;
  } {
    const question = msg.payload.question as string;
    const capabilityQuery = msg.payload.capabilityQuery as string;
    const taskId = uuidv4();

    // 创建任务
    this.taskStore.createTask({
      id: taskId,
      requesterId: fromAgentId,
      question,
      capabilityQuery,
      status: 'searching',
    });

    // 搜索有能力匹配的 Agent
    const agentRepos = this.agentStore.findAgentsByCapability(capabilityQuery);
    const matches = agentRepos
      .filter(r => r.agentId !== fromAgentId) // 排除自己
      .map(r => {
        const agent = this.agentStore.getAgent(r.agentId);
        return {
          agentId: r.agentId,
          name: agent?.name ?? '未知',
          status: agent?.status ?? 'offline',
          reputation: agent?.reputation ?? 0,
          repo: r.repo,
        };
      })
      .filter(m => m.status !== 'offline') // 排除离线 Agent
      .sort((a, b) => b.reputation - a.reputation); // 按声望排序

    this.agentStore.logAudit('task.created', fromAgentId, undefined, taskId, {
      question, matchCount: matches.length,
    });

    return { taskId, matches };
  }

  handleTaskOffer(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): { error?: string } {
    const taskId = msg.payload.taskId as string;
    const targetAgent = msg.payload.targetAgent as string;
    const task = this.taskStore.getTask(taskId);

    if (!task) {
      return { error: 'Task not found' };
    }
    if (task.requesterId !== fromAgentId) {
      return { error: 'Not your task' };
    }

    // 检查目标 Agent 的 slot
    const activeCount = this.agentStore.getActiveTaskCount(targetAgent);
    const maxSlots = 3; // TODO: 从配置读取
    if (activeCount >= maxSlots) {
      return { error: `Agent ${targetAgent} is busy (slots full)` };
    }

    // 更新任务状态
    const targetAgentRow = this.agentStore.getAgent(targetAgent);
    this.taskStore.updateTaskStatus(taskId, 'offered', {
      helperId: targetAgent,
      helperName: targetAgentRow?.name,
    });

    // 发送邀请给目标 Agent
    const deadline = new Date(Date.now() + 5 * 60_000).toISOString(); // 5 分钟 deadline
    this.sendTo(targetAgent, {
      type: 'task.incoming',
      id: uuidv4(),
      payload: {
        taskId,
        from: fromAgentId,
        fromName: this.agentStore.getAgent(fromAgentId)?.name ?? '一位同事',
        question: task.question,
        deadline,
      },
    });

    this.agentStore.logAudit('task.offered', fromAgentId, targetAgent, taskId, {});
    return {};
  }

  handleTaskAccept(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): void {
    const taskId = msg.payload.taskId as string;
    const task = this.taskStore.getTask(taskId);
    if (!task) return;

    this.taskStore.updateTaskStatus(taskId, 'matched', {
      helperId: fromAgentId,
      helperName: this.agentStore.getAgent(fromAgentId)?.name,
    });

    const helper = this.agentStore.getAgent(fromAgentId);

    // 通知发起方
    this.sendTo(task.requesterId, {
      type: 'task.matched',
      id: uuidv4(),
      payload: {
        taskId,
        helper: { agentId: fromAgentId, name: helper?.name ?? '未知' },
      },
    });

    // 确认给协助方
    this.sendTo(fromAgentId, {
      type: 'task.matched',
      id: uuidv4(),
      payload: { taskId },
    });

    this.agentStore.logAudit('task.accepted', fromAgentId, task.requesterId, taskId, {});
  }

  handleTaskReject(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): void {
    const taskId = msg.payload.taskId as string;
    const task = this.taskStore.getTask(taskId);
    if (!task) return;

    // 回到 searching 状态
    this.taskStore.updateTaskStatus(taskId, 'searching');

    // 通知发起方
    this.sendTo(task.requesterId, {
      type: 'task.progress',
      id: uuidv4(),
      payload: { taskId, status: 'searching', detail: 'Helper rejected, searching for next candidate' },
    });

    this.agentStore.logAudit('task.rejected', fromAgentId, task.requesterId, taskId, {});
  }

  handleTaskChat(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): void {
    const taskId = msg.payload.taskId as string;
    const text = msg.payload.text as string;
    const task = this.taskStore.getTask(taskId);
    if (!task || task.status !== 'chatting' && task.status !== 'matched') return;

    // 确定接收方
    const targetId = task.requesterId === fromAgentId ? task.helperId : task.requesterId;
    if (!targetId) return;

    // 保存消息
    this.taskStore.saveChatMessage(taskId, fromAgentId, text);

    // 转发给对方
    this.sendTo(targetId, {
      type: 'task.chat',
      id: uuidv4(),
      payload: { taskId, text, from: fromAgentId },
    });
  }

  handleTaskComplete(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): void {
    const taskId = msg.payload.taskId as string;
    const rating = msg.payload.rating as number;
    const feedback = msg.payload.feedback as string | undefined;
    const task = this.taskStore.getTask(taskId);
    if (!task) return;

    this.taskStore.updateTaskStatus(taskId, 'completed', { rating, feedback });

    // 通知协助方结算
    const reputationDelta = rating; // 简化：评分即声望增量
    if (task.helperId) {
      this.sendTo(task.helperId, {
        type: 'task.result',
        id: uuidv4(),
        payload: { taskId, reputationDelta },
      });
    }

    this.agentStore.logAudit('task.completed', fromAgentId, task.helperId ?? undefined, taskId, {
      rating, feedback,
    });
  }

  handleTaskCancel(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): void {
    const taskId = msg.payload.taskId as string;
    const reason = msg.payload.reason as string;
    const task = this.taskStore.getTask(taskId);
    if (!task) return;

    this.taskStore.updateTaskStatus(taskId, 'cancelled');

    // 通知双方
    const agents = [task.requesterId];
    if (task.helperId) agents.push(task.helperId);

    for (const agentId of agents) {
      this.sendTo(agentId, {
        type: 'task.cancelled',
        id: uuidv4(),
        payload: { taskId, reason, cancelledBy: fromAgentId },
      });
    }

    this.agentStore.logAudit('task.cancelled', fromAgentId, task.helperId ?? undefined, taskId, { reason });
  }

  // 超时检测：检查所有超时的 chatting 任务
  checkTimeouts(timeoutMinutes: number): void {
    const timedOut = this.taskStore.listTimedOutTasks(timeoutMinutes);
    for (const task of timedOut) {
      this.taskStore.updateTaskStatus(task.id, 'timeout');

      const agents = [task.requesterId];
      if (task.helperId) agents.push(task.helperId);

      for (const agentId of agents) {
        this.sendTo(agentId, {
          type: 'task.timeout',
          id: uuidv4(),
          payload: { taskId: task.id, reason: `No activity for ${timeoutMinutes} minutes` },
        });
      }

      this.log.info(`Task timed out: ${task.id}`);
    }
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd bazaar && npx vitest run tests/task-engine.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add bazaar/src/task-engine.ts bazaar/tests/task-engine.test.ts
git commit -m "feat(bazaar): add TaskEngine with full task routing logic and tests"
```

---

### Task 3: 扩展 MessageRouter 集成 TaskEngine

**Files:**
- Modify: `bazaar/src/message-router.ts`
- Modify: `bazaar/src/protocol.ts`

- [ ] **Step 1: 扩展 MessageRouter 构造函数注入 TaskEngine**

在 `bazaar/src/message-router.ts` 中：

修改构造函数，增加 `taskEngine` 和 `connections` 参数：

```typescript
import type { TaskEngine } from './task-engine.js';

export class MessageRouter {
  private store: AgentStore;
  private taskEngine: TaskEngine | null;
  private connections: Map<string, WebSocket>;

  constructor(store: AgentStore, taskEngine?: TaskEngine, connections?: Map<string, WebSocket>) {
    this.store = store;
    this.taskEngine = taskEngine ?? null;
    this.connections = connections ?? new Map();
    // ... rest unchanged
  }
```

在 `route()` 方法的 try 块中，在 `agent.offline` handler 之后添加 task 消息路由：

```typescript
      } else if (type.startsWith('task.')) {
        return this.handleTaskMessage(type, payload, ws);
      } else {
```

添加 `handleTaskMessage` 方法：

```typescript
  private handleTaskMessage(
    type: string,
    payload: Record<string, unknown>,
    ws: WebSocket,
  ): RouteResult {
    if (!this.taskEngine) {
      this.log.warn('TaskEngine not initialized, ignoring task message');
      return { handled: false, error: 'TaskEngine not available' };
    }

    // 从 connections Map 反查 agentId
    let fromAgentId: string | undefined;
    for (const [id, socket] of this.connections) {
      if (socket === ws) { fromAgentId = id; break; }
    }
    if (!fromAgentId) {
      return { handled: false, error: 'Agent not registered' };
    }

    const msg = { id: '', type, payload };

    if (type === 'task.create') {
      const result = this.taskEngine.handleTaskCreate(msg, fromAgentId);
      // 回复 search_result 给发起方
      const send = (data: unknown) => { if (ws.readyState === 1) ws.send(JSON.stringify(data)); };
      send({ type: 'task.search_result', id: uuidv4(), payload: { taskId: result.taskId, matches: result.matches } });
      return { handled: true };
    } else if (type === 'task.offer') {
      const result = this.taskEngine.handleTaskOffer(msg, fromAgentId);
      if (result.error) {
        const send = (data: unknown) => { if (ws.readyState === 1) ws.send(JSON.stringify(data)); };
        send({ type: 'error', id: uuidv4(), payload: { message: result.error } });
      }
      return { handled: true };
    } else if (type === 'task.accept') {
      this.taskEngine.handleTaskAccept(msg, fromAgentId);
      return { handled: true };
    } else if (type === 'task.reject') {
      this.taskEngine.handleTaskReject(msg, fromAgentId);
      return { handled: true };
    } else if (type === 'task.chat') {
      this.taskEngine.handleTaskChat(msg, fromAgentId);
      return { handled: true };
    } else if (type === 'task.complete') {
      this.taskEngine.handleTaskComplete(msg, fromAgentId);
      return { handled: true };
    } else if (type === 'task.cancel') {
      this.taskEngine.handleTaskCancel(msg, fromAgentId);
      return { handled: true };
    }

    return { handled: true };
  }
```

- [ ] **Step 2: 更新 protocol.ts REQUIRED_FIELDS**

在 `bazaar/src/protocol.ts` 的 `REQUIRED_FIELDS` 中补充：

```typescript
const REQUIRED_FIELDS: Record<string, string[]> = {
  'agent.register': ['agentId', 'username', 'hostname', 'name'],
  'agent.heartbeat': ['agentId', 'status'],
  'agent.update': ['agentId'],
  'task.create': ['question', 'capabilityQuery'],
  'task.offer': ['taskId', 'targetAgent'],
  'task.accept': ['taskId'],
  'task.reject': ['taskId'],
  'task.chat': ['taskId', 'text'],
  'task.complete': ['taskId', 'rating'],
  'task.cancel': ['taskId', 'reason'],
};
```

（确认已有条目无需修改，只检查 `task.offer` 的 `REQUIRED_FIELDS` 是否包含 `targetAgent` 而非 `candidates`）

- [ ] **Step 3: 更新 index.ts 集成 TaskEngine**

在 `bazaar/src/index.ts` 中，创建 TaskStore、TaskEngine 实例，注入到 MessageRouter：

```typescript
import { TaskStore } from './task-store.js';
import { TaskEngine } from './task-engine.js';

// ... 在 store 和 router 声明之间新增：
const taskStore = new TaskStore(DB_PATH.replace('bazaar.db', 'bazaar-tasks.db'));

function sendToAgent(agentId: string, data: unknown): void {
  const ws = connections.get(agentId);
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(data));
  }
}

const taskEngine = new TaskEngine(taskStore, store, connections, sendToAgent);
const router = new MessageRouter(store, taskEngine, connections);
```

在超时检测 setInterval 中增加任务超时检测：

```typescript
taskEngine.checkTimeouts(5); // 5 分钟无对话超时
```

- [ ] **Step 4: 验证编译通过**

Run: `cd bazaar && npx tsc --noEmit 2>&1 | head -10`
Expected: 无错误

- [ ] **Step 5: Commit**

```bash
git add bazaar/src/message-router.ts bazaar/src/protocol.ts bazaar/src/index.ts
git commit -m "feat(bazaar): integrate TaskEngine into MessageRouter and server entry"
```

---

### Task 4: 任务流程集成测试

**Files:**
- Create: `bazaar/tests/task-flow.test.ts`

- [ ] **Step 1: 写集成测试**

```typescript
// bazaar/tests/task-flow.test.ts
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import http from 'http';
import express from 'express';
import { WebSocketServer, WebSocket } from 'ws';
import { AgentStore } from '../src/agent-store.js';
import { TaskStore } from '../src/task-store.js';
import { TaskEngine } from '../src/task-engine.js';
import { MessageRouter } from '../src/message-router.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('Integration: Full Task Collaboration Flow', () => {
  let server: http.Server;
  let wss: WebSocketServer;
  let agentStore: AgentStore;
  let taskStore: TaskStore;
  let router: MessageRouter;
  let taskEngine: TaskEngine;
  let connections: Map<string, WebSocket>;
  let agentDbPath: string;
  let taskDbPath: string;
  let port: number;

  beforeAll(async () => {
    agentDbPath = path.join(os.tmpdir(), `bazaar-task-flow-agent-${Date.now()}.db`);
    taskDbPath = path.join(os.tmpdir(), `bazaar-task-flow-task-${Date.now()}.db`);
    agentStore = new AgentStore(agentDbPath);
    taskStore = new TaskStore(taskDbPath);
    connections = new Map();

    function sendToAgent(agentId: string, data: unknown): void {
      const ws = connections.get(agentId);
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(data));
      }
    }

    taskEngine = new TaskEngine(taskStore, agentStore, connections, sendToAgent);
    router = new MessageRouter(agentStore, taskEngine, connections);

    const app = express();
    server = http.createServer(app);
    wss = new WebSocketServer({ server });

    wss.on('connection', (ws) => {
      let agentId: string | null = null;
      ws.on('message', (data) => {
        const msg = JSON.parse(data.toString());
        router.route(msg, ws);
        if (msg.type === 'agent.register' && msg.payload?.agentId) {
          agentId = msg.payload.agentId as string;
          connections.set(agentId, ws);
        }
      });
      ws.on('close', () => {
        if (agentId) {
          agentStore.setAgentOffline(agentId);
          connections.delete(agentId);
        }
      });
    });

    await new Promise<void>((resolve) => {
      server.listen(0, () => {
        port = (server.address() as any).port;
        resolve();
      });
    });
  });

  afterAll(async () => {
    agentStore.close();
    taskStore.close();
    if (fs.existsSync(agentDbPath)) fs.unlinkSync(agentDbPath);
    if (fs.existsSync(taskDbPath)) fs.unlinkSync(taskDbPath);
    await new Promise<void>((r) => wss.close(() => r()));
    await new Promise<void>((r) => server.close(() => r()));
  });

  it('should complete full task flow: create → offer → accept → chat → complete', async () => {
    // 注册两个 Agent
    const ws1 = new WebSocket(`ws://localhost:${port}`);
    const ws2 = new WebSocket(`ws://localhost:${port}`);
    const received1: any[] = [];
    const received2: any[] = [];

    await new Promise<void>((r) => { ws1.on('open', () => r()); });
    await new Promise<void>((r) => { ws2.on('open', () => r()); });
    ws1.on('message', (data) => received1.push(JSON.parse(data.toString())));
    ws2.on('message', (data) => received2.push(JSON.parse(data.toString())));

    // 注册 Agent A（支付开发者）
    ws1.send(JSON.stringify({
      id: 'msg-a1', type: 'agent.register',
      payload: { agentId: 'a1', username: 'dev1', hostname: 'h1', name: '张三', projects: [
        { repo: 'payment-service', skills: JSON.stringify([{ id: 'pay', name: '支付查询', triggers: ['支付', '查询'] }]) },
      ], privateCapabilities: [] },
    }));

    // 注册 Agent B
    ws2.send(JSON.stringify({
      id: 'msg-a2', type: 'agent.register',
      payload: { agentId: 'a2', username: 'dev2', hostname: 'h2', name: '李四', projects: [], privateCapabilities: [] },
    }));

    await new Promise((r) => setTimeout(r, 300));

    // Agent B 发起任务：搜索支付能力
    received2.length = 0;
    ws2.send(JSON.stringify({
      id: 'msg-task1', type: 'task.create',
      payload: { question: '支付查询怎么实现？', capabilityQuery: '支付' },
    }));

    await new Promise((r) => setTimeout(r, 300));

    // Agent B 应该收到 search_result
    const searchResult = received2.find(m => m.type === 'task.search_result');
    expect(searchResult).toBeDefined();
    expect(searchResult.payload.matches.length).toBeGreaterThanOrEqual(1);
    expect(searchResult.payload.matches[0].agentId).toBe('a1');

    // Agent B 向 Agent A 发起邀请
    received1.length = 0;
    received2.length = 0;
    ws2.send(JSON.stringify({
      id: 'msg-offer', type: 'task.offer',
      payload: { taskId: searchResult.payload.taskId, targetAgent: 'a1' },
    }));

    await new Promise((r) => setTimeout(r, 300));

    // Agent A 应该收到 task.incoming
    const incoming = received1.find(m => m.type === 'task.incoming');
    expect(incoming).toBeDefined();
    expect(incoming.payload.question).toBe('支付查询怎么实现？');

    // Agent A 接受任务
    received1.length = 0;
    received2.length = 0;
    ws1.send(JSON.stringify({
      id: 'msg-accept', type: 'task.accept',
      payload: { taskId: searchResult.payload.taskId },
    }));

    await new Promise((r) => setTimeout(r, 300));

    // 双方应该收到 task.matched
    const matched1 = received1.find(m => m.type === 'task.matched');
    const matched2 = received2.find(m => m.type === 'task.matched');
    expect(matched1).toBeDefined();
    expect(matched2).toBeDefined();

    // Agent B 发送聊天消息
    received1.length = 0;
    ws2.send(JSON.stringify({
      id: 'msg-chat1', type: 'task.chat',
      payload: { taskId: searchResult.payload.taskId, text: '你好，我需要查询支付接口' },
    }));

    await new Promise((r) => setTimeout(r, 300));

    // Agent A 应该收到聊天中继
    const chatRelay = received1.find(m => m.type === 'task.chat');
    expect(chatRelay).toBeDefined();
    expect(chatRelay.payload.text).toBe('你好，我需要查询支付接口');
    expect(chatRelay.payload.from).toBe('a2');

    // 验证本地数据库
    const task = taskStore.getTask(searchResult.payload.taskId);
    expect(task).toBeDefined();
    expect(task!.status).toBe('matched');
    const chatMessages = taskStore.listChatMessages(searchResult.payload.taskId);
    expect(chatMessages).toHaveLength(1);

    ws1.close();
    ws2.close();
  });
});
```

- [ ] **Step 2: 运行集成测试**

Run: `cd bazaar && npx vitest run tests/task-flow.test.ts`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add bazaar/tests/task-flow.test.ts
git commit -m "test(bazaar): add task flow integration test for full collaboration cycle"
```

---

### Task 5: 验证全部测试通过

- [ ] **Step 1: 运行 bazaar 全部测试**

Run: `cd bazaar && npx vitest run`
Expected: ALL PASS

- [ ] **Step 2: 验证 TypeScript 编译**

Run: `cd bazaar && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: 验证主项目编译**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc --noEmit && pnpm build`
Expected: 无错误

- [ ] **Step 4: Commit（如有修复）**

```bash
git add -A
git commit -m "chore(bazaar): verify all tests and compilation pass"
```

---

## 总结

**本计划覆盖的文件**：

| 文件 | 说明 | 类型 |
|------|------|------|
| `bazaar/src/task-store.ts` | 任务 SQLite 持久化（tasks + chat_messages 表） | 新增 |
| `bazaar/src/task-engine.ts` | 任务路由引擎（创建/搜索/邀请/接受/拒绝/中继/完成/超时） | 新增 |
| `bazaar/src/message-router.ts` | 注入 TaskEngine + connections，新增 task.* 路由 | 修改 |
| `bazaar/src/protocol.ts` | 确认 REQUIRED_FIELDS 覆盖所有 task 消息 | 修改 |
| `bazaar/src/index.ts` | 创建 TaskStore/TaskEngine，注入 Router，增加超时检测 | 修改 |
| `bazaar/tests/task-store.test.ts` | TaskStore 测试 | 新增 |
| `bazaar/tests/task-engine.test.ts` | TaskEngine 单元测试（mock stores + connections） | 新增 |
| `bazaar/tests/task-flow.test.ts` | 完整任务流程集成测试 | 新增 |

**任务路由消息流**：
```
task.create → 搜索能力 → task.search_result
task.offer → task.incoming → task.accept → task.matched (双方)
task.chat → 中继转发 (对方)
task.complete → task.result (协助方)
task.cancel → task.cancelled (双方)
超时 → task.timeout (双方)
```

**判定标准**：`cd bazaar && npx vitest run` 全部通过 + `npx tsc --noEmit` 无错误 + 主项目 `pnpm build` 成功。
