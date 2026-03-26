import { createLogger, type Logger } from './utils/logger.js';
import type { CronTaskStore } from './cron-task-store.js';
import { CronExecutor } from './cron-executor.js';
import type { CronTask } from './types.js';
import type { ClaudeSessionManager } from './claude-session.js';

export class CronScheduler {
  private log: Logger;
  private timers = new Map<string, ReturnType<typeof setInterval>>(); // taskId -> timer
  private nextRunAt = new Map<string, number>(); // taskId -> timestamp
  private executor: CronExecutor;

  constructor(
    private taskStore: CronTaskStore,
  ) {
    this.log = createLogger('CronScheduler');
    this.executor = new CronExecutor(taskStore);
  }

  /**
   * 设置 SessionManager（在 ClaudeSessionManager 初始化后调用）
   */
  setSessionManager(sm: ClaudeSessionManager): void {
    this.executor.setSessionManager(sm);
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
    for (const [, timer] of this.timers) {
      clearInterval(timer);
    }
    this.timers.clear();
    this.nextRunAt.clear();
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
    const nextTime = Date.now() + intervalMs;
    this.nextRunAt.set(task.id, nextTime);

    const timer = setInterval(() => {
      this.nextRunAt.set(task.id, Date.now() + intervalMs);
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
   * 获取下次执行时间
   */
  getNextRunAt(taskId: string): string | null {
    const ts = this.nextRunAt.get(taskId);
    return ts ? new Date(ts).toISOString() : null;
  }

  /**
   * 获取执行器
   */
  getExecutor(): CronExecutor {
    return this.executor;
  }
}
