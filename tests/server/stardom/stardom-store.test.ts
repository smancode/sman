// tests/server/stardom/stardom-store.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { StardomStore } from '../../../server/stardom/stardom-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('StardomStore', () => {
  let store: StardomStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `stardom-local-test-${Date.now()}.db`);
    store = new StardomStore(dbPath);
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
        server: 'stardom.company.com:5890',
      });
      const identity = store.getIdentity();
      expect(identity).toBeDefined();
      expect(identity!.agentId).toBe('agent-001');
      expect(identity!.server).toBe('stardom.company.com:5890');
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

  describe('chat_messages', () => {
    it('should save and list chat messages', () => {
      store.saveChatMessage({
        taskId: 'task-001',
        from: 'remote',
        text: '你好，请问支付系统怎么查？',
      });
      store.saveChatMessage({
        taskId: 'task-001',
        from: 'local',
        text: '让我查一下支付系统的转账记录...',
      });

      const messages = store.listChatMessages('task-001');
      expect(messages).toHaveLength(2);
      expect(messages[0].from).toBe('remote');
      expect(messages[1].from).toBe('local');
    });

    it('should list messages ordered by createdAt ASC', () => {
      store.saveChatMessage({ taskId: 'task-001', from: 'remote', text: 'first' });
      store.saveChatMessage({ taskId: 'task-001', from: 'local', text: 'second' });
      store.saveChatMessage({ taskId: 'task-001', from: 'remote', text: 'third' });

      const messages = store.listChatMessages('task-001');
      expect(messages[0].text).toBe('first');
      expect(messages[2].text).toBe('third');
    });

    it('should return empty array for non-existent task', () => {
      const messages = store.listChatMessages('non-existent');
      expect(messages).toHaveLength(0);
    });

    it('should separate messages by task', () => {
      store.saveChatMessage({ taskId: 'task-001', from: 'remote', text: 'q1' });
      store.saveChatMessage({ taskId: 'task-002', from: 'remote', text: 'q2' });

      expect(store.listChatMessages('task-001')).toHaveLength(1);
      expect(store.listChatMessages('task-002')).toHaveLength(1);
    });
  });

  describe('learned_routes', () => {
    it('should save and find learned routes by capability', () => {
      store.saveLearnedRoute({ capability: '支付查询', agentId: 'agent-002', agentName: '小李' });
      store.saveLearnedRoute({ capability: '风控规则', agentId: 'agent-003', agentName: '王五' });

      const results = store.findLearnedRoutes('支付');
      expect(results).toHaveLength(1);
      expect(results[0].agentId).toBe('agent-002');
    });

    it('should accumulate multiple routes for same capability', () => {
      store.saveLearnedRoute({ capability: '支付', agentId: 'a1', agentName: 'A' });
      store.saveLearnedRoute({ capability: '支付', agentId: 'a2', agentName: 'B' });

      const results = store.findLearnedRoutes('支付');
      expect(results).toHaveLength(2);
    });

    it('should update existing route (upsert by capability + agentId)', () => {
      store.saveLearnedRoute({ capability: '支付', agentId: 'a1', agentName: 'A' });
      store.saveLearnedRoute({ capability: '支付', agentId: 'a1', agentName: 'A-Updated' });

      const results = store.findLearnedRoutes('支付');
      expect(results).toHaveLength(1);
      expect(results[0].agentName).toBe('A-Updated');
    });

    it('should return empty array when no matches', () => {
      store.saveLearnedRoute({ capability: '支付', agentId: 'a1', agentName: 'A' });
      const results = store.findLearnedRoutes('风控');
      expect(results).toHaveLength(0);
    });

    it('should list all learned routes', () => {
      store.saveLearnedRoute({ capability: '支付', agentId: 'a1', agentName: 'A' });
      store.saveLearnedRoute({ capability: '风控', agentId: 'a2', agentName: 'B' });

      const all = store.listLearnedRoutes();
      expect(all).toHaveLength(2);
    });

    it('should save and retrieve experience field', () => {
      store.saveLearnedRoute({
        capability: '支付查询',
        agentId: 'agent-002',
        agentName: '小李',
        experience: '用 JOIN 优化了支付流水查询，将查询时间从 3s 降到 50ms',
      });

      const results = store.findLearnedRoutes('支付');
      expect(results).toHaveLength(1);
      expect(results[0].experience).toBe('用 JOIN 优化了支付流水查询，将查询时间从 3s 降到 50ms');
    });

    it('should search experience field by keyword', () => {
      store.saveLearnedRoute({ capability: '支付', agentId: 'a1', agentName: 'A', experience: '风控规则配置需要修改白名单' });

      const results = store.findLearnedRoutes('风控');
      expect(results).toHaveLength(1);
      expect(results[0].agentId).toBe('a1');
    });

    it('should return empty experience by default', () => {
      store.saveLearnedRoute({ capability: '支付', agentId: 'a1', agentName: 'A' });

      const results = store.findLearnedRoutes('支付');
      expect(results[0].experience).toBe('');
    });
  });

  describe('pair_history', () => {
    it('should save and get pair history', () => {
      store.savePairHistory({ partnerId: 'agent-002', partnerName: '小李', rating: 4.5 });
      const pair = store.getPairHistory('agent-002');
      expect(pair).toBeDefined();
      expect(pair!.partnerName).toBe('小李');
      expect(pair!.taskCount).toBe(1);
      expect(pair!.avgRating).toBe(4.5);
    });

    it('should accumulate pair history (upsert)', () => {
      store.savePairHistory({ partnerId: 'a1', partnerName: 'A', rating: 4 });
      store.savePairHistory({ partnerId: 'a1', partnerName: 'A', rating: 5 });

      const pair = store.getPairHistory('a1');
      expect(pair!.taskCount).toBe(2);
      expect(pair!.totalRating).toBe(9);
      expect(pair!.avgRating).toBe(4.5);
    });

    it('should return undefined for unknown partner', () => {
      expect(store.getPairHistory('unknown')).toBeUndefined();
    });

    it('should list all pair histories sorted by avgRating', () => {
      store.savePairHistory({ partnerId: 'a1', partnerName: 'A', rating: 3 });
      store.savePairHistory({ partnerId: 'a2', partnerName: 'B', rating: 5 });

      const pairs = store.listPairHistories();
      expect(pairs).toHaveLength(2);
      expect(pairs[0].partnerId).toBe('a2'); // avgRating 5 first
    });
  });
});
