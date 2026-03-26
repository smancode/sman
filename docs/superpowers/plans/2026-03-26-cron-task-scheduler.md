# 定时任务调度器 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Sman 后端添加定时任务调度功能，支持按业务系统 + Skill 配置定时执行，具备并发控制、假死检测和执行记录。

**Architecture:**
- 后端新增 `CronTaskStore`（SQLite 持久化）、`CronScheduler`（定时调度）、`CronExecutor`（执行任务）
- 通过 crontab.lock 文件 + activeRuns Map 实现并发控制
- 通过 lastActivityAt 检测假死，超时强制终止

**Tech Stack:** Node.js + TypeScript + SQLite (better-sqlite3) + ws (WebSocket)

---

## File Structure

### 后端新增文件
| 文件 | 职责 |
|------|------|
| `server/cron-task-store.ts` | 定时任务和执行记录的数据库操作 |
| `server/cron-scheduler.ts` | 定时调度器，管理 interval 定时器 |
| `server/cron-executor.ts` | 执行器，调用 Claude SDK，管理并发锁 |
| `server/types.ts` | 新增 CronTask、CronRun 类型定义 |

### 后端修改文件
| 文件 | 修改内容 |
|------|---------|
| `server/index.ts` | 初始化 scheduler，添加 WebSocket handlers |
| `server/session-store.ts` | 新增 `createSessionWithId` 方法 |
| `server/claude-session.ts` | 新增 `sendMessageWithSessionId` 方法 |

### 前端新增文件
| 文件 | 职责 |
|------|------|
| `src/stores/cron.ts` | 定时任务状态管理 |
| `src/features/settings/CronTaskSettings.tsx` | 定时任务配置组件 |

### 前端修改文件
| 文件 | 修改内容 |
|------|---------|
| `src/features/settings/SettingsPage.tsx` | 添加定时任务设置 Tab |
| `src/types/settings.ts` | 新增 CronTask、CronRun 类型 |

---

## Chunk 1: 后端数据层

### Task 1: 定义类型

**Files:**
- Modify: `server/types.ts`

- [ ] **Step 1: 添加 CronTask 和 CronRun 类型定义**

```typescript
// 添加到 server/types.ts 末尾

export interface CronTask {
  id: string;
  workspace: string;
  skillName: string;
  intervalMinutes: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CronRun {
  id: number;
  taskId: string;
  sessionId: string;
  status: 'running' | 'success' | 'failed';
  startedAt: string;
  finishedAt: string | null;
  lastActivityAt: string | null;
  errorMessage: string | null;
}
```

- [ ] **Step 2: 验证类型定义**

```bash
cd /Users/nasakim/projects/smanbase && pnpm exec tsc --noEmit server/types.ts
```

---

### Task 2: 实现 CronTaskStore

**Files:**
- Create: `server/cron-task-store.ts`

- [ ] **Step 1: 创建 CronTaskStore 类框架**

```typescript
import Database from 'better-sqlite3';
import { createLogger, type Logger } from './utils/logger.js';
import { v4 as uuidv4 } from 'uuid';
import type { CronTask, CronRun } from './types.js';

export class CronTaskStore {
  private db: Database.Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new Database(dbPath);
    this.log = createLogger('CronTaskStore');
    this.init();
  }

  private init(): void {
    // 创建定时任务表
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS cron_tasks (
        id TEXT PRIMARY KEY,
        workspace TEXT NOT NULL,
        skill_name TEXT NOT NULL,
        interval_minutes INTEGER NOT NULL,
        enabled INTEGER NOT NULL DEFAULT 1,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
    `);

    // 创建执行记录表
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS cron_runs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        task_id TEXT NOT NULL,
        session_id TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('running', 'success', 'failed')),
        started_at TEXT NOT NULL,
        finished_at TEXT,
        last_activity_at TEXT,
        error_message TEXT,
        FOREIGN KEY (task_id) REFERENCES cron_tasks(id) ON DELETE CASCADE
      );
    `);

    // 创建索引
    this.db.exec(`
      CREATE INDEX IF NOT EXISTS idx_cron_runs_task ON cron_runs(task_id);
      CREATE INDEX IF NOT EXISTS idx_cron_runs_started ON cron_runs(started_at DESC);
    `);

    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
    this.log.info('CronTaskStore initialized');
  }

  // === Task CRUD ===

  createTask(input: { workspace: string; skillName: string; intervalMinutes: number }): CronTask {
    const id = uuidv4();
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO cron_tasks (id, workspace, skill_name, interval_minutes, enabled, created_at, updated_at)
      VALUES (?, ?, ?, ?, 1, ?, ?)
    `).run(id, input.workspace, input.skillName, input.intervalMinutes, now, now);

    return this.getTask(id)!;
  }

  getTask(id: string): CronTask | undefined {
    const row = this.db.prepare(`
      SELECT id, workspace, skill_name as skillName, interval_minutes as intervalMinutes,
             enabled, created_at as createdAt, updated_at as updatedAt
      FROM cron_tasks WHERE id = ?
    `).get(id);
    return row as CronTask | undefined;
  }

  listTasks(): CronTask[] {
    return this.db.prepare(`
      SELECT id, workspace, skill_name as skillName, interval_minutes as intervalMinutes,
             enabled, created_at as createdAt, updated_at as updatedAt
      FROM cron_tasks ORDER BY created_at DESC
    `).all() as CronTask[];
  }

  listEnabledTasks(): CronTask[] {
    return this.db.prepare(`
      SELECT id, workspace, skill_name as skillName, interval_minutes as intervalMinutes,
             enabled, created_at as createdAt, updated_at as updatedAt
      FROM cron_tasks WHERE enabled = 1 ORDER BY created_at DESC
    `).all() as CronTask[];
  }

  updateTask(id: string, updates: Partial<Pick<CronTask, 'intervalMinutes' | 'enabled'>>): CronTask | undefined {
    const fields: string[] = [];
    const values: (string | number | boolean)[] = [];

    if (updates.intervalMinutes !== undefined) {
      fields.push('interval_minutes = ?');
      values.push(updates.intervalMinutes);
    }
    if (updates.enabled !== undefined) {
      fields.push('enabled = ?');
      values.push(updates.enabled ? 1 : 0);
    }

    if (fields.length === 0) return this.getTask(id);

    fields.push('updated_at = ?');
    values.push(new Date().toISOString());
    values.push(id);

    this.db.prepare(`
      UPDATE cron_tasks SET ${fields.join(', ')} WHERE id = ?
    `).run(...values);

    return this.getTask(id);
  }

  deleteTask(id: string): void {
    this.db.prepare('DELETE FROM cron_tasks WHERE id = ?').run(id);
  }

  // === Run Records ===

  createRun(taskId: string, sessionId: string): CronRun {
    const now = new Date().toISOString();
    const result = this.db.prepare(`
      INSERT INTO cron_runs (task_id, session_id, status, started_at, last_activity_at)
      VALUES (?, ?, 'running', ?, ?)
    `).run(taskId, sessionId, now, now);

    return {
      id: result.lastInsertRowid as number,
      taskId,
      sessionId: sessionId,
      status: 'running',
      startedAt: now,
      finishedAt: null,
      lastActivityAt: now,
      errorMessage: null,
    };
  }

  updateRun(id: number, updates: { status?: 'running' | 'success' | 'failed'; lastActivityAt?: string; errorMessage?: string }): void {
    const fields: string[] = [];
    const values: (string | number)[] = [];

    if (updates.status !== undefined) {
      fields.push('status = ?');
      values.push(updates.status);
      if (updates.status !== 'running') {
        fields.push('finished_at = ?');
        values.push(new Date().toISOString());
      }
    }
    if (updates.lastActivityAt !== undefined) {
      fields.push('last_activity_at = ?');
      values.push(updates.lastActivityAt);
    }
    if (updates.errorMessage !== undefined) {
      fields.push('error_message = ?');
      values.push(updates.errorMessage);
    }

    if (fields.length === 0) return;

    values.push(id);
    this.db.prepare(`UPDATE cron_runs SET ${fields.join(', ')} WHERE id = ?`).run(...values);
  }

  getLatestRun(taskId: string): CronRun | undefined {
    const row = this.db.prepare(`
      SELECT id, task_id as taskId, session_id as sessionId, status,
             started_at as startedAt, finished_at as finishedAt,
             last_activity_at as lastActivityAt, error_message as errorMessage
      FROM cron_runs WHERE task_id = ? ORDER BY started_at DESC LIMIT 1
    `).get(taskId);
    return row as CronRun | undefined;
  }

  listRuns(taskId: string, limit = 20): CronRun[] {
    return this.db.prepare(`
      SELECT id, task_id as taskId, session_id as sessionId, status,
             started_at as startedAt, finished_at as finishedAt,
             last_activity_at as lastActivityAt, error_message as errorMessage
      FROM cron_runs WHERE task_id = ? ORDER BY started_at DESC LIMIT ?
    `).all(taskId, limit) as CronRun[];
  }

  getRunningRuns(): CronRun[] {
    return this.db.prepare(`
      SELECT id, task_id as taskId, session_id as sessionId, status,
             started_at as startedAt, finished_at as finishedAt,
             last_activity_at as lastActivityAt, error_message as errorMessage
      FROM cron_runs WHERE status = 'running'
    `).all() as CronRun[];
  }

  close(): void {
    this.db.close();
  }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd /Users/nasakim/projects/smanbase && pnpm exec tsc --noEmit server/cron-task-store.ts
```

---

## Chunk 2: 并发控制与执行器

### Task 3: 实现 CronExecutor

**Files:**
- Create: `server/cron-executor.ts`

- [ ] **Step 1: 创建 CronExecutor 类**

```typescript
import fs from 'fs';
import path from 'path';
import { v4 as uuidv4 } from 'uuid';
import { createLogger, type Logger } from './utils/logger.js';
import type { CronTaskStore, CronRun } from './cron-task-store.js';
import type { CronTask } from './types.js';
import type { ClaudeSessionManager } from './claude-session.js';

interface LockFile {
  triggers: Array<{
    sessionId: string;
    triggeredAt: string;
  }>;
}

interface ActiveRun {
  runId: number;
  taskId: string;
  sessionId: string;
  workspace: string;
  skillName: string;
  abortController: AbortController;
  lastActivityAt: Date;
}

const ZOMBIE_THRESHOLD_MS = 30 * 60 * 1000; // 30 分钟

export class CronExecutor {
  private log: Logger;
  private activeRuns = new Map<string, ActiveRun>(); // sessionId -> ActiveRun
  private zombieCheckInterval: NodeJS.Timeout | null = null;

  constructor(
    private taskStore: CronTaskStore,
    private sessionManager: ClaudeSessionManager,
  ) {
    this.log = createLogger('CronExecutor');
  }

  /**
   * 启动假死检测
   */
  startZombieCheck(): void {
    this.zombieCheckInterval = setInterval(() => {
      this.checkZombies();
    }, 5 * 60 * 1000); // 每 5 分钟检查一次
    this.log.info('Zombie check started');
  }

  /**
   * 停止假死检测
   */
  stopZombieCheck(): void {
    if (this.zombieCheckInterval) {
      clearInterval(this.zombieCheckInterval);
      this.zombieCheckInterval = null;
    }
  }

  /**
   * 检查并处理假死任务
   */
  private checkZombies(): void {
    const now = Date.now();
    for (const [sessionId, run] of this.activeRuns) {
      const idleMs = now - run.lastActivityAt.getTime();
      if (idleMs > ZOMBIE_THRESHOLD_MS) {
        this.log.warn(`Zombie task detected: ${sessionId}, idle for ${Math.round(idleMs / 1000 / 60)} minutes`);
        this.killTask(sessionId, '超时无响应，已强制终止');
      }
    }
  }

  /**
   * 强制终止任务
   */
  private killTask(sessionId: string, reason: string): void {
    const run = this.activeRuns.get(sessionId);
    if (!run) return;

    try {
      run.abortController.abort();
      this.sessionManager.abort(sessionId);
    } catch (err) {
      this.log.error(`Failed to abort task ${sessionId}`, { error: err });
    }

    this.taskStore.updateRun(run.runId, {
      status: 'failed',
      errorMessage: reason,
    });

    this.activeRuns.delete(sessionId);
    this.log.info(`Task killed: ${sessionId}, reason: ${reason}`);
  }

  /**
   * 获取 lock 文件路径
   */
  private getLockFilePath(workspace: string, skillName: string): string {
    return path.join(workspace, '.claude', 'skills', skillName, 'crontab.lock');
  }

  /**
   * 获取 crontab.md 文件路径
   */
  private getCrontabPath(workspace: string, skillName: string): string {
    return path.join(workspace, '.claude', 'skills', skillName, 'crontab.md');
  }

  /**
   * 读取 lock 文件
   */
  private readLockFile(lockPath: string): LockFile | null {
    if (!fs.existsSync(lockPath)) return null;
    try {
      const content = fs.readFileSync(lockPath, 'utf-8');
      return JSON.parse(content);
    } catch {
      return null;
    }
  }

  /**
   * 写入/追加 lock 文件
   */
  private appendLockFile(lockPath: string, sessionId: string): void {
    let lock: LockFile = { triggers: [] };
    if (fs.existsSync(lockPath)) {
      try {
        lock = JSON.parse(fs.readFileSync(lockPath, 'utf-8'));
      } catch {
        // ignore
      }
    }
    lock.triggers.push({
      sessionId,
      triggeredAt: new Date().toISOString(),
    });
    fs.writeFileSync(lockPath, JSON.stringify(lock, null, 2), 'utf-8');
  }

  /**
   * 检查任务是否可以执行
   * @returns 可以执行返回新的 sessionId，不能执行返回 null
   */
  canExecute(task: CronTask): string | null {
    const lockPath = this.getLockFilePath(task.workspace, task.skillName);
    const lock = this.readLockFile(lockPath);

    if (!lock || lock.triggers.length === 0) {
      // 无 lock，可以执行
      return this.generateSessionId(task);
    }

    // 获取最近一次触发
    const lastTrigger = lock.triggers[lock.triggers.length - 1];
    const lastSessionId = lastTrigger.sessionId;

    // 检查是否在 activeRuns 中
    const activeRun = this.activeRuns.get(lastSessionId);
    if (activeRun) {
      // 检查是否假死
      const now = Date.now();
      const idleMs = now - activeRun.lastActivityAt.getTime();
      if (idleMs > ZOMBIE_THRESHOLD_MS) {
        // 假死，kill 掉
        this.killTask(lastSessionId, '超时无响应，已强制终止');
        return this.generateSessionId(task);
      }
      // 还在运行，跳过
      this.log.info(`Task ${task.id} already running: ${lastSessionId}, skipping`);
      return null;
    }

    // 不在 activeRuns 中，检查数据库状态
    const latestRun = this.taskStore.getLatestRun(task.id);
    if (latestRun && latestRun.status === 'running') {
      // 数据库显示 running 但 activeRuns 没有，说明异常终止
      this.taskStore.updateRun(latestRun.id, {
        status: 'failed',
        errorMessage: '进程异常终止',
      });
    }

    // 可以执行
    return this.generateSessionId(task);
  }

  /**
   * 生成 sessionId
   */
  private generateSessionId(task: CronTask): string {
    const projectName = path.basename(task.workspace);
    const timestamp = new Date().toISOString().replace(/[-:T.Z]/g, '').slice(0, 14);
    return `cron-${projectName}-${task.skillName}-${timestamp}`;
  }

  /**
   * 执行定时任务
   */
  async execute(task: CronTask): Promise<void> {
    const sessionId = this.canExecute(task);
    if (!sessionId) return;

    const crontabPath = this.getCrontabPath(task.workspace, task.skillName);
    if (!fs.existsSync(crontabPath)) {
      this.log.warn(`crontab.md not found for task ${task.id}: ${crontabPath}`);
      return;
    }

    const crontabContent = fs.readFileSync(crontabPath, 'utf-8').trim();
    if (!crontabContent) {
      this.log.warn(`crontab.md is empty for task ${task.id}`);
      return;
    }

    // 写入 lock
    const lockPath = this.getLockFilePath(task.workspace, task.skillName);
    this.appendLockFile(lockPath, sessionId);

    // 创建执行记录
    const run = this.taskStore.createRun(task.id, sessionId);

    // 创建 AbortController
    const abortController = new AbortController();

    // 记录 activeRun
    const activeRun: ActiveRun = {
      runId: run.id,
      taskId: task.id,
      sessionId,
      workspace: task.workspace,
      skillName: task.skillName,
      abortController,
      lastActivityAt: new Date(),
    };
    this.activeRuns.set(sessionId, activeRun);

    // 构造提示词
    const prompt = `/${task.skillName} ${crontabContent}`;
    this.log.info(`Executing task ${task.id}: ${prompt}`);

    try {
      // 创建会话（使用指定的 sessionId）
      this.sessionManager.createSessionWithId(task.workspace, sessionId);

      // 更新活动的回调
      const updateActivity = () => {
        const ar = this.activeRuns.get(sessionId);
        if (ar) {
          ar.lastActivityAt = new Date();
          this.taskStore.updateRun(ar.runId, { lastActivityAt: new Date().toISOString() });
        }
      };

      // 发送消息
      await this.sessionManager.sendMessageForCron(
        sessionId,
        prompt,
        abortController,
        updateActivity,
      );

      // 成功
      this.taskStore.updateRun(run.id, { status: 'success' });
      this.log.info(`Task ${task.id} completed successfully`);
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      if (abortController.signal.aborted) {
        // 已被 kill，状态已在 killTask 中更新
        this.log.info(`Task ${task.id} was aborted`);
      } else {
        this.taskStore.updateRun(run.id, { status: 'failed', errorMessage });
        this.log.error(`Task ${task.id} failed`, { error: errorMessage });
      }
    } finally {
      this.activeRuns.delete(sessionId);
    }
  }

  /**
   * 获取所有活跃运行
   */
  getActiveRuns(): Map<string, ActiveRun> {
    return this.activeRuns;
  }

  /**
   * 关闭
   */
  close(): void {
    this.stopZombieCheck();
    for (const [sessionId, run] of this.activeRuns) {
      try {
        run.abortController.abort();
      } catch {
        // ignore
      }
    }
    this.activeRuns.clear();
  }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd /Users/nasakim/projects/smanbase && pnpm exec tsc --noEmit server/cron-executor.ts
```

---

### Task 4: 修改 ClaudeSessionManager

**Files:**
- Modify: `server/claude-session.ts`

- [ ] **Step 1: 添加 createSessionWithId 方法**

在 `createSession` 方法后添加：

```typescript
/**
 * 创建会话（使用指定的 ID）
 */
createSessionWithId(workspace: string, sessionId: string): string {
  if (!fs.existsSync(workspace)) {
    throw new Error(`Workspace does not exist: ${workspace}`);
  }

  // 检查是否已存在
  const existing = this.sessions.get(sessionId);
  if (existing) {
    return sessionId;
  }

  // 使用指定的 sessionId 创建
  this.store.createSession({
    id: sessionId,
    systemId: workspace,
    workspace,
  });

  const session: ActiveSession = {
    id: sessionId,
    workspace,
    createdAt: new Date().toISOString(),
    lastActiveAt: new Date().toISOString(),
  };

  this.sessions.set(sessionId, session);
  this.log.info(`Session created with custom ID: ${sessionId} for workspace ${workspace}`);
  return sessionId;
}
```

- [ ] **Step 2: 添加 sendMessageForCron 方法**

在 `sendMessage` 方法后添加：

```typescript
/**
 * 发送消息（定时任务专用，支持外部 AbortController 和活动回调）
 */
async sendMessageForCron(
  sessionId: string,
  content: string,
  abortController: AbortController,
  onActivity: () => void,
): Promise<void> {
  const session = this.sessions.get(sessionId);
  if (!session) throw new Error(`Session not found: ${sessionId}`);

  if (this.activeQueries.has(sessionId)) {
    throw new Error(`Session ${sessionId} already has an active query`);
  }

  // 校验 LLM 配置
  if (!this.config?.llm?.apiKey) {
    throw new Error('缺少 API Key，请在设置中配置');
  }
  if (!this.config?.llm?.model) {
    throw new Error('缺少 Model 配置，请在设置中选择模型');
  }

  this.store.addMessage(sessionId, { role: 'user', content });

  const sessionConfig = this.getSessionConfig(session.workspace);
  this.abortControllers.set(sessionId, abortController);

  try {
    const options = this.buildOptions(sessionConfig, abortController);
    options.cwd = session.workspace;

    this.log.info(`Starting cron query for session ${sessionId}`);

    const q = query({
      prompt: content,
      options,
    });

    this.activeQueries.set(sessionId, q);

    let fullContent = '';

    for await (const sdkMsg of q) {
      // 调用活动回调
      onActivity();

      if (abortController.signal.aborted) {
        this.log.info(`Cron query aborted for session ${sessionId}`);
        break;
      }

      switch (sdkMsg.type) {
        case 'assistant': {
          const text = this.extractTextContent(sdkMsg);
          if (text) fullContent = text;
          break;
        }
        case 'stream_event': {
          const delta = this.extractDeltaText((sdkMsg as SDKPartialAssistantMessage).event);
          if (delta) {
            fullContent += delta;
          }
          break;
        }
        case 'result': {
          const result = sdkMsg as SDKResultMessage;

          if (result.session_id) {
            this.sdkSessionIds.set(sessionId, result.session_id);
            this.store.updateSdkSessionId(sessionId, result.session_id);
          }

          if (fullContent) {
            this.store.addMessage(sessionId, { role: 'assistant', content: fullContent });
          }

          this.log.info(`Cron query completed for session ${sessionId}`);
          break;
        }
      }
    }
  } catch (err: any) {
    if (err?.name !== 'AbortError' && !abortController.signal.aborted) {
      throw err;
    }
  } finally {
    this.activeQueries.delete(sessionId);
    this.abortControllers.delete(sessionId);
  }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd /Users/nasakim/projects/smanbase && pnpm exec tsc --noEmit server/claude-session.ts
```

---

## Chunk 3: 调度器与集成

### Task 5: 实现 CronScheduler

**Files:**
- Create: `server/cron-scheduler.ts`

- [ ] **Step 1: 创建 CronScheduler 类**

```typescript
import { createLogger, type Logger } from './utils/logger.js';
import type { CronTaskStore } from './cron-task-store.js';
import type { CronExecutor } from './cron-executor.js';
import type { CronTask } from './types.js';

export class CronScheduler {
  private log: Logger;
  private timers = new Map<string, NodeJS.Timeout>(); // taskId -> timer
  private executor: CronExecutor;

  constructor(
    private taskStore: CronTaskStore,
  ) {
    this.log = createLogger('CronScheduler');
    this.executor = new CronExecutor(taskStore, this.sessionManager);
  }

  private sessionManager: any; // Will be set via setSessionManager

  setSessionManager(sm: any): void {
    this.sessionManager = sm;
    (this.executor as any).sessionManager = sm;
  }

  /**
   * 启动调度器
   */
  start(): void {
    this.log.info('Starting CronScheduler...');

    // 加载所有启用的任务
    const tasks = this.taskStore.listEnabledTasks();
    for (const task of tasks) {
      this.schedule(task);
    }

    // 启动假死检测
    this.executor.startZombieCheck();

    this.log.info(`CronScheduler started with ${tasks.length} tasks`);
  }

  /**
   * 停止调度器
   */
  stop(): void {
    this.log.info('Stopping CronScheduler...');
    for (const [taskId, timer] of this.timers) {
      clearInterval(timer);
    }
    this.timers.clear();
    this.executor.stopZombieCheck();
    this.log.info('CronScheduler stopped');
  }

  /**
   * 调度单个任务
   */
  schedule(task: CronTask): void {
    // 先取消现有的
    this.unschedule(task.id);

    if (!task.enabled) {
      this.log.info(`Task ${task.id} is disabled, not scheduling`);
      return;
    }

    const intervalMs = task.intervalMinutes * 60 * 1000;
    const timer = setInterval(() => {
      this.executeTask(task);
    }, intervalMs);

    this.timers.set(task.id, timer);
    this.log.info(`Task ${task.id} scheduled: every ${task.intervalMinutes} minutes`);
  }

  /**
   * 取消调度
   */
  unschedule(taskId: string): void {
    const timer = this.timers.get(taskId);
    if (timer) {
      clearInterval(timer);
      this.timers.delete(taskId);
      this.log.info(`Task ${taskId} unscheduled`);
    }
  }

  /**
   * 执行任务
   */
  private async executeTask(task: CronTask): Promise<void> {
    this.log.info(`Executing task ${task.id}...`);
    try {
      await this.executor.execute(task);
    } catch (err) {
      this.log.error(`Task ${task.id} execution failed`, { error: err });
    }
  }

  /**
   * 手动触发执行（用于立即执行一次）
   */
  async executeNow(taskId: string): Promise<void> {
    const task = this.taskStore.getTask(taskId);
    if (!task) {
      throw new Error(`Task not found: ${taskId}`);
    }
    await this.executor.execute(task);
  }

  /**
   * 获取执行器
   */
  getExecutor(): CronExecutor {
    return this.executor;
  }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd /Users/nasakim/projects/smanbase && pnpm exec tsc --noEmit server/cron-scheduler.ts
```

---

### Task 6: 集成到 server/index.ts

**Files:**
- Modify: `server/index.ts`

- [ ] **Step 1: 导入并初始化**

在文件顶部导入部分添加：

```typescript
import { CronTaskStore } from './cron-task-store.js';
import { CronScheduler } from './cron-scheduler.js';
```

在 `const settingsManager = new SettingsManager(homeDir);` 后添加：

```typescript
// Cron task scheduler
const cronTaskStore = new CronTaskStore(dbPath);
const cronScheduler = new CronScheduler(cronTaskStore);
cronScheduler.setSessionManager(sessionManager);
```

在 `sessionManager.updateConfig(settingsManager.getConfig());` 后添加：

```typescript
// Start cron scheduler
cronScheduler.start();
```

在 graceful shutdown 部分添加：

```typescript
process.on('SIGTERM', () => {
  log.info('SIGTERM received, shutting down...');
  cronScheduler.stop();
  sessionManager.close();
  wss.close();
  server.close(() => process.exit(0));
});

process.on('SIGINT', () => {
  log.info('SIGINT received, shutting down...');
  cronScheduler.stop();
  sessionManager.close();
  wss.close();
  server.close(() => process.exit(0));
});
```

- [ ] **Step 2: 添加 WebSocket handlers**

在 `wss.on('connection', ...)` 的 switch 语句中，`case 'settings.update':` 之后添加：

```typescript
        // ── Cron Tasks ──
        case 'cron.workspaces': {
          // 从会话列表中获取所有唯一的 workspace
          const sessions = store.listSessions();
          const workspaces = [...new Set(sessions.map(s => s.workspace))];
          ws.send(JSON.stringify({ type: 'cron.workspaces', workspaces }));
          break;
        }

        case 'cron.skills': {
          if (!msg.workspace) throw new Error('Missing workspace');
          const skillsDir = path.join(msg.workspace, '.claude', 'skills');
          const skills: { name: string; hasCrontab: boolean }[] = [];

          if (fs.existsSync(skillsDir)) {
            const entries = fs.readdirSync(skillsDir, { withFileTypes: true });
            for (const entry of entries) {
              if (entry.isDirectory()) {
                const crontabPath = path.join(skillsDir, entry.name, 'crontab.md');
                skills.push({
                  name: entry.name,
                  hasCrontab: fs.existsSync(crontabPath),
                });
              }
            }
          }

          ws.send(JSON.stringify({ type: 'cron.skills', workspace: msg.workspace, skills }));
          break;
        }

        case 'cron.list': {
          const tasks = cronTaskStore.listTasks().map(task => ({
            ...task,
            latestRun: cronTaskStore.getLatestRun(task.id),
          }));
          ws.send(JSON.stringify({ type: 'cron.list', tasks }));
          break;
        }

        case 'cron.create': {
          if (!msg.workspace || !msg.skillName || !msg.intervalMinutes) {
            throw new Error('Missing required fields');
          }
          const task = cronTaskStore.createTask({
            workspace: msg.workspace,
            skillName: msg.skillName,
            intervalMinutes: msg.intervalMinutes,
          });
          cronScheduler.schedule(task);
          ws.send(JSON.stringify({ type: 'cron.created', task }));
          break;
        }

        case 'cron.update': {
          if (!msg.taskId) throw new Error('Missing taskId');
          const task = cronTaskStore.updateTask(msg.taskId, {
            intervalMinutes: msg.intervalMinutes,
            enabled: msg.enabled,
          });
          if (task) {
            if (task.enabled) {
              cronScheduler.schedule(task);
            } else {
              cronScheduler.unschedule(task.id);
            }
          }
          ws.send(JSON.stringify({ type: 'cron.updated', task }));
          break;
        }

        case 'cron.delete': {
          if (!msg.taskId) throw new Error('Missing taskId');
          cronScheduler.unschedule(msg.taskId);
          cronTaskStore.deleteTask(msg.taskId);
          ws.send(JSON.stringify({ type: 'cron.deleted', taskId: msg.taskId }));
          break;
        }

        case 'cron.runs': {
          if (!msg.taskId) throw new Error('Missing taskId');
          const limit = (msg.limit as number) || 20;
          const runs = cronTaskStore.listRuns(msg.taskId, limit);
          ws.send(JSON.stringify({ type: 'cron.runs', taskId: msg.taskId, runs }));
          break;
        }

        case 'cron.execute': {
          if (!msg.taskId) throw new Error('Missing taskId');
          try {
            await cronScheduler.executeNow(msg.taskId);
            ws.send(JSON.stringify({ type: 'cron.executed', taskId: msg.taskId }));
          } catch (err) {
            const errorMessage = err instanceof Error ? err.message : String(err);
            ws.send(JSON.stringify({ type: 'chat.error', error: errorMessage }));
          }
          break;
        }
```

- [ ] **Step 3: 编译验证**

```bash
cd /Users/nasakim/projects/smanbase && pnpm exec tsc --noEmit server/index.ts
```

---

## Chunk 4: 前端实现

### Task 7: 添加类型定义

**Files:**
- Modify: `src/types/settings.ts`

- [ ] **Step 1: 添加 CronTask 和 CronRun 类型**

```typescript
// 添加到 src/types/settings.ts 末尾

export interface CronTask {
  id: string;
  workspace: string;
  skillName: string;
  intervalMinutes: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  latestRun?: CronRun;
}

export interface CronRun {
  id: number;
  taskId: string;
  sessionId: string;
  status: 'running' | 'success' | 'failed';
  startedAt: string;
  finishedAt: string | null;
  lastActivityAt: string | null;
  errorMessage: string | null;
}

export interface CronSkill {
  name: string;
  hasCrontab: boolean;
}
```

---

### Task 8: 创建 cron store

**Files:**
- Create: `src/stores/cron.ts`

- [ ] **Step 1: 创建 cron store**

```typescript
/**
 * Cron Task Store
 * Manages scheduled tasks via WebSocket.
 */
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type { CronTask, CronRun, CronSkill } from '@/types/settings';

type MsgHandler = (msg: Record<string, unknown>) => void;

function getWsClient() {
  return useWsConnection.getState().client;
}

function wrapHandler(
  client: { on: (e: string, h: (...a: unknown[]) => void) => void; off: (e: string, h: (...a: unknown[]) => void) => void },
  event: string,
  handler: MsgHandler,
) {
  const wrapped = (...args: unknown[]) => handler(args[0] as Record<string, unknown>);
  client.on(event, wrapped);
  return () => client.off(event, wrapped);
}

interface CronState {
  workspaces: string[];
  skills: CronSkill[];
  tasks: CronTask[];
  loading: boolean;
  error: string | null;

  fetchWorkspaces: () => Promise<void>;
  fetchSkills: (workspace: string) => Promise<void>;
  fetchTasks: () => Promise<void>;
  createTask: (workspace: string, skillName: string, intervalMinutes: number) => Promise<CronTask>;
  updateTask: (taskId: string, updates: { intervalMinutes?: number; enabled?: boolean }) => Promise<void>;
  deleteTask: (taskId: string) => Promise<void>;
  fetchRuns: (taskId: string, limit?: number) => Promise<CronRun[]>;
  executeNow: (taskId: string) => Promise<void>;
  clearError: () => void;
}

export const useCronStore = create<CronState>((set, get) => ({
  workspaces: [],
  skills: [],
  tasks: [],
  loading: false,
  error: null,

  fetchWorkspaces: async () => {
    const client = getWsClient();
    if (!client) return;

    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'cron.workspaces', (data) => {
        unsub();
        set({ workspaces: data.workspaces as string[] });
        resolve();
      });
      client.send({ type: 'cron.workspaces' });
    });
  },

  fetchSkills: async (workspace: string) => {
    const client = getWsClient();
    if (!client) return;

    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'cron.skills', (data) => {
        unsub();
        set({ skills: data.skills as CronSkill[] });
        resolve();
      });
      client.send({ type: 'cron.skills', workspace });
    });
  },

  fetchTasks: async () => {
    const client = getWsClient();
    if (!client) return;

    set({ loading: true, error: null });
    return new Promise<void>((resolve) => {
      const unsub = wrapHandler(client, 'cron.list', (data) => {
        unsub();
        set({ tasks: data.tasks as CronTask[], loading: false });
        resolve();
      });
      client.send({ type: 'cron.list' });
    });
  },

  createTask: async (workspace: string, skillName: string, intervalMinutes: number) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<CronTask>((resolve, reject) => {
      const unsub = wrapHandler(client, 'cron.created', (data) => {
        unsub();
        unsubErr();
        const task = data.task as CronTask;
        set((state) => ({ tasks: [task, ...state.tasks] }));
        resolve(task);
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'cron.create', workspace, skillName, intervalMinutes });
    });
  },

  updateTask: async (taskId: string, updates: { intervalMinutes?: number; enabled?: boolean }) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'cron.updated', (data) => {
        unsub();
        unsubErr();
        const task = data.task as CronTask;
        set((state) => ({
          tasks: state.tasks.map((t) => (t.id === taskId ? { ...t, ...task } : t)),
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'cron.update', taskId, ...updates });
    });
  },

  deleteTask: async (taskId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'cron.deleted', (data) => {
        unsub();
        unsubErr();
        set((state) => ({
          tasks: state.tasks.filter((t) => t.id !== data.taskId),
        }));
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'cron.delete', taskId });
    });
  },

  fetchRuns: async (taskId: string, limit = 20) => {
    const client = getWsClient();
    if (!client) return [];

    return new Promise<CronRun[]>((resolve) => {
      const unsub = wrapHandler(client, 'cron.runs', (data) => {
        unsub();
        resolve(data.runs as CronRun[]);
      });
      client.send({ type: 'cron.runs', taskId, limit });
    });
  },

  executeNow: async (taskId: string) => {
    const client = getWsClient();
    if (!client) throw new Error('Not connected');

    return new Promise<void>((resolve, reject) => {
      const unsub = wrapHandler(client, 'cron.executed', () => {
        unsub();
        unsubErr();
        resolve();
      });
      const unsubErr = wrapHandler(client, 'chat.error', (data) => {
        unsub();
        unsubErr();
        set({ error: String(data.error) });
        reject(new Error(String(data.error)));
      });
      client.send({ type: 'cron.execute', taskId });
    });
  },

  clearError: () => set({ error: null }),
}));
```

---

### Task 9: 创建 CronTaskSettings 组件

**Files:**
- Create: `src/features/settings/CronTaskSettings.tsx`

- [ ] **Step 1: 创建组件**

```tsx
import { useEffect, useState } from 'react';
import { Plus, Trash2, Play, CheckCircle, XCircle, Clock, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { useCronStore } from '@/stores/cron';
import { cn } from '@/lib/utils';
import type { CronTask, CronRun } from '@/types/settings';

function formatTime(isoString: string): string {
  return new Date(isoString).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function RunStatusBadge({ run }: { run?: CronRun }) {
  if (!run) return <span className="text-muted-foreground text-xs">无记录</span>;

  const statusConfig = {
    running: { icon: Loader2, text: '执行中', className: 'text-yellow-600' },
    success: { icon: CheckCircle, text: '成功', className: 'text-green-600' },
    failed: { icon: XCircle, text: '失败', className: 'text-red-600' },
  };

  const config = statusConfig[run.status];
  const Icon = config.icon;

  return (
    <div className={cn('flex items-center gap-1 text-xs', config.className)}>
      <Icon className={cn('h-3 w-3', run.status === 'running' && 'animate-spin')} />
      <span>{config.text}</span>
      <span className="text-muted-foreground ml-1">{formatTime(run.startedAt)}</span>
      {run.errorMessage && (
        <span className="text-red-500 ml-1 truncate max-w-[150px]" title={run.errorMessage}>
          ({run.errorMessage})
        </span>
      )}
    </div>
  );
}

function TaskItem({
  task,
  onDelete,
  onToggle,
  onExecute,
}: {
  task: CronTask;
  onDelete: () => void;
  onToggle: () => void;
  onExecute: () => void;
}) {
  const workspaceName = task.workspace.split(/[/\\]/).pop() || task.workspace;

  return (
    <div className="flex items-center justify-between p-3 rounded-lg border bg-card">
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className="font-medium truncate">{workspaceName}</span>
          <span className="text-muted-foreground">/</span>
          <span className="text-muted-foreground truncate">{task.skillName}</span>
        </div>
        <div className="flex items-center gap-2 mt-1 text-xs text-muted-foreground">
          <Clock className="h-3 w-3" />
          <span>每 {task.intervalMinutes} 分钟</span>
        </div>
        <div className="mt-1">
          <RunStatusBadge run={task.latestRun} />
        </div>
      </div>

      <div className="flex items-center gap-2">
        <Button
          variant="ghost"
          size="icon"
          onClick={onExecute}
          title="立即执行"
          className="h-8 w-8"
        >
          <Play className="h-4 w-4" />
        </Button>

        <Switch checked={task.enabled} onCheckedChange={onToggle} />

        <Button
          variant="ghost"
          size="icon"
          onClick={onDelete}
          className="h-8 w-8 text-destructive hover:text-destructive"
        >
          <Trash2 className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}

export function CronTaskSettings() {
  const [showForm, setShowForm] = useState(false);
  const [selectedWorkspace, setSelectedWorkspace] = useState('');
  const [selectedSkill, setSelectedSkill] = useState('');
  const [intervalValue, setIntervalValue] = useState('30');
  const [intervalUnit, setIntervalUnit] = useState<'minutes' | 'hours'>('minutes');

  const {
    workspaces,
    skills,
    tasks,
    loading,
    error,
    fetchWorkspaces,
    fetchSkills,
    fetchTasks,
    createTask,
    updateTask,
    deleteTask,
    executeNow,
    clearError,
  } = useCronStore();

  useEffect(() => {
    fetchWorkspaces();
    fetchTasks();
  }, [fetchWorkspaces, fetchTasks]);

  useEffect(() => {
    if (selectedWorkspace) {
      fetchSkills(selectedWorkspace);
      setSelectedSkill('');
    }
  }, [selectedWorkspace, fetchSkills]);

  const handleCreate = async () => {
    if (!selectedWorkspace || !selectedSkill) return;

    const intervalMinutes =
      intervalUnit === 'hours' ? parseInt(intervalValue) * 60 : parseInt(intervalValue);

    if (isNaN(intervalMinutes) || intervalMinutes < 1) {
      alert('请输入有效的间隔时间');
      return;
    }

    try {
      await createTask(selectedWorkspace, selectedSkill, intervalMinutes);
      setShowForm(false);
      setSelectedWorkspace('');
      setSelectedSkill('');
      setIntervalValue('30');
    } catch (err) {
      console.error('Failed to create task:', err);
    }
  };

  const handleToggle = async (task: CronTask) => {
    try {
      await updateTask(task.id, { enabled: !task.enabled });
    } catch (err) {
      console.error('Failed to toggle task:', err);
    }
  };

  const handleDelete = async (taskId: string) => {
    if (!confirm('确定要删除这个定时任务吗？')) return;

    try {
      await deleteTask(taskId);
    } catch (err) {
      console.error('Failed to delete task:', err);
    }
  };

  const handleExecute = async (taskId: string) => {
    try {
      await executeNow(taskId);
      // 刷新任务列表以获取最新执行记录
      await fetchTasks();
    } catch (err) {
      console.error('Failed to execute task:', err);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-lg font-medium">定时任务</h3>
          <p className="text-sm text-muted-foreground">
            配置定时执行 Skill 的任务
          </p>
        </div>
        <Button onClick={() => setShowForm(!showForm)}>
          <Plus className="h-4 w-4 mr-2" />
          新建任务
        </Button>
      </div>

      {error && (
        <div className="p-3 rounded-lg bg-destructive/10 text-destructive text-sm flex items-center justify-between">
          <span>{error}</span>
          <Button variant="ghost" size="sm" onClick={clearError}>
            关闭
          </Button>
        </div>
      )}

      {showForm && (
        <div className="p-4 rounded-lg border bg-muted/50 space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>业务系统</Label>
              <Select value={selectedWorkspace} onValueChange={setSelectedWorkspace}>
                <SelectTrigger>
                  <SelectValue placeholder="选择业务系统" />
                </SelectTrigger>
                <SelectContent>
                  {workspaces.map((ws) => (
                    <SelectItem key={ws} value={ws}>
                      {ws.split(/[/\\]/).pop()}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-2">
              <Label>Skill</Label>
              <Select
                value={selectedSkill}
                onValueChange={setSelectedSkill}
                disabled={!selectedWorkspace}
              >
                <SelectTrigger>
                  <SelectValue placeholder="选择 Skill" />
                </SelectTrigger>
                <SelectContent>
                  {skills.map((skill) => (
                    <SelectItem key={skill.name} value={skill.name}>
                      {skill.name}
                      {!skill.hasCrontab && (
                        <span className="text-muted-foreground ml-1">(无 crontab.md)</span>
                      )}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="space-y-2">
            <Label>执行间隔</Label>
            <div className="flex items-center gap-2">
              <Input
                type="number"
                min={1}
                max={60}
                value={intervalValue}
                onChange={(e) => setIntervalValue(e.target.value)}
                className="w-20"
              />
              <Select
                value={intervalUnit}
                onValueChange={(v) => setIntervalUnit(v as 'minutes' | 'hours')}
              >
                <SelectTrigger className="w-24">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="minutes">分钟</SelectItem>
                  <SelectItem value="hours">小时</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setShowForm(false)}>
              取消
            </Button>
            <Button
              onClick={handleCreate}
              disabled={!selectedWorkspace || !selectedSkill}
            >
              创建
            </Button>
          </div>
        </div>
      )}

      <div className="space-y-2">
        {loading ? (
          <div className="text-center py-8 text-muted-foreground">加载中...</div>
        ) : tasks.length === 0 ? (
          <div className="text-center py-8 text-muted-foreground">
            暂无定时任务，点击"新建任务"开始配置
          </div>
        ) : (
          tasks.map((task) => (
            <TaskItem
              key={task.id}
              task={task}
              onDelete={() => handleDelete(task.id)}
              onToggle={() => handleToggle(task)}
              onExecute={() => handleExecute(task.id)}
            />
          ))
        )}
      </div>
    </div>
  );
}
```

---

### Task 10: 集成到设置页面

**Files:**
- Modify: `src/features/settings/index.tsx`

- [ ] **Step 1: 导入并添加 CronTaskSettings**

```tsx
import { useEffect } from 'react';
import { LLMSettings } from './LLMSettings';
import { WebSearchSettings } from './WebSearchSettings';
import { CronTaskSettings } from './CronTaskSettings';
import { useSettingsStore } from '@/stores/settings';

export function Settings() {
  const fetchSettings = useSettingsStore((s) => s.fetchSettings);

  useEffect(() => {
    fetchSettings();
  }, [fetchSettings]);

  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">设置</h1>
        <p className="text-muted-foreground mt-1">配置 Sman</p>
      </div>

      <div className="max-w-2xl space-y-6">
        <LLMSettings />
        <WebSearchSettings />
        <CronTaskSettings />
      </div>
    </div>
  );
}

export default Settings;
```

---

## 验证清单

- [ ] 后端编译通过：`pnpm exec tsc --noEmit`
- [ ] 前端编译通过：`pnpm exec tsc --noEmit`
- [ ] 后端启动成功：`pnpm dev:server`
- [ ] 前端启动成功：`pnpm dev`
- [ ] WebSocket 连接正常
- [ ] 定时任务 CRUD 正常
- [ ] 定时执行正常
- [ ] 并发控制正常（重复触发时跳过）
- [ ] 假死检测正常（30 分钟无响应强制终止）
