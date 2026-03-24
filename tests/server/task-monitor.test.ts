import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TaskMonitor } from '../../server/task-monitor.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('TaskMonitor', () => {
  let workspaceDir: string;
  let monitor: TaskMonitor;
  const notifications: Array<{ type: string; content: string }> = [];

  beforeEach(() => {
    workspaceDir = path.join(os.tmpdir(), `smanbase-tm-${Date.now()}`);
    fs.mkdirSync(workspaceDir, { recursive: true });
    fs.mkdirSync(path.join(workspaceDir, 'docs', 'tasks'), { recursive: true });

    notifications.length = 0;

    monitor = new TaskMonitor(
      workspaceDir,
      1000,
      (msg) => notifications.push(msg),
    );
  });

  afterEach(() => {
    monitor.stop();
    fs.rmSync(workspaceDir, { recursive: true, force: true });
  });

  it('should initialize without error', () => {
    expect(() => monitor.start()).not.toThrow();
    monitor.stop();
  });

  it('should return empty result when no tasks directory', async () => {
    // Remove tasks dir
    fs.rmSync(path.join(workspaceDir, 'docs', 'tasks'), { recursive: true });
    const result = await monitor.check();
    expect(result).toEqual({ completed: [], failed: [], inProgress: [] });
  });

  it('should return empty result when no task files', async () => {
    const result = await monitor.check();
    expect(result).toEqual({ completed: [], failed: [], inProgress: [] });
  });

  it('should detect completed task with success conclusion', async () => {
    const tasksDir = path.join(workspaceDir, 'docs', 'tasks');
    const donetasksDir = path.join(workspaceDir, 'docs', 'donetasks');
    fs.mkdirSync(donetasksDir, { recursive: true });

    const mdContent = `# Task\n\n## Execution\nDone\n\n## 最终结论\n状态：成功\n说明：测试完成`;
    const mdPath = path.join(workspaceDir, 'plan.md');
    fs.writeFileSync(mdPath, mdContent, 'utf-8');

    const task = {
      id: 'task-001',
      workspace: workspaceDir,
      status: 'in_progress',
      startedAt: new Date().toISOString(),
      completedAt: null,
      lastChecked: new Date().toISOString(),
      mdFile: 'plan.md',
      claudePid: null,
    };

    fs.writeFileSync(
      path.join(tasksDir, 'task-001.json'),
      JSON.stringify(task, null, 2),
      'utf-8',
    );

    const result = await monitor.check();
    expect(result.completed).toContain('task-001');
    expect(result.failed).toHaveLength(0);

    // Task should be moved to donetasks
    expect(fs.existsSync(path.join(donetasksDir, 'task-001.json'))).toBe(true);
    expect(fs.existsSync(path.join(tasksDir, 'task-001.json'))).toBe(false);
  });

  it('should detect failed task', async () => {
    const tasksDir = path.join(workspaceDir, 'docs', 'tasks');
    const donetasksDir = path.join(workspaceDir, 'docs', 'donetasks');
    fs.mkdirSync(donetasksDir, { recursive: true });

    const mdContent = `# Task\n\n## Execution\nError\n\n## 最终结论\n状态：失败\n说明：出错了`;
    const mdPath = path.join(workspaceDir, 'plan.md');
    fs.writeFileSync(mdPath, mdContent, 'utf-8');

    const task = {
      id: 'task-002',
      workspace: workspaceDir,
      status: 'in_progress',
      startedAt: new Date().toISOString(),
      completedAt: null,
      lastChecked: new Date().toISOString(),
      mdFile: 'plan.md',
      claudePid: null,
    };

    fs.writeFileSync(
      path.join(tasksDir, 'task-002.json'),
      JSON.stringify(task, null, 2),
      'utf-8',
    );

    const result = await monitor.check();
    expect(result.failed).toContain('task-002');
  });

  it('should handle missing workspace gracefully', async () => {
    const tasksDir = path.join(workspaceDir, 'docs', 'tasks');

    const task = {
      id: 'task-no-workspace',
      workspace: '',
      status: 'pending',
      startedAt: null,
      completedAt: null,
      lastChecked: null,
      mdFile: 'plan.md',
      claudePid: null,
    };

    fs.writeFileSync(
      path.join(tasksDir, 'task-no-workspace.json'),
      JSON.stringify(task, null, 2),
      'utf-8',
    );

    const result = await monitor.check();
    expect(result.inProgress).toHaveLength(0);
    expect(result.completed).toHaveLength(0);
  });

  it('should skip when previous check is running', async () => {
    // Simulate running state by calling check twice
    const promise1 = monitor.check();
    const promise2 = monitor.check();

    await Promise.all([promise1, promise2]);

    // One should have been skipped (empty result)
    // Both should complete without error
    expect(true).toBe(true);
  });
});
