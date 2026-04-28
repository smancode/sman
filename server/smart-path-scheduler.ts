/**
 * SmartPathScheduler — 地球路径定时调度
 * 管理 cron 表达式驱动的自动执行
 */
import cron from 'node-cron';
import type { ScheduledTask } from 'node-cron';
import { CronExpressionParser } from 'cron-parser';
import { createLogger, type Logger } from './utils/logger.js';
import type { SmartPathStore } from './smart-path-store.js';
import type { SmartPathEngine } from './smart-path-engine.js';
import type { SmartPath } from './types.js';

interface ActiveSchedule {
  task: ScheduledTask;
  pathId: string;
  workspace: string;
  cronExpression: string;
}

export class SmartPathScheduler {
  private log: Logger;
  private schedules = new Map<string, ActiveSchedule>();
  private store: SmartPathStore;
  private engine: SmartPathEngine;
  private onProgress?: (pathId: string, data: { status: string; path?: SmartPath }) => void;

  constructor(store: SmartPathStore, engine: SmartPathEngine) {
    this.store = store;
    this.engine = engine;
    this.log = createLogger('SmartPathScheduler');
  }

  setOnProgress(cb: (pathId: string, data: { status: string; path?: SmartPath }) => void): void {
    this.onProgress = cb;
  }

  /** 启动时加载所有有 cron 表达式的路径 */
  start(workspaces: string[]): void {
    const allPaths = this.store.listAll(workspaces);
    for (const p of allPaths) {
      if (p.cronExpression && p.status !== 'running') {
        this.schedule(p);
      }
    }
    this.log.info(`Started with ${this.schedules.size} scheduled path(s)`);
  }

  /** 调度一个路径 */
  schedule(smartPath: SmartPath): void {
    this.unschedule(smartPath.id);

    if (!smartPath.cronExpression) return;
    if (!cron.validate(smartPath.cronExpression)) {
      this.log.error(`Invalid cron expression for path ${smartPath.id}: ${smartPath.cronExpression}`);
      return;
    }

    const task = cron.schedule(smartPath.cronExpression, () => {
      this.execute(smartPath.id, smartPath.workspace);
    });

    this.schedules.set(smartPath.id, {
      task,
      pathId: smartPath.id,
      workspace: smartPath.workspace,
      cronExpression: smartPath.cronExpression,
    });
    this.log.info(`Path ${smartPath.id} scheduled: ${smartPath.cronExpression}`);
  }

  /** 取消调度 */
  unschedule(pathId: string): void {
    const s = this.schedules.get(pathId);
    if (s) {
      s.task.stop();
      this.schedules.delete(pathId);
      this.log.info(`Path ${pathId} unscheduled`);
    }
  }

  /** 更新调度（cron 表达式变更时调用） */
  reschedule(smartPath: SmartPath): void {
    this.unschedule(smartPath.id);
    if (smartPath.cronExpression) {
      this.schedule(smartPath);
    }
  }

  /** 执行路径 */
  private async execute(pathId: string, workspace: string): Promise<void> {
    this.log.info(`Executing scheduled path ${pathId}...`);
    try {
      this.onProgress?.(pathId, { status: 'running' });

      await this.engine.run(
        pathId,
        workspace,
        () => {},
        () => {},
        () => {},
      );

      const p = this.store.get(pathId, workspace);
      this.onProgress?.(pathId, { status: 'completed', path: p || undefined });
      this.log.info(`Scheduled path ${pathId} completed`);
    } catch (err) {
      this.onProgress?.(pathId, { status: 'failed' });
      this.log.error(`Scheduled path ${pathId} failed`, { error: err });
    }
  }

  /** 获取下次执行时间 */
  getNextRunAt(pathId: string): string | null {
    const s = this.schedules.get(pathId);
    if (!s) return null;
    try {
      const interval = CronExpressionParser.parse(s.cronExpression);
      return interval.next().toISOString();
    } catch {
      return null;
    }
  }

  /** 停止所有调度 */
  stop(): void {
    for (const s of this.schedules.values()) s.task.stop();
    this.schedules.clear();
    this.log.info('SmartPathScheduler stopped');
  }
}
