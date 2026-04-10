// tests/server/bazaar/bazaar-client.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { BazaarClient } from '../../../server/bazaar/bazaar-client.js';
import type { BazaarStore } from '../../../server/bazaar/bazaar-store.js';
import http from 'http';
import { WebSocketServer } from 'ws';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('BazaarClient', () => {
  let client: BazaarClient;
  let mockStore: BazaarStore;
  let dbPath: string;
  let wss: WebSocketServer;
  let server: http.Server;
  let port: number;
  let receivedByServer: any[] = [];

  beforeEach(async () => {
    dbPath = path.join(os.tmpdir(), `bazaar-client-test-${Date.now()}.db`);

    // 简化 mock store
    const { BazaarStore: RealStore } = await import('../../../server/bazaar/bazaar-store.js');
    mockStore = new RealStore(dbPath);

    // 启动模拟集市服务器
    const app = http.createServer();
    wss = new WebSocketServer({ server: app });
    receivedByServer = [];

    wss.on('connection', (ws) => {
      ws.on('message', (data) => {
        receivedByServer.push(JSON.parse(data.toString()));
        // 自动回复 ack
        const msg = JSON.parse(data.toString());
        ws.send(JSON.stringify({ type: 'ack', id: msg.id }));
      });
    });

    server = app;
    await new Promise<void>((resolve) => {
      server.listen(0, () => {
        port = (server.address() as any).port;
        resolve();
      });
    });
  }, 15_000);

  afterEach(async () => {
    client?.disconnect();
    (mockStore as any).close?.();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
    // Close all connected clients first
    for (const ws of wss.clients) {
      ws.close();
    }
    wss.close();
    server.close();
  }, 15_000);

  it('should connect and register with bazaar server', async () => {
    mockStore.saveIdentity({
      agentId: 'agent-001',
      hostname: 'test-host',
      username: 'zhangsan',
      name: '张三',
      server: `localhost:${port}`,
    });

    client = new BazaarClient(mockStore, {
      getAgentProjects: () => [],
    });

    await client.connect();

    // 等待注册消息到达
    await new Promise((r) => setTimeout(r, 300));

    expect(receivedByServer.length).toBeGreaterThanOrEqual(1);
    const registerMsg = receivedByServer.find(m => m.type === 'agent.register');
    expect(registerMsg).toBeDefined();
    expect(registerMsg.payload.agentId).toBe('agent-001');
    expect(registerMsg.payload.name).toBe('张三');
  }, 10_000);

  it('should send heartbeat after connection', async () => {
    mockStore.saveIdentity({
      agentId: 'agent-002',
      hostname: 'h',
      username: 'u',
      name: 'n',
      server: `localhost:${port}`,
    });

    client = new BazaarClient(mockStore, {
      getAgentProjects: () => [],
      heartbeatIntervalMs: 500, // 快速测试
    });

    await client.connect();
    await new Promise((r) => setTimeout(r, 800));

    const heartbeats = receivedByServer.filter(m => m.type === 'agent.heartbeat');
    expect(heartbeats.length).toBeGreaterThanOrEqual(1);
  }, 10_000);
});
