// stardom/src/message-router.ts
import type WebSocket from 'ws';
import { validateMessage } from './protocol.js';
import type { AgentStore } from './agent-store.js';
import type { TaskEngine } from './task-engine.js';
import type { WorldState } from './world-state.js';
import { createLogger, type Logger } from './utils/logger.js';
import { v4 as uuidv4 } from 'uuid';

interface RouteResult {
  handled: boolean;
  error?: string;
}

// ws → agentId 反向索引
const wsToAgent = new WeakMap<WebSocket, string>();

export class MessageRouter {
  private log: Logger;
  private store: AgentStore;
  private taskEngine: TaskEngine | null;
  private worldState: WorldState | null;
  private connections: Map<string, WebSocket>;

  constructor(store: AgentStore, taskEngine?: TaskEngine, connections?: Map<string, WebSocket>, worldState?: WorldState) {
    this.store = store;
    this.taskEngine = taskEngine ?? null;
    this.worldState = worldState ?? null;
    this.connections = connections ?? new Map();
    this.log = createLogger('MessageRouter');
  }

  route(raw: unknown, ws: WebSocket): RouteResult {
    // 校验消息格式
    const validation = validateMessage(raw);
    if (!validation.valid) {
      this.log.warn('Invalid message received', { errors: validation.errors });
      return { handled: false, error: validation.errors.join('; ') };
    }

    const msg = raw as { id: string; type: string; payload: Record<string, unknown> };

    // 发送 ack
    const send = (data: unknown) => {
      if (ws.readyState === 1) { // WebSocket.OPEN
        ws.send(JSON.stringify(data));
      }
    };

    send({ type: 'ack', id: msg.id });

    // 路由分发
    const type = msg.type;
    const payload = msg.payload;

    try {
      if (type === 'agent.register') {
        return this.handleRegister(payload, ws, send);
      } else if (type === 'agent.heartbeat') {
        return this.handleHeartbeat(payload, send);
      } else if (type === 'agent.update') {
        return this.handleUpdate(payload, send);
      } else if (type === 'agent.offline') {
        return this.handleOffline(payload, send);
      } else if (type.startsWith('task.')) {
        return this.handleTaskMessage(type, payload, ws, send);
      } else if (type.startsWith('world.')) {
        return this.handleWorldMessage(type, payload, ws);
      } else {
        this.log.warn(`Unhandled message type: ${type}`);
        return { handled: true }; // 已知类型但当前 phase 未实现
      }
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : String(err);
      this.log.error(`Error handling ${type}`, { error: errorMsg });
      send({ type: 'error', id: msg.id, payload: { message: errorMsg } });
      return { handled: false, error: errorMsg };
    }
  }

  private handleRegister(
    payload: Record<string, unknown>,
    _ws: WebSocket,
    send: (data: unknown) => void,
  ): RouteResult {
    const agentId = payload.agentId as string;

    // 检查是否已注册（恢复身份）
    let agent = this.store.getAgent(agentId);
    if (!agent) {
      // 按 username 查找
      agent = this.store.getAgentByUsername(payload.username as string);
    }

    if (agent) {
      // 恢复身份
      this.store.updateAgentStatus(agent.id, 'idle');
      this.store.updateHeartbeat(agent.id);
    } else {
      // 新注册
      this.store.registerAgent({
        id: agentId,
        username: payload.username as string,
        hostname: payload.hostname as string,
        name: payload.name as string,
        description: (payload.description as string) ?? '',
        avatar: (payload.avatar as string) ?? '🧙',
      });
    }

    // 更新能力标签（如有）
    const domains = payload.domains;
    if (Array.isArray(domains) && domains.length > 0) {
      this.store.updateCapabilities(agentId, domains);
    }

    this.store.logAudit('agent.online', agentId);

    this.log.info(`Agent registered: ${payload.name} (${agentId})`);

    // 记录 ws → agentId 映射，用于 task 消息路由
    wsToAgent.set(_ws, agentId);

    // 回复注册成功
    send({
      type: 'agent.registered',
      id: crypto.randomUUID(),
      inReplyTo: undefined,
      payload: { agentId, status: 'idle' },
    });

    return { handled: true };
  }

  private handleHeartbeat(
    payload: Record<string, unknown>,
    send: (data: unknown) => void,
  ): RouteResult {
    const agentId = payload.agentId as string;
    const status = payload.status as string;

    const agent = this.store.getAgent(agentId);
    if (!agent) {
      send({ type: 'error', payload: { message: `Agent not found: ${agentId}` } });
      return { handled: false, error: 'Agent not found' };
    }

    this.store.updateAgentStatus(agentId, status);
    this.store.updateHeartbeat(agentId);

    return { handled: true };
  }

  private handleUpdate(
    payload: Record<string, unknown>,
    _send: (data: unknown) => void,
  ): RouteResult {
    const agentId = payload.agentId as string;

    if (payload.status) {
      this.store.updateAgentStatus(agentId, payload.status as string);
    }
    this.store.updateHeartbeat(agentId);

    this.log.info(`Agent updated: ${agentId}`);
    return { handled: true };
  }

  private handleOffline(
    payload: Record<string, unknown>,
    _send: (data: unknown) => void,
  ): RouteResult {
    const agentId = payload.agentId as string;
    this.store.setAgentOffline(agentId);
    this.store.logAudit('agent.offline', agentId);
    this.log.info(`Agent offline: ${agentId}`);
    return { handled: true };
  }

  private handleTaskMessage(
    type: string,
    payload: Record<string, unknown>,
    ws: WebSocket,
    send: (data: unknown) => void,
  ): RouteResult {
    if (!this.taskEngine) {
      this.log.warn('TaskEngine not initialized, ignoring task message');
      return { handled: false, error: 'TaskEngine not available' };
    }

    // 从 ws 反查 agentId
    const fromAgentId = wsToAgent.get(ws);
    if (!fromAgentId) {
      return { handled: false, error: 'Agent not registered' };
    }

    const msg = { id: '', type, payload };

    if (type === 'task.create') {
      const result = this.taskEngine.handleTaskCreate(msg, fromAgentId);
      send({ type: 'task.search_result', id: uuidv4(), payload: { taskId: result.taskId, matches: result.matches } });
      return { handled: true };
    } else if (type === 'task.offer') {
      const result = this.taskEngine.handleTaskOffer(msg, fromAgentId);
      if (result.error) {
        send({ type: 'error', id: uuidv4(), payload: { message: result.error } });
      }
      return { handled: true };
    } else if (type === 'task.accept') {
      this.taskEngine.handleTaskAccept(msg, fromAgentId);
      return { handled: true };
    } else if (type === 'task.reject') {
      this.taskEngine.handleTaskReject(msg, fromAgentId);
      return { handled: true };
    } else if (type === 'task.chat') {
      this.taskEngine.handleTaskChat(msg, fromAgentId);
      return { handled: true };
    } else if (type === 'task.complete') {
      this.taskEngine.handleTaskComplete(msg, fromAgentId);
      return { handled: true };
    } else if (type === 'task.cancel') {
      this.taskEngine.handleTaskCancel(msg, fromAgentId);
      return { handled: true };
    } else if (type === 'task.sync') {
      const result = this.taskEngine.handleTaskSync(msg, fromAgentId);
      if (result.error) {
        send({ type: 'error', id: uuidv4(), payload: { message: result.error } });
      }
      return { handled: true };
    }

    return { handled: true };
  }

  private handleWorldMessage(type: string, payload: Record<string, unknown>, ws: WebSocket): RouteResult {
    if (!this.worldState) {
      this.log.warn('WorldState not initialized, ignoring world message');
      return { handled: false, error: 'WorldState not available' };
    }

    const agentId = wsToAgent.get(ws);
    if (!agentId) return { handled: false, error: 'Agent not registered' };

    if (type === 'world.move') {
      this.worldState.handleMove(
        agentId,
        payload.x as number,
        payload.y as number,
        (payload.state as string) ?? 'walking',
        (payload.facing as string) ?? 'down',
      );
      return { handled: true };
    }

    return { handled: true };
  }
}
