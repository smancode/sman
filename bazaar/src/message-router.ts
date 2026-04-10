// bazaar/src/message-router.ts
import type WebSocket from 'ws';
import { validateMessage } from './protocol.js';
import type { AgentStore } from './agent-store.js';
import { createLogger, type Logger } from './utils/logger.js';

interface RouteResult {
  handled: boolean;
  error?: string;
}

export class MessageRouter {
  private log: Logger;
  private store: AgentStore;

  constructor(store: AgentStore) {
    this.store = store;
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
        avatar: (payload.avatar as string) ?? '🧙',
      });
    }

    // 更新项目列表（确保 skills 为 JSON string）
    const rawProjects = (payload.projects as Array<{ repo: string; skills: unknown }>) ?? [];
    const projects = rawProjects.map(p => ({
      repo: p.repo,
      skills: typeof p.skills === 'string' ? p.skills : JSON.stringify(p.skills),
    }));
    if (projects.length > 0) {
      this.store.updateProjects(agentId, projects);
    }

    this.store.logAudit('agent.online', agentId, undefined, undefined, {
      projects: projects.map(p => p.repo),
    });

    this.log.info(`Agent registered: ${payload.name} (${agentId})`);

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
    if (payload.projects) {
      this.store.updateProjects(agentId, payload.projects as Array<{ repo: string; skills: string }>);
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
}
