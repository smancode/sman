// server/bazaar/bazaar-bridge.ts
import { v4 as uuidv4 } from 'uuid';
import os from 'os';
import { createLogger, type Logger } from '../utils/logger.js';
import { BazaarClient } from './bazaar-client.js';
import { BazaarStore } from './bazaar-store.js';
import { BazaarSession } from './bazaar-session.js';
import { createBazaarMcpServer } from './bazaar-mcp.js';
import type { BridgeDeps } from './types.js';
import type { BazaarConfig } from '../../shared/bazaar-types.js';

export class BazaarBridge {
  private log: Logger;
  private client: BazaarClient;
  private store: BazaarStore;
  private deps: BridgeDeps;
  private bazaarSession: BazaarSession | null = null;
  private notifyTimeouts = new Map<string, ReturnType<typeof setTimeout>>();

  constructor(deps: BridgeDeps) {
    this.deps = deps;
    this.log = createLogger('BazaarBridge');
    const dbPath = `${deps.homeDir}/bazaar.db`;
    this.store = new BazaarStore(dbPath);
    this.client = new BazaarClient(this.store, {
      getAgentProjects: () => this.getAgentProjects(),
    });

    // 集市消息 → 前端推送
    this.client.onMessage = (msg) => this.handleBazaarMessage(msg);
  }

  async start(): Promise<void> {
    const config = this.deps.settingsManager.getConfig();
    if (!config.bazaar?.server) {
      this.log.info('Bazaar not configured, bridge not started');
      return;
    }

    // 确保有 Agent 身份
    this.ensureIdentity(config.bazaar);

    try {
      await this.client.connect();
      this.log.info('Bazaar bridge started');

      // 初始化协作 Session 管理器
      this.bazaarSession = new BazaarSession({
        sessionManager: this.deps.sessionManager,
        client: this.client,
        store: this.store,
        broadcast: this.deps.broadcast,
        homeDir: this.deps.homeDir,
        maxConcurrentTasks: config.bazaar?.maxConcurrentTasks ?? 3,
      });

      // 创建 MCP Server（bazaar_search + bazaar_collaborate）
      const mcpServer = createBazaarMcpServer({
        store: this.store,
        client: this.client,
        broadcast: this.deps.broadcast,
      });
      this.log.info('Bazaar MCP server created', { serverName: mcpServer.name });

      // 推送初始连接状态给前端
      const identity = this.store.getIdentity();
      this.deps.broadcast(JSON.stringify({
        type: 'bazaar.status',
        event: 'connected',
        agentId: identity?.agentId,
        agentName: identity?.name,
        reputation: 0,
        activeSlots: 0,
        maxSlots: config.bazaar?.maxConcurrentTasks ?? 3,
      }));
    } catch (err) {
      this.log.error('Failed to connect to bazaar', { error: String(err) });
    }
  }

  stop(): void {
    // 清理所有活跃协作
    this.bazaarSession?.stopAll();
    this.bazaarSession = null;

    // 清理所有 notify 超时
    for (const timer of this.notifyTimeouts.values()) {
      clearTimeout(timer);
    }
    this.notifyTimeouts.clear();

    this.client.disconnect();
    this.log.info('Bazaar bridge stopped');
  }

  // ── 前端 → Bridge 消息处理 ──

  handleFrontendMessage(type: string, payload: Record<string, unknown>, ws: unknown): void {
    switch (type) {
      case 'bazaar.task.list':
        this.deps.broadcast(JSON.stringify({
          type: 'bazaar.task.list.update',
          tasks: this.store.listTasks(),
        }));
        break;

      case 'bazaar.task.accept': {
        const taskId = payload.taskId as string;
        this.clearNotifyTimeout(taskId);
        this.client.send({
          id: uuidv4(),
          type: 'task.accept',
          payload: { taskId },
        });
        break;
      }

      case 'bazaar.task.reject': {
        const taskId = payload.taskId as string;
        this.clearNotifyTimeout(taskId);
        this.store.updateTaskStatus(taskId, 'rejected');
        this.client.send({
          id: uuidv4(),
          type: 'task.reject',
          payload: { taskId, reason: 'user_manual_reject' },
        });
        break;
      }

      case 'bazaar.task.cancel': {
        const taskId = payload.taskId as string;
        // 中止协作 Session
        this.bazaarSession?.abortCollaboration(taskId);
        this.client.send({
          id: uuidv4(),
          type: 'task.cancel',
          payload: { taskId, reason: 'user_manual_cancel' },
        });
        break;
      }

      case 'bazaar.config.update': {
        const config = this.deps.settingsManager.getConfig();
        const updated = { ...config.bazaar, ...payload } as import('../../shared/bazaar-types.js').BazaarConfig | undefined;
        this.deps.settingsManager.updateConfig({ ...config, bazaar: updated });
        this.log.info('Bazaar config updated', { mode: payload.mode });
        break;
      }

      default:
        this.log.warn(`Unknown frontend message type: ${type}`);
    }
  }

  // ── 集市 → Bridge 消息处理 ──

  private handleBazaarMessage(msg: { type: string; payload: Record<string, unknown> }): void {
    this.log.info(`Bazaar message: ${msg.type}`);

    switch (msg.type) {
      case 'task.incoming':
        this.handleIncomingTask(msg.payload);
        break;

      case 'task.chat':
        this.handleIncomingChat(msg.payload);
        break;

      case 'task.accept':
        this.handleTaskAccepted(msg.payload);
        break;

      case 'task.complete':
        this.handleTaskComplete(msg.payload);
        break;

      case 'task.matched':
        this.deps.broadcast(JSON.stringify({
          type: 'bazaar.status',
          event: 'task_matched',
          taskId: msg.payload.taskId,
          helper: msg.payload.helper,
        }));
        break;

      case 'task.timeout':
      case 'task.cancelled':
        this.store.updateTaskStatus(
          msg.payload.taskId as string,
          msg.type === 'task.timeout' ? 'timeout' : 'cancelled',
        );
        this.deps.broadcast(JSON.stringify({
          type: 'bazaar.status',
          event: msg.type,
          taskId: msg.payload.taskId,
        }));
        break;

      case 'reputation.update':
        this.deps.broadcast(JSON.stringify({
          type: 'bazaar.status',
          event: 'reputation_updated',
          agentId: msg.payload.agentId,
          delta: msg.payload.delta,
          newTotal: msg.payload.newTotal,
          reason: msg.payload.reason,
        }));
        break;

      default:
        // 其他消息直接推送前端
        this.deps.broadcast(JSON.stringify({
          type: 'bazaar.status',
          event: msg.type,
          payload: msg.payload,
        }));
    }
  }

  private handleIncomingTask(payload: Record<string, unknown>): void {
    const config = this.deps.settingsManager.getConfig().bazaar;
    const mode = config?.mode ?? 'notify';

    const taskId = payload.taskId as string;
    const helperAgentId = payload.from as string;
    const helperName = (payload.fromName as string) ?? '一位同事';
    const question = payload.question as string;

    this.store.saveTask({
      taskId,
      direction: 'incoming',
      requesterAgentId: helperAgentId,
      requesterName: helperName,
      question,
      status: 'offered',
      createdAt: new Date().toISOString(),
    });

    if (mode === 'auto') {
      // 全自动模式：直接接受并启动协作
      this.client.send({
        id: uuidv4(),
        type: 'task.accept',
        payload: { taskId },
      });
    } else if (mode === 'notify') {
      // 半自动模式：通知前端，30 秒超时自动接受
      this.deps.broadcast(JSON.stringify({
        type: 'bazaar.notify',
        taskId,
        from: helperName,
        question,
        mode,
      }));

      // 30 秒后自动接受
      const timer = setTimeout(() => {
        this.notifyTimeouts.delete(taskId);
        const task = this.store.getTask(taskId);
        if (task && task.status === 'offered') {
          this.client.send({
            id: uuidv4(),
            type: 'task.accept',
            payload: { taskId },
          });
        }
      }, 30_000);
      this.notifyTimeouts.set(taskId, timer);
    } else {
      // manual 模式：等待前端操作
      this.deps.broadcast(JSON.stringify({
        type: 'bazaar.notify',
        taskId,
        from: helperName,
        question,
        mode,
      }));
    }
  }

  private ensureIdentity(bazaarConfig: BazaarConfig): void {
    let identity = this.store.getIdentity();
    if (!identity) {
      identity = {
        agentId: uuidv4(),
        hostname: os.hostname(),
        username: os.userInfo().username,
        name: bazaarConfig.agentName ?? os.userInfo().username,
        server: bazaarConfig.server,
      };
      this.store.saveIdentity(identity);
      this.log.info(`New agent identity created: ${identity.agentId}`);
    } else {
      // 更新服务器地址（可能变了）
      identity.server = bazaarConfig.server;
      if (bazaarConfig.agentName) identity.name = bazaarConfig.agentName;
      this.store.saveIdentity(identity);
    }
  }

  private getAgentProjects(): Array<{ repo: string; skills: string }> {
    // 从 SkillsRegistry 获取当前项目的 skills
    const projects: Array<{ repo: string; skills: string }> = [];
    // Phase 1 简化版：从配置中的工作目录推断
    // 后续 Phase 从 SkillsRegistry 动态获取
    return projects;
  }

  // ── 协作 Session 相关处理 ──

  private handleTaskAccepted(payload: Record<string, unknown>): void {
    if (!this.bazaarSession) return;

    const taskId = payload.taskId as string;
    const task = this.store.getTask(taskId);
    if (!task) {
      this.log.warn(`Task not found for accept: ${taskId}`);
      return;
    }

    // 取消 notify 超时（如果有）
    this.clearNotifyTimeout(taskId);

    // 更新状态
    this.store.updateTaskStatus(taskId, 'chatting');

    // 启动协作 Session
    const workspace = this.deps.homeDir;
    this.bazaarSession.startCollaboration(
      taskId,
      task.question,
      task.requesterAgentId ?? 'unknown',
      task.requesterName ?? '一位同事',
      workspace,
    ).catch((err) => {
      this.log.error(`Failed to start collaboration for ${taskId}`, { error: String(err) });
    });
  }

  private handleIncomingChat(payload: Record<string, unknown>): void {
    if (!this.bazaarSession) return;

    const taskId = payload.taskId as string;
    const from = payload.from as string;
    const text = payload.text as string;

    // 推送到前端
    this.deps.broadcast(JSON.stringify({
      type: 'bazaar.task.chat.delta',
      taskId,
      from,
      text,
    }));

    // 如果是对方发来的消息，注入协作 Session
    if (from !== 'local') {
      this.bazaarSession.sendCollaborationMessage(taskId, text).catch((err) => {
        this.log.error(`Failed to send collaboration message for ${taskId}`, { error: String(err) });
      });
    }
  }

  private handleTaskComplete(payload: Record<string, unknown>): void {
    const taskId = payload.taskId as string;
    const rating = payload.rating as number;
    const feedback = (payload.feedback as string) ?? '';

    // 保存经验路由（rating >= 3 表示成功）
    if (rating >= 3) {
      const task = this.store.getTask(taskId);
      if (task) {
        const capability = task.question.slice(0, 50);
        const agentId = task.requesterAgentId ?? task.helperAgentId;
        const agentName = task.requesterName ?? task.helperName;
        const repo = '';
        if (agentId && agentName) {
          this.store.saveLearnedRoute({ capability, agentId, agentName, repo });
        }
      }
    }

    if (this.bazaarSession) {
      this.bazaarSession.completeCollaboration(taskId, rating, feedback);
    }
  }

  // ── notify 超时清理辅助 ──

  private clearNotifyTimeout(taskId: string): void {
    const timer = this.notifyTimeouts.get(taskId);
    if (timer) {
      clearTimeout(timer);
      this.notifyTimeouts.delete(taskId);
    }
  }
}
