// bazaar/tests/message-router.test.ts
import { describe, it, expect, beforeEach } from 'vitest';
import { MessageRouter } from '../src/message-router.js';
import type { AgentStore } from '../src/agent-store.js';
import type WebSocket from 'ws';

// Mock AgentStore
function createMockStore(): AgentStore {
  const agents = new Map<string, { id: string; username: string; status: string }>();
  return {
    registerAgent: (input: { id: string; username: string; hostname: string; name: string }) => {
      agents.set(input.id, { id: input.id, username: input.username, status: 'idle' });
      return { ...input, avatar: '🧙', status: 'idle', reputation: 0, lastSeenAt: null, createdAt: new Date().toISOString() };
    },
    getAgent: (id: string) => agents.get(id) as any,
    getAgentByUsername: () => undefined,
    updateAgentStatus: (id: string, status: string) => {
      const a = agents.get(id);
      if (a) a.status = status;
    },
    updateHeartbeat: () => {},
    setAgentOffline: () => {},
    listOnlineAgents: () => Array.from(agents.values()),
    updateProjects: () => {},
    getProjects: () => [],
    findAgentsByCapability: () => [],
    logAudit: () => {},
    getAuditLogs: () => [],
    close: () => {},
  } as unknown as AgentStore;
}

describe('MessageRouter', () => {
  let router: MessageRouter;
  let mockStore: AgentStore;

  beforeEach(() => {
    mockStore = createMockStore();
    router = new MessageRouter(mockStore);
  });

  describe('route', () => {
    it('should reject invalid message format', () => {
      const result = router.route({ type: 'foo' }, {} as WebSocket);
      expect(result.error).toBeDefined();
      expect(result.error).toContain('Missing required field');
    });

    it('should reject unknown message type', () => {
      const result = router.route({ id: 'm1', type: 'unknown.type', payload: {} }, {} as WebSocket);
      expect(result.error).toContain('Unknown message type');
    });

    it('should route agent.register', () => {
      const sent: unknown[] = [];
      const ws = { readyState: 1, send: (data: string) => sent.push(JSON.parse(data)) } as unknown as WebSocket;
      const result = router.route({
        id: 'm1',
        type: 'agent.register',
        payload: {
          agentId: 'a1',
          username: 'zhangsan',
          hostname: 'h1',
          name: '张三',
          projects: [],
          privateCapabilities: [],
        },
      }, ws);

      expect(result.handled).toBe(true);
      // 应该回复 ack
      expect(sent.length).toBeGreaterThan(0);
      expect(sent[0]).toHaveProperty('type', 'ack');
    });

    it('should route agent.heartbeat', () => {
      // 先注册
      mockStore.registerAgent({ id: 'a1', username: 'zhangsan', hostname: 'h1', name: '张三' });

      const sent: unknown[] = [];
      const ws = { readyState: 1, send: (data: string) => sent.push(JSON.parse(data)) } as unknown as WebSocket;
      const result = router.route({
        id: 'm2',
        type: 'agent.heartbeat',
        payload: { agentId: 'a1', status: 'idle', activeTaskCount: 0 },
      }, ws);

      expect(result.handled).toBe(true);
    });

    it('should route agent.update', () => {
      mockStore.registerAgent({ id: 'a1', username: 'zhangsan', hostname: 'h1', name: '张三' });
      const ws = { readyState: 1, send: () => {} } as unknown as WebSocket;
      const result = router.route({
        id: 'm3',
        type: 'agent.update',
        payload: { agentId: 'a1', status: 'busy' },
      }, ws);

      expect(result.handled).toBe(true);
    });
  });
});
