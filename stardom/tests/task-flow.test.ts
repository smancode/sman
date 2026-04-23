// stardom/tests/task-flow.test.ts
// 集成测试：验证完整的 task 生命周期通过 MessageRouter + TaskEngine 走通
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { MessageRouter } from '../src/message-router.js';
import { TaskEngine } from '../src/task-engine.js';
import { TaskStore } from '../src/task-store.js';
import type { AgentStore } from '../src/agent-store.js';
import type WebSocket from 'ws';
import fs from 'fs';
import path from 'path';
import os from 'os';

// --- Mock helpers ---

function createMockAgentStore(): AgentStore {
  const agents = new Map<string, any>();

  return {
    getAgent: (id: string) => agents.get(id),
    getAgentByUsername: () => undefined,
    registerAgent: (input: any) => agents.set(input.id, { ...input, status: 'idle', reputation: 10, lastSeenAt: new Date().toISOString() }),
    updateAgentStatus: (id: string, status: string) => { const a = agents.get(id); if (a) a.status = status; },
    updateHeartbeat: () => {},
    setAgentOffline: (id: string) => { const a = agents.get(id); if (a) a.status = 'offline'; },
    listOnlineAgents: () => Array.from(agents.values()).filter((a: any) => a.status !== 'offline'),
    logAudit: () => {},
    close: () => {},
  } as unknown as AgentStore;
}

function createMockWs(): WebSocket {
  const sent: any[] = [];
  const ws = {
    readyState: 1,
    send: (data: string) => sent.push(JSON.parse(data)),
    close: () => {},
    on: () => {},
    _sent: sent,
  } as unknown as WebSocket & { _sent: any[] };
  return ws;
}

// --- Integration test ---

describe('Task Flow Integration', () => {
  let agentStore: AgentStore;
  let taskStore: TaskStore;
  let connections: Map<string, WebSocket>;
  let sentMessages: Array<{ agentId: string; data: any }>;
  let taskEngine: TaskEngine;
  let router: MessageRouter;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `stardom-flow-test-${Date.now()}.db`);
    taskStore = new TaskStore(dbPath);
    agentStore = createMockAgentStore();
    connections = new Map();
    sentMessages = [];

    const sendTo = (agentId: string, data: unknown) => {
      sentMessages.push({ agentId, data });
    };

    taskEngine = new TaskEngine(taskStore, agentStore, connections, sendTo);
    router = new MessageRouter(agentStore, taskEngine, connections);
  });

  afterEach(() => {
    taskStore.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  it('should complete full task lifecycle: register → create → offer → accept → chat → complete', () => {
    // 1. Register requester
    const ws1 = createMockWs();
    router.route({
      id: 'msg-001', type: 'agent.register',
      payload: { agentId: 'req-1', username: 'alice', hostname: 'mac1', name: 'Alice', description: '请求者' },
    }, ws1);
    connections.set('req-1', ws1);

    // 2. Register helper with matching name (capability 搜索基于 name + description)
    const ws2 = createMockWs();
    router.route({
      id: 'msg-002', type: 'agent.register',
      payload: {
        agentId: 'help-1', username: 'bob', hostname: 'mac2', name: 'Bob',
        description: '擅长支付查询',
      },
    }, ws2);
    connections.set('help-1', ws2);

    // 3. Create task — capabilityQuery "支付" 会匹配 help-1 的 description
    const createResult = router.route({
      id: 'msg-003', type: 'task.create',
      payload: { question: '支付查询怎么做？', capabilityQuery: '支付' },
    }, ws1);

    // route() returns RouteResult, but task.create sends search_result via ws callback
    // Check ws1 received search_result
    const ws1Messages = (ws1 as any)._sent;
    const searchResult = ws1Messages.find((m: any) => m.type === 'task.search_result');
    expect(searchResult).toBeDefined();
    expect(searchResult.payload.taskId).toBeDefined();
    expect(searchResult.payload.matches).toHaveLength(1);
    expect(searchResult.payload.matches[0].agentId).toBe('help-1');

    const taskId = searchResult.payload.taskId;

    // 4. Offer task to helper
    router.route({
      id: 'msg-004', type: 'task.offer',
      payload: { taskId, targetAgent: 'help-1' },
    }, ws1);

    // Helper should receive task.incoming
    expect(sentMessages.some(m => m.agentId === 'help-1' && m.data.type === 'task.incoming')).toBe(true);

    // 5. Accept task
    router.route({
      id: 'msg-005', type: 'task.accept',
      payload: { taskId },
    }, ws2);

    // Both should receive task.matched
    expect(sentMessages.some(m => m.agentId === 'req-1' && m.data.type === 'task.matched')).toBe(true);
    expect(sentMessages.some(m => m.agentId === 'help-1' && m.data.type === 'task.matched')).toBe(true);

    // 6. Chat
    sentMessages.length = 0;
    router.route({
      id: 'msg-006', type: 'task.chat',
      payload: { taskId, text: '请问支付接口怎么调用？' },
    }, ws1);

    // Helper should receive chat
    expect(sentMessages).toHaveLength(1);
    expect(sentMessages[0].agentId).toBe('help-1');
    expect(sentMessages[0].data.payload.text).toBe('请问支付接口怎么调用？');

    // 7. Complete
    sentMessages.length = 0;
    router.route({
      id: 'msg-007', type: 'task.complete',
      payload: { taskId, rating: 5, feedback: '非常感谢' },
    }, ws1);

    // Helper should receive task.result
    expect(sentMessages.some(m => m.agentId === 'help-1' && m.data.type === 'task.result')).toBe(true);

    // Verify final task state in DB
    const task = taskStore.getTask(taskId);
    expect(task).toBeDefined();
    expect(task!.status).toBe('completed');
    expect(task!.rating).toBe(5);
    expect(task!.feedback).toBe('非常感谢');
  });

  it('should handle task rejection and return to searching', () => {
    // Register agents
    const ws1 = createMockWs();
    router.route({
      id: 'msg-010', type: 'agent.register',
      payload: { agentId: 'req-2', username: 'carol', hostname: 'mac3', name: 'Carol', description: '' },
    }, ws1);
    connections.set('req-2', ws1);

    const ws2 = createMockWs();
    router.route({
      id: 'msg-011', type: 'agent.register',
      payload: {
        agentId: 'help-2', username: 'dave', hostname: 'mac4', name: 'Dave',
        description: '擅长认证登录',
      },
    }, ws2);
    connections.set('help-2', ws2);

    // Create task — "认证" 匹配 help-2 的 description
    router.route({
      id: 'msg-012', type: 'task.create',
      payload: { question: '认证问题', capabilityQuery: '认证' },
    }, ws1);

    const ws1Messages = (ws1 as any)._sent;
    const searchResult = ws1Messages.find((m: any) => m.type === 'task.search_result');
    const taskId = searchResult.payload.taskId;

    // Offer
    router.route({
      id: 'msg-013', type: 'task.offer',
      payload: { taskId, targetAgent: 'help-2' },
    }, ws1);

    // Reject
    sentMessages.length = 0;
    router.route({
      id: 'msg-014', type: 'task.reject',
      payload: { taskId },
    }, ws2);

    // Requester should be notified about rejection
    expect(sentMessages.some(m => m.agentId === 'req-2' && m.data.type === 'task.progress')).toBe(true);

    // Task should be back in searching
    const task = taskStore.getTask(taskId);
    expect(task!.status).toBe('searching');
  });

  it('should handle task cancellation', () => {
    const ws1 = createMockWs();
    router.route({
      id: 'msg-020', type: 'agent.register',
      payload: { agentId: 'req-3', username: 'eve', hostname: 'mac5', name: 'Eve', description: '' },
    }, ws1);
    connections.set('req-3', ws1);

    const ws2 = createMockWs();
    router.route({
      id: 'msg-021', type: 'agent.register',
      payload: {
        agentId: 'help-3', username: 'frank', hostname: 'mac6', name: 'Frank',
        description: '擅长核心模块',
      },
    }, ws2);
    connections.set('help-3', ws2);

    // Create + Offer + Accept — "核心" 匹配 help-3 的 description
    router.route({
      id: 'msg-022', type: 'task.create',
      payload: { question: '核心模块问题', capabilityQuery: '核心' },
    }, ws1);

    const ws1Messages = (ws1 as any)._sent;
    const taskId = (ws1Messages.find((m: any) => m.type === 'task.search_result')).payload.taskId;

    router.route({ id: 'msg-023', type: 'task.offer', payload: { taskId, targetAgent: 'help-3' } }, ws1);
    router.route({ id: 'msg-024', type: 'task.accept', payload: { taskId } }, ws2);

    // Cancel
    sentMessages.length = 0;
    router.route({
      id: 'msg-025', type: 'task.cancel',
      payload: { taskId, reason: '不再需要' },
    }, ws1);

    // Both agents notified
    expect(sentMessages).toHaveLength(2);
    expect(sentMessages.every(m => m.data.type === 'task.cancelled')).toBe(true);

    const task = taskStore.getTask(taskId);
    expect(task!.status).toBe('cancelled');
  });
});
