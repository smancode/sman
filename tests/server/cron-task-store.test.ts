import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { CronTaskStore } from '../../server/cron-task-store.js';
import betterSqlite3 from 'better-sqlite3';
import fs from 'fs';
import path from 'path';
import os from 'os';

// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;

describe('CronTaskStore', () => {
  let store: CronTaskStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `smanbase-cron-test-${Date.now()}.db`);
    store = new CronTaskStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  it('should create a task with cronExpression', () => {
    const task = store.createTask({
      workspace: '/data/projectA',
      skillName: 'daily-check',
      cronExpression: '0 9 * * 1-5',
    });
    expect(task.cronExpression).toBe('0 9 * * 1-5');
    expect(task.workspace).toBe('/data/projectA');
    expect(task.skillName).toBe('daily-check');
    expect(task.enabled).toBe(true);
  });

  it('should get task by id', () => {
    const created = store.createTask({
      workspace: '/data/projectA',
      skillName: 'daily-check',
      cronExpression: '0 9 * * *',
    });
    const task = store.getTask(created.id);
    expect(task).toBeDefined();
    expect(task!.cronExpression).toBe('0 9 * * *');
  });

  it('should get task by workspace and skill', () => {
    store.createTask({
      workspace: '/data/projectA',
      skillName: 'daily-check',
      cronExpression: '0 9 * * *',
    });
    const task = store.getTaskByWorkspaceAndSkill('/data/projectA', 'daily-check');
    expect(task).toBeDefined();
    expect(task!.cronExpression).toBe('0 9 * * *');

    const notFound = store.getTaskByWorkspaceAndSkill('/data/projectA', 'other-skill');
    expect(notFound).toBeUndefined();
  });

  it('should list tasks', () => {
    store.createTask({ workspace: '/a', skillName: 's1', cronExpression: '*/5 * * * *' });
    store.createTask({ workspace: '/b', skillName: 's2', cronExpression: '0 * * * *' });
    const tasks = store.listTasks();
    expect(tasks).toHaveLength(2);
  });

  it('should list enabled tasks only', () => {
    const t1 = store.createTask({ workspace: '/a', skillName: 's1', cronExpression: '*/5 * * * *' });
    store.createTask({ workspace: '/b', skillName: 's2', cronExpression: '0 * * * *' });
    store.updateTask(t1.id, { enabled: false });
    const enabled = store.listEnabledTasks();
    expect(enabled).toHaveLength(1);
    expect(enabled[0].skillName).toBe('s2');
  });

  it('should update task cronExpression', () => {
    const created = store.createTask({
      workspace: '/a',
      skillName: 's1',
      cronExpression: '*/5 * * * *',
    });
    const updated = store.updateTask(created.id, { cronExpression: '0 9 * * 1-5' });
    expect(updated!.cronExpression).toBe('0 9 * * 1-5');
  });

  it('should update task enabled state', () => {
    const created = store.createTask({
      workspace: '/a',
      skillName: 's1',
      cronExpression: '*/5 * * * *',
    });
    const updated = store.updateTask(created.id, { enabled: false });
    expect(updated!.enabled).toBe(false);
  });

  it('should delete task', () => {
    const created = store.createTask({
      workspace: '/a',
      skillName: 's1',
      cronExpression: '*/5 * * * *',
    });
    store.deleteTask(created.id);
    expect(store.getTask(created.id)).toBeUndefined();
  });

  it('should create and list run records', () => {
    const task = store.createTask({ workspace: '/a', skillName: 's1', cronExpression: '*/5 * * * *' });
    const run = store.createRun(task.id, 'cron-test-session-1');
    expect(run.status).toBe('running');
    expect(run.taskId).toBe(task.id);

    const runs = store.listRuns(task.id);
    expect(runs).toHaveLength(1);
  });

  it('should update run status', () => {
    const task = store.createTask({ workspace: '/a', skillName: 's1', cronExpression: '*/5 * * * *' });
    const run = store.createRun(task.id, 'cron-test-session-1');
    store.updateRun(run.id, { status: 'success' });

    const latest = store.getLatestRun(task.id);
    expect(latest!.status).toBe('success');
    expect(latest!.finishedAt).toBeDefined();
  });

  it('should drop old table with interval_minutes and recreate', () => {
    // 关闭当前 store，手动创建旧表结构
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);

    // 模拟旧表结构：有 interval_minutes NOT NULL
    const rawDb = new DatabaseConstructor(dbPath);
    rawDb.exec(`
      CREATE TABLE cron_tasks (
        id TEXT PRIMARY KEY,
        workspace TEXT NOT NULL,
        skill_name TEXT NOT NULL,
        interval_minutes INTEGER NOT NULL,
        enabled INTEGER NOT NULL DEFAULT 1,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
      INSERT INTO cron_tasks (id, workspace, skill_name, interval_minutes, enabled, created_at, updated_at)
        VALUES ('old-task-1', '/old/project', 'old-skill', 30, 1, '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z');
    `);
    rawDb.close();

    // 用 CronTaskStore 重新打开，触发迁移（旧表被删，新表被建）
    const migratedStore = new CronTaskStore(dbPath);

    // 旧数据已随旧表删除
    expect(migratedStore.getTask('old-task-1')).toBeUndefined();

    // 新任务可以正常创建
    const newTask = migratedStore.createTask({
      workspace: '/new/project',
      skillName: 'new-skill',
      cronExpression: '0 9 * * 1-5',
    });
    expect(newTask.cronExpression).toBe('0 9 * * 1-5');

    migratedStore.close();
    store = migratedStore;
  });
});
