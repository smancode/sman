// tests/server/stardom/stardom-client.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { StardomClient } from '../../../server/stardom/stardom-client.js';
import type { StardomStore } from '../../../server/stardom/stardom-store.js';
import http from 'http';
import { WebSocketServer } from 'ws';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('StardomClient', () => {
  let client: StardomClient;
  let mockStore: StardomStore;
  let dbPath: string;
  let wss: WebSocketServer;
  let server: http.Server;
  let port: number;
  let receivedByServer: any[] = [];

  beforeEach(async () => {
    dbPath = path.join(os.tmpdir(), `stardom-client-test-${Date.now()}.db`);

    // 简化 mock store
    const { StardomStore: RealStore } = await import('../../../server/stardom/stardom-store.js');
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

  it('should connect and register with stardom server', async () => {
    mockStore.saveIdentity({
      agentId: 'agent-001',
      hostname: 'test-host',
      username: 'zhangsan',
      name: '张三',
      server: `localhost:${port}`,
    });

    client = new StardomClient(mockStore, {
      getAgentDescription: () => '测试 Agent',
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

    client = new StardomClient(mockStore, {
      getAgentDescription: () => '测试 Agent',
      heartbeatIntervalMs: 500, // 快速测试
    });

    await client.connect();
    await new Promise((r) => setTimeout(r, 800));

    const heartbeats = receivedByServer.filter(m => m.type === 'agent.heartbeat');
    expect(heartbeats.length).toBeGreaterThanOrEqual(1);
  }, 10_000);
});
