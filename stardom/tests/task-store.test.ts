// stardom/tests/task-store.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TaskStore } from '../src/task-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('TaskStore', () => {
  let store: TaskStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `stardom-task-test-${Date.now()}.db`);
    store = new TaskStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  describe('createTask / getTask', () => {
    it('should create and retrieve a task', () => {
      store.createTask({
        id: 'task-001',
        requesterId: 'agent-001',
        question: '支付查询怎么实现？',
        capabilityQuery: '支付 查询',
        status: 'searching',
      });
      const task = store.getTask('task-001');
      expect(task).toBeDefined();
      expect(task!.id).toBe('task-001');
      expect(task!.requesterId).toBe('agent-001');
      expect(task!.question).toBe('支付查询怎么实现？');
      expect(task!.status).toBe('searching');
    });

    it('should return undefined for non-existent task', () => {
      expect(store.getTask('non-existent')).toBeUndefined();
    });
  });

  describe('updateTaskStatus', () => {
    it('should update task status', () => {
      store.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'searching' });
      store.updateTaskStatus('t1', 'offered', { helperId: 'agent-002', helperName: '小李' });
      const task = store.getTask('t1');
      expect(task!.status).toBe('offered');
      expect(task!.helperId).toBe('agent-002');
    });

    it('should update to completed with rating', () => {
      store.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting', helperId: 'a2' });
      store.updateTaskStatus('t1', 'completed', { rating: 5, feedback: '很棒' });
      const task = store.getTask('t1');
      expect(task!.status).toBe('completed');
      expect(task!.rating).toBe(5);
      expect(task!.feedback).toBe('很棒');
      expect(task!.completedAt).toBeDefined();
    });
  });

  describe('listActiveTasks', () => {
    it('should return only active tasks', () => {
      store.createTask({ id: 't1', requesterId: 'a1', question: 'q1', capabilityQuery: 'c', status: 'chatting' });
      store.createTask({ id: 't2', requesterId: 'a1', question: 'q2', capabilityQuery: 'c', status: 'completed' });
      store.createTask({ id: 't3', requesterId: 'a1', question: 'q3', capabilityQuery: 'c', status: 'offered' });
      const active = store.listActiveTasks();
      expect(active).toHaveLength(2);
      expect(active.map(t => t.id)).toContain('t1');
      expect(active.map(t => t.id)).toContain('t3');
    });
  });

  describe('listTasksByAgent', () => {
    it('should return tasks where agent is requester or helper', () => {
      store.createTask({ id: 't1', requesterId: 'a1', question: 'q1', capabilityQuery: 'c', status: 'chatting', helperId: 'a2' });
      store.createTask({ id: 't2', requesterId: 'a3', question: 'q2', capabilityQuery: 'c', status: 'chatting', helperId: 'a1' });
      store.createTask({ id: 't3', requesterId: 'a3', question: 'q3', capabilityQuery: 'c', status: 'searching' });
      const tasks = store.listTasksByAgent('a1');
      expect(tasks).toHaveLength(2);
    });
  });

  describe('getActiveTaskCount', () => {
    it('should count active tasks for an agent (requester or helper)', () => {
      store.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting' });
      store.createTask({ id: 't2', requesterId: 'a3', question: 'q', capabilityQuery: 'c', status: 'chatting', helperId: 'a1' });
      store.createTask({ id: 't3', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'completed' });
      expect(store.getActiveTaskCount('a1')).toBe(2);
    });
  });

  describe('saveChatMessage / listChatMessages', () => {
    it('should persist and retrieve chat messages', () => {
      store.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting' });
      store.saveChatMessage('t1', 'agent-001', '你好，我需要帮助');
      store.saveChatMessage('t1', 'agent-002', '好的，我来帮你');
      const messages = store.listChatMessages('t1');
      expect(messages).toHaveLength(2);
      expect(messages[0].from).toBe('agent-001');
      expect(messages[1].text).toBe('好的，我来帮你');
    });

    it('should return empty array for task with no messages', () => {
      expect(store.listChatMessages('no-task')).toEqual([]);
    });
  });
});
