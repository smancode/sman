// bazaar/tests/agent-store.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { AgentStore } from '../src/agent-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('AgentStore', () => {
  let store: AgentStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `bazaar-agent-test-${Date.now()}.db`);
    store = new AgentStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  // === Agent CRUD ===

  describe('registerAgent', () => {
    it('should register a new agent', () => {
      const agent = store.registerAgent({
        id: 'agent-001',
        username: 'zhangsan',
        hostname: 'VDI-ZS-01',
        name: '张三',
        avatar: '🧙',
      });
      expect(agent.id).toBe('agent-001');
      expect(agent.username).toBe('zhangsan');
      expect(agent.status).toBe('idle');
      expect(agent.reputation).toBe(0);
    });

    it('should throw on duplicate username', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      expect(() => {
        store.registerAgent({ id: 'agent-002', username: 'zhangsan', hostname: 'h2', name: '张三' });
      }).toThrow(/UNIQUE constraint/);
    });
  });

  describe('getAgent', () => {
    it('should return undefined for non-existent agent', () => {
      expect(store.getAgent('non-existent')).toBeUndefined();
    });

    it('should return registered agent', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      const agent = store.getAgent('agent-001');
      expect(agent).toBeDefined();
      expect(agent!.username).toBe('zhangsan');
    });
  });

  describe('getAgentByUsername', () => {
    it('should find agent by username', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      const agent = store.getAgentByUsername('zhangsan');
      expect(agent).toBeDefined();
      expect(agent!.id).toBe('agent-001');
    });
  });

  describe('updateAgentStatus', () => {
    it('should update agent status', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      store.updateAgentStatus('agent-001', 'busy');
      const agent = store.getAgent('agent-001');
      expect(agent!.status).toBe('busy');
    });
  });

  describe('updateHeartbeat', () => {
    it('should update last_seen_at', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      const before = store.getAgent('agent-001')!.lastSeenAt;
      // small delay to ensure different timestamp
      const start = Date.now();
      while (Date.now() === start) { /* spin */ }
      store.updateHeartbeat('agent-001');
      const after = store.getAgent('agent-001')!.lastSeenAt;
      expect(after).not.toBe(before);
    });
  });

  describe('setAgentOffline', () => {
    it('should set agent status to offline', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      store.setAgentOffline('agent-001');
      const agent = store.getAgent('agent-001');
      expect(agent!.status).toBe('offline');
    });
  });

  describe('listOnlineAgents', () => {
    it('should return only non-offline agents', () => {
      store.registerAgent({ id: 'a1', username: 'u1', hostname: 'h1', name: '张三' });
      store.registerAgent({ id: 'a2', username: 'u2', hostname: 'h2', name: '李四' });
      store.setAgentOffline('a2');
      const online = store.listOnlineAgents();
      expect(online).toHaveLength(1);
      expect(online[0].id).toBe('a1');
    });
  });

  // === Projects ===

  describe('updateProjects', () => {
    it('should replace projects for an agent', () => {
      store.registerAgent({ id: 'agent-001', username: 'zhangsan', hostname: 'h1', name: '张三' });
      store.updateProjects('agent-001', [
        { repo: 'payment-service', skills: JSON.stringify([{ id: 'pay', name: '支付', triggers: ['支付'] }]) },
        { repo: 'risk-engine', skills: JSON.stringify([]) },
      ]);
      const projects = store.getProjects('agent-001');
      expect(projects).toHaveLength(2);
      expect(projects[0].repo).toBe('payment-service');
    });
  });

  describe('findAgentsByCapability', () => {
    it('should find agents with matching skill keywords', () => {
      store.registerAgent({ id: 'a1', username: 'u1', hostname: 'h1', name: '张三' });
      store.updateProjects('a1', [
        { repo: 'payment-service', skills: JSON.stringify([{ id: 'pay', name: '支付查询', triggers: ['支付', '查询'] }]) },
      ]);
      store.registerAgent({ id: 'a2', username: 'u2', hostname: 'h2', name: '李四' });
      store.updateProjects('a2', [
        { repo: 'risk-engine', skills: JSON.stringify([{ id: 'risk', name: '风控', triggers: ['风控', '规则'] }]) },
      ]);

      const results = store.findAgentsByCapability('支付');
      expect(results).toHaveLength(1);
      expect(results[0].agentId).toBe('a1');
    });
  });

  // === Audit Log ===

  describe('logAudit', () => {
    it('should record an audit event', () => {
      store.registerAgent({ id: 'a1', username: 'u1', hostname: 'h1', name: '张三' });
      store.logAudit('agent.online', 'a1', undefined, undefined, { projects: [] });
      const logs = store.getAuditLogs('a1');
      expect(logs).toHaveLength(1);
      expect(logs[0].eventType).toBe('agent.online');
    });
  });

  // === Reputation ===

  describe('reputation', () => {
    it('should update agent reputation', () => {
      store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
      store.updateReputation('a1', 2.5);
      const agent = store.getAgent('a1');
      expect(agent!.reputation).toBe(2.5);
    });

    it('should accumulate reputation', () => {
      store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
      store.updateReputation('a1', 1.5);
      store.updateReputation('a1', 2.0);
      const agent = store.getAgent('a1');
      expect(agent!.reputation).toBeCloseTo(3.5);
    });

    it('should not go below 0', () => {
      store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
      store.updateReputation('a1', -5);
      const agent = store.getAgent('a1');
      expect(agent!.reputation).toBe(0);
    });

    it('should log reputation changes', () => {
      store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
      store.logReputation('a1', 'task-001', 1.5, 'base');
      store.logReputation('a1', 'task-002', 2.0, 'quality');
      const logs = store.getReputationLogs('a1');
      expect(logs).toHaveLength(2);
      // Check both entries exist regardless of order
      const deltas = logs.map(l => l.delta);
      expect(deltas).toContain(1.5);
      expect(deltas).toContain(2.0);
    });

    it('should count reputation events between two agents today', () => {
      store.registerAgent({ id: 'a1', username: 'test1', hostname: 'h', name: 'T1' });
      store.logReputation('a1', 't1', 1, 'base', 'req-1');
      store.logReputation('a1', 't2', 1, 'base', 'req-1');
      store.logReputation('a1', 't3', 1, 'base', 'req-1');
      store.logReputation('a1', 't4', 1, 'base', 'req-1');
      expect(store.getReputationCountToday('a1', 'req-1')).toBe(4);
    });
  });

  describe('reputation decay', () => {
    it('should return last collaboration date', () => {
      store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
      expect(store.getLastCollaborationAt('a1')).toBeNull();

      store.logReputation('a1', 't1', 1.5, 'base', 'req-1');
      const lastDate = store.getLastCollaborationAt('a1');
      expect(lastDate).not.toBeNull();
    });

    it('should decay reputation for inactive agents', () => {
      store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
      store.updateReputation('a1', 10);
      store.logReputation('a1', 't1', 1.5, 'base', 'req-1');

      // 模拟 31 天前的日志记录
      const oldDate = new Date(Date.now() - 31 * 24 * 60 * 60 * 1000).toISOString();
      (store as any).db.prepare(
        'UPDATE reputation_log SET created_at = ? WHERE agent_id = ?'
      ).run(oldDate, 'a1');

      const decayed = store.decayReputation(30, 0.1);
      expect(decayed).toBe(1);
      const agent = store.getAgent('a1');
      expect(agent!.reputation).toBeCloseTo(9.9);
    });

    it('should not decay reputation for active agents', () => {
      store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
      store.updateReputation('a1', 10);
      store.logReputation('a1', 't1', 1.5, 'base', 'req-1'); // 今天活跃

      const decayed = store.decayReputation(30, 0.1);
      expect(decayed).toBe(0);
      const agent = store.getAgent('a1');
      expect(agent!.reputation).toBe(10);
    });

    it('should not decay reputation below 0', () => {
      store.registerAgent({ id: 'a1', username: 'test', hostname: 'h', name: 'Test' });
      store.updateReputation('a1', 0.05);
      store.logReputation('a1', 't1', 0.05, 'base', 'req-1');

      const oldDate = new Date(Date.now() - 31 * 24 * 60 * 60 * 1000).toISOString();
      (store as any).db.prepare(
        'UPDATE reputation_log SET created_at = ? WHERE agent_id = ?'
      ).run(oldDate, 'a1');

      store.decayReputation(30, 0.1);
      const agent = store.getAgent('a1');
      expect(agent!.reputation).toBe(0);
    });
  });
});
