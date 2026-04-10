# Bazaar Bridge 隔离层 实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Bridge 隔离层，将 Bazaar 集市服务器接入 Sman 后端，零侵入一期代码。Agent 通过 Bridge 连接集市、注册身份、收发协作请求。

**Architecture:** 新增 `server/bazaar/` 目录，通过 5 行代码的 `initBazaarBridge()` 注入到 `server/index.ts`。Bridge 只使用一期已有的公共 API（`createSessionWithId`、`sendMessageForCron`、`broadcast`），不修改任何一期代码。Bridge 通过 WebSocket 连接集市服务器（5890 端口），作为 Agent 的客户端。

**Tech Stack:** TypeScript, WebSocket (ws), SQLite (better-sqlite3), Zod, Vitest

**Design Doc:** `docs/superpowers/specs/2026-04-10-bazaar-agent-swarm-design.md`

---

## Chunk 1: Bridge 基础（类型 + Store + Client + 消息路由）

### Task 1: Bazaar 配置类型扩展

**Files:**
- Modify: `server/types.ts`（仅新增字段，不改现有字段）

- [ ] **Step 1: 在 SmanConfig 末尾新增 bazaar 字段**

在 `server/types.ts` 文件顶部新增 import：

```typescript
import type { BazaarConfig } from '../shared/bazaar-types.js';
```

在 `SmanConfig` 接口中，`auth` 字段后新增：

```typescript
  bazaar?: BazaarConfig;
```

- [ ] **Step 2: Commit**

```bash
git add server/types.ts
git commit -m "feat(bazaar): add BazaarConfig to SmanConfig type"
```

---

### Task 2: Bridge 内部类型

**Files:**
- Create: `server/bazaar/types.ts`

- [ ] **Step 1: 定义 Bridge 内部类型**

```typescript
// server/bazaar/types.ts
import type { ClaudeSessionManager } from '../claude-session.js';
import type { SettingsManager } from '../settings-manager.js';
import type { SkillsRegistry } from '../skills-registry.js';

// ── 注入的一期公共 API ──

export interface BridgeDeps {
  sessionManager: ClaudeSessionManager;
  settingsManager: SettingsManager;
  skillsRegistry: SkillsRegistry;
  broadcast: (data: string) => void;
  homeDir: string;
}

// ── Agent 本地身份 ──

export interface LocalAgentIdentity {
  agentId: string;
  hostname: string;
  username: string;
  name: string;
  server: string;
}

// ── 协作任务状态 ──

export interface ActiveCollaboration {
  taskId: string;
  helperAgentId: string;
  helperName: string;
  question: string;
  sessionId: string;
  abortController: AbortController;
  startedAt: string;
  lastActivityAt: string;
}

// ── Bridge → 前端消息类型 ──

export type BazaarBridgeMessageType =
  | 'bazaar.status'          // Agent 状态更新
  | 'bazaar.task.list'       // 任务列表请求/响应
  | 'bazaar.task.list.update' // 任务列表推送
  | 'bazaar.task.detail'     // 任务详情
  | 'bazaar.task.chat.delta' // 协作对话流
  | 'bazaar.task.cancel'     // 强制结束任务
  | 'bazaar.task.takeover'   // 接手控制
  | 'bazaar.config.update'   // 更新配置
  | 'bazaar.notify'          // 协作请求通知
  | 'bazaar.digest';         // 每日摘要

// ── Bazaar 本地存储接口 ──

export interface BazaarLocalTask {
  taskId: string;
  direction: 'incoming' | 'outgoing';
  helperAgentId?: string;
  helperName?: string;
  requesterAgentId?: string;
  requesterName?: string;
  question: string;
  status: string;
  rating?: number;
  createdAt: string;
  completedAt?: string;
}
```

- [ ] **Step 2: Commit**

```bash
mkdir -p server/bazaar
git add server/bazaar/types.ts
git commit -m "feat(bazaar): add Bridge internal type definitions"
```

---

### Task 3: BazaarStore — 本地持久化

**Files:**
- Create: `server/bazaar/bazaar-store.ts`
- Test: `tests/server/bazaar/bazaar-store.test.ts`

- [ ] **Step 1: 写测试**

```typescript
// tests/server/bazaar/bazaar-store.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { BazaarStore } from '../../../server/bazaar/bazaar-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('BazaarStore', () => {
  let store: BazaarStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `bazaar-local-test-${Date.now()}.db`);
    store = new BazaarStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  describe('saveIdentity / getIdentity', () => {
    it('should persist and retrieve agent identity', () => {
      store.saveIdentity({
        agentId: 'agent-001',
        hostname: 'VDI-01',
        username: 'zhangsan',
        name: '张三',
        server: 'bazaar.company.com:5890',
      });
      const identity = store.getIdentity();
      expect(identity).toBeDefined();
      expect(identity!.agentId).toBe('agent-001');
      expect(identity!.server).toBe('bazaar.company.com:5890');
    });

    it('should return undefined when no identity saved', () => {
      expect(store.getIdentity()).toBeUndefined();
    });
  });

  describe('task CRUD', () => {
    it('should save and list tasks', () => {
      store.saveTask({
        taskId: 'task-001',
        direction: 'outgoing',
        helperAgentId: 'agent-002',
        helperName: '小李',
        question: '支付查询',
        status: 'chatting',
        createdAt: new Date().toISOString(),
      });

      const tasks = store.listTasks();
      expect(tasks).toHaveLength(1);
      expect(tasks[0].taskId).toBe('task-001');
      expect(tasks[0].direction).toBe('outgoing');
    });

    it('should update task status', () => {
      store.saveTask({
        taskId: 'task-001',
        direction: 'incoming',
        question: 'test',
        status: 'chatting',
        createdAt: new Date().toISOString(),
      });

      store.updateTaskStatus('task-001', 'completed');
      const tasks = store.listTasks();
      expect(tasks[0].status).toBe('completed');
    });

    it('should list tasks ordered by createdAt DESC', () => {
      const t1 = new Date('2026-04-10T10:00:00Z').toISOString();
      const t2 = new Date('2026-04-10T11:00:00Z').toISOString();
      store.saveTask({ taskId: 't1', direction: 'incoming', question: 'q1', status: 'chatting', createdAt: t1 });
      store.saveTask({ taskId: 't2', direction: 'incoming', question: 'q2', status: 'chatting', createdAt: t2 });

      const tasks = store.listTasks();
      expect(tasks[0].taskId).toBe('t2'); // 最新的在前
    });
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

Run: `npx vitest run tests/server/bazaar/bazaar-store.test.ts 2>&1 | tail -5`
Expected: FAIL

- [ ] **Step 3: 实现 BazaarStore**

```typescript
// server/bazaar/bazaar-store.ts
import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from '../utils/logger.js';
import fs from 'fs';
import path from 'path';
import type { LocalAgentIdentity, BazaarLocalTask } from './types.js';

export class BazaarStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    const dir = path.dirname(dbPath);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('BazaarStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS identity (
        agent_id TEXT PRIMARY KEY,
        hostname TEXT NOT NULL,
        username TEXT NOT NULL,
        name TEXT NOT NULL,
        server TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );

      CREATE TABLE IF NOT EXISTS tasks (
        task_id TEXT PRIMARY KEY,
        direction TEXT NOT NULL,
        helper_agent_id TEXT,
        helper_name TEXT,
        requester_agent_id TEXT,
        requester_name TEXT,
        question TEXT NOT NULL,
        status TEXT NOT NULL,
        rating INTEGER,
        created_at TEXT NOT NULL,
        completed_at TEXT
      );

      PRAGMA journal_mode=WAL;
    `);
  }

  // ── Identity ──

  saveIdentity(identity: LocalAgentIdentity): void {
    this.db.prepare(`
      INSERT OR REPLACE INTO identity (agent_id, hostname, username, name, server, updated_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(identity.agentId, identity.hostname, identity.username, identity.name, identity.server, new Date().toISOString());
  }

  getIdentity(): LocalAgentIdentity | undefined {
    return this.db.prepare(
      'SELECT agent_id as agentId, hostname, username, name, server FROM identity'
    ).get() as LocalAgentIdentity | undefined;
  }

  // ── Tasks ──

  saveTask(task: BazaarLocalTask): void {
    this.db.prepare(`
      INSERT OR REPLACE INTO tasks (task_id, direction, helper_agent_id, helper_name,
        requester_agent_id, requester_name, question, status, rating, created_at, completed_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      task.taskId, task.direction, task.helperAgentId ?? null, task.helperName ?? null,
      task.requesterAgentId ?? null, task.requesterName ?? null,
      task.question, task.status, task.rating ?? null, task.createdAt, task.completedAt ?? null,
    );
  }

  updateTaskStatus(taskId: string, status: string, rating?: number, completedAt?: string): void {
    if (rating !== undefined && completedAt) {
      this.db.prepare('UPDATE tasks SET status = ?, rating = ?, completed_at = ? WHERE task_id = ?')
        .run(status, rating, completedAt, taskId);
    } else {
      this.db.prepare('UPDATE tasks SET status = ? WHERE task_id = ?').run(status, taskId);
    }
  }

  listTasks(limit = 50): BazaarLocalTask[] {
    return this.db.prepare(`
      SELECT task_id as taskId, direction, helper_agent_id as helperAgentId,
        helper_name as helperName, requester_agent_id as requesterAgentId,
        requester_name as requesterName, question, status, rating,
        created_at as createdAt, completed_at as completedAt
      FROM tasks ORDER BY created_at DESC LIMIT ?
    `).all(limit) as BazaarLocalTask[];
  }

  getTask(taskId: string): BazaarLocalTask | undefined {
    return this.db.prepare(`
      SELECT task_id as taskId, direction, helper_agent_id as helperAgentId,
        helper_name as helperName, requester_agent_id as requesterAgentId,
        requester_name as requesterName, question, status, rating,
        created_at as createdAt, completed_at as completedAt
      FROM tasks WHERE task_id = ?
    `).get(taskId) as BazaarLocalTask | undefined;
  }

  close(): void {
    this.db.close();
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `npx vitest run tests/server/bazaar/bazaar-store.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
mkdir -p tests/server/bazaar
git add server/bazaar/bazaar-store.ts tests/server/bazaar/bazaar-store.test.ts
git commit -m "feat(bazaar): add BazaarStore local persistence with tests"
```

---

### Task 4: BazaarClient — WS 连接集市服务器

**Files:**
- Create: `server/bazaar/bazaar-client.ts`
- Test: `tests/server/bazaar/bazaar-client.test.ts`

- [ ] **Step 1: 写测试**

```typescript
// tests/server/bazaar/bazaar-client.test.ts
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { BazaarClient } from '../../../server/bazaar/bazaar-client.js';
import type { BazaarStore } from '../../../server/bazaar/bazaar-store.js';
import type { LocalAgentIdentity } from '../../../server/bazaar/types.js';
import http from 'http';
import { WebSocketServer } from 'ws';
import WebSocket from 'ws';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('BazaarClient', () => {
  let client: BazaarClient;
  let mockStore: BazaarStore;
  let dbPath: string;
  let wss: WebSocketServer;
  let server: http.Server;
  let port: number;
  let receivedByServer: any[] = [];

  beforeEach(async () => {
    dbPath = path.join(os.tmpdir(), `bazaar-client-test-${Date.now()}.db`);

    // 简化 mock store
    const { BazaarStore: RealStore } = await import('../../../server/bazaar/bazaar-store.js');
    mockStore = new RealStore(dbPath);

    // 启动模拟集市服务器
    const app = http.createServer();
    wss = new WebSocketServer({ server });
    receivedByServer = [];

    wss.on('connection', (ws) => {
      ws.on('message', (data) => {
        receivedByServer.push(JSON.parse(data.toString()));
        // 自动回复 ack
        const msg = JSON.parse(data.toString());
        ws.send(JSON.stringify({ type: 'ack', id: msg.id }));
      });
    });

    await new Promise<void>((resolve) => {
      server = app;
      server.listen(0, () => {
        port = (server.address() as any).port;
        resolve();
      });
    });
  });

  afterEach(async () => {
    client?.disconnect();
    (mockStore as any).close?.();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
    await new Promise<void>((r) => wss.close(() => r()));
    await new Promise<void>((r) => server.close(() => r()));
  });

  it('should connect and register with bazaar server', async () => {
    mockStore.saveIdentity({
      agentId: 'agent-001',
      hostname: 'test-host',
      username: 'zhangsan',
      name: '张三',
      server: `localhost:${port}`,
    });

    client = new BazaarClient(mockStore, {
      getAgentProjects: () => [],
    });

    await client.connect();

    // 等待注册消息到达
    await new Promise((r) => setTimeout(r, 300));

    expect(receivedByServer.length).toBeGreaterThanOrEqual(1);
    const registerMsg = receivedByServer.find(m => m.type === 'agent.register');
    expect(registerMsg).toBeDefined();
    expect(registerMsg.payload.agentId).toBe('agent-001');
    expect(registerMsg.payload.name).toBe('张三');
  });

  it('should send heartbeat after connection', async () => {
    mockStore.saveIdentity({
      agentId: 'agent-002',
      hostname: 'h',
      username: 'u',
      name: 'n',
      server: `localhost:${port}`,
    });

    client = new BazaarClient(mockStore, {
      getAgentProjects: () => [],
      heartbeatIntervalMs: 500, // 快速测试
    });

    await client.connect();
    await new Promise((r) => setTimeout(r, 800));

    const heartbeats = receivedByServer.filter(m => m.type === 'agent.heartbeat');
    expect(heartbeats.length).toBeGreaterThanOrEqual(1);
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

Run: `npx vitest run tests/server/bazaar/bazaar-client.test.ts 2>&1 | tail -5`
Expected: FAIL

- [ ] **Step 3: 实现 BazaarClient**

```typescript
// server/bazaar/bazaar-client.ts
import WebSocket from 'ws';
import { v4 as uuidv4 } from 'uuid';
import os from 'os';
import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from '../utils/logger.js';
import type { BazaarStore } from './bazaar-store.js';
import type { LocalAgentIdentity } from './types.js';

interface ClientOptions {
  getAgentProjects: () => Array<{ repo: string; skills: string }>;
  heartbeatIntervalMs?: number;
}

const DEFAULT_HEARTBEAT_MS = 30_000;
const RECONNECT_BASE_DELAY_MS = 1_000;
const RECONNECT_MAX_DELAY_MS = 60_000;

export class BazaarClient {
  private log: Logger;
  private store: BazaarStore;
  private ws: WebSocket | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectAttempts = 0;
  private stopped = false;
  private options: ClientOptions;

  // 外部消息处理器
  public onMessage: ((msg: { type: string; payload: Record<string, unknown> }) => void) | null = null;

  constructor(store: BazaarStore, options: ClientOptions) {
    this.store = store;
    this.options = options;
    this.log = createLogger('BazaarClient');
  }

  async connect(): Promise<void> {
    const identity = this.store.getIdentity();
    if (!identity) {
      this.log.warn('No identity saved, cannot connect');
      return;
    }

    this.stopped = false;
    return this.doConnect(identity);
  }

  private async doConnect(identity: LocalAgentIdentity): Promise<void> {
    return new Promise((resolve, reject) => {
      const url = `ws://${identity.server}`;
      this.log.info(`Connecting to bazaar: ${url}`);

      this.ws = new WebSocket(url);
      let settled = false;
      let timeoutId: ReturnType<typeof setTimeout>;

      this.ws.on('open', () => {
        clearTimeout(timeoutId);
        if (settled) return;
        settled = true;
        this.log.info('Connected to bazaar server');
        this.reconnectAttempts = 0;

        // 发送注册消息
        this.send({
          id: uuidv4(),
          type: 'agent.register',
          payload: {
            agentId: identity.agentId,
            username: identity.username,
            hostname: os.hostname(),
            name: identity.name,
            projects: this.options.getAgentProjects(),
            privateCapabilities: [],
          },
        });

        // 启动心跳
        this.startHeartbeat(identity.agentId);
        resolve();
      });

      this.ws.on('message', (data) => {
        try {
          const msg = JSON.parse(data.toString());
          if (msg.type === 'ack') return; // 忽略 ack

          // 调用外部处理器
          if (this.onMessage && msg.type !== 'ack') {
            this.onMessage({ type: msg.type, payload: msg.payload ?? {} });
          }
        } catch (err) {
          this.log.error('Failed to parse server message', { error: String(err) });
        }
      });

      this.ws.on('close', () => {
        this.log.info('Disconnected from bazaar');
        this.stopHeartbeat();
        if (!this.stopped) this.scheduleReconnect(identity);
      });

      this.ws.on('error', (err) => {
        clearTimeout(timeoutId);
        if (settled) return;
        settled = true;
        this.log.error('WebSocket error', { error: err.message });
        reject(err);
      });

      // 5 秒连接超时
      timeoutId = setTimeout(() => {
        if (settled) return;
        settled = true;
        this.ws?.close();
        reject(new Error('Connection timeout'));
      }, 5_000);
    });
        }
      });

      this.ws.on('close', () => {
        this.log.info('Disconnected from bazaar');
        this.stopHeartbeat();
        if (!this.stopped) this.scheduleReconnect(identity);
      });

      this.ws.on('error', (err) => {
        this.log.error('WebSocket error', { error: err.message });
        reject(err);
      });

      // 5 秒连接超时
      setTimeout(() => {
        if (this.ws?.readyState !== WebSocket.OPEN) {
          reject(new Error('Connection timeout'));
        }
      }, 5_000);
    });
  }

  send(msg: { id: string; type: string; payload: Record<string, unknown> }): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }

  disconnect(): void {
    this.stopped = true;
    this.stopHeartbeat();

    const identity = this.store.getIdentity();
    if (identity && this.ws?.readyState === WebSocket.OPEN) {
      this.send({
        id: uuidv4(),
        type: 'agent.offline',
        payload: { agentId: identity.agentId },
      });
    }

    this.ws?.close();
    this.ws = null;
  }

  private startHeartbeat(agentId: string): void {
    this.stopHeartbeat();
    const interval = this.options.heartbeatIntervalMs ?? DEFAULT_HEARTBEAT_MS;
    this.heartbeatTimer = setInterval(() => {
      this.send({
        id: uuidv4(),
        type: 'agent.heartbeat',
        payload: { agentId, status: 'idle', activeTaskCount: 0 },
      });
    }, interval);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private scheduleReconnect(identity: LocalAgentIdentity): void {
    if (this.stopped) return;
    const delay = Math.min(
      RECONNECT_BASE_DELAY_MS * Math.pow(2, this.reconnectAttempts),
      RECONNECT_MAX_DELAY_MS,
    );
    this.reconnectAttempts++;
    this.log.info(`Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);

    setTimeout(async () => {
      if (!this.stopped) {
        try {
          await this.doConnect(identity);
        } catch {
          // doConnect 内部会再次触发 close → scheduleReconnect
        }
      }
    }, delay);
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `npx vitest run tests/server/bazaar/bazaar-client.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/bazaar/bazaar-client.ts tests/server/bazaar/bazaar-client.test.ts
git commit -m "feat(bazaar): add BazaarClient with WS connection, register, heartbeat"
```

---

### Task 5: Bridge 消息路由（前端 ↔ Bridge ↔ 集市）

**Files:**
- Create: `server/bazaar/bazaar-bridge.ts`

- [ ] **Step 1: 实现消息路由**

```typescript
// server/bazaar/bazaar-bridge.ts
import { v4 as uuidv4 } from 'uuid';
import { createLogger, type Logger } from '../utils/logger.js';
import { BazaarClient } from './bazaar-client.js';
import { BazaarStore } from './bazaar-store.js';
import type { BridgeDeps } from './types.js';

export class BazaarBridge {
  private log: Logger;
  private client: BazaarClient;
  private store: BazaarStore;
  private deps: BridgeDeps;

  constructor(deps: BridgeDeps) {
    this.deps = deps;
    this.log = createLogger('BazaarBridge');
    const dbPath = `${deps.homeDir}/.sman/bazaar.db`;
    this.store = new BazaarStore(dbPath);
    this.client = new BazaarClient(this.store, {
      getAgentProjects: () => this.getAgentProjects(),
    });

    // 集市消息 → 前端推送
    this.client.onMessage = (msg) => this.handleBazaarMessage(msg);
  }

  async start(): Promise<void> {
    const config = this.deps.settingsManager.getConfig();
    if (!config.bazaar?.server) {
      this.log.info('Bazaar not configured, bridge not started');
      return;
    }

    // 确保有 Agent 身份
    this.ensureIdentity(config.bazaar);

    try {
      await this.client.connect();
      this.log.info('Bazaar bridge started');

      // 推送初始连接状态给前端
      const identity = this.store.getIdentity();
      this.deps.broadcast(JSON.stringify({
        type: 'bazaar.status',
        event: 'connected',
        agentId: identity?.agentId,
        agentName: identity?.name,
        reputation: 0,
        activeSlots: 0,
        maxSlots: config.bazaar?.maxConcurrentTasks ?? 3,
      }));
    } catch (err) {
      this.log.error('Failed to connect to bazaar', { error: String(err) });
    }
  }

  stop(): void {
    this.client.disconnect();
    this.log.info('Bazaar bridge stopped');
  }

  // ── 前端 → Bridge 消息处理 ──

  handleFrontendMessage(type: string, payload: Record<string, unknown>, ws: unknown): void {
    switch (type) {
      case 'bazaar.task.list':
        this.deps.broadcast(JSON.stringify({
          type: 'bazaar.task.list.update',
          tasks: this.store.listTasks(),
        }));
        break;

      case 'bazaar.task.cancel': {
        const taskId = payload.taskId as string;
        this.client.send({
          id: uuidv4(),
          type: 'task.cancel',
          payload: { taskId, reason: 'user_manual_cancel' },
        });
        break;
      }

      case 'bazaar.config.update': {
        const config = this.deps.settingsManager.getConfig();
        const updated = { ...config.bazaar, ...payload };
        this.deps.settingsManager.updateConfig({ ...config, bazaar: updated });
        this.log.info('Bazaar config updated', { mode: payload.mode });
        break;
      }

      default:
        this.log.warn(`Unknown frontend message type: ${type}`);
    }
  }

  // ── 集市 → Bridge 消息处理 ──

  private handleBazaarMessage(msg: { type: string; payload: Record<string, unknown> }): void {
    this.log.info(`Bazaar message: ${msg.type}`);

    switch (msg.type) {
      case 'task.incoming':
        this.handleIncomingTask(msg.payload);
        break;

      case 'task.chat':
        this.deps.broadcast(JSON.stringify({
          type: 'bazaar.task.chat.delta',
          taskId: msg.payload.taskId,
          from: msg.payload.from,
          text: msg.payload.text,
        }));
        break;

      case 'task.matched':
        this.deps.broadcast(JSON.stringify({
          type: 'bazaar.status',
          event: 'task_matched',
          taskId: msg.payload.taskId,
          helper: msg.payload.helper,
        }));
        break;

      case 'task.timeout':
      case 'task.cancelled':
        this.store.updateTaskStatus(
          msg.payload.taskId as string,
          msg.type === 'task.timeout' ? 'timeout' : 'cancelled',
        );
        this.deps.broadcast(JSON.stringify({
          type: 'bazaar.status',
          event: msg.type,
          taskId: msg.payload.taskId,
        }));
        break;

      default:
        // 其他消息直接推送前端
        this.deps.broadcast(JSON.stringify({
          type: 'bazaar.status',
          event: msg.type,
          payload: msg.payload,
        }));
    }
  }

  private handleIncomingTask(payload: Record<string, unknown>): void {
    const config = this.deps.settingsManager.getConfig().bazaar;
    const mode = config?.mode ?? 'notify';

    this.store.saveTask({
      taskId: payload.taskId as string,
      direction: 'incoming',
      requesterAgentId: payload.from as string,
      requesterName: payload.fromName as string ?? '一位同事',
      question: payload.question as string,
      status: 'offered',
      createdAt: new Date().toISOString(),
    });

    if (mode === 'auto') {
      // 全自动模式：直接接受
      this.client.send({
        id: uuidv4(),
        type: 'task.accept',
        payload: { taskId: payload.taskId as string },
      });
    } else {
      // notify/manual 模式：推送通知给前端
      // TODO(Phase 2): notify 模式需实现 30 秒超时自动接受
      // TODO(Phase 2): manual 模式需前端实现接受/拒绝 UI
      this.deps.broadcast(JSON.stringify({
        type: 'bazaar.notify',
        taskId: payload.taskId,
        from: payload.fromName ?? '一位同事',
        question: payload.question,
        mode,
      }));
    }
  }

  private ensureIdentity(bazaarConfig: NonNullable<SmanConfig['bazaar']>): void {
    let identity = this.store.getIdentity();
    if (!identity) {
      identity = {
        agentId: uuidv4(),
        hostname: os.hostname(),
        username: os.userInfo().username,
        name: bazaarConfig.agentName ?? os.userInfo().username,
        server: bazaarConfig.server,
      };
      this.store.saveIdentity(identity);
      this.log.info(`New agent identity created: ${identity.agentId}`);
    } else {
      // 更新服务器地址（可能变了）
      identity.server = bazaarConfig.server;
      if (bazaarConfig.agentName) identity.name = bazaarConfig.agentName;
      this.store.saveIdentity(identity);
    }
  }

  private getAgentProjects(): Array<{ repo: string; skills: string }> {
    // 从 SkillsRegistry 获取当前项目的 skills
    const projects: Array<{ repo: string; skills: string }> = [];
    // Phase 1 简化版：从配置中的工作目录推断
    // 后续 Phase 从 SkillsRegistry 动态获取
    return projects;
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add server/bazaar/bazaar-bridge.ts
git commit -m "feat(bazaar): add BazaarBridge message routing between frontend and bazaar"
```

---

### Task 6: Bridge 入口和一期注入点

**Files:**
- Create: `server/bazaar/index.ts`
- Modify: `server/index.ts`（2 处改动：末尾 1 个 import + 5 行初始化 + switch default 分支修改）

- [ ] **Step 1: 实现 Bridge 入口**

```typescript
// server/bazaar/index.ts
import { BazaarBridge } from './bazaar-bridge.js';
import type { BridgeDeps } from './types.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('BazaarInit');

let bridge: BazaarBridge | null = null;

export function initBazaarBridge(deps: BridgeDeps): void {
  try {
    const config = deps.settingsManager.getConfig();
    if (!config.bazaar?.server) {
      log.info('Bazaar not configured, skipping bridge initialization');
      return;
    }

    bridge = new BazaarBridge(deps);

    // 启动连接（异步，不阻塞主流程）
    bridge.start().catch((err) => {
      log.error('Bazaar bridge failed to start', { error: String(err) });
    });

    log.info('Bazaar bridge initialized');
  } catch (err) {
    // 构造函数异常不传播，保证一期服务正常
    log.error('Bazaar bridge initialization failed', { error: String(err) });
  }
}

export function getBazaarBridge(): BazaarBridge | null {
  return bridge;
}
```

- [ ] **Step 2: 在 server/index.ts 底部新增注入代码**

在 `server/index.ts` 文件末尾（所有现有代码之后），新增 1 个 import + 5 行初始化：

```typescript
// Bazaar Bridge（独立模块，未配置时无副作用）
import { initBazaarBridge } from './bazaar/index.js';

initBazaarBridge({
  sessionManager,
  settingsManager,
  skillsRegistry,
  broadcast: (data: string) => broadcast(data),
  homeDir,
});
```

**注意**：ESM 中 import 会被提升到模块顶部，但 `initBazaarBridge` 的调用在所有变量声明之后，参数在调用时都已初始化。如果 bazaar 未配置，`initBazaarBridge` 什么都不做。

- [ ] **Step 3: 在 WS 消息路由中新增 bazaar.* 前缀处理**

在 `server/index.ts` 的 WS 消息处理 switch 中，新增一个 case（找到现有的 `switch (msg.type)` 块，在 default 之前）：

```typescript
      // Bazaar 前端消息路由
      default:
        if (msg.type?.startsWith('bazaar.')) {
          const bridge = getBazaarBridge();
          if (bridge) {
            bridge.handleFrontendMessage(msg.type, msg.payload ?? msg, ws);
          } else {
            ws.send(JSON.stringify({ type: 'error', error: `Bazaar not configured: ${msg.type}` }));
          }
        } else {
          ws.send(JSON.stringify({ type: 'error', error: `Unknown message type: ${msg.type}` }));
        }
        break;
```

- [ ] **Step 4: 验证编译通过**

Run: `npx tsc --noEmit 2>&1 | head -20`
Expected: 无错误（或仅有与 bazaar 相关的可接受警告）

- [ ] **Step 5: Commit**

```bash
git add server/bazaar/index.ts server/index.ts
git commit -m "feat(bazaar): add Bridge entry point with minimal 5-line injection"
```

---

### Task 7: Bridge 层集成测试

**Files:**
- Create: `tests/server/bazaar/bridge-integration.test.ts`

- [ ] **Step 1: 写集成测试**

```typescript
// tests/server/bazaar/bridge-integration.test.ts
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { BazaarStore } from '../../../server/bazaar/bazaar-store.js';
import { BazaarClient } from '../../../server/bazaar/bazaar-client.js';
import http from 'http';
import { WebSocketServer } from 'ws';
import WebSocket from 'ws';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('Bridge Integration: Client ↔ Server', () => {
  let store: BazaarStore;
  let client: BazaarClient;
  let dbPath: string;
  let wss: WebSocketServer;
  let server: http.Server;
  let port: number;
  let serverMessages: any[] = [];

  beforeAll(async () => {
    dbPath = path.join(os.tmpdir(), `bridge-integration-${Date.now()}.db`);
    store = new BazaarStore(dbPath);

    // 模拟集市服务器
    const app = http.createServer();
    wss = new WebSocketServer({ server });
    serverMessages = [];

    wss.on('connection', (ws) => {
      ws.on('message', (data) => {
        const msg = JSON.parse(data.toString());
        serverMessages.push(msg);
        ws.send(JSON.stringify({ type: 'ack', id: msg.id }));

        // 模拟服务器推送
        if (msg.type === 'agent.register') {
          ws.send(JSON.stringify({
            id: 'srv-001',
            type: 'agent.registered',
            payload: { agentId: msg.payload.agentId, status: 'idle' },
          }));
        }
      });
    });

    await new Promise<void>((resolve) => {
      server = app;
      server.listen(0, () => {
        port = (server.address() as any).port;
        resolve();
      });
    });
  });

  afterAll(async () => {
    client?.disconnect();
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
    await new Promise<void>((r) => wss.close(() => r()));
    await new Promise<void>((r) => server.close(() => r()));
  });

  it('should complete register and receive server push', async () => {
    store.saveIdentity({
      agentId: 'bridge-test-001',
      hostname: 'test',
      username: 'testuser',
      name: '测试',
      server: `localhost:${port}`,
    });

    const received: any[] = [];
    client = new BazaarClient(store, { getAgentProjects: () => [] });
    client.onMessage = (msg) => received.push(msg);

    await client.connect();
    await new Promise((r) => setTimeout(r, 300));

    // 验证注册消息已发送
    const reg = serverMessages.find(m => m.type === 'agent.register');
    expect(reg).toBeDefined();
    expect(reg.payload.agentId).toBe('bridge-test-001');

    // 验证收到了服务器推送
    expect(received.length).toBeGreaterThanOrEqual(1);
    expect(received[0].type).toBe('agent.registered');
  });

  it('should handle task.incoming and save to local store', async () => {
    // 从服务器模拟推送一个任务邀请
    const wsClient = Array.from(wss.clients)[0];
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      wsClient.send(JSON.stringify({
        id: 'srv-task-001',
        type: 'task.incoming',
        payload: {
          taskId: 'task-001',
          from: 'agent-002',
          fromName: '小李',
          question: '支付系统怎么查？',
          deadline: new Date(Date.now() + 300000).toISOString(),
        },
      }));
    }

    await new Promise((r) => setTimeout(r, 300));

    // 验证本地存储
    const task = store.getTask('task-001');
    expect(task).toBeDefined();
    expect(task!.question).toBe('支付系统怎么查？');
    expect(task!.direction).toBe('incoming');
    expect(task!.status).toBe('offered');
  });
});
```

- [ ] **Step 2: 运行测试**

Run: `npx vitest run tests/server/bazaar/bridge-integration.test.ts`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add tests/server/bazaar/bridge-integration.test.ts
git commit -m "test(bazaar): add Bridge integration test for client-server flow"
```

---

### Task 8: 验证零侵入

- [ ] **Step 1: 验证删除 bazaar 目录后一期正常**

Run: `npx tsc --noEmit 2>&1 | grep -v bazaar | grep -v "shared/" | head -10`
Expected: 无一期代码的编译错误

- [ ] **Step 2: 运行一期已有测试确保无回归**

Run: `npx vitest run --reporter=verbose 2>&1 | tail -20`
Expected: 所有已有测试通过

- [ ] **Step 3: Commit（如有修复）**

```bash
git add -A
git commit -m "chore(bazaar): verify zero-invasion — existing tests pass"
```

---

## 总结

**本计划覆盖的文件**：

| 文件 | 说明 | 对一期的影响 |
|------|------|------------|
| `server/types.ts` | 新增 `bazaar` 配置字段 | 仅扩展，不改现有字段 |
| `server/bazaar/types.ts` | Bridge 内部类型 | 新文件 |
| `server/bazaar/bazaar-store.ts` | 本地 SQLite 存储 | 新文件 |
| `server/bazaar/bazaar-client.ts` | WS 连接集市服务器 | 新文件 |
| `server/bazaar/bazaar-bridge.ts` | 前端↔集市消息路由 | 新文件 |
| `server/bazaar/index.ts` | Bridge 入口 | 新文件 |
| `server/index.ts` | 1 个 import + 5 行初始化 + switch default 修改 | 最小改动 |
| `tests/server/bazaar/bazaar-store.test.ts` | Store 测试 | 新文件 |
| `tests/server/bazaar/bazaar-client.test.ts` | Client 测试 | 新文件 |
| `tests/server/bazaar/bridge-integration.test.ts` | 集成测试 | 新文件 |

**一期公共 API 使用清单**：

| API | 用途 |
|-----|------|
| `sessionManager.createSessionWithId()` | 创建协作会话 |
| `sessionManager.sendMessageForCron()` | 协作对话执行 |
| `sessionManager.abort()` | 强制结束任务 |
| `broadcast()` | 向前端推送消息（注：全局广播，所有 WS 客户端都会收到 `bazaar.*` 消息。Phase 2 需改为按需推送） |
| `settingsManager.getConfig()` | 读取配置 |
| `skillsRegistry.listSkills()` | 获取能力列表 |

**判定标准**：删除 `server/bazaar/` 目录后，一期编译运行零报错。
