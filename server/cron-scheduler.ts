import cron from 'node-cron';
import type { ScheduledTask } from 'node-cron';
import { CronExpressionParser } from 'cron-parser';
import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from './utils/logger.js';
import type { CronTaskStore } from './cron-task-store.js';
import { CronExecutor } from './cron-executor.js';
import type { CronTask } from './types.js';
import type { ClaudeSessionManager } from './claude-session.js';
import type { SessionStore } from './session-store.js';

const SCAN_INTERVAL_MS = 30 * 60 * 1000; // 30 分钟

export class CronScheduler {
  private log: Logger;
  private jobs = new Map<string, ScheduledTask>(); // taskId -> job
  private executor: CronExecutor;
  private _sessionManager: ClaudeSessionManager | null = null;
  private _sessionStore: SessionStore | null = null;
  private scanTimer: ReturnType<typeof setInterval> | null = null;
  private isStarted = false;
  private isScanning = false;

  constructor(
    private taskStore: CronTaskStore,
  ) {
    this.log = createLogger('CronScheduler');
    this.executor = new CronExecutor(taskStore);
  }

  setSessionManager(sm: ClaudeSessionManager): void {
    this._sessionManager = sm;
    this.executor.setSessionManager(sm);
  }

  setSessionStore(store: SessionStore): void {
    this._sessionStore = store;
  }

  start(): void {
    if (this.isStarted) return;
    this.isStarted = true;

    this.log.info('Starting CronScheduler...');

    // 清理孤儿记录：进程重启后 activeRuns 为空，数据库中 status=running 的都是未正常结束的
    this.recoverOrphanRuns();

    const tasks = this.taskStore.listEnabledTasks();
    for (const task of tasks) {
      this.schedule(task);
    }

    this.executor.startZombieCheck();
    this.startAutoScan();

    this.log.info(`CronScheduler started with ${tasks.length} tasks`);
  }

  /**
   * 清理孤儿执行记录
   * 进程重启后，activeRuns（内存 Map）丢失，数据库中残留的 running 记录永远不会被更新
   * 将这些记录标记为 failed，避免任务永远卡在"执行中"
   */
  private recoverOrphanRuns(): void {
    const orphans = this.taskStore.getRunningRuns();
    if (orphans.length === 0) return;

    this.log.warn(`Found ${orphans.length} orphan running run(s), marking as failed`);

    for (const run of orphans) {
      this.taskStore.updateRun(run.id, {
        status: 'failed',
        errorMessage: '进程重启，任务异常终止',
      });
    }
  }

  stop(): void {
    this.log.info('Stopping CronScheduler...');
    this.jobs.forEach(job => job.stop());
    this.jobs.clear();
    this.stopAutoScan();
    this.executor.stopZombieCheck();
    this.isStarted = false;
    this.log.info('CronScheduler stopped');
  }

  schedule(task: CronTask): void {
    this.unschedule(task.id);

    if (!task.enabled) {
      this.log.info(`Task ${task.id} is disabled, not scheduling`);
      return;
    }

    if (!cron.validate(task.cronExpression)) {
      this.log.error(`Invalid cron expression for task ${task.id}: ${task.cronExpression}`);
      return;
    }

    const job = cron.schedule(task.cronExpression, () => {
      this.executeTask(task);
    });

    this.jobs.set(task.id, job);
    this.log.info(`Task ${task.id} scheduled: ${task.cronExpression}`);
  }

  unschedule(taskId: string): void {
    const job = this.jobs.get(taskId);
    if (job) {
      job.stop();
      this.jobs.delete(taskId);
      this.log.info(`Task ${taskId} unscheduled`);
    }
  }

  private async executeTask(task: CronTask): Promise<void> {
    this.log.info(`Executing task ${task.id}...`);
    try {
      await this.executor.execute(task);
    } catch (err) {
      this.log.error(`Task ${task.id} execution failed`, { error: err });
    }
  }

  async executeNow(taskId: string): Promise<void> {
    const task = this.taskStore.getTask(taskId);
    if (!task) {
      throw new Error(`Task not found: ${taskId}`);
    }
    await this.executor.execute(task);
  }

  getNextRunAt(taskId: string): string | null {
    const task = this.taskStore.getTask(taskId);
    if (!task || !task.enabled) return null;

    try {
      const interval = CronExpressionParser.parse(task.cronExpression);
      return interval.next().toISOString();
    } catch {
      return null;
    }
  }

  // === Auto Scan ===

  private startAutoScan(): void {
    this.scanTimer = setInterval(() => {
      this.scanAndSync().catch((err) => {
        this.log.error('Auto-scan failed', { error: err });
      });
    }, SCAN_INTERVAL_MS);
    this.log.info('Auto-scan started (every 30 minutes)');
  }

  private stopAutoScan(): void {
    if (this.scanTimer) {
      clearInterval(this.scanTimer);
      this.scanTimer = null;
    }
  }

  async scanAndSync(): Promise<{ created: number; updated: number; skipped: number; disabled: number }> {
    if (this.isScanning) {
      this.log.info('Scan already in progress, skipping');
      return { created: 0, updated: 0, skipped: 0, disabled: 0 };
    }
    this.isScanning = true;

    try {
      const result = { created: 0, updated: 0, skipped: 0, disabled: 0 };

      // 收集所有 workspace
      if (!this._sessionStore) {
        this.log.warn('SessionStore not set, cannot scan');
        return result;
      }
      const sessions = this._sessionStore.listSessions();
      const workspaces = [...new Set(sessions.map(s => s.workspace))];

      // 收集所有扫描到的 (workspace, skillName) 组合
      const scannedKeys = new Set<string>();

      for (const workspace of workspaces) {
        const skillsDir = path.join(workspace, '.claude', 'skills');
        if (!fs.existsSync(skillsDir)) continue;

        const entries = fs.readdirSync(skillsDir, { withFileTypes: true });
        for (const entry of entries) {
          if (!entry.isDirectory()) continue;

          const crontabPath = path.join(skillsDir, entry.name, 'crontab.md');
          if (!fs.existsSync(crontabPath)) continue;

          const content = fs.readFileSync(crontabPath, 'utf-8');
          const parsed = parseCrontabMd(content);
          if (!parsed) continue;

          const key = `${workspace}:${entry.name}`;
          scannedKeys.add(key);

          const existing = this.taskStore.getTaskByWorkspaceAndSkill(workspace, entry.name);
          if (existing) {
            const cronChanged = existing.cronExpression !== parsed.expression;
            // Don't override enabled state from crontab.md — user's manual toggle takes precedence
            if (cronChanged) {
              const updates: Partial<Pick<CronTask, 'cronExpression'>> = {};
              updates.cronExpression = parsed.expression;
              const updated = this.taskStore.updateTask(existing.id, updates);
              if (updated && updated.enabled) {
                this.schedule(updated);
              }
              result.updated++;
            } else {
              result.skipped++;
            }
          } else {
            const task = this.taskStore.createTask({
              workspace,
              skillName: entry.name,
              cronExpression: parsed.expression,
              source: 'scan',
              enabled: parsed.enabled ?? true,
            });
            if (task.enabled) {
              this.schedule(task);
            }
            result.created++;
          }
        }
      }

      // 禁用 crontab.md 已被删除的任务
      const allTasks = this.taskStore.listTasks();
      for (const task of allTasks) {
        const key = `${task.workspace}:${task.skillName}`;
        if (!scannedKeys.has(key) && task.enabled) {
          // 检查 crontab.md 是否还存在
          const crontabPath = path.join(task.workspace, '.claude', 'skills', task.skillName, 'crontab.md');
          if (!fs.existsSync(crontabPath)) {
            this.taskStore.updateTask(task.id, { enabled: false });
            this.unschedule(task.id);
            result.disabled++;
          }
        }
      }

      if (result.created > 0 || result.updated > 0 || result.disabled > 0) {
        this.log.info(`Scan completed: created=${result.created}, updated=${result.updated}, skipped=${result.skipped}, disabled=${result.disabled}`);
      }

      return result;
    } finally {
      this.isScanning = false;
    }
  }

  getExecutor(): CronExecutor {
    return this.executor;
  }
}

// === Crontab.md Parsing ===

// 匹配 5 段 cron 表达式（支持 */N, N-M, N,M, ~, 等标准语法）
const CRON_EXPR_RE = /^((?:[^\s]+\s+){4}[^\s]+)/;

/**
 * 解析 crontab.md 文件
 * 支持格式：
 * 1. 纯 5 段 cron 表达式（可选 frontmatter）
 * 2. cron 表达式 + 空格 + 命令/提示词（类系统 crontab）
 * 3. 前面可有 # 注释行，跳过
 * 4. YAML frontmatter（--- 包裹），提取 enabled 和 schedule 字段
 *
 * 返回 null 表示文件不包含有效 cron 表达式
 */
export function parseCrontabMd(content: string): { expression: string; promptContent: string; enabled?: boolean } | null {
  const trimmed = content.replace(/^\uFEFF/, '').trim(); // strip BOM
  let lines = trimmed.split('\n');
  if (lines.length === 0) return null;

  // 跳过 YAML frontmatter（--- ... ---）
  let enabled: boolean | undefined;
  if (lines[0].trim() === '---') {
    const endIdx = lines.findIndex((line, i) => i > 0 && line.trim() === '---');
    if (endIdx > 0) {
      // 先遍历提取所有 frontmatter 字段
      let scheduleExpr: string | undefined;
      for (let i = 1; i < endIdx; i++) {
        const fmLine = lines[i].trim();
        const enabledMatch = fmLine.match(/^enabled:\s*(true|false)/);
        if (enabledMatch) {
          enabled = enabledMatch[1] === 'true';
          continue;
        }
        const scheduleMatch = fmLine.match(/^schedule:\s*["'](.+)["']/);
        if (scheduleMatch && cron.validate(scheduleMatch[1])) {
          scheduleExpr = scheduleMatch[1];
        }
      }
      // frontmatter 中有合法 schedule，优先使用并直接返回
      if (scheduleExpr) {
        const promptContent = lines.slice(endIdx + 1).join('\n').trim();
        return { expression: scheduleExpr, promptContent, enabled };
      }
      lines = lines.slice(endIdx + 1);
    }
  }

  if (lines.length === 0) return enabled !== undefined ? null : null;

  // 跳过注释行和空行，找到第一行有效内容
  let cronLineIndex = 0;
  while (cronLineIndex < lines.length) {
    const line = lines[cronLineIndex].trim();
    if (line && !line.startsWith('#')) break;
    cronLineIndex++;
  }
  if (cronLineIndex >= lines.length) return null;

  const cronLine = lines[cronLineIndex].trim();

  // 情况 1: 整行就是 5 段 cron 表达式
  if (cron.validate(cronLine)) {
    const promptContent = lines.slice(cronLineIndex + 1).join('\n').trim();
    return { expression: cronLine, promptContent, enabled };
  }

  // 情况 2: 行首包含 5 段 cron 表达式 + 额外内容
  const match = CRON_EXPR_RE.exec(cronLine);
  if (match) {
    const expression = match[1];
    if (cron.validate(expression)) {
      const inlinePrompt = cronLine.slice(expression.length).trim();
      const remainingContent = lines.slice(cronLineIndex + 1).join('\n').trim();
      const promptContent = [inlinePrompt, remainingContent].filter(Boolean).join('\n');
      return { expression, promptContent, enabled };
    }
  }

  return null;
}
