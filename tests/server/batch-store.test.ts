import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { BatchStore } from '../../server/batch-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('BatchStore', () => {
  let store: BatchStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `batch-test-${Date.now()}.db`);
    store = new BatchStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  // === Task CRUD ===

  describe('createTask', () => {
    it('should create a task with default values', () => {
      const task = store.createTask({
        workspace: '/data/project',
        skillName: 'stock-ai-analyze',
        mdContent: '# Batch Config\n## 执行模板\n/test ${name}',
        execTemplate: '/test ${name}',
      });
      expect(task.id).toBeDefined();
      expect(task.status).toBe('draft');
      expect(task.concurrency).toBe(10);
      expect(task.retryOnFailure).toBe(0);
      expect(task.totalItems).toBe(0);
      expect(task.successCount).toBe(0);
      expect(task.totalCost).toBe(0);
    });

    it('should create a task with custom env vars', () => {
      const task = store.createTask({
        workspace: '/data/project',
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${item}',
        envVars: { DB_HOST: 'localhost', DB_PORT: '3306' },
      });
      expect(task.envVars).toBe(JSON.stringify({ DB_HOST: 'localhost', DB_PORT: '3306' }));
    });
  });

  describe('getTask', () => {
    it('should return undefined for non-existent task', () => {
      expect(store.getTask('non-existent')).toBeUndefined();
    });

    it('should return the created task', () => {
      const created = store.createTask({
        workspace: '/data/project',
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${item}',
      });
      const fetched = store.getTask(created.id);
      expect(fetched).toBeDefined();
      expect(fetched!.id).toBe(created.id);
    });
  });

  describe('listTasks', () => {
    it('should return empty array initially', () => {
      expect(store.listTasks()).toHaveLength(0);
    });

    it('should list all tasks ordered by created_at DESC', async () => {
      store.createTask({ workspace: '/a', skillName: 's1', mdContent: '', execTemplate: '' });
      // Ensure different created_at timestamp
      await new Promise(r => setTimeout(r, 2));
      store.createTask({ workspace: '/b', skillName: 's2', mdContent: '', execTemplate: '' });
      const tasks = store.listTasks();
      expect(tasks).toHaveLength(2);
      // First task in list should be the most recently created
      expect(tasks[0].skillName).toBe('s2');
    });
  });

  describe('updateTask', () => {
    it('should update status', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      const updated = store.updateTask(task.id, { status: 'generated' });
      expect(updated!.status).toBe('generated');
    });

    it('should update generated_code', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      const updated = store.updateTask(task.id, { generatedCode: 'print("hello")' });
      expect(updated!.generatedCode).toBe('print("hello")');
    });

    it('should update counters', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      const updated = store.updateTask(task.id, {
        totalItems: 100,
        successCount: 50,
        failedCount: 5,
        totalCost: 1.23,
      });
      expect(updated!.totalItems).toBe(100);
      expect(updated!.successCount).toBe(50);
      expect(updated!.failedCount).toBe(5);
      expect(updated!.totalCost).toBe(1.23);
    });
  });

  describe('deleteTask', () => {
    it('should delete task and its items', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      store.createItem(task.id, 0, '{"name":"test"}');
      store.deleteTask(task.id);
      expect(store.getTask(task.id)).toBeUndefined();
      expect(store.listItems(task.id)).toHaveLength(0);
    });
  });

  // === Item CRUD ===

  describe('createItem', () => {
    it('should create an item with default status pending', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      const item = store.createItem(task.id, 0, '{"name":"贵州茅台"}');
      expect(item.id).toBeDefined();
      expect(item.status).toBe('pending');
      expect(item.itemIndex).toBe(0);
      expect(item.itemData).toBe('{"name":"贵州茅台"}');
    });
  });

  describe('bulkCreateItems', () => {
    it('should create multiple items from JSON array', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      const items = [{ name: 'a' }, { name: 'b' }, { name: 'c' }];
      const created = store.bulkCreateItems(task.id, items);
      expect(created).toHaveLength(3);
      expect(store.listItems(task.id)).toHaveLength(3);
    });
  });

  describe('listItems', () => {
    it('should list items filtered by status', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }, { name: 'c' }]);
      store.updateItem(1, { status: 'success' });
      store.updateItem(2, { status: 'failed' });

      const pending = store.listItems(task.id, { status: 'pending' });
      expect(pending).toHaveLength(1);

      const failed = store.listItems(task.id, { status: 'failed' });
      expect(failed).toHaveLength(1);
    });

    it('should support pagination', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      store.bulkCreateItems(task.id, Array.from({ length: 20 }, (_, i) => ({ name: `item-${i}` })));

      const page1 = store.listItems(task.id, { offset: 0, limit: 5 });
      expect(page1).toHaveLength(5);

      const page2 = store.listItems(task.id, { offset: 5, limit: 5 });
      expect(page2).toHaveLength(5);
      expect(page2[0].itemIndex).toBe(5);
    });
  });

  describe('updateItem', () => {
    it('should update item status and error', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      const item = store.createItem(task.id, 0, '{"name":"test"}');
      const updated = store.updateItem(item.id, {
        status: 'failed',
        errorMessage: 'timeout',
        cost: 0.05,
      });
      expect(updated!.status).toBe('failed');
      expect(updated!.errorMessage).toBe('timeout');
      expect(updated!.cost).toBe(0.05);
    });
  });

  describe('getItemCounts', () => {
    it('should return counts by status', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }, { name: 'c' }]);
      store.updateItem(1, { status: 'success' });
      store.updateItem(2, { status: 'running' });

      const counts = store.getItemCounts(task.id);
      expect(counts.pending).toBe(1);
      expect(counts.success).toBe(1);
      expect(counts.running).toBe(1);
    });
  });

  describe('getOrphanedItems', () => {
    it('should find running items for crash recovery', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }]);
      store.updateItem(1, { status: 'running' });
      store.updateItem(2, { status: 'running' });

      const orphaned = store.getOrphanedItems();
      expect(orphaned).toHaveLength(2);
    });
  });

  describe('resetRunningItems', () => {
    it('should mark running items as failed with process shutdown reason', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }]);
      store.updateItem(1, { status: 'running' });

      store.resetRunningItems('进程关闭');
      const item = store.getItem(1);
      expect(item!.status).toBe('failed');
      expect(item!.errorMessage).toBe('进程关闭');
    });
  });

  describe('resetItemsForExecution', () => {
    it('should reset all items to pending regardless of status', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }, { name: 'c' }]);
      store.updateItem(1, { status: 'success' });
      store.updateItem(2, { status: 'failed' });

      store.resetItemsForExecution(task.id);

      const items = store.listItems(task.id);
      expect(items.every(i => i.status === 'pending')).toBe(true);
    });
  });

  describe('incrementSuccessCount', () => {
    it('should atomically increment success count', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      expect(store.getTask(task.id)!.successCount).toBe(0);

      store.incrementSuccessCount(task.id);
      expect(store.getTask(task.id)!.successCount).toBe(1);

      store.incrementSuccessCount(task.id);
      store.incrementSuccessCount(task.id);
      expect(store.getTask(task.id)!.successCount).toBe(3);
    });
  });

  describe('incrementFailedCount', () => {
    it('should atomically increment failed count', () => {
      const task = store.createTask({
        workspace: '/a', skillName: 'test', mdContent: '', execTemplate: '',
      });
      expect(store.getTask(task.id)!.failedCount).toBe(0);

      store.incrementFailedCount(task.id);
      expect(store.getTask(task.id)!.failedCount).toBe(1);

      store.incrementFailedCount(task.id);
      store.incrementFailedCount(task.id);
      expect(store.getTask(task.id)!.failedCount).toBe(3);
    });
  });
});
