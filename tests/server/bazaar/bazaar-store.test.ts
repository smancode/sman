// tests/server/bazaar/bazaar-store.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { BazaarStore } from '../../../server/bazaar/bazaar-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('BazaarStore', () => {
  let store: BazaarStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `bazaar-local-test-${Date.now()}.db`);
    store = new BazaarStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  describe('saveIdentity / getIdentity', () => {
    it('should persist and retrieve agent identity', () => {
      store.saveIdentity({
        agentId: 'agent-001',
        hostname: 'VDI-01',
        username: 'zhangsan',
        name: '张三',
        server: 'bazaar.company.com:5890',
      });
      const identity = store.getIdentity();
      expect(identity).toBeDefined();
      expect(identity!.agentId).toBe('agent-001');
      expect(identity!.server).toBe('bazaar.company.com:5890');
    });

    it('should return undefined when no identity saved', () => {
      expect(store.getIdentity()).toBeUndefined();
    });
  });

  describe('task CRUD', () => {
    it('should save and list tasks', () => {
      store.saveTask({
        taskId: 'task-001',
        direction: 'outgoing',
        helperAgentId: 'agent-002',
        helperName: '小李',
        question: '支付查询',
        status: 'chatting',
        createdAt: new Date().toISOString(),
      });

      const tasks = store.listTasks();
      expect(tasks).toHaveLength(1);
      expect(tasks[0].taskId).toBe('task-001');
      expect(tasks[0].direction).toBe('outgoing');
    });

    it('should update task status', () => {
      store.saveTask({
        taskId: 'task-001',
        direction: 'incoming',
        question: 'test',
        status: 'chatting',
        createdAt: new Date().toISOString(),
      });

      store.updateTaskStatus('task-001', 'completed');
      const tasks = store.listTasks();
      expect(tasks[0].status).toBe('completed');
    });

    it('should list tasks ordered by createdAt DESC', () => {
      const t1 = new Date('2026-04-10T10:00:00Z').toISOString();
      const t2 = new Date('2026-04-10T11:00:00Z').toISOString();
      store.saveTask({ taskId: 't1', direction: 'incoming', question: 'q1', status: 'chatting', createdAt: t1 });
      store.saveTask({ taskId: 't2', direction: 'incoming', question: 'q2', status: 'chatting', createdAt: t2 });

      const tasks = store.listTasks();
      expect(tasks[0].taskId).toBe('t2'); // 最新的在前
    });
  });
});
