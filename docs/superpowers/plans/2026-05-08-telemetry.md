# Sman 遥测系统实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Sman 添加遥测能力 + 整合自动更新服务。新建 sman-server 收集使用统计、支持广播通知、托管更新包。

**Architecture:** Client 每小时 HTTP POST 上报心跳（hostname@ip、版本、活跃会话数）+ 拉取广播。Server 独立部署 Express + SQLite，AES-256-GCM 加密通讯，同时通过静态文件服务托管 electron-updater 更新包。所有网络请求 try-catch，失败静默。更新 URL 通过 config.json 持久化，Electron 启动时读取并设置。

**Tech Stack:** Node.js, TypeScript, Express, better-sqlite3, Node.js crypto (AES-256-GCM)

**Spec:** `docs/superpowers/specs/2026-05-08-telemetry-design.md`

---

## Chunk 1: sman-server 服务端

> 独立项目，建在 `/Users/nasakim/projects/sman-server/`

### Task 1: 项目初始化 + 类型定义 + 加密模块

**Files:**
- Create: `sman-server/package.json`
- Create: `sman-server/tsconfig.json`
- Create: `sman-server/.env.example`
- Create: `sman-server/.gitignore`
- Create: `sman-server/src/types.ts`
- Create: `sman-server/src/crypto.ts`
- Test: `sman-server/src/__tests__/crypto.test.ts`

- [ ] **Step 1: 初始化项目**

```bash
cd /Users/nasakim/projects && mkdir -p sman-server/src/__tests__ sman-server/src/routes sman-server/data && cd sman-server
```

- [ ] **Step 2: 创建 package.json**

```json
{
  "name": "sman-server",
  "version": "1.0.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "tsx src/index.ts",
    "build": "tsc",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "better-sqlite3": "^11.7.0",
    "dotenv": "^16.4.7",
    "express": "^5.1.0"
  },
  "devDependencies": {
    "@types/better-sqlite3": "^7.6.12",
    "@types/express": "^5.0.0",
    "@types/node": "^22.10.0",
    "tsx": "^4.19.0",
    "typescript": "^5.7.0",
    "vitest": "^3.0.0"
  }
}
```

- [ ] **Step 3: 创建 tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ES2022",
    "moduleResolution": "node",
    "outDir": "dist",
    "rootDir": "src",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "declaration": true
  },
  "include": ["src"],
  "exclude": ["node_modules", "dist"]
}
```

- [ ] **Step 4: 创建 .env.example 和 .gitignore**

`.env.example`:
```
PSK=changeme-32-bytes-psk-key-here!!!!!
PSK_VERSION=1
PORT=5882
ADMIN_TOKEN=changeme-admin-token
```

`.gitignore`:
```
node_modules/
dist/
data/
.env
```

- [ ] **Step 5: 创建 src/types.ts**

```typescript
export interface ReportPayload {
  clientId: string;
  version: string;
  hostname: string;
  ip: string;
  reportTime: string;
  activeSessions: number;
}

export interface BroadcastQueryPayload {
  clientId: string;
  since: string;
}

export interface AckPayload {
  clientId: string;
  broadcastIds: string[];
}

export interface BroadcastMessage {
  id: string;
  title: string;
  body: string;
  createdAt: string;
}

export interface BroadcastResponse {
  messages: BroadcastMessage[];
  hasMore: boolean;
}

export interface EncryptedRequest {
  payload: string;
  timestamp: number;
  pskVersion: number;
}

export interface ClientRecord {
  client_id: string;
  version: string | null;
  hostname: string | null;
  ip: string | null;
  first_seen: string;
  last_seen: string;
  active_sessions: number;
}

export interface AdminStats {
  totalClients: number;
  onlineClients: number;
  totalReports24h: number;
  activeBroadcasts: number;
}
```

- [ ] **Step 6: 写加密模块测试 `src/__tests__/crypto.test.ts`**

```typescript
import { describe, it, expect } from 'vitest';
import { encrypt, decrypt } from '../crypto.js';

const PSK = '0123456789abcdef0123456789abcdef';

describe('crypto', () => {
  it('should encrypt then decrypt to original', () => {
    const data = { hello: 'world', num: 42 };
    const encrypted = encrypt(data, PSK);
    const decrypted = decrypt(encrypted, PSK);
    expect(decrypted).toEqual(data);
  });

  it('should produce different ciphertext each time (random IV)', () => {
    const data = { same: 'data' };
    const a = encrypt(data, PSK);
    const b = encrypt(data, PSK);
    expect(a).not.toBe(b);
  });

  it('should fail to decrypt with wrong key', () => {
    const data = { secret: 'test' };
    const encrypted = encrypt(data, PSK);
    expect(() => decrypt(encrypted, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')).toThrow();
  });

  it('should fail to decrypt corrupted data', () => {
    expect(() => decrypt('not-valid-base64!!!', PSK)).toThrow();
  });

  it('should handle empty objects', () => {
    const data = {};
    const encrypted = encrypt(data, PSK);
    const decrypted = decrypt(encrypted, PSK);
    expect(decrypted).toEqual(data);
  });
});
```

- [ ] **Step 7: 实现 src/crypto.ts**

```typescript
import crypto from 'node:crypto';

const ALGORITHM = 'aes-256-gcm';
const IV_LENGTH = 12;
const AUTH_TAG_LENGTH = 16;

export function encrypt(data: unknown, psk: string): string {
  const iv = crypto.randomBytes(IV_LENGTH);
  const key = Buffer.from(psk, 'utf-8');
  const cipher = crypto.createCipheriv(ALGORITHM, key, iv, { authTagLength: AUTH_TAG_LENGTH });
  const plaintext = JSON.stringify(data);
  const encrypted = Buffer.concat([cipher.update(plaintext, 'utf-8'), cipher.final()]);
  const authTag = cipher.getAuthTag();
  return Buffer.concat([iv, encrypted, authTag]).toString('base64');
}

export function decrypt(encoded: string, psk: string): unknown {
  const buf = Buffer.from(encoded, 'base64');
  const key = Buffer.from(psk, 'utf-8');
  const iv = buf.subarray(0, IV_LENGTH);
  const authTag = buf.subarray(buf.length - AUTH_TAG_LENGTH);
  const ciphertext = buf.subarray(IV_LENGTH, buf.length - AUTH_TAG_LENGTH);
  const decipher = crypto.createDecipheriv(ALGORITHM, key, iv, { authTagLength: AUTH_TAG_LENGTH });
  decipher.setAuthTag(authTag);
  const decrypted = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
  return JSON.parse(decrypted.toString('utf-8'));
}
```

- [ ] **Step 8: 安装依赖并运行测试**

```bash
cd /Users/nasakim/projects/sman-server && pnpm install && pnpm test
```

- [ ] **Step 9: 提交**

```bash
cd /Users/nasakim/projects/sman-server && git init && git add -A && git commit -m "feat: init sman-server with types and crypto module"
```

---

### Task 2: 数据库模块

**Depends on:** Task 1

**Files:**
- Create: `sman-server/src/db.ts`
- Test: `sman-server/src/__tests__/db.test.ts`

- [ ] **Step 1: 写数据库测试 `src/__tests__/db.test.ts`**

```typescript
import { describe, it, expect, beforeEach } from 'vitest';
import { TelemetryDB } from '../db.js';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';

let db: TelemetryDB;
let tmpDir: string;

beforeEach(() => {
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
  db = new TelemetryDB(path.join(tmpDir, 'test.db'));
});

describe('TelemetryDB', () => {
  describe('upsertClient', () => {
    it('should insert new client', () => {
      db.upsertClient({
        clientId: 'host@1.2.3.4',
        version: '1.0.0',
        hostname: 'host',
        ip: '1.2.3.4',
        activeSessions: 3,
      });
      const client = db.getClient('host@1.2.3.4');
      expect(client).toBeDefined();
      expect(client!.client_id).toBe('host@1.2.3.4');
      expect(client!.active_sessions).toBe(3);
    });

    it('should update existing client', () => {
      db.upsertClient({ clientId: 'host@1.2.3.4', version: '1.0', hostname: 'host', ip: '1.2.3.4', activeSessions: 1 });
      db.upsertClient({ clientId: 'host@1.2.3.4', version: '1.1', hostname: 'host', ip: '1.2.3.4', activeSessions: 5 });
      const client = db.getClient('host@1.2.3.4');
      expect(client!.version).toBe('1.1');
      expect(client!.active_sessions).toBe(5);
    });
  });

  describe('insertReport', () => {
    it('should insert and query reports', () => {
      db.upsertClient({ clientId: 'host@1.2.3.4', version: '1.0', hostname: 'host', ip: '1.2.3.4', activeSessions: 0 });
      db.insertReport({ clientId: 'host@1.2.3.4', reportTime: '2026-05-08T14:00:00Z', activeSessions: 3 });
      const reports = db.getReportsByClientId('host@1.2.3.4');
      expect(reports).toHaveLength(1);
      expect(reports[0].active_sessions).toBe(3);
    });
  });

  describe('broadcasts', () => {
    it('should CRUD broadcasts', () => {
      db.createBroadcast({ id: 'bc_001', title: 'Test', body: 'Hello', createdAt: '2026-05-08T10:00:00Z' });
      let bcs = db.getActiveBroadcasts();
      expect(bcs).toHaveLength(1);
      expect(bcs[0].title).toBe('Test');

      db.deactivateBroadcast('bc_001');
      bcs = db.getActiveBroadcasts();
      expect(bcs).toHaveLength(0);
    });

    it('should get broadcasts since a given time', () => {
      db.createBroadcast({ id: 'bc_001', title: 'Old', body: 'Old', createdAt: '2026-05-07T10:00:00Z' });
      db.createBroadcast({ id: 'bc_002', title: 'New', body: 'New', createdAt: '2026-05-08T10:00:00Z' });
      const since = db.getBroadcastsSince('2026-05-08T00:00:00Z');
      expect(since).toHaveLength(1);
      expect(since[0].id).toBe('bc_002');
    });
  });

  describe('readLog', () => {
    it('should mark broadcasts as read', () => {
      db.upsertClient({ clientId: 'host@1.2.3.4', version: '1.0', hostname: 'host', ip: '1.2.3.4', activeSessions: 0 });
      db.createBroadcast({ id: 'bc_001', title: 'T', body: 'B', createdAt: '2026-05-08T10:00:00Z' });
      db.markAsRead({ clientId: 'host@1.2.3.4', broadcastId: 'bc_001' });
      const readIds = db.getReadBroadcastIds('host@1.2.3.4');
      expect(readIds).toContain('bc_001');
    });
  });

  describe('stats', () => {
    it('should return admin stats', () => {
      db.upsertClient({ clientId: 'a@1', version: '1.0', hostname: 'a', ip: '1', activeSessions: 2 });
      db.upsertClient({ clientId: 'b@2', version: '1.0', hostname: 'b', ip: '2', activeSessions: 0 });
      const stats = db.getStats();
      expect(stats.totalClients).toBe(2);
    });
  });
});
```

- [ ] **Step 2: 实现 src/db.ts**

```typescript
import Database from 'better-sqlite3';
import path from 'node:path';
import fs from 'node:fs';
import type { ClientRecord, AdminStats } from './types.js';

interface UpsertClientParams {
  clientId: string;
  version: string;
  hostname: string;
  ip: string;
  activeSessions: number;
}

interface InsertReportParams {
  clientId: string;
  reportTime: string;
  activeSessions: number;
}

interface CreateBroadcastParams {
  id: string;
  title: string;
  body: string;
  createdAt: string;
}

interface BroadcastRow {
  id: string;
  title: string;
  body: string;
  created_at: string;
  active: number;
}

interface ReportRow {
  id: number;
  client_id: string;
  report_time: string;
  active_sessions: number;
}

export class TelemetryDB {
  private db: Database.Database;

  constructor(dbPath: string) {
    fs.mkdirSync(path.dirname(dbPath), { recursive: true });
    this.db = new Database(dbPath);
    this.db.pragma('journal_mode = WAL');
    this.initTables();
  }

  private initTables(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS clients (
        client_id TEXT PRIMARY KEY,
        version TEXT,
        hostname TEXT,
        ip TEXT,
        first_seen TEXT NOT NULL DEFAULT (datetime('now')),
        last_seen TEXT NOT NULL DEFAULT (datetime('now')),
        active_sessions INTEGER DEFAULT 0
      );

      CREATE TABLE IF NOT EXISTS reports (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        client_id TEXT NOT NULL,
        report_time TEXT NOT NULL,
        active_sessions INTEGER DEFAULT 0,
        FOREIGN KEY (client_id) REFERENCES clients(client_id)
      );

      CREATE TABLE IF NOT EXISTS broadcasts (
        id TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        body TEXT NOT NULL,
        created_at TEXT NOT NULL,
        active INTEGER DEFAULT 1
      );

      CREATE TABLE IF NOT EXISTS read_log (
        client_id TEXT NOT NULL,
        broadcast_id TEXT NOT NULL,
        read_at TEXT NOT NULL DEFAULT (datetime('now')),
        PRIMARY KEY (client_id, broadcast_id)
      );

      CREATE INDEX IF NOT EXISTS idx_reports_client ON reports(client_id);
      CREATE INDEX IF NOT EXISTS idx_reports_time ON reports(report_time);
      CREATE INDEX IF NOT EXISTS idx_broadcasts_active ON broadcasts(active);
    `);
  }

  upsertClient(params: UpsertClientParams): void {
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO clients (client_id, version, hostname, ip, first_seen, last_seen, active_sessions)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(client_id) DO UPDATE SET
        version = excluded.version,
        hostname = excluded.hostname,
        ip = excluded.ip,
        last_seen = excluded.last_seen,
        active_sessions = excluded.active_sessions
    `).run(params.clientId, params.version, params.hostname, params.ip, now, now, params.activeSessions);
  }

  getClient(clientId: string): ClientRecord | undefined {
    return this.db.prepare('SELECT * FROM clients WHERE client_id = ?').get(clientId) as ClientRecord | undefined;
  }

  getAllClients(): ClientRecord[] {
    return this.db.prepare('SELECT * FROM clients ORDER BY last_seen DESC').all() as ClientRecord[];
  }

  insertReport(params: InsertReportParams): void {
    this.db.prepare(
      'INSERT INTO reports (client_id, report_time, active_sessions) VALUES (?, ?, ?)'
    ).run(params.clientId, params.reportTime, params.activeSessions);
  }

  getReportsByClientId(clientId: string): ReportRow[] {
    return this.db.prepare(
      'SELECT * FROM reports WHERE client_id = ? ORDER BY report_time DESC LIMIT 100'
    ).all(clientId) as ReportRow[];
  }

  createBroadcast(params: CreateBroadcastParams): void {
    this.db.prepare(
      'INSERT INTO broadcasts (id, title, body, created_at) VALUES (?, ?, ?, ?)'
    ).run(params.id, params.title, params.body, params.createdAt);
  }

  deactivateBroadcast(id: string): void {
    this.db.prepare('UPDATE broadcasts SET active = 0 WHERE id = ?').run(id);
  }

  getActiveBroadcasts(): BroadcastRow[] {
    return this.db.prepare(
      "SELECT * FROM broadcasts WHERE active = 1 ORDER BY created_at DESC"
    ).all() as BroadcastRow[];
  }

  getBroadcastsSince(since: string): BroadcastRow[] {
    return this.db.prepare(
      "SELECT * FROM broadcasts WHERE active = 1 AND created_at > ? ORDER BY created_at DESC"
    ).all(since) as BroadcastRow[];
  }

  markAsRead(params: { clientId: string; broadcastId: string }): void {
    this.db.prepare(
      'INSERT OR IGNORE INTO read_log (client_id, broadcast_id) VALUES (?, ?)'
    ).run(params.clientId, params.broadcastId);
  }

  getReadBroadcastIds(clientId: string): string[] {
    const rows = this.db.prepare(
      'SELECT broadcast_id FROM read_log WHERE client_id = ?'
    ).all(clientId) as { broadcast_id: string }[];
    return rows.map(r => r.broadcast_id);
  }

  getStats(): AdminStats {
    const totalClients = (this.db.prepare('SELECT COUNT(*) as c FROM clients').get() as { c: number }).c;
    const onlineClients = (this.db.prepare(
      "SELECT COUNT(*) as c FROM clients WHERE last_seen > datetime('now', '-1 hour')"
    ).get() as { c: number }).c;
    const totalReports24h = (this.db.prepare(
      "SELECT COUNT(*) as c FROM reports WHERE report_time > datetime('now', '-24 hours')"
    ).get() as { c: number }).c;
    const activeBroadcasts = (this.db.prepare(
      "SELECT COUNT(*) as c FROM broadcasts WHERE active = 1"
    ).get() as { c: number }).c;
    return { totalClients, onlineClients, totalReports24h, activeBroadcasts };
  }

  close(): void {
    this.db.close();
  }
}
```

- [ ] **Step 3: 运行测试**

```bash
cd /Users/nasakim/projects/sman-server && pnpm test
```

- [ ] **Step 4: 提交**

```bash
cd /Users/nasakim/projects/sman-server && git add -A && git commit -m "feat: add telemetry database module"
```

---

### Task 3: API 路由 + Express 入口

**Depends on:** Task 2

**Files:**
- Create: `sman-server/src/routes/report.ts`
- Create: `sman-server/src/routes/broadcast.ts`
- Create: `sman-server/src/routes/admin.ts`
- Create: `sman-server/src/index.ts`

- [ ] **Step 1: 创建 src/routes/report.ts**

```typescript
import { Router } from 'express';
import type { Request, Response } from 'express';
import type { TelemetryDB } from '../db.js';
import { decrypt } from '../crypto.js';
import type { ReportPayload, EncryptedRequest } from '../types.js';

const REPLAY_WINDOW_MS = 5 * 60 * 1000; // 5 minutes

export function createReportRouter(db: TelemetryDB, psk: string): Router {
  const router = Router();

  router.post('/report', (req: Request, res: Response) => {
    try {
      const { payload, timestamp, pskVersion } = req.body as EncryptedRequest;

      if (pskVersion !== 1) {
        res.status(400).json({ error: 'Unsupported PSK version' });
        return;
      }

      const now = Date.now();
      if (Math.abs(now - timestamp * 1000) > REPLAY_WINDOW_MS) {
        res.status(400).json({ error: 'Timestamp out of range' });
        return;
      }

      const data = decrypt(payload, psk) as ReportPayload;

      db.upsertClient({
        clientId: data.clientId,
        version: data.version,
        hostname: data.hostname,
        ip: data.ip,
        activeSessions: data.activeSessions,
      });

      db.insertReport({
        clientId: data.clientId,
        reportTime: data.reportTime,
        activeSessions: data.activeSessions,
      });

      res.json({ ok: true, serverTime: new Date().toISOString() });
    } catch (err) {
      res.status(400).json({ error: 'Invalid request' });
    }
  });

  return router;
}
```

- [ ] **Step 2: 创建 src/routes/broadcast.ts**

```typescript
import { Router } from 'express';
import type { Request, Response } from 'express';
import type { TelemetryDB } from '../db.js';
import { decrypt, encrypt } from '../crypto.js';
import type { BroadcastQueryPayload, AckPayload, EncryptedRequest } from '../types.js';

const REPLAY_WINDOW_MS = 5 * 60 * 1000;

export function createBroadcastRouter(db: TelemetryDB, psk: string): Router {
  const router = Router();

  router.post('/broadcasts', (req: Request, res: Response) => {
    try {
      const { payload, timestamp, pskVersion } = req.body as EncryptedRequest;

      if (pskVersion !== 1) {
        res.status(400).json({ error: 'Unsupported PSK version' });
        return;
      }

      if (Math.abs(Date.now() - timestamp * 1000) > REPLAY_WINDOW_MS) {
        res.status(400).json({ error: 'Timestamp out of range' });
        return;
      }

      const data = decrypt(payload, psk) as BroadcastQueryPayload;
      const rows = db.getBroadcastsSince(data.since);
      const readIds = new Set(db.getReadBroadcastIds(data.clientId));
      const messages = rows
        .filter(r => !readIds.has(r.id))
        .map(r => ({ id: r.id, title: r.title, body: r.body, createdAt: r.created_at }));

      const responsePayload = encrypt({ messages, hasMore: false }, psk);
      res.json({ payload: responsePayload });
    } catch (err) {
      res.status(400).json({ error: 'Invalid request' });
    }
  });

  router.post('/ack', (req: Request, res: Response) => {
    try {
      const { payload, timestamp, pskVersion } = req.body as EncryptedRequest;

      if (pskVersion !== 1) {
        res.status(400).json({ error: 'Unsupported PSK version' });
        return;
      }

      if (Math.abs(Date.now() - timestamp * 1000) > REPLAY_WINDOW_MS) {
        res.status(400).json({ error: 'Timestamp out of range' });
        return;
      }

      const data = decrypt(payload, psk) as AckPayload;
      for (const bid of data.broadcastIds) {
        db.markAsRead({ clientId: data.clientId, broadcastId: bid });
      }

      res.json({ ok: true });
    } catch (err) {
      res.status(400).json({ error: 'Invalid request' });
    }
  });

  return router;
}
```

- [ ] **Step 3: 创建 src/routes/admin.ts**

```typescript
import { Router } from 'express';
import type { Request, Response } from 'express';
import type { TelemetryDB } from '../db.js';
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

export function createAdminRouter(db: TelemetryDB, adminToken: string, updatesDir: string): Router {
  const router = Router();

  router.use((req: Request, res: Response, next) => {
    const auth = req.headers.authorization;
    if (auth !== `Bearer ${adminToken}`) {
      res.status(401).json({ error: 'Unauthorized' });
      return;
    }
    next();
  });

  router.post('/broadcast', (req: Request, res: Response) => {
    const { id, title, body } = req.body;
    if (!id || !title || !body) {
      res.status(400).json({ error: 'id, title, body required' });
      return;
    }
    db.createBroadcast({ id, title, body, createdAt: new Date().toISOString() });
    res.json({ ok: true });
  });

  router.delete('/broadcast/:id', (req: Request, res: Response) => {
    db.deactivateBroadcast(req.params.id);
    res.json({ ok: true });
  });

  router.get('/stats', (_req: Request, res: Response) => {
    res.json(db.getStats());
  });

  router.get('/clients', (_req: Request, res: Response) => {
    res.json(db.getAllClients());
  });

  // 上传更新包 — multipart form 解析用 express 原始 body
  router.put('/upload', (req: Request, res: Response) => {
    const chunks: Buffer[] = [];
    req.on('data', (chunk: Buffer) => chunks.push(chunk));
    req.on('end', () => {
      const filename = req.query.filename as string;
      if (!filename) {
        res.status(400).json({ error: 'filename query param required' });
        return;
      }
      // 只允许 yml, dmg, exe 扩展名
      const ext = path.extname(filename).toLowerCase();
      if (!['.yml', '.dmg', '.exe', '.blockmap'].includes(ext)) {
        res.status(400).json({ error: 'Unsupported file type' });
        return;
      }
      const targetPath = path.join(updatesDir, path.basename(filename));
      fs.writeFileSync(targetPath, Buffer.concat(chunks));
      res.json({ ok: true, path: `/updates/sman/${path.basename(filename)}` });
    });
    req.on('error', () => res.status(500).json({ error: 'Upload failed' }));
  });

  return router;
}
```

> 上传用 `PUT /admin/upload?filename=latest.yml`，body 是文件原始内容。生产环境可以用 scp/sftp 直接传文件到 `data/updates/sman/`，更简单。

- [ ] **Step 4: 创建 src/index.ts**

```typescript
import 'dotenv/config';
import express from 'express';
import path from 'node:path';
import fs from 'node:fs';
import { TelemetryDB } from './db.js';
import { createReportRouter } from './routes/report.js';
import { createBroadcastRouter } from './routes/broadcast.js';
import { createAdminRouter } from './routes/admin.js';

const PORT = parseInt(process.env.PORT || '5882', 10);
const PSK = process.env.PSK;
const ADMIN_TOKEN = process.env.ADMIN_TOKEN;
const DATA_DIR = path.resolve(process.cwd(), 'data');

if (!PSK || PSK.length !== 32) {
  console.error('ERROR: PSK must be exactly 32 characters. Set PSK in .env');
  process.exit(1);
}

if (!ADMIN_TOKEN) {
  console.error('ERROR: ADMIN_TOKEN must be set in .env');
  process.exit(1);
}

// 确保更新文件目录存在
const updatesDir = path.join(DATA_DIR, 'updates', 'sman');
fs.mkdirSync(updatesDir, { recursive: true });

const db = new TelemetryDB(path.join(DATA_DIR, 'telemetry.db'));
const app = express();

app.use(express.json({ limit: '1mb' }));

// 更新文件静态服务 — electron-updater 从这里下载 latest.yml 和安装包
app.use('/updates/sman', express.static(updatesDir));

app.use('/api', createReportRouter(db, PSK));
app.use('/api', createBroadcastRouter(db, PSK));
app.use('/admin', createAdminRouter(db, ADMIN_TOKEN, updatesDir));

app.get('/health', (_req, res) => res.json({ ok: true }));

const server = app.listen(PORT, () => {
  console.log(`sman-server listening on port ${PORT}`);
  console.log(`Updates served at /updates/sman`);
});

process.on('SIGTERM', () => {
  server.close(() => {
    db.close();
    process.exit(0);
  });
});

process.on('SIGINT', () => {
  server.close(() => {
    db.close();
    process.exit(0);
  });
});
```

- [ ] **Step 5: 创建 .env 文件用于开发**

```
PSK=sman-telemetry-psk-2026-key!!32b!
PSK_VERSION=1
PORT=5882
ADMIN_TOKEN=sman-admin-token-2026
```

- [ ] **Step 6: 运行测试确认通过**

```bash
cd /Users/nasakim/projects/sman-server && pnpm test
```

- [ ] **Step 7: 启动 server 手动验证**

```bash
cd /Users/nasakim/projects/sman-server && pnpm dev
```

验证 `curl http://localhost:5882/health` 返回 `{"ok":true}`

- [ ] **Step 8: 提交**

```bash
cd /Users/nasakim/projects/sman-server && git add -A && git commit -m "feat: add API routes and Express entry point"
```

---

## Chunk 2: Sman Client 端集成

> 修改 `/Users/nasakim/projects/sman/` 项目

### Task 4: SmanConfig 类型扩展 + Telemetry 加密/客户端模块

**Depends on:** Task 1 (需要 crypto 模块的 client 端实现)

**Files:**
- Modify: `server/types.ts` — 添加 TelemetryConfig 类型
- Modify: `server/settings-manager.ts` — 添加 telemetry 默认配置
- Modify: `server/session-store.ts` — 添加 `getActiveSessionCount()` 方法
- Create: `server/telemetry/crypto.ts`
- Create: `server/telemetry/types.ts`
- Create: `server/telemetry/client.ts`
- Create: `server/telemetry/index.ts`
- Test: `server/telemetry/__tests__/client.test.ts`

- [ ] **Step 1: 在 server/types.ts 的 SmanConfig 接口添加 telemetry 字段**

在 `SmanConfig` 接口中（约 line 76 之后）添加:
```typescript
  telemetry?: {
    serverUrl: string;
    updateUrl: string;
    enabled: boolean;
  };
```

- `serverUrl`: 遥测服务器地址（如 `https://your-server.com`）
- `updateUrl`: electron-updater feed URL（如 `https://your-server.com/updates/sman`）
- `enabled`: 是否启用遥测

- [ ] **Step 2: 在 server/settings-manager.ts 的 DEFAULT_CONFIG 添加 telemetry 默认值**

在 `DEFAULT_CONFIG` 对象中添加:
```typescript
  telemetry: {
    serverUrl: '',
    updateUrl: '',
    enabled: false,
  },
```

- [ ] **Step 2b: 在 server/session-store.ts 添加 getActiveSessionCount 方法**

在 `SessionStore` 类中添加公共方法（替代直接访问内部 db）:
```typescript
getActiveSessionCount(): number {
  const row = this.db.prepare(
    "SELECT COUNT(*) as c FROM sessions WHERE last_active_at > datetime('now', '-1 hour') AND deleted_at IS NULL"
  ).get() as { c: number };
  return row.c;
}
```

- [ ] **Step 3: 创建 server/telemetry/types.ts**

```typescript
export interface TelemetryConfig {
  serverUrl: string;
  enabled: boolean;
}

export interface ReportPayload {
  clientId: string;
  version: string;
  hostname: string;
  ip: string;
  reportTime: string;
  activeSessions: number;
}

export interface BroadcastQueryPayload {
  clientId: string;
  since: string;
}

export interface AckPayload {
  clientId: string;
  broadcastIds: string[];
}

export interface BroadcastMessage {
  id: string;
  title: string;
  body: string;
  createdAt: string;
}

export interface EncryptedRequest {
  payload: string;
  timestamp: number;
  pskVersion: number;
}
```

- [ ] **Step 4: 创建 server/telemetry/crypto.ts**

```typescript
import crypto from 'node:crypto';

const ALGORITHM = 'aes-256-gcm';
const IV_LENGTH = 12;
const AUTH_TAG_LENGTH = 16;

// PSK 硬编码 — 编译后嵌入 Electron 包
const PSK = 'sman-telemetry-psk-2026-key!!32b!';
const PSK_VERSION = 1;

export function encrypt(data: unknown): string {
  const iv = crypto.randomBytes(IV_LENGTH);
  const key = Buffer.from(PSK, 'utf-8');
  const cipher = crypto.createCipheriv(ALGORITHM, key, iv, { authTagLength: AUTH_TAG_LENGTH });
  const plaintext = JSON.stringify(data);
  const encrypted = Buffer.concat([cipher.update(plaintext, 'utf-8'), cipher.final()]);
  const authTag = cipher.getAuthTag();
  return Buffer.concat([iv, encrypted, authTag]).toString('base64');
}

export function decrypt(encoded: string): unknown {
  const buf = Buffer.from(encoded, 'base64');
  const key = Buffer.from(PSK, 'utf-8');
  const iv = buf.subarray(0, IV_LENGTH);
  const authTag = buf.subarray(buf.length - AUTH_TAG_LENGTH);
  const ciphertext = buf.subarray(IV_LENGTH, buf.length - AUTH_TAG_LENGTH);
  const decipher = crypto.createDecipheriv(ALGORITHM, key, iv, { authTagLength: AUTH_TAG_LENGTH });
  decipher.setAuthTag(authTag);
  const decrypted = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
  return JSON.parse(decrypted.toString('utf-8'));
}

export function buildEncryptedRequest(payload: unknown): EncryptedRequest {
  return {
    payload: encrypt(payload),
    timestamp: Math.floor(Date.now() / 1000),
    pskVersion: PSK_VERSION,
  };
}
```

- [ ] **Step 5: 创建 server/telemetry/client.ts**

```typescript
import os from 'node:os';
import type { SessionStore } from '../session-store.js';
import type { BroadcastMessage, ReportPayload, BroadcastQueryPayload, AckPayload } from './types.js';
import { buildEncryptedRequest, decrypt } from './crypto.js';

const TIMEOUT_MS = 5000;
const REPORT_INTERVAL_MS = 60 * 60 * 1000; // 1 hour

interface TelemetryDeps {
  getServerUrl: () => string;
  getEnabled: () => boolean;
  getVersion: () => string;
  sessionStore: SessionStore;
  onBroadcast: (messages: BroadcastMessage[]) => void;
}

export class TelemetryClient {
  private deps: TelemetryDeps;
  private timer: ReturnType<typeof setInterval> | null = null;
  private lastBroadcastFetch: string;

  constructor(deps: TelemetryDeps) {
    this.deps = deps;
    this.lastBroadcastFetch = new Date(0).toISOString();
  }

  start(): void {
    if (!this.deps.getEnabled() || !this.deps.getServerUrl()) return;
    this.reportHeartbeat();
    this.timer = setInterval(() => {
      this.reportHeartbeat();
      this.fetchBroadcasts();
    }, REPORT_INTERVAL_MS);
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  private getClientId(): string {
    const hostname = os.hostname();
    const nets = os.networkInterfaces();
    let ip = '127.0.0.1';
    for (const name of Object.keys(nets)) {
      for (const net of nets[name] || []) {
        if (net.family === 'IPv4' && !net.internal) {
          ip = net.address;
          break;
        }
      }
    }
    return `${hostname}@${ip}`;
  }

  private getActiveSessionCount(): number {
    return this.deps.sessionStore.getActiveSessionCount();
  }

  async reportHeartbeat(): Promise<void> {
    try {
      const payload: ReportPayload = {
        clientId: this.getClientId(),
        version: this.deps.getVersion(),
        hostname: os.hostname(),
        ip: this.getClientId().split('@')[1],
        reportTime: new Date().toISOString(),
        activeSessions: this.getActiveSessionCount(),
      };

      const url = `${this.deps.getServerUrl()}/api/report`;
      const controller = new AbortController();
      const tid = setTimeout(() => controller.abort(), TIMEOUT_MS);

      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildEncryptedRequest(payload)),
        signal: controller.signal,
      });

      clearTimeout(tid);
      if (!res.ok) {
        console.error(`[telemetry] report failed: ${res.status}`);
      }
    } catch {
      // 静默失败
    }
  }

  async fetchBroadcasts(): Promise<void> {
    try {
      const payload: BroadcastQueryPayload = {
        clientId: this.getClientId(),
        since: this.lastBroadcastFetch,
      };

      const url = `${this.deps.getServerUrl()}/api/broadcasts`;
      const controller = new AbortController();
      const tid = setTimeout(() => controller.abort(), TIMEOUT_MS);

      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildEncryptedRequest(payload)),
        signal: controller.signal,
      });

      clearTimeout(tid);
      if (!res.ok) return;

      const body = await res.json();
      const data = decrypt(body.payload) as { messages: BroadcastMessage[]; hasMore: boolean };

      if (data.messages.length > 0) {
        this.lastBroadcastFetch = new Date().toISOString();
        this.deps.onBroadcast(data.messages);
      }
    } catch {
      // 静默失败
    }
  }

  async ackBroadcasts(ids: string[]): Promise<void> {
    if (ids.length === 0) return;
    try {
      const payload: AckPayload = {
        clientId: this.getClientId(),
        broadcastIds: ids,
      };

      const url = `${this.deps.getServerUrl()}/api/ack`;
      const controller = new AbortController();
      const tid = setTimeout(() => controller.abort(), TIMEOUT_MS);

      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildEncryptedRequest(payload)),
        signal: controller.signal,
      });

      clearTimeout(tid);
      if (!res.ok) {
        console.error(`[telemetry] ack failed: ${res.status}`);
      }
    } catch {
      // 静默失败
    }
  }
}
```

- [ ] **Step 6: 创建 server/telemetry/index.ts**

```typescript
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { SessionStore } from '../session-store.js';
import type { SettingsManager } from '../settings-manager.js';
import { TelemetryClient } from './client.js';
import type { BroadcastMessage } from './types.js';

let telemetryClient: TelemetryClient | null = null;

function getVersion(): string {
  try {
    const __dirname = path.dirname(fileURLToPath(import.meta.url));
    const pkgPath = path.resolve(__dirname, '../../package.json');
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8'));
    return pkg.version || 'unknown';
  } catch {
    return 'unknown';
  }
}

export function initTelemetry(
  settingsManager: SettingsManager,
  sessionStore: SessionStore,
  onBroadcast: (messages: BroadcastMessage[]) => void,
): void {
  const config = settingsManager.getConfig();
  const telemetry = config.telemetry;

  if (!telemetry?.enabled || !telemetry?.serverUrl) return;

  telemetryClient = new TelemetryClient({
    getServerUrl: () => settingsManager.getConfig().telemetry?.serverUrl || '',
    getEnabled: () => settingsManager.getConfig().telemetry?.enabled ?? false,
    getVersion,
    sessionStore,
    onBroadcast,
  });

  telemetryClient.start();
}

export function stopTelemetry(): void {
  telemetryClient?.stop();
  telemetryClient = null;
}

export function getTelemetryClient(): TelemetryClient | null {
  return telemetryClient;
}
```

- [ ] **Step 7: 写测试 server/telemetry/__tests__/client.test.ts**

```typescript
import { describe, it, expect } from 'vitest';
import { encrypt, decrypt, buildEncryptedRequest } from '../crypto.js';

const PSK = 'sman-telemetry-psk-2026-key!!32b!';

// 注意: client 端 crypto.ts 使用硬编码 PSK，测试时直接 import
describe('telemetry crypto', () => {
  it('should encrypt and decrypt round-trip', () => {
    const data = { clientId: 'test@1.2.3.4', version: '1.0' };
    const encrypted = encrypt(data);
    const decrypted = decrypt(encrypted);
    expect(decrypted).toEqual(data);
  });

  it('buildEncryptedRequest should produce valid structure', () => {
    const data = { test: true };
    const req = buildEncryptedRequest(data);
    expect(req).toHaveProperty('payload');
    expect(req).toHaveProperty('timestamp');
    expect(req).toHaveProperty('pskVersion', 1);
    expect(typeof req.payload).toBe('string');
    expect(req.timestamp).toBeCloseTo(Math.floor(Date.now() / 1000), -1);
  });
});
```

- [ ] **Step 8: 运行测试**

```bash
cd /Users/nasakim/projects/sman && pnpm test
```

- [ ] **Step 9: 提交**

```bash
cd /Users/nasakim/projects/sman && git add server/types.ts server/settings-manager.ts server/telemetry/ && git commit -m "feat: add telemetry client module"
```

---

### Task 5: 集成到 server/index.ts — 启动遥测 + WebSocket 广播推送

**Depends on:** Task 4

**Files:**
- Modify: `server/index.ts` — import telemetry, 在 server.listen 后调用 initTelemetry

- [ ] **Step 1: 在 server/index.ts 添加 import**

在文件顶部 import 区域添加:
```typescript
import { initTelemetry, stopTelemetry, getTelemetryClient } from './telemetry/index.js';
```

- [ ] **Step 2: 添加遥测广播处理函数**

在 broadcast 函数（约 line 236）附近添加:
```typescript
function broadcastTelemetryMessages(messages: Array<{ id: string; title: string; body: string; createdAt: string }>): void {
  for (const msg of messages) {
    broadcast(JSON.stringify({ type: 'telemetry:broadcast', data: msg }));
  }
}
```

- [ ] **Step 3: 在 server.listen 回调中初始化遥测**

找到 server.listen 的回调（dev mode 在约 line 2180，或 electron 模式在 electron/main.ts）。在 listen 回调中添加:
```typescript
initTelemetry(settingsManager, store, broadcastTelemetryMessages);
console.log('Telemetry initialized');
```

- [ ] **Step 4: 在 shutdown 函数中停止遥测**

找到 `shutdown()` 函数（约 line 2080），在关闭 server 前添加:
```typescript
stopTelemetry();
```

- [ ] **Step 5: 运行编译检查**

```bash
cd /Users/nasakim/projects/sman && pnpm build
```

- [ ] **Step 6: 提交**

```bash
cd /Users/nasakim/projects/sman && git add server/index.ts && git commit -m "feat: integrate telemetry into server startup"
```

---

### Task 6: 前端广播 Toast 展示

**Depends on:** Task 5

**Files:**
- Modify: `src/locales/zh-CN.json` — 添加 telemetry 相关翻译 key
- Modify: `src/locales/en-US.json` — 添加 telemetry 相关翻译 key
- Create: `src/stores/broadcast.ts` — 广播 Zustand store，监听 WS 事件
- Create: `src/components/BroadcastToast.tsx`
- Modify: `src/components/layout/MainLayout.tsx` — 集成 BroadcastToast

**前置调研结论：**
- 前端 WebSocket 消息通过 `WsClient` 的 `client.on(eventType, handler)` 监听，每个 store 独立订阅
- WsClient 实例通过 `useWsConnection.getState().client` 获取
- MainLayout 实际路径: `src/components/layout/MainLayout.tsx`
- 不存在 `useWebSocket` hook，需用 Zustand store 模式
- 服务端 `server/index.ts` 的 WebSocket switch 中需添加 `telemetry:ack` case

- [ ] **Step 1: 在 zh-CN.json 添加翻译 key**

在 JSON 中添加:
```json
"telemetry.broadcast.title": { "text": "Sman 通知", "context": "广播通知的默认标题" },
"telemetry.broadcast.dismiss": { "text": "知道了", "context": "关闭广播通知按钮" }
```

- [ ] **Step 2: 在 en-US.json 添加翻译 key**

```json
"telemetry.broadcast.title": { "text": "Sman Notice", "context": "Default title for broadcast notification" },
"telemetry.broadcast.dismiss": { "text": "Got it", "context": "Button to dismiss broadcast notification" }
```

- [ ] **Step 3: 创建 src/stores/broadcast.ts — Zustand 广播 store**

遵循项目现有模式（如 `chat.ts`、`settings.ts`），通过 `getWsClient()` 获取 WsClient 实例，订阅 `telemetry:broadcast` 事件。

```typescript
import { create } from 'zustand';
import { getWsClient } from './ws-connection.js';
import type { BroadcastMessage } from '../../../server/telemetry/types.js';

interface BroadcastState {
  queue: BroadcastMessage[];
  shown: Set<string>;
  subscribe: () => () => void;
  dismiss: (id: string) => void;
}

export const useBroadcastStore = create<BroadcastState>((set, get) => ({
  queue: [],
  shown: new Set<string>(),

  subscribe: () => {
    const client = getWsClient();
    if (!client) return () => {};

    const handler = (msg: Record<string, unknown>) => {
      if (msg?.type === 'telemetry:broadcast' && msg?.data) {
        const data = msg.data as BroadcastMessage;
        const { shown } = get();
        if (!shown.has(data.id)) {
          set(state => ({
            queue: [...state.queue, data],
            shown: new Set([...state.shown, data.id]),
          }));
        }
      }
    };

    client.on('telemetry:broadcast', handler);
    return () => client.off('telemetry:broadcast', handler);
  },

  dismiss: (id: string) => {
    set(state => ({
      queue: state.queue.filter(m => m.id !== id),
    }));
    // 通过 WS 发送 ack，服务端转发给 telemetry 模块
    const client = getWsClient();
    client?.send({ type: 'telemetry:ack', broadcastIds: [id] });
  },
}));
```

- [ ] **Step 4: 创建 src/components/BroadcastToast.tsx**

```tsx
import { useEffect } from 'react';
import { t } from '@/locales';
import { useBroadcastStore } from '@/stores/broadcast';

export function BroadcastToast() {
  const queue = useBroadcastStore(s => s.queue);
  const dismiss = useBroadcastStore(s => s.dismiss);
  const subscribe = useBroadcastStore(s => s.subscribe);

  useEffect(() => {
    const unsub = subscribe();
    return unsub;
  }, [subscribe]);

  const current = queue[0];
  if (!current) return null;

  return (
    <div className="fixed bottom-4 right-4 z-50 max-w-sm">
      <div className="bg-white dark:bg-gray-800 rounded-lg shadow-lg border border-gray-200 dark:border-gray-700 p-4">
        <h3 className="font-semibold text-sm">{current.title || t('telemetry.broadcast.title')}</h3>
        <p className="text-sm text-gray-600 dark:text-gray-300 mt-1">{current.body}</p>
        <button
          onClick={() => dismiss(current.id)}
          className="mt-2 text-xs text-blue-500 hover:text-blue-600"
        >
          {t('telemetry.broadcast.dismiss')}
        </button>
      </div>
    </div>
  );
}
```

- [ ] **Step 5: 在 server/index.ts 的 WS switch 中添加 telemetry:ack case**

在 `server/index.ts` 的 `switch (msg.type)` 中（约 line 946 附近）添加:
```typescript
case 'telemetry:ack': {
  const tc = getTelemetryClient();
  if (tc) {
    tc.ackBroadcasts(msg.broadcastIds as string[]);
  }
  break;
}
```

- [ ] **Step 6: 在 src/components/layout/MainLayout.tsx 中添加 BroadcastToast**

在 MainLayout 组件的 JSX 末尾添加:
```tsx
<BroadcastToast />
```

- [ ] **Step 7: 运行前端构建**

```bash
cd /Users/nasakim/projects/sman && pnpm build
```

- [ ] **Step 8: 提交**

```bash
cd /Users/nasakim/projects/sman && git add src/stores/broadcast.ts src/components/BroadcastToast.tsx src/components/layout/MainLayout.tsx src/locales/ server/index.ts && git commit -m "feat: add broadcast toast UI and WS integration"
```

---

### Task 7: Electron 配置注入 — 遥测 + 更新 URL 持久化

**Depends on:** Task 4

**Files:**
- Modify: `electron/main.ts` — 启动后写入默认 telemetry config + 设置 autoUpdater feed URL

- [ ] **Step 1: 在 electron/main.ts 添加 telemetry config 注入逻辑**

在 `startServerInProcess()` 完成后（约 line 293 之后），添加配置注入:

```typescript
// 注入 telemetry 配置 + 持久化更新 URL（仅 production）
async function ensureTelemetryConfig(homeDir: string): Promise<void> {
  const configPath = path.join(homeDir, 'config.json');
  try {
    const content = await fs.readFile(configPath, 'utf-8');
    const config = JSON.parse(content);
    if (!config.telemetry || !config.telemetry.serverUrl) {
      config.telemetry = {
        serverUrl: 'https://your-sman-server.com',
        updateUrl: 'https://your-sman-server.com/updates/sman',
        enabled: true,
      };
      await fs.writeFile(configPath, JSON.stringify(config, null, 2));
    }
    // 从 config 读取 updateUrl 并设置到 autoUpdater（持久化 runtime override）
    if (config.telemetry?.updateUrl) {
      autoUpdater.setFeedURL({ provider: 'generic', url: config.telemetry.updateUrl });
    }
  } catch {
    // 配置文件不存在，server 初始化时会创建
  }
}
```

在 `startServerInProcess()` 回调中调用:
```typescript
const home = serverModule.homeDir;
await ensureTelemetryConfig(home);
```

> **关键变更**: autoUpdater 的 feed URL 不再依赖 `package.json` 的 placeholder，也不需要用户每次手动在 UpdateSettings 中设置。首次启动时从 config.json 读取并设置，后续用户修改 config 中的 `telemetry.updateUrl` 即可。

- [ ] **Step 2: 运行编译检查**

```bash
cd /Users/nasakim/projects/sman && pnpm build:electron
```

- [ ] **Step 3: 提交**

```bash
cd /Users/nasakim/projects/sman && git add electron/main.ts && git commit -m "feat: inject telemetry + update URL config on Electron startup"
```

---

## 依赖关系

```
Task 1 (sman-server: 初始化+crypto)
  └─→ Task 2 (sman-server: DB)
       └─→ Task 3 (sman-server: 路由+入口+更新静态服务+上传接口)

Task 4 (sman: telemetry client 模块 + session store 扩展) — 独立于 Task 1-3
  └─→ Task 5 (sman: server/index.ts 集成)
       └─→ Task 6 (sman: 前端 Toast + WS ack)
  Task 4 └─→ Task 7 (sman: Electron 遥测+更新URL配置注入)
```

**可并行**: Task 1-3 (server) 和 Task 4 (client 模块) 可以同时进行。
**串行**: Task 5 → Task 6 必须在 Task 4 之后。

## sman-server 部署说明

1. 复制 `.env.example` 为 `.env`，填入真实的 PSK（32字节）和 ADMIN_TOKEN
2. `pnpm install && pnpm dev` 启动
3. 上传更新包:
   ```bash
   curl -X PUT -H "Authorization: Bearer $TOKEN" --data-binary @latest.yml \
     "http://localhost:5882/admin/upload?filename=latest.yml"
   curl -X PUT -H "Authorization: Bearer $TOKEN" --data-binary @Sman-26.5.0.dmg \
     "http://localhost:5882/admin/upload?filename=Sman-26.5.0.dmg"
   ```
4. Client 配置 `telemetry.serverUrl` 指向 `http://your-server:5882`（不含路径）
5. Client 配置 `telemetry.updateUrl` 指向 `http://your-server:5882/updates/sman`
6. 或直接 scp 文件到 `data/updates/sman/` 目录
