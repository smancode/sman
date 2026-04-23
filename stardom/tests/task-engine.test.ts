// stardom/tests/task-engine.test.ts
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { TaskEngine } from '../src/task-engine.js';
import type { TaskStore } from '../src/task-store.js';
import type { AgentStore } from '../src/agent-store.js';
import type WebSocket from 'ws';

function createMockStores() {
  const tasks: Map<string, any> = new Map();
  const sent: any[] = [];

  const mockTaskStore = {
    createTask: (input: any) => { tasks.set(input.id, { ...input, status: input.status, updatedAt: new Date().toISOString() }); },
    getTask: (id: string) => tasks.get(id),
    updateTaskStatus: vi.fn((id: string, status: string, extra?: any) => {
      const t = tasks.get(id);
      if (t) { t.status = status; if (extra?.helperId) t.helperId = extra.helperId; if (extra?.helperName) t.helperName = extra.helperName; t.updatedAt = new Date().toISOString(); }
    }),
    listActiveTasks: () => Array.from(tasks.values()).filter((t: any) => ['searching', 'offered', 'matched', 'chatting'].includes(t.status)),
    getActiveTaskCount: vi.fn((agentId: string) => Array.from(tasks.values()).filter((t: any) =>
      (t.requesterId === agentId || t.helperId === agentId) && ['searching', 'offered', 'matched', 'chatting'].includes(t.status)
    ).length),
    listTimedOutTasks: () => [],
    saveChatMessage: vi.fn(),
    listChatMessages: () => [],
    close: () => {},
  } as unknown as TaskStore;

  const mockAgentStore = {
    listOnlineAgents: vi.fn(() => []),
    findAgentsByDomain: vi.fn(() => []),
    getAgent: vi.fn(),
    logAudit: vi.fn(),
    updateAgentStatus: vi.fn(),
  } as unknown as AgentStore;

  const connections = new Map<string, WebSocket>();

  const sendTo = (agentId: string, data: unknown) => {
    sent.push({ agentId, data });
  };

  return { mockTaskStore, mockAgentStore, connections, sent, sendTo };
}

describe('TaskEngine', () => {
  let engine: TaskEngine;
  let ctx: ReturnType<typeof createMockStores>;

  beforeEach(() => {
    ctx = createMockStores();
    engine = new TaskEngine(ctx.mockTaskStore, ctx.mockAgentStore, ctx.connections, ctx.sendTo);
  });

  describe('handleTaskCreate', () => {
    it('should create task, search capabilities, and return results', () => {
      ctx.mockAgentStore.listOnlineAgents = vi.fn(() => [
        { id: 'a1', name: '支付Agent', status: 'idle', reputation: 10, description: '擅长支付查询' },
        { id: 'a2', name: '查询Agent', status: 'idle', reputation: 8, description: '擅长支付' },
      ]);

      const result = engine.handleTaskCreate({
        id: 'msg-001',
        payload: { question: '支付查询', capabilityQuery: '支付' },
      }, 'agent-req');

      expect(result.taskId).toBeDefined();
      expect(result.matches).toHaveLength(2);
      expect(result.matches[0].agentId).toBe('a1');
      expect(ctx.sent.length).toBe(0); // search_result returned, not pushed
    });

    it('should return empty matches when no agents found', () => {
      ctx.mockAgentStore.listOnlineAgents = vi.fn(() => []);

      const result = engine.handleTaskCreate({
        id: 'msg-001',
        payload: { question: '未知领域', capabilityQuery: 'xyz' },
      }, 'agent-req');

      expect(result.matches).toHaveLength(0);
    });
  });

  describe('handleTaskOffer', () => {
    it('should send task.incoming to target agent', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'searching' });
      ctx.mockTaskStore.getActiveTaskCount = vi.fn(() => 0);

      engine.handleTaskOffer({
        id: 'msg-002',
        payload: { taskId: 't1', targetAgent: 'a2' },
      }, 'a1');

      expect(ctx.mockTaskStore.updateTaskStatus).toHaveBeenCalledWith('t1', 'offered', expect.objectContaining({ helperId: 'a2' }));
      expect(ctx.sent).toHaveLength(1);
      expect(ctx.sent[0].agentId).toBe('a2');
      expect((ctx.sent[0].data as any).type).toBe('task.incoming');
    });

    it('should reject when target agent is busy (slot full)', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'searching' });
      ctx.mockTaskStore.getActiveTaskCount = vi.fn(() => 3); // slot full

      const result = engine.handleTaskOffer({
        id: 'msg-002',
        payload: { taskId: 't1', targetAgent: 'a2' },
      }, 'a1');

      expect(result.error).toContain('busy');
    });
  });

  describe('handleTaskAccept', () => {
    it('should match task and notify both agents', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'offered', helperId: 'a2' });

      engine.handleTaskAccept({
        id: 'msg-003',
        payload: { taskId: 't1' },
      }, 'a2');

      expect(ctx.mockTaskStore.updateTaskStatus).toHaveBeenCalledWith('t1', 'matched', expect.anything());
      // Should notify requester
      expect(ctx.sent.some(s => s.agentId === 'a1')).toBe(true);
      // Should notify helper
      expect(ctx.sent.some(s => s.agentId === 'a2')).toBe(true);
    });
  });

  describe('handleTaskReject', () => {
    it('should set task back to searching', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'offered', helperId: 'a2' });

      engine.handleTaskReject({
        id: 'msg-004',
        payload: { taskId: 't1' },
      }, 'a2');

      expect(ctx.mockTaskStore.updateTaskStatus).toHaveBeenCalledWith('t1', 'searching');
      // Should notify requester
      expect(ctx.sent.some(s => s.agentId === 'a1')).toBe(true);
    });
  });

  describe('handleTaskChat', () => {
    it('should relay chat to the other agent and save message', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting', helperId: 'a2' });

      engine.handleTaskChat({
        id: 'msg-005',
        payload: { taskId: 't1', text: '你好' },
      }, 'a1');

      // Should relay to a2
      expect(ctx.sent).toHaveLength(1);
      expect(ctx.sent[0].agentId).toBe('a2');
      expect((ctx.sent[0].data as any).payload.text).toBe('你好');
      expect((ctx.sent[0].data as any).payload.from).toBe('a1');
      expect(ctx.mockTaskStore.saveChatMessage).toHaveBeenCalledWith('t1', 'a1', '你好');
    });

    it('should upgrade matched to chatting on first chat message', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'matched', helperId: 'a2' });

      engine.handleTaskChat({
        id: 'msg-005b',
        payload: { taskId: 't1', text: '开始对话' },
      }, 'a1');

      // Should first update to chatting, then relay
      expect(ctx.mockTaskStore.updateTaskStatus).toHaveBeenCalledWith('t1', 'chatting');
      expect(ctx.sent).toHaveLength(1);
    });
  });

  describe('handleTaskComplete', () => {
    it('should complete task with rating and notify helper', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting', helperId: 'a2' });

      engine.handleTaskComplete({
        id: 'msg-006',
        payload: { taskId: 't1', rating: 5, feedback: '很棒' },
      }, 'a1');

      expect(ctx.mockTaskStore.updateTaskStatus).toHaveBeenCalledWith('t1', 'completed', expect.objectContaining({ rating: 5 }));
      expect(ctx.sent.some(s => s.agentId === 'a2')).toBe(true);
      expect((ctx.sent.find(s => s.agentId === 'a2')!.data as any).type).toBe('task.result');
    });
  });

  describe('handleTaskCancel', () => {
    it('should cancel task and notify both agents', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting', helperId: 'a2' });

      engine.handleTaskCancel({
        id: 'msg-007',
        payload: { taskId: 't1', reason: 'user_cancel' },
      }, 'a1');

      expect(ctx.mockTaskStore.updateTaskStatus).toHaveBeenCalledWith('t1', 'cancelled');
      expect(ctx.sent).toHaveLength(2); // both agents notified
    });
  });

  describe('handleTaskSync', () => {
    it('should forward task.sync to the helper agent', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting', helperId: 'a2' });
      // Simulate helper has an active connection
      const mockWs = { readyState: 1 } as any;
      ctx.connections.set('a2', mockWs);

      const result = engine.handleTaskSync(
        { id: 'm1', payload: { taskId: 't1' } },
        'a1',
      );

      expect(result.error).toBeUndefined();
      expect(ctx.sent).toEqual([
        expect.objectContaining({
          agentId: 'a2',
          data: expect.objectContaining({
            type: 'task.sync',
            payload: expect.objectContaining({ taskId: 't1' }),
          }),
        }),
      ]);
    });

    it('should reply waiting_helper when peer is offline', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting', helperId: 'a2' });
      // No connection for helper

      const result = engine.handleTaskSync(
        { id: 'm1', payload: { taskId: 't1' } },
        'a1',
      );

      expect(result.error).toBeUndefined();
      expect(ctx.sent).toEqual([
        expect.objectContaining({
          agentId: 'a1',
          data: expect.objectContaining({
            type: 'task.progress',
            payload: expect.objectContaining({ taskId: 't1', status: 'waiting_helper' }),
          }),
        }),
      ]);
    });

    it('should reject sync from non-participant', () => {
      ctx.mockTaskStore.createTask({ id: 't1', requesterId: 'a1', question: 'q', capabilityQuery: 'c', status: 'chatting', helperId: 'a2' });

      const result = engine.handleTaskSync(
        { id: 'm1', payload: { taskId: 't1' } },
        'random-agent',
      );

      expect(result.error).toBeTruthy();
    });

    it('should reject sync for nonexistent task', () => {
      const result = engine.handleTaskSync(
        { id: 'm1', payload: { taskId: 'nonexistent' } },
        'a1',
      );

      expect(result.error).toBe('Task not found');
    });
  });

  describe('checkTimeouts', () => {
    it('should timeout idle chatting tasks and notify both agents', () => {
      const timedOutTask = { id: 't1', requesterId: 'a1', helperId: 'a2', helperName: null, question: 'q', capabilityQuery: 'c', status: 'chatting', rating: null, feedback: null, createdAt: '2026-01-01', updatedAt: '2026-01-01', completedAt: null, deadline: null };
      (ctx.mockTaskStore as any).listTimedOutTasks = vi.fn(() => [timedOutTask]);

      engine.checkTimeouts(5);

      expect(ctx.mockTaskStore.updateTaskStatus).toHaveBeenCalledWith('t1', 'timeout');
      expect(ctx.sent).toHaveLength(2); // both agents notified
      expect(ctx.sent.every(s => (s.data as any).type === 'task.timeout')).toBe(true);
    });
  });
});
