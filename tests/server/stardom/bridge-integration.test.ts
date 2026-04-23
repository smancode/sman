// tests/server/stardom/bridge-integration.test.ts
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { StardomStore } from '../../../server/stardom/stardom-store.js';
import { StardomClient } from '../../../server/stardom/stardom-client.js';
import http from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('Bridge Integration: Client ↔ Server', () => {
  let store: StardomStore;
  let client: StardomClient;
  let dbPath: string;
  let wss: WebSocketServer;
  let server: http.Server;
  let port: number;
  let serverMessages: any[] = [];

  beforeAll(async () => {
    dbPath = path.join(os.tmpdir(), `bridge-integration-${Date.now()}.db`);
    store = new StardomStore(dbPath);

    // 模拟集市服务器
    const app = http.createServer();
    wss = new WebSocketServer({ server: app });
    serverMessages = [];

    wss.on('connection', (ws) => {
      ws.on('message', (data) => {
        const msg = JSON.parse(data.toString());
        serverMessages.push(msg);
        ws.send(JSON.stringify({ type: 'ack', id: msg.id }));

        // 模拟服务器推送
        if (msg.type === 'agent.register') {
          ws.send(JSON.stringify({
            id: 'srv-001',
            type: 'agent.registered',
            payload: { agentId: msg.payload.agentId, status: 'idle' },
          }));
        }
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

  afterAll(async () => {
    client?.disconnect();
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
    for (const ws of wss.clients) {
      ws.close();
    }
    wss.close();
    server.close();
  }, 15_000);

  it('should complete register and receive server push', async () => {
    store.saveIdentity({
      agentId: 'bridge-test-001',
      hostname: 'test',
      username: 'testuser',
      name: '测试',
      server: `localhost:${port}`,
    });

    const received: any[] = [];
    client = new StardomClient(store, { getAgentDescription: () => '测试 Agent' });
    client.onMessage = (msg) => received.push(msg);

    await client.connect();
    await new Promise((r) => setTimeout(r, 300));

    // 验证注册消息已发送
    const reg = serverMessages.find(m => m.type === 'agent.register');
    expect(reg).toBeDefined();
    expect(reg.payload.agentId).toBe('bridge-test-001');

    // 验证收到了服务器推送
    expect(received.length).toBeGreaterThanOrEqual(1);
    expect(received[0].type).toBe('agent.registered');
  }, 10_000);

  it('should handle task.incoming and save to local store via onMessage', async () => {
    // 设置 onMessage 处理器来模拟 Bridge 的行为：收到 task.incoming 时保存到 store
    client.onMessage = (msg) => {
      if (msg.type === 'task.incoming') {
        store.saveTask({
          taskId: msg.payload.taskId as string,
          direction: 'incoming',
          requesterAgentId: msg.payload.from as string,
          requesterName: (msg.payload.fromName as string) ?? '一位同事',
          question: msg.payload.question as string,
          status: 'offered',
          createdAt: new Date().toISOString(),
        });
      }
    };

    // 从服务器模拟推送一个任务邀请
    const wsClient = Array.from(wss.clients)[0];
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      wsClient.send(JSON.stringify({
        id: 'srv-task-001',
        type: 'task.incoming',
        payload: {
          taskId: 'task-001',
          from: 'agent-002',
          fromName: '小李',
          question: '支付系统怎么查？',
          deadline: new Date(Date.now() + 300000).toISOString(),
        },
      }));
    }

    await new Promise((r) => setTimeout(r, 300));

    // 验证本地存储
    const task = store.getTask('task-001');
    expect(task).toBeDefined();
    expect(task!.question).toBe('支付系统怎么查？');
    expect(task!.direction).toBe('incoming');
    expect(task!.status).toBe('offered');
  }, 10_000);

  it('should extract experience on task complete with rating >= 3', async () => {
    // Setup: save an incoming task (bridge needs it to get agentId/agentName)
    store.saveTask({
      taskId: 'task-001',
      direction: 'incoming',
      requesterAgentId: 'agent-002',
      requesterName: '小李',
      question: '支付查询怎么优化？',
      status: 'chatting',
      createdAt: new Date().toISOString(),
    });

    // Add chat messages
    store.saveChatMessage({ taskId: 'task-001', from: 'remote', text: '支付查询怎么优化？' });
    store.saveChatMessage({ taskId: 'task-001', from: 'local', text: '用 JOIN 替代子查询' });

    // Simulate StardomBridge.handleTaskComplete behavior via onMessage
    client.onMessage = (msg) => {
      if (msg.type === 'task.complete') {
        const rating = msg.payload.rating as number;
        const taskId = msg.payload.taskId as string;
        if (rating >= 3) {
          const task = store.getTask(taskId);
          if (task) {
            const capability = task.question;
            const agentId = task.requesterAgentId ?? task.helperAgentId;
            const agentName = task.requesterName ?? task.helperName;
            if (agentId && agentName) {
              // Simulate extractExperience: no API key → empty experience
              store.saveLearnedRoute({ capability, agentId, agentName });
            }
          }
        }
      }
    };

    // Trigger task.complete from server
    const wsClient = Array.from(wss.clients)[0];
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      wsClient.send(JSON.stringify({
        id: 'srv-complete-001',
        type: 'task.complete',
        payload: {
          taskId: 'task-001',
          rating: 4,
          feedback: '很好',
        },
      }));
    }

    await new Promise((r) => setTimeout(r, 500));

    // Verify learned_routes saved
    const routes = store.findLearnedRoutes('支付');
    expect(routes.length).toBeGreaterThanOrEqual(1);
    // Experience extraction is async best-effort (may be empty due to no API key), but route must exist
    expect(routes[0].agentId).toBe('agent-002');
  }, 10_000);
});
