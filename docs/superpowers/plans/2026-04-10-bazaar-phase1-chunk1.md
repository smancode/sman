# Bazaar 集市服务器骨架 实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现独立的 Bazaar 集市服务器骨架，支持 Agent 注册/心跳/项目能力上报、WS 消息路由、SQLite 持久化

**Architecture:** 独立 Node.js 服务（`bazaar/` 目录），拥有自己的 `package.json`、`tsconfig.json`、SQLite 数据库。通过 WebSocket 接收 Sman Bridge 层的连接。不依赖 `server/` 代码。共享类型放 `shared/bazaar-types.ts`。

**Tech Stack:** TypeScript, SQLite (better-sqlite3), WebSocket (ws), Express, Vitest, Zod

**Design Doc:** `docs/superpowers/specs/2026-04-10-bazaar-agent-swarm-design.md`

---

## Chunk 1: 基础设施（共享类型 + Logger + Protocol + Store）

### Task 1: 共享类型定义

**Files:**
- Create: `shared/bazaar-types.ts`

- [ ] **Step 1: 创建 shared 目录和类型文件**

```typescript
// shared/bazaar-types.ts
// Bazaar 集市服务器与 Sman Bridge 层的共享消息协议类型

// ── Agent 相关 ──

export type AgentStatus = 'idle' | 'busy' | 'afk' | 'offline';

export interface AgentProfile {
  id: string;              // UUID
  username: string;
  hostname: string;
  name: string;            // 显示名
  avatar: string;          // emoji
  status: AgentStatus;
  reputation: number;
  projects: AgentProject[];
  privateCapabilities: PrivateCapability[];
  joinedAt: string;
}

export interface AgentProject {
  repo: string;
  path: string;
  skills: SkillSummary[];
}

export interface SkillSummary {
  id: string;
  name: string;
  triggers: string[];
}

export interface PrivateCapability {
  id: string;
  name: string;
  triggers: string[];
}

// ── 消息协议 ──

export interface BazaarMessage {
  id: string;              // 消息 UUID（幂等去重）
  type: string;
  inReplyTo?: string;
  payload: Record<string, unknown>;
}

export interface BazaarAck {
  type: 'ack';
  id: string;              // 原消息 ID
}

// ── Agent 消息类型 ──

export type AgentMessageType =
  | 'agent.register'
  | 'agent.registered'
  | 'agent.heartbeat'
  | 'agent.update'
  | 'agent.offline';

export interface AgentRegisterPayload {
  agentId: string;
  username: string;
  hostname: string;
  name: string;
  avatar?: string;
  projects: AgentProject[];
  privateCapabilities: PrivateCapability[];
  protocolVersion?: string;
}

export interface AgentHeartbeatPayload {
  agentId: string;
  status: AgentStatus;
  activeTaskCount: number;
}

export interface AgentUpdatePayload {
  agentId: string;
  projects?: AgentProject[];
  privateCapabilities?: PrivateCapability[];
  status?: AgentStatus;
}

// ── Task 消息类型 ──

export type TaskMessageType =
  | 'task.create'
  | 'task.search_result'
  | 'task.offer'
  | 'task.incoming'
  | 'task.accept'
  | 'task.reject'
  | 'task.matched'
  | 'task.chat'
  | 'task.progress'
  | 'task.complete'
  | 'task.result'
  | 'task.timeout'
  | 'task.cancel'
  | 'task.cancelled';

// ── World 消息类型 ──

export type WorldMessageType =
  | 'world.move'
  | 'world.agent_update'
  | 'world.enter_zone'
  | 'world.leave_zone'
  | 'world.zone_snapshot'
  | 'world.agent_enter'
  | 'world.agent_leave'
  | 'world.event';

// ── Server 消息类型 ──

export type ServerMessageType =
  | 'ack'
  | 'error'
  | 'server.maintenance'
  | 'agent.kicked'
  | 'agent.resume_tasks'
  | 'world.resync';

// ── Task 状态和协作模式枚举 ──

export type TaskStatus = 'created' | 'searching' | 'offered' | 'matched' | 'chatting' | 'completed' | 'rated' | 'failed';

export type CollaborationMode = 'auto' | 'notify' | 'manual';

// ── Task Payload 骨架（后续 Chunk 扩展） ──

export interface TaskCreatePayload {
  question: string;
  capabilityQuery: string;
  provenance?: string[];
  hopCount?: number;
}

export interface TaskOfferPayload {
  taskId: string;
  candidates: Array<{ agentId: string; reputation: number }>;
}

// ── Bazaar 配置（嵌入 SmanConfig） ──

export interface BazaarConfig {
  server: string;          // 集市服务器地址，如 "bazaar.company.com:5890"
  agentName?: string;      // Agent 显示名
  mode: CollaborationMode;  // 协作模式
  maxConcurrentTasks: number;  // 最大并发槽位，默认 3
}
```

- [ ] **Step 2: 验证类型文件无语法错误**

Run: `npx tsc --noEmit shared/bazaar-types.ts 2>&1 || echo "Type check not applicable for standalone file"`
Expected: 文件创建成功

- [ ] **Step 3: Commit**

```bash
mkdir -p shared
git add shared/bazaar-types.ts
git commit -m "feat(bazaar): add shared message protocol types"
```

---

### Task 2: Logger 工具

**Files:**
- Create: `bazaar/src/utils/logger.ts`

- [ ] **Step 1: 创建 Logger（复用 server/utils/logger.ts 的模式）**

```typescript
// bazaar/src/utils/logger.ts
export interface Logger {
  info(message: string, meta?: Record<string, unknown>): void;
  warn(message: string, meta?: Record<string, unknown>): void;
  error(message: string, meta?: Record<string, unknown>): void;
  debug(message: string, meta?: Record<string, unknown>): void;
}

function formatLocalTime(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const ms = String(now.getMilliseconds()).padStart(3, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}.${ms}`;
}

export function createLogger(module: string): Logger {
  const isDebug = process.env.LOG_LEVEL === 'debug';

  return {
    info(message: string, meta?: Record<string, unknown>) {
      console.log(JSON.stringify({ level: 'info', module, message, ...meta, ts: formatLocalTime() }));
    },
    warn(message: string, meta?: Record<string, unknown>) {
      console.warn(JSON.stringify({ level: 'warn', module, message, ...meta, ts: formatLocalTime() }));
    },
    error(message: string, meta?: Record<string, unknown>) {
      console.error(JSON.stringify({ level: 'error', module, message, ...meta, ts: formatLocalTime() }));
    },
    debug(message: string, meta?: Record<string, unknown>) {
      if (isDebug) {
        console.debug(JSON.stringify({ level: 'debug', module, message, ...meta, ts: formatLocalTime() }));
      }
    },
  };
}
```

- [ ] **Step 2: Commit**

```bash
mkdir -p bazaar/src/utils
git add bazaar/src/utils/logger.ts
git commit -m "feat(bazaar): add logger utility"
```

---

### Task 3: 消息协议校验

**Files:**
- Create: `bazaar/src/protocol.ts`
- Test: `bazaar/tests/protocol.test.ts`

- [ ] **Step 1: 写测试**

```typescript
// bazaar/tests/protocol.test.ts
import { describe, it, expect } from 'vitest';
import { validateMessage, isValidMessageType } from '../src/protocol.js';

describe('validateMessage', () => {
  it('should validate a well-formed agent.register message', () => {
    const msg = {
      id: 'msg-001',
      type: 'agent.register',
      payload: {
        agentId: 'agent-abc',
        username: 'zhangsan',
        hostname: 'VDI-ZHANGSAN-01',
        name: '张三',
        projects: [],
        privateCapabilities: [],
      },
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(true);
    expect(result.errors).toHaveLength(0);
  });

  it('should reject message without id', () => {
    const msg = {
      type: 'agent.register',
      payload: { agentId: 'abc' },
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(false);
    expect(result.errors).toContain('Missing required field: id');
  });

  it('should reject message without type', () => {
    const msg = {
      id: 'msg-002',
      payload: {},
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(false);
    expect(result.errors).toContain('Missing required field: type');
  });

  it('should reject unknown message type', () => {
    const msg = {
      id: 'msg-003',
      type: 'unknown.type',
      payload: {},
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(false);
    expect(result.errors[0]).toMatch(/Unknown message type/);
  });

  it('should reject agent.register with missing required fields', () => {
    const msg = {
      id: 'msg-004',
      type: 'agent.register',
      payload: {
        agentId: 'agent-abc',
        // missing username, hostname, name
      },
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(false);
    expect(result.errors.length).toBeGreaterThan(0);
  });

  it('should accept agent.heartbeat message', () => {
    const msg = {
      id: 'msg-005',
      type: 'agent.heartbeat',
      payload: {
        agentId: 'agent-abc',
        status: 'idle',
        activeTaskCount: 0,
      },
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(true);
  });

  it('should accept ack message', () => {
    const msg = {
      id: 'msg-006',
      type: 'ack',
      payload: { id: 'msg-001' },
    };
    const result = validateMessage(msg);
    expect(result.valid).toBe(true);
  });
});

describe('isValidMessageType', () => {
  it('should return true for known types', () => {
    expect(isValidMessageType('agent.register')).toBe(true);
    expect(isValidMessageType('task.create')).toBe(true);
    expect(isValidMessageType('ack')).toBe(true);
  });

  it('should return false for unknown types', () => {
    expect(isValidMessageType('foo.bar')).toBe(false);
    expect(isValidMessageType('')).toBe(false);
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd bazaar && npx vitest run tests/protocol.test.ts 2>&1 | tail -5`
Expected: FAIL — module not found

- [ ] **Step 3: 实现 protocol.ts**

```typescript
// bazaar/src/protocol.ts
import type {
  AgentMessageType,
  TaskMessageType,
  WorldMessageType,
  ServerMessageType,
} from '../../shared/bazaar-types.js';

type AllMessageTypes = AgentMessageType | TaskMessageType | WorldMessageType | ServerMessageType;

const VALID_TYPES: Set<string> = new Set([
  // Agent
  'agent.register', 'agent.registered', 'agent.heartbeat', 'agent.update', 'agent.offline',
  'agent.kicked', 'agent.resume_tasks',
  // Task
  'task.create', 'task.search_result', 'task.offer', 'task.incoming',
  'task.accept', 'task.reject', 'task.matched', 'task.chat',
  'task.progress', 'task.complete', 'task.result', 'task.timeout',
  'task.cancel', 'task.cancelled', 'task.escalate',
  // World
  'world.move', 'world.agent_update', 'world.enter_zone', 'world.leave_zone',
  'world.zone_snapshot', 'world.agent_enter', 'world.agent_leave', 'world.event',
  'world.resync',
  // Server
  'ack', 'error', 'server.maintenance',
]);

// 各消息类型的必填 payload 字段
const REQUIRED_FIELDS: Record<string, string[]> = {
  'agent.register': ['agentId', 'username', 'hostname', 'name'],
  'agent.heartbeat': ['agentId', 'status'],
  'agent.update': ['agentId'],
  'task.create': ['question', 'capabilityQuery'],
  'task.offer': ['taskId', 'candidates'],
  'task.accept': ['taskId'],
  'task.reject': ['taskId'],
  'task.chat': ['taskId', 'text'],
  'task.complete': ['taskId', 'rating'],
  'task.cancel': ['taskId', 'reason'],
};

export interface ValidationResult {
  valid: boolean;
  errors: string[];
}

export function isValidMessageType(type: string): boolean {
  return VALID_TYPES.has(type);
}

export function validateMessage(raw: unknown): ValidationResult {
  const errors: string[] = [];

  if (!raw || typeof raw !== 'object') {
    return { valid: false, errors: ['Message must be an object'] };
  }

  const msg = raw as Record<string, unknown>;

  // 必填字段检查
  if (!msg.id || typeof msg.id !== 'string') {
    errors.push('Missing required field: id');
  }
  if (!msg.type || typeof msg.type !== 'string') {
    errors.push('Missing required field: type');
  }

  if (errors.length > 0) {
    return { valid: false, errors };
  }

  // 消息类型检查
  if (!isValidMessageType(msg.type as string)) {
    errors.push(`Unknown message type: ${msg.type}`);
    return { valid: false, errors };
  }

  // 特定消息类型的 payload 校验
  const required = REQUIRED_FIELDS[msg.type as string];
  if (required) {
    const payload = (msg.payload as Record<string, unknown>) ?? {};
    for (const field of required) {
      if (payload[field] === undefined || payload[field] === null) {
        errors.push(`Missing required payload field: ${field} for ${msg.type}`);
      }
    }
  }

  return { valid: errors.length === 0, errors };
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd bazaar && npx vitest run tests/protocol.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
mkdir -p bazaar/src bazaar/tests
git add bazaar/src/protocol.ts bazaar/tests/protocol.test.ts
git commit -m "feat(bazaar): add message protocol validation with tests"
```

---

### Task 4: AgentStore — SQLite 持久化层

**Files:**
- Create: `bazaar/src/agent-store.ts`
- Test: `bazaar/tests/agent-store.test.ts`

- [ ] **Step 1: 写测试**

```typescript
// bazaar/tests/agent-store.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { AgentStore } from '../src/agent-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('AgentStore', () => {
  let store: AgentStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `bazaar-agent-test-${Date.now()}.db`);
    store = new AgentStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  // === Agent CRUD ===

  describe('registerAgent', () => {
    it('should register a new agent', () => {
      const agent = store.registerAgent({
        id: 'agent-001',
        username: 'zhangsan',
        hostname: 'VDI-ZS-01',
        name: '张三',
        avatar: '🧙',
      });
      expect(agent.id).toBe('agent-001');
      expect(agent.username).toBe('zhangsan');
      expect(agent.status).toBe('idle');
      expect(agent.reputation).toBe(0);
    });

    it('should throw on duplicate username', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      expect(() => {
        store.registerAgent({ id: 'agent-002', username: 'zhangsan', hostname: 'h2', name: '张三' });
      }).toThrow(/UNIQUE constraint/);
    });
  });

  describe('getAgent', () => {
    it('should return undefined for non-existent agent', () => {
      expect(store.getAgent('non-existent')).toBeUndefined();
    });

    it('should return registered agent', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      const agent = store.getAgent('agent-001');
      expect(agent).toBeDefined();
      expect(agent!.username).toBe('zhangsan');
    });
  });

  describe('getAgentByUsername', () => {
    it('should find agent by username', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      const agent = store.getAgentByUsername('zhangsan');
      expect(agent).toBeDefined();
      expect(agent!.id).toBe('agent-001');
    });
  });

  describe('updateAgentStatus', () => {
    it('should update agent status', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      store.updateAgentStatus('agent-001', 'busy');
      const agent = store.getAgent('agent-001');
      expect(agent!.status).toBe('busy');
    });
  });

  describe('updateHeartbeat', () => {
    it('should update last_seen_at', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      const before = store.getAgent('agent-001')!.lastSeenAt;
      // small delay to ensure different timestamp
      const start = Date.now();
      while (Date.now() === start) { /* spin */ }
      store.updateHeartbeat('agent-001');
      const after = store.getAgent('agent-001')!.lastSeenAt;
      expect(after).not.toBe(before);
    });
  });

  describe('setAgentOffline', () => {
    it('should set agent status to offline', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      store.setAgentOffline('agent-001');
      const agent = store.getAgent('agent-001');
      expect(agent!.status).toBe('offline');
    });
  });

  describe('listOnlineAgents', () => {
    it('should return only non-offline agents', () => {
      store.registerAgent({ id: 'a1', username: 'u1', hostname: 'h1', name: '张三' });
      store.registerAgent({ id: 'a2', username: 'u2', hostname: 'h2', name: '李四' });
      store.setAgentOffline('a2');
      const online = store.listOnlineAgents();
      expect(online).toHaveLength(1);
      expect(online[0].id).toBe('a1');
    });
  });

  // === Projects ===

  describe('updateProjects', () => {
    it('should replace projects for an agent', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      store.updateProjects('agent-001', [
        { repo: 'payment-service', skills: JSON.stringify([{ id: 'pay', name: '支付', triggers: ['支付'] }]) },
        { repo: 'risk-engine', skills: JSON.stringify([]) },
      ]);
      const projects = store.getProjects('agent-001');
      expect(projects).toHaveLength(2);
      expect(projects[0].repo).toBe('payment-service');
    });
  });

  describe('findAgentsByCapability', () => {
    it('should find agents with matching skill keywords', () => {
      store.registerAgent({ id: 'a1', username: 'u1', hostname: 'h1', name: '张三' });
      store.updateProjects('a1', [
        { repo: 'payment-service', skills: JSON.stringify([{ id: 'pay', name: '支付查询', triggers: ['支付', '查询'] }]) },
      ]);
      store.registerAgent({ id: 'a2', username: 'u2', hostname: 'h2', name: '李四' });
      store.updateProjects('a2', [
        { repo: 'risk-engine', skills: JSON.stringify([{ id: 'risk', name: '风控', triggers: ['风控', '规则'] }]) },
      ]);

      const results = store.findAgentsByCapability('支付');
      expect(results).toHaveLength(1);
      expect(results[0].agentId).toBe('a1');
    });
  });

  // === Audit Log ===

  describe('logAudit', () => {
    it('should record an audit event', () => {
      store.registerAgent({ id: 'a1', username: 'u1', hostname: 'h1', name: '张三' });
      store.logAudit('agent.online', 'a1', undefined, undefined, { projects: [] });
      const logs = store.getAuditLogs('a1');
      expect(logs).toHaveLength(1);
      expect(logs[0].eventType).toBe('agent.online');
    });
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd bazaar && npx vitest run tests/agent-store.test.ts 2>&1 | tail -5`
Expected: FAIL — module not found

- [ ] **Step 3: 实现 AgentStore**

```typescript
// bazaar/src/agent-store.ts
import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from './utils/logger.js';

// ── 输入类型 ──

interface RegisterInput {
  id: string;
  username: string;
  hostname: string;
  name: string;
  avatar?: string;
}

interface ProjectInput {
  repo: string;
  skills: string; // JSON string
}

export interface AgentRow {
  id: string;
  username: string;
  hostname: string;
  name: string;
  avatar: string;
  status: string;
  reputation: number;
  lastSeenAt: string | null;
  createdAt: string;
}

export interface ProjectRow {
  agentId: string;
  repo: string;
  skills: string;
  updatedAt: string;
}

export interface AuditRow {
  id: number;
  timestamp: string;
  eventType: string;
  agentId: string;
  targetAgentId: string | null;
  taskId: string | null;
  detail: string;
}

export class AgentStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('AgentStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS agents (
        id TEXT PRIMARY KEY,
        username TEXT UNIQUE NOT NULL,
        hostname TEXT,
        name TEXT NOT NULL,
        avatar TEXT DEFAULT '🧙',
        status TEXT DEFAULT 'offline',
        reputation REAL DEFAULT 0,
        last_seen_at TEXT,
        created_at TEXT NOT NULL
      );

      CREATE TABLE IF NOT EXISTS agent_projects (
        agent_id TEXT NOT NULL,
        repo TEXT NOT NULL,
        skills TEXT NOT NULL DEFAULT '[]',
        updated_at TEXT NOT NULL,
        PRIMARY KEY (agent_id, repo),
        FOREIGN KEY (agent_id) REFERENCES agents(id)
      );

      CREATE TABLE IF NOT EXISTS agent_private_capabilities (
        agent_id TEXT NOT NULL,
        id TEXT NOT NULL,
        name TEXT NOT NULL,
        triggers TEXT DEFAULT '[]',
        source TEXT DEFAULT 'experience',
        updated_at TEXT NOT NULL,
        PRIMARY KEY (agent_id, id),
        FOREIGN KEY (agent_id) REFERENCES agents(id)
      );

      CREATE TABLE IF NOT EXISTS audit_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp TEXT NOT NULL,
        event_type TEXT NOT NULL,
        agent_id TEXT NOT NULL,
        target_agent_id TEXT,
        task_id TEXT,
        detail TEXT NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_audit_agent ON audit_log(agent_id);
      CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(timestamp);
      CREATE INDEX IF NOT EXISTS idx_audit_event ON audit_log(event_type);
      CREATE INDEX IF NOT EXISTS idx_projects_agent ON agent_projects(agent_id);
      CREATE INDEX IF NOT EXISTS idx_privcap_agent ON agent_private_capabilities(agent_id);

      PRAGMA journal_mode=WAL;
    `);
  }

  // ── Agent CRUD ──

  registerAgent(input: RegisterInput): AgentRow {
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO agents (id, username, hostname, name, avatar, status, created_at)
      VALUES (?, ?, ?, ?, ?, 'idle', ?)
    `).run(input.id, input.username, input.hostname, input.name, input.avatar ?? '🧙', now);
    return this.getAgent(input.id)!;
  }

  getAgent(id: string): AgentRow | undefined {
    return this.db.prepare(
      'SELECT id, username, hostname, name, avatar, status, reputation, last_seen_at as lastSeenAt, created_at as createdAt FROM agents WHERE id = ?'
    ).get(id) as AgentRow | undefined;
  }

  getAgentByUsername(username: string): AgentRow | undefined {
    return this.db.prepare(
      'SELECT id, username, hostname, name, avatar, status, reputation, last_seen_at as lastSeenAt, created_at as createdAt FROM agents WHERE username = ?'
    ).get(username) as AgentRow | undefined;
  }

  updateAgentStatus(id: string, status: string): void {
    this.db.prepare('UPDATE agents SET status = ? WHERE id = ?').run(status, id);
  }

  updateHeartbeat(id: string): void {
    this.db.prepare('UPDATE agents SET last_seen_at = ?, status = CASE WHEN status = ? THEN ? ELSE status END WHERE id = ?')
      .run(new Date().toISOString(), 'offline', 'idle', id);
  }

  setAgentOffline(id: string): void {
    this.db.prepare('UPDATE agents SET status = ? WHERE id = ?').run('offline', id);
  }

  listOnlineAgents(): AgentRow[] {
    return this.db.prepare(
      "SELECT id, username, hostname, name, avatar, status, reputation, last_seen_at as lastSeenAt, created_at as createdAt FROM agents WHERE status != 'offline'"
    ).all() as AgentRow[];
  }

  // ── Projects ──

  updateProjects(agentId: string, projects: ProjectInput[]): void {
    const now = new Date().toISOString();
    const deleteStmt = this.db.prepare('DELETE FROM agent_projects WHERE agent_id = ?');
    const insertStmt = this.db.prepare('INSERT INTO agent_projects (agent_id, repo, skills, updated_at) VALUES (?, ?, ?, ?)');

    const tx = this.db.transaction(() => {
      deleteStmt.run(agentId);
      for (const p of projects) {
        insertStmt.run(agentId, p.repo, p.skills, now);
      }
    });
    tx();
  }

  getProjects(agentId: string): ProjectRow[] {
    return this.db.prepare(
      'SELECT agent_id as agentId, repo, skills, updated_at as updatedAt FROM agent_projects WHERE agent_id = ?'
    ).all(agentId) as ProjectRow[];
  }

  findAgentsByCapability(keyword: string): { agentId: string; repo: string }[] {
    // 转义 SQL LIKE 通配符防止非预期匹配
    const escaped = keyword.replace(/%/g, '\\%').replace(/_/g, '\\_');
    return this.db.prepare(`
      SELECT ap.agent_id as agentId, ap.repo
      FROM agent_projects ap
      JOIN agents a ON a.id = ap.agent_id
      WHERE a.status != 'offline'
        AND ap.skills LIKE ? ESCAPE '\\'
    `).all(`%${escaped}%`) as { agentId: string; repo: string }[];
  }

  // ── Audit Log ──

  logAudit(eventType: string, agentId: string, targetAgentId?: string, taskId?: string, detail?: Record<string, unknown>): void {
    this.db.prepare(`
      INSERT INTO audit_log (timestamp, event_type, agent_id, target_agent_id, task_id, detail)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(new Date().toISOString(), eventType, agentId, targetAgentId ?? null, taskId ?? null, JSON.stringify(detail ?? {}));
  }

  getAuditLogs(agentId: string, limit = 100): AuditRow[] {
    return this.db.prepare(
      'SELECT id, timestamp, event_type as eventType, agent_id as agentId, target_agent_id as targetAgentId, task_id as taskId, detail FROM audit_log WHERE agent_id = ? ORDER BY timestamp DESC LIMIT ?'
    ).all(agentId, limit) as AuditRow[];
  }

  close(): void {
    this.db.close();
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd bazaar && npx vitest run tests/agent-store.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add bazaar/src/agent-store.ts bazaar/tests/agent-store.test.ts
git commit -m "feat(bazaar): add AgentStore with SQLite persistence and tests"
```

---

### Task 5: 消息路由器

**Files:**
- Create: `bazaar/src/message-router.ts`
- Test: `bazaar/tests/message-router.test.ts`

- [ ] **Step 1: 写测试**

```typescript
// bazaar/tests/message-router.test.ts
import { describe, it, expect, beforeEach } from 'vitest';
import { MessageRouter } from '../src/message-router.js';
import type { AgentStore } from '../src/agent-store.js';
import type WebSocket from 'ws';

// Mock AgentStore
function createMockStore(): AgentStore {
  const agents = new Map<string, { id: string; username: string; status: string }>();
  return {
    registerAgent: (input: { id: string; username: string; hostname: string; name: string }) => {
      agents.set(input.id, { id: input.id, username: input.username, status: 'idle' });
      return { ...input, avatar: '🧙', status: 'idle', reputation: 0, lastSeenAt: null, createdAt: new Date().toISOString() };
    },
    getAgent: (id: string) => agents.get(id) as any,
    getAgentByUsername: () => undefined,
    updateAgentStatus: (id: string, status: string) => {
      const a = agents.get(id);
      if (a) a.status = status;
    },
    updateHeartbeat: () => {},
    setAgentOffline: () => {},
    listOnlineAgents: () => Array.from(agents.values()),
    updateProjects: () => {},
    getProjects: () => [],
    findAgentsByCapability: () => [],
    logAudit: () => {},
    getAuditLogs: () => [],
    close: () => {},
  } as unknown as AgentStore;
}

describe('MessageRouter', () => {
  let router: MessageRouter;
  let mockStore: AgentStore;

  beforeEach(() => {
    mockStore = createMockStore();
    router = new MessageRouter(mockStore);
  });

  describe('route', () => {
    it('should reject invalid message format', () => {
      const result = router.route({ type: 'foo' }, {} as WebSocket);
      expect(result.error).toBeDefined();
      expect(result.error).toContain('Missing required field');
    });

    it('should reject unknown message type', () => {
      const result = router.route({ id: 'm1', type: 'unknown.type', payload: {} }, {} as WebSocket);
      expect(result.error).toContain('Unknown message type');
    });

    it('should route agent.register', () => {
      const sent: unknown[] = [];
      const ws = { send: (data: string) => sent.push(JSON.parse(data)) } as unknown as WebSocket;
      const result = router.route({
        id: 'm1',
        type: 'agent.register',
        payload: {
          agentId: 'a1',
          username: 'zhangsan',
          hostname: 'h1',
          name: '张三',
          projects: [],
          privateCapabilities: [],
        },
      }, ws);

      expect(result.handled).toBe(true);
      // 应该回复 ack
      expect(sent.length).toBeGreaterThan(0);
      expect(sent[0]).toHaveProperty('type', 'ack');
    });

    it('should route agent.heartbeat', () => {
      // 先注册
      mockStore.registerAgent({ id: 'a1', username: 'zhangsan', hostname: 'h1', name: '张三' });

      const sent: unknown[] = [];
      const ws = { send: (data: string) => sent.push(JSON.parse(data)) } as unknown as WebSocket;
      const result = router.route({
        id: 'm2',
        type: 'agent.heartbeat',
        payload: { agentId: 'a1', status: 'idle', activeTaskCount: 0 },
      }, ws);

      expect(result.handled).toBe(true);
    });

    it('should route agent.update', () => {
      mockStore.registerAgent({ id: 'a1', username: 'zhangsan', hostname: 'h1', name: '张三' });
      const ws = { send: () => {} } as unknown as WebSocket;
      const result = router.route({
        id: 'm3',
        type: 'agent.update',
        payload: { agentId: 'a1', status: 'busy' },
      }, ws);

      expect(result.handled).toBe(true);
    });
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd bazaar && npx vitest run tests/message-router.test.ts 2>&1 | tail -5`
Expected: FAIL — module not found

- [ ] **Step 3: 实现 MessageRouter**

```typescript
// bazaar/src/message-router.ts
import type WebSocket from 'ws';
import { validateMessage } from './protocol.js';
import type { AgentStore } from './agent-store.js';
import { createLogger, type Logger } from './utils/logger.js';

interface RouteResult {
  handled: boolean;
  error?: string;
}

export class MessageRouter {
  private log: Logger;
  private store: AgentStore;

  constructor(store: AgentStore) {
    this.store = store;
    this.log = createLogger('MessageRouter');
  }

  route(raw: unknown, ws: WebSocket): RouteResult {
    // 校验消息格式
    const validation = validateMessage(raw);
    if (!validation.valid) {
      this.log.warn('Invalid message received', { errors: validation.errors });
      return { handled: false, error: validation.errors.join('; ') };
    }

    const msg = raw as { id: string; type: string; payload: Record<string, unknown> };

    // 发送 ack
    const send = (data: unknown) => {
      if (ws.readyState === 1) { // WebSocket.OPEN
        ws.send(JSON.stringify(data));
      }
    };

    send({ type: 'ack', id: msg.id });

    // 路由分发
    const type = msg.type;
    const payload = msg.payload;

    try {
      if (type === 'agent.register') {
        return this.handleRegister(payload, ws, send);
      } else if (type === 'agent.heartbeat') {
        return this.handleHeartbeat(payload, send);
      } else if (type === 'agent.update') {
        return this.handleUpdate(payload, send);
      } else if (type === 'agent.offline') {
        return this.handleOffline(payload, send);
      } else {
        this.log.warn(`Unhandled message type: ${type}`);
        return { handled: true }; // 已知类型但当前 phase 未实现
      }
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : String(err);
      this.log.error(`Error handling ${type}`, { error: errorMsg });
      send({ type: 'error', id: msg.id, payload: { message: errorMsg } });
      return { handled: false, error: errorMsg };
    }
  }

  private handleRegister(
    payload: Record<string, unknown>,
    _ws: WebSocket,
    send: (data: unknown) => void,
  ): RouteResult {
    const agentId = payload.agentId as string;

    // 检查是否已注册（恢复身份）
    let agent = this.store.getAgent(agentId);
    if (!agent) {
      // 按 username 查找
      agent = this.store.getAgentByUsername(payload.username as string);
    }

    if (agent) {
      // 恢复身份
      this.store.updateAgentStatus(agent.id, 'idle');
      this.store.updateHeartbeat(agent.id);
    } else {
      // 新注册
      this.store.registerAgent({
        id: agentId,
        username: payload.username as string,
        hostname: payload.hostname as string,
        name: payload.name as string,
        avatar: (payload.avatar as string) ?? '🧙',
      });
    }

    // 更新项目列表（确保 skills 为 JSON string）
    const rawProjects = (payload.projects as Array<{ repo: string; skills: unknown }>) ?? [];
    const projects = rawProjects.map(p => ({
      repo: p.repo,
      skills: typeof p.skills === 'string' ? p.skills : JSON.stringify(p.skills),
    }));
    if (projects.length > 0) {
      this.store.updateProjects(agentId, projects);
    }

    this.store.logAudit('agent.online', agentId, undefined, undefined, {
      projects: projects.map(p => p.repo),
    });

    this.log.info(`Agent registered: ${payload.name} (${agentId})`);

    // 回复注册成功
    send({
      type: 'agent.registered',
      id: crypto.randomUUID(),
      inReplyTo: undefined,
      payload: { agentId, status: 'idle' },
    });

    return { handled: true };
  }

  private handleHeartbeat(
    payload: Record<string, unknown>,
    send: (data: unknown) => void,
  ): RouteResult {
    const agentId = payload.agentId as string;
    const status = payload.status as string;

    const agent = this.store.getAgent(agentId);
    if (!agent) {
      send({ type: 'error', payload: { message: `Agent not found: ${agentId}` } });
      return { handled: false, error: 'Agent not found' };
    }

    this.store.updateAgentStatus(agentId, status);
    this.store.updateHeartbeat(agentId);

    return { handled: true };
  }

  private handleUpdate(
    payload: Record<string, unknown>,
    _send: (data: unknown) => void,
  ): RouteResult {
    const agentId = payload.agentId as string;

    if (payload.status) {
      this.store.updateAgentStatus(agentId, payload.status as string);
    }
    if (payload.projects) {
      this.store.updateProjects(agentId, payload.projects as Array<{ repo: string; skills: string }>);
    }
    this.store.updateHeartbeat(agentId);

    this.log.info(`Agent updated: ${agentId}`);
    return { handled: true };
  }

  private handleOffline(
    payload: Record<string, unknown>,
    _send: (data: unknown) => void,
  ): RouteResult {
    const agentId = payload.agentId as string;
    this.store.setAgentOffline(agentId);
    this.store.logAudit('agent.offline', agentId);
    this.log.info(`Agent offline: ${agentId}`);
    return { handled: true };
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd bazaar && npx vitest run tests/message-router.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add bazaar/src/message-router.ts bazaar/tests/message-router.test.ts
git commit -m "feat(bazaar): add MessageRouter with agent message handling and tests"
```

---

### Task 6: Bazaar 服务器入口

**Files:**
- Create: `bazaar/src/index.ts`

- [ ] **Step 1: 实现服务器入口**

```typescript
// bazaar/src/index.ts
import express from 'express';
import http from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import { AgentStore } from './agent-store.js';
import { MessageRouter } from './message-router.js';
import { createLogger } from './utils/logger.js';

const log = createLogger('BazaarServer');

const PORT = parseInt(process.env.BAZAAR_PORT ?? '5890', 10);
const DB_PATH = process.env.BAZAAR_DB_PATH ?? `${process.env.HOME}/.bazaar/bazaar.db`;
const HEARTBEAT_TIMEOUT_MS = 90_000; // 3 × 30s 心跳间隔

// 确保数据目录存在
import fs from 'fs';
import path from 'path';
const dbDir = path.dirname(DB_PATH);
if (!fs.existsSync(dbDir)) {
  fs.mkdirSync(dbDir, { recursive: true });
}

const store = new AgentStore(DB_PATH);
const router = new MessageRouter(store);

const app = express();
const server = http.createServer(app);

// 健康检查
app.get('/api/health', (_req, res) => {
  res.json({ status: 'ok', uptime: process.uptime(), agents: store.listOnlineAgents().length });
});

// WebSocket 服务器
const wss = new WebSocketServer({ server });

// Agent ID → WebSocket 映射
const connections = new Map<string, WebSocket>();

wss.on('connection', (ws) => {
  let agentId: string | null = null;

  log.info('New WebSocket connection');

  ws.on('message', (data) => {
    try {
      const raw = JSON.parse(data.toString());
      router.route(raw, ws);
      // 记录连接（从 register 消息中获取 agentId）
      if (raw.type === 'agent.register' && raw.payload?.agentId) {
        agentId = raw.payload.agentId as string;
        connections.set(agentId, ws);
      }
    } catch (err) {
      log.error('Failed to parse message', { error: String(err) });
      ws.send(JSON.stringify({ type: 'error', payload: { message: 'Invalid JSON' } }));
    }
  });

  ws.on('close', () => {
    if (agentId) {
      store.setAgentOffline(agentId);
      store.logAudit('agent.offline', agentId);
      connections.delete(agentId);
      log.info(`Agent disconnected: ${agentId}`);
    }
  });
});

// 心跳超时检测：每 60 秒检查一次
setInterval(() => {
  const online = store.listOnlineAgents();
  const now = Date.now();
  for (const agent of online) {
    if (agent.lastSeenAt) {
      const elapsed = now - new Date(agent.lastSeenAt).getTime();
      if (elapsed > HEARTBEAT_TIMEOUT_MS) {
        log.warn(`Agent heartbeat timeout: ${agent.id} (${agent.name})`);
        store.setAgentOffline(agent.id);
        store.logAudit('agent.offline', agent.id, undefined, undefined, { reason: 'heartbeat_timeout' });
        const ws = connections.get(agent.id);
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.close(1000, 'heartbeat_timeout');
        }
        connections.delete(agent.id);
      }
    }
  }
}, 60_000);

// 优雅停机
function gracefulShutdown(signal: string) {
  log.info(`Received ${signal}, shutting down gracefully...`);
  // 广播维护通知
  for (const [id, ws] of connections) {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'server.maintenance', payload: { message: 'Server shutting down' } }));
    }
    store.setAgentOffline(id);
  }
  store.close();
  server.close(() => {
    log.info('Server closed');
    process.exit(0);
  });
  // 60 秒强制退出
  setTimeout(() => process.exit(1), 60_000);
}

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

server.listen(PORT, () => {
  log.info(`Bazaar server started on port ${PORT}`);
  log.info(`Database: ${DB_PATH}`);
});
```

- [ ] **Step 2: Commit**

```bash
git add bazaar/src/index.ts
git commit -m "feat(bazaar): add server entry point with WS, health check, graceful shutdown"
```

---

### Task 7: package.json 和 tsconfig.json

**Files:**
- Create: `bazaar/package.json`
- Create: `bazaar/tsconfig.json`

- [ ] **Step 1: 创建 package.json**

```json
{
  "name": "bazaar-server",
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "build": "tsc",
    "start": "node dist/index.js",
    "dev": "tsc --watch",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "better-sqlite3": "^11.0.0",
    "express": "^4.21.0",
    "uuid": "^11.0.0",
    "ws": "^8.18.0"
  },
  "devDependencies": {
    "@types/better-sqlite3": "^7.6.0",
    "@types/express": "^5.0.0",
    "@types/node": "^22.0.0",
    "@types/uuid": "^10.0.0",
    "@types/ws": "^8.5.0",
    "typescript": "^5.7.0",
    "vitest": "^3.0.0"
  }
}
```

- [ ] **Step 2: 创建 tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ES2022",
    "moduleResolution": "bundler",
    "outDir": "dist",
    "rootDir": "src",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "forceConsistentCasingInFileNames": true,
    "declaration": true,
    "sourceMap": true,
    "resolveJsonModule": true
  },
  "include": ["src/**/*.ts", "../shared/**/*.ts"]
}
```

- [ ] **Step 3: 创建 vitest.config.ts**

```typescript
// bazaar/vitest.config.ts
import { defineConfig } from 'vitest/config';
import path from 'path';

export default defineConfig({
  test: {
    globals: true,
  },
  resolve: {
    alias: {
      '../../shared/bazaar-types.js': path.resolve(__dirname, '../shared/bazaar-types.ts'),
    },
  },
});
```

- [ ] **Step 4: Commit**

```bash
git add bazaar/package.json bazaar/tsconfig.json bazaar/vitest.config.ts
git commit -m "feat(bazaar): add package.json, tsconfig.json, and vitest config"
```

---

### Task 8: 集成测试 — 完整连接流程

**Files:**
- Create: `bazaar/tests/integration.test.ts`

- [ ] **Step 1: 写集成测试**

```typescript
// bazaar/tests/integration.test.ts
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import http from 'http';
import express from 'express';
import { WebSocketServer } from 'ws';
import WebSocket from 'ws';
import { AgentStore } from '../src/agent-store.js';
import { MessageRouter } from '../src/message-router.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('Integration: Full Agent Connection Flow', () => {
  let server: http.Server;
  let wss: WebSocketServer;
  let store: AgentStore;
  let router: MessageRouter;
  let dbPath: string;
  let port: number;

  beforeAll(async () => {
    dbPath = path.join(os.tmpdir(), `bazaar-integration-${Date.now()}.db`);
    store = new AgentStore(dbPath);
    router = new MessageRouter(store);

    const app = express();
    server = http.createServer(app);
    wss = new WebSocketServer({ server });

    wss.on('connection', (ws) => {
      let agentId: string | null = null;
      ws.on('message', (data) => {
        const msg = JSON.parse(data.toString());
        router.route(msg, ws);
        if (msg.type === 'agent.register') {
          agentId = msg.payload.agentId;
        }
      });
      ws.on('close', () => {
        if (agentId) store.setAgentOffline(agentId);
      });
    });

    // 随机端口
    await new Promise<void>((resolve) => {
      server.listen(0, () => {
        port = (server.address() as any).port;
        resolve();
      });
    });
  });

  afterAll(async () => {
    await new Promise<void>((resolve) => wss.close(() => resolve()));
    await new Promise<void>((resolve) => server.close(() => resolve()));
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  it('should complete full register → heartbeat → update → offline flow', async () => {
    const ws = new WebSocket(`ws://localhost:${port}`);
    const received: any[] = [];

    await new Promise<void>((resolve) => { ws.on('open', () => resolve()); });
    ws.on('message', (data) => received.push(JSON.parse(data.toString())));

    // 1. Register
    ws.send(JSON.stringify({
      id: 'msg-001',
      type: 'agent.register',
      payload: {
        agentId: 'agent-test-001',
        username: 'testuser',
        hostname: 'test-host',
        name: '测试用户',
        projects: [
          { repo: 'payment-service', skills: JSON.stringify([{ id: 'pay', name: '支付', triggers: ['支付'] }]) },
        ],
        privateCapabilities: [],
      },
    }));

    await new Promise((r) => setTimeout(r, 200));
    expect(received.length).toBeGreaterThanOrEqual(2); // ack + registered

    // 验证数据库
    const agent = store.getAgent('agent-test-001');
    expect(agent).toBeDefined();
    expect(agent!.username).toBe('testuser');
    expect(agent!.status).toBe('idle');

    const projects = store.getProjects('agent-test-001');
    expect(projects).toHaveLength(1);
    expect(projects[0].repo).toBe('payment-service');

    // 2. Heartbeat
    received.length = 0;
    ws.send(JSON.stringify({
      id: 'msg-002',
      type: 'agent.heartbeat',
      payload: { agentId: 'agent-test-001', status: 'busy', activeTaskCount: 1 },
    }));

    await new Promise((r) => setTimeout(r, 200));
    const afterHeartbeat = store.getAgent('agent-test-001');
    expect(afterHeartbeat!.status).toBe('busy');

    // 3. Update
    ws.send(JSON.stringify({
      id: 'msg-003',
      type: 'agent.update',
      payload: { agentId: 'agent-test-001', status: 'idle' },
    }));

    await new Promise((r) => setTimeout(r, 200));
    const afterUpdate = store.getAgent('agent-test-001');
    expect(afterUpdate!.status).toBe('idle');

    // 4. Offline
    ws.send(JSON.stringify({
      id: 'msg-004',
      type: 'agent.offline',
      payload: { agentId: 'agent-test-001' },
    }));

    await new Promise((r) => setTimeout(r, 200));
    const afterOffline = store.getAgent('agent-test-001');
    expect(afterOffline!.status).toBe('offline');

    ws.close();
  });

  it('should handle two agents and capability search', async () => {
    // 注册两个 Agent
    const ws1 = new WebSocket(`ws://localhost:${port}`);
    const ws2 = new WebSocket(`ws://localhost:${port}`);
    await new Promise<void>((r) => { ws1.on('open', () => r()); });
    await new Promise<void>((r) => { ws2.on('open', () => r()); });

    ws1.send(JSON.stringify({
      id: 'msg-a1', type: 'agent.register',
      payload: { agentId: 'a1', username: 'user1', hostname: 'h1', name: '用户1', projects: [
        { repo: 'payment', skills: JSON.stringify([{ id: 'pay', name: '支付', triggers: ['支付', '查询'] }]) },
      ], privateCapabilities: [] },
    }));

    ws2.send(JSON.stringify({
      id: 'msg-a2', type: 'agent.register',
      payload: { agentId: 'a2', username: 'user2', hostname: 'h2', name: '用户2', projects: [
        { repo: 'risk', skills: JSON.stringify([{ id: 'risk', name: '风控', triggers: ['风控'] }]) },
      ], privateCapabilities: [] },
    }));

    await new Promise((r) => setTimeout(r, 300));

    // 搜索"支付"能力
    const results = store.findAgentsByCapability('支付');
    expect(results).toHaveLength(1);
    expect(results[0].agentId).toBe('a1');

    // 搜索"风控"能力
    const riskResults = store.findAgentsByCapability('风控');
    expect(riskResults).toHaveLength(1);
    expect(riskResults[0].agentId).toBe('a2');

    ws1.close();
    ws2.close();
  });
});
```

- [ ] **Step 2: 运行集成测试**

Run: `cd bazaar && npx vitest run tests/integration.test.ts`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add bazaar/tests/integration.test.ts
git commit -m "test(bazaar): add integration test for full agent connection flow"
```

---

### Task 9: 安装依赖并验证构建

- [ ] **Step 1: 安装依赖**

Run: `cd bazaar && pnpm install`

- [ ] **Step 2: 运行全部测试**

Run: `cd bazaar && npx vitest run`
Expected: ALL PASS

- [ ] **Step 3: 验证 TypeScript 编译**

Run: `cd bazaar && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 4: Commit（如有变更）**

```bash
git add bazaar/
git commit -m "chore(bazaar): install dependencies and verify build"
```

---

## 总结

**本计划覆盖的文件**：

| 文件 | 说明 |
|------|------|
| `shared/bazaar-types.ts` | 共享消息协议类型（Bazaar + Sman Bridge 共用） |
| `bazaar/src/utils/logger.ts` | 日志工具 |
| `bazaar/src/protocol.ts` | 消息格式校验 |
| `bazaar/src/agent-store.ts` | Agent/Project/Audit SQLite 存储 |
| `bazaar/src/message-router.ts` | WS 消息路由分发 |
| `bazaar/src/index.ts` | 服务器入口（HTTP + WS + 心跳检测 + 优雅停机） |
| `bazaar/package.json` | 独立依赖 |
| `bazaar/tsconfig.json` | 独立编译配置 |
| `bazaar/vitest.config.ts` | 测试配置 |
| `bazaar/tests/protocol.test.ts` | 协议校验测试 |
| `bazaar/tests/agent-store.test.ts` | AgentStore 测试 |
| `bazaar/tests/message-router.test.ts` | 消息路由测试 |
| `bazaar/tests/integration.test.ts` | 完整连接流程集成测试 |

**Phase 1 MVP 范围**：
- Agent 注册 + 心跳 + 项目能力上报
- 关键词能力搜索
- 消息 ID + ack
- 基础审计日志
- 优雅停机 + 心跳超时检测
- 健康检查 API

**不在本 Chunk 的范围**（后续 Chunk）：
- Task 协作流程（Chunk 2: Bridge 层）
- 前端传送门 UI（Chunk 3）
- 语义搜索、声望系统、像素世界
