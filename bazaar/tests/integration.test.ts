// bazaar/tests/integration.test.ts
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import http from 'http';
import express from 'express';
import { WebSocketServer } from 'ws';
import WebSocket from 'ws';
import { AgentStore } from '../src/agent-store.js';
import { MessageRouter } from '../src/message-router.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('Integration: Full Agent Connection Flow', () => {
  let server: http.Server;
  let wss: WebSocketServer;
  let store: AgentStore;
  let router: MessageRouter;
  let dbPath: string;
  let port: number;

  beforeAll(async () => {
    dbPath = path.join(os.tmpdir(), `bazaar-integration-${Date.now()}.db`);
    store = new AgentStore(dbPath);
    router = new MessageRouter(store);

    const app = express();
    server = http.createServer(app);
    wss = new WebSocketServer({ server });

    wss.on('connection', (ws) => {
      let agentId: string | null = null;
      ws.on('message', (data) => {
        const msg = JSON.parse(data.toString());
        router.route(msg, ws);
        if (msg.type === 'agent.register') {
          agentId = msg.payload.agentId;
        }
      });
      ws.on('close', () => {
        if (agentId) store.setAgentOffline(agentId);
      });
    });

    // 随机端口
    await new Promise<void>((resolve) => {
      server.listen(0, () => {
        port = (server.address() as any).port;
        resolve();
      });
    });
  });

  afterAll(async () => {
    await new Promise<void>((resolve) => wss.close(() => resolve()));
    await new Promise<void>((resolve) => server.close(() => resolve()));
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  it('should complete full register → heartbeat → update → offline flow', async () => {
    const ws = new WebSocket(`ws://localhost:${port}`);
    const received: any[] = [];

    await new Promise<void>((resolve) => { ws.on('open', () => resolve()); });
    ws.on('message', (data) => received.push(JSON.parse(data.toString())));

    // 1. Register
    ws.send(JSON.stringify({
      id: 'msg-001',
      type: 'agent.register',
      payload: {
        agentId: 'agent-test-001',
        username: 'testuser',
        hostname: 'test-host',
        name: '测试用户',
        projects: [
          { repo: 'payment-service', skills: JSON.stringify([{ id: 'pay', name: '支付', triggers: ['支付'] }]) },
        ],
        privateCapabilities: [],
      },
    }));

    await new Promise((r) => setTimeout(r, 200));
    expect(received.length).toBeGreaterThanOrEqual(2); // ack + registered

    // 验证数据库
    const agent = store.getAgent('agent-test-001');
    expect(agent).toBeDefined();
    expect(agent!.username).toBe('testuser');
    expect(agent!.status).toBe('idle');

    const projects = store.getProjects('agent-test-001');
    expect(projects).toHaveLength(1);
    expect(projects[0].repo).toBe('payment-service');

    // 2. Heartbeat
    received.length = 0;
    ws.send(JSON.stringify({
      id: 'msg-002',
      type: 'agent.heartbeat',
      payload: { agentId: 'agent-test-001', status: 'busy', activeTaskCount: 1 },
    }));

    await new Promise((r) => setTimeout(r, 200));
    const afterHeartbeat = store.getAgent('agent-test-001');
    expect(afterHeartbeat!.status).toBe('busy');

    // 3. Update
    ws.send(JSON.stringify({
      id: 'msg-003',
      type: 'agent.update',
      payload: { agentId: 'agent-test-001', status: 'idle' },
    }));

    await new Promise((r) => setTimeout(r, 200));
    const afterUpdate = store.getAgent('agent-test-001');
    expect(afterUpdate!.status).toBe('idle');

    // 4. Offline
    ws.send(JSON.stringify({
      id: 'msg-004',
      type: 'agent.offline',
      payload: { agentId: 'agent-test-001' },
    }));

    await new Promise((r) => setTimeout(r, 200));
    const afterOffline = store.getAgent('agent-test-001');
    expect(afterOffline!.status).toBe('offline');

    ws.close();
  });

  it('should handle two agents and capability search', async () => {
    // 注册两个 Agent
    const ws1 = new WebSocket(`ws://localhost:${port}`);
    const ws2 = new WebSocket(`ws://localhost:${port}`);
    await new Promise<void>((r) => { ws1.on('open', () => r()); });
    await new Promise<void>((r) => { ws2.on('open', () => r()); });

    ws1.send(JSON.stringify({
      id: 'msg-a1', type: 'agent.register',
      payload: { agentId: 'a1', username: 'user1', hostname: 'h1', name: '用户1', projects: [
        { repo: 'payment', skills: JSON.stringify([{ id: 'pay', name: '支付', triggers: ['支付', '查询'] }]) },
      ], privateCapabilities: [] },
    }));

    ws2.send(JSON.stringify({
      id: 'msg-a2', type: 'agent.register',
      payload: { agentId: 'a2', username: 'user2', hostname: 'h2', name: '用户2', projects: [
        { repo: 'risk', skills: JSON.stringify([{ id: 'risk', name: '风控', triggers: ['风控'] }]) },
      ], privateCapabilities: [] },
    }));

    await new Promise((r) => setTimeout(r, 300));

    // 搜索"支付"能力
    const results = store.findAgentsByCapability('支付');
    expect(results).toHaveLength(1);
    expect(results[0].agentId).toBe('a1');

    // 搜索"风控"能力
    const riskResults = store.findAgentsByCapability('风控');
    expect(riskResults).toHaveLength(1);
    expect(riskResults[0].agentId).toBe('a2');

    ws1.close();
    ws2.close();
  });
});
