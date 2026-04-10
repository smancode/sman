// bazaar/src/index.ts
import express from 'express';
import http from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import { AgentStore } from './agent-store.js';
import { TaskStore } from './task-store.js';
import { TaskEngine } from './task-engine.js';
import { MessageRouter } from './message-router.js';
import { ReputationEngine } from './reputation.js';
import { createLogger } from './utils/logger.js';
import fs from 'fs';
import path from 'path';

const log = createLogger('BazaarServer');

const PORT = parseInt(process.env.BAZAAR_PORT ?? '5890', 10);
const DB_PATH = process.env.BAZAAR_DB_PATH ?? `${process.env.HOME}/.bazaar/bazaar.db`;
const HEARTBEAT_TIMEOUT_MS = 90_000; // 3 × 30s 心跳间隔

// 确保数据目录存在
const dbDir = path.dirname(DB_PATH);
if (!fs.existsSync(dbDir)) {
  fs.mkdirSync(dbDir, { recursive: true });
}

const TASK_DB_PATH = process.env.BAZAAR_TASK_DB_PATH ?? `${process.env.HOME}/.bazaar/tasks.db`;

const store = new AgentStore(DB_PATH);
const taskStore = new TaskStore(TASK_DB_PATH);
const connections = new Map<string, WebSocket>();

// 发送消息给指定 Agent
const sendToAgent = (agentId: string, data: unknown) => {
  const ws = connections.get(agentId);
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(data));
  }
};

const reputationEngine = new ReputationEngine(store);
const taskEngine = new TaskEngine(taskStore, store, connections, sendToAgent, reputationEngine);
const router = new MessageRouter(store, taskEngine, connections);

const app = express();
const server = http.createServer(app);

// 健康检查
app.get('/api/health', (_req, res) => {
  res.json({ status: 'ok', uptime: process.uptime(), agents: store.listOnlineAgents().length });
});

// WebSocket 服务器
const wss = new WebSocketServer({ server });

// Agent ID → WebSocket 映射（已在前方声明）

wss.on('connection', (ws) => {
  let agentId: string | null = null;

  log.info('New WebSocket connection');

  ws.on('message', (data) => {
    try {
      const raw = JSON.parse(data.toString());
      router.route(raw, ws);
      // 记录连接（从 register 消息中获取 agentId）
      if (raw.type === 'agent.register' && raw.payload?.agentId) {
        agentId = raw.payload.agentId as string;
        connections.set(agentId, ws);
      }
    } catch (err) {
      log.error('Failed to parse message', { error: String(err) });
      ws.send(JSON.stringify({ type: 'error', payload: { message: 'Invalid JSON' } }));
    }
  });

  ws.on('close', () => {
    if (agentId) {
      store.setAgentOffline(agentId);
      store.logAudit('agent.offline', agentId);
      connections.delete(agentId);
      log.info(`Agent disconnected: ${agentId}`);
    }
  });
});

// 心跳超时检测：每 60 秒检查一次
setInterval(() => {
  const online = store.listOnlineAgents();
  const now = Date.now();
  for (const agent of online) {
    if (agent.lastSeenAt) {
      const elapsed = now - new Date(agent.lastSeenAt).getTime();
      if (elapsed > HEARTBEAT_TIMEOUT_MS) {
        log.warn(`Agent heartbeat timeout: ${agent.id} (${agent.name})`);
        store.setAgentOffline(agent.id);
        store.logAudit('agent.offline', agent.id, undefined, undefined, { reason: 'heartbeat_timeout' });
        const ws = connections.get(agent.id);
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.close(1000, 'heartbeat_timeout');
        }
        connections.delete(agent.id);
      }
    }
  }

  // 检查超时的任务
  taskEngine.checkTimeouts(5);
}, 60_000);

// 优雅停机
function gracefulShutdown(signal: string) {
  log.info(`Received ${signal}, shutting down gracefully...`);
  // 广播维护通知
  for (const [id, ws] of connections) {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'server.maintenance', payload: { message: 'Server shutting down' } }));
    }
    store.setAgentOffline(id);
  }
  store.close();
  taskStore.close();
  server.close(() => {
    log.info('Server closed');
    process.exit(0);
  });
  // 60 秒强制退出
  setTimeout(() => process.exit(1), 60_000);
}

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

server.listen(PORT, () => {
  log.info(`Bazaar server started on port ${PORT}`);
  log.info(`Database: ${DB_PATH}`);
});
