import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from './utils/logger.js';
import type { CronTaskStore } from './cron-task-store.js';
import type { CronTask } from './types.js';
import type { ClaudeSessionManager } from './claude-session.js';
import { parseCrontabMd } from './cron-scheduler.js';

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
  private zombieCheckInterval: ReturnType<typeof setInterval> | null = null;
  private _sessionManager: ClaudeSessionManager | null = null;

  constructor(
    private taskStore: CronTaskStore,
  ) {
    this.log = createLogger('CronExecutor');
  }

  /**
   * 设置 SessionManager
   */
  setSessionManager(sm: ClaudeSessionManager): void {
    this._sessionManager = sm;
  }

  private get sessionManager(): ClaudeSessionManager {
    if (!this._sessionManager) {
      throw new Error('SessionManager not set');
    }
    return this._sessionManager;
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
      triggeredAt: new Date().toLocaleString('zh-CN', { hour12: false }),
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
    const now = new Date();
    const y = now.getFullYear();
    const M = String(now.getMonth() + 1).padStart(2, '0');
    const d = String(now.getDate()).padStart(2, '0');
    const h = String(now.getHours()).padStart(2, '0');
    const m = String(now.getMinutes()).padStart(2, '0');
    const s = String(now.getSeconds()).padStart(2, '0');
    return `cron-${projectName}-${task.skillName}-${y}${M}${d}${h}${m}${s}`;
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

    const crontabContent = fs.readFileSync(crontabPath, 'utf-8');
    if (!crontabContent.trim()) {
      this.log.warn(`crontab.md is empty for task ${task.id}`);
      return;
    }

    // 解析 crontab.md：第一行可能是 cron 表达式，需要跳过
    const promptContent = extractPromptContent(crontabContent);
    if (!promptContent) {
      this.log.warn(`crontab.md has no prompt content for task ${task.id}`);
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
    const prompt = `/${task.skillName} ${promptContent}`;
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
    for (const [, run] of this.activeRuns) {
      try {
        run.abortController.abort();
      } catch {
        // ignore
      }
    }
    this.activeRuns.clear();
  }
}

/**
 * 从 crontab.md 内容中提取提示词
 * 如果第一行是合法 cron 表达式，跳过它，返回后续内容
 * 否则返回全部内容
 */
function extractPromptContent(content: string): string {
  const parsed = parseCrontabMd(content);
  if (parsed) return parsed.promptContent;
  return content.replace(/^\uFEFF/, '').trim();
}
