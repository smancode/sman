// server/bazaar/bazaar-bridge.ts
import { v4 as uuidv4 } from 'uuid';
import os from 'os';
import { createLogger, type Logger } from '../utils/logger.js';
import { BazaarClient } from './bazaar-client.js';
import { BazaarStore } from './bazaar-store.js';
import type { BridgeDeps } from './types.js';
import type { BazaarConfig } from '../../shared/bazaar-types.js';

export class BazaarBridge {
  private log: Logger;
  private client: BazaarClient;
  private store: BazaarStore;
  private deps: BridgeDeps;

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

      case 'bazaar.task.cancel': {
        const taskId = payload.taskId as string;
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
        this.deps.broadcast(JSON.stringify({
          type: 'bazaar.task.chat.delta',
          taskId: msg.payload.taskId,
          from: msg.payload.from,
          text: msg.payload.text,
        }));
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

    this.store.saveTask({
      taskId: payload.taskId as string,
      direction: 'incoming',
      requesterAgentId: payload.from as string,
      requesterName: (payload.fromName as string) ?? '一位同事',
      question: payload.question as string,
      status: 'offered',
      createdAt: new Date().toISOString(),
    });

    if (mode === 'auto') {
      // 全自动模式：直接接受
      this.client.send({
        id: uuidv4(),
        type: 'task.accept',
        payload: { taskId: payload.taskId as string },
      });
    } else {
      // notify/manual 模式：推送通知给前端
      // TODO(Phase 2): notify 模式需实现 30 秒超时自动接受
      // TODO(Phase 2): manual 模式需前端实现接受/拒绝 UI
      this.deps.broadcast(JSON.stringify({
        type: 'bazaar.notify',
        taskId: payload.taskId,
        from: payload.fromName ?? '一位同事',
        question: payload.question,
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
}
