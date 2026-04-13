// server/bazaar/bazaar-bridge.ts
import { v4 as uuidv4 } from 'uuid';
import os from 'os';
import { createLogger, type Logger } from '../utils/logger.js';
import { BazaarClient } from './bazaar-client.js';
import { BazaarStore } from './bazaar-store.js';
import { BazaarSession } from './bazaar-session.js';
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
      getAgentDescription: () => this.getAgentDescription(),
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

      // MCP Server 通过 sman cli 按需加载，不自动注入到 Claude Session
      // 当用户需要 bazaar 协作能力时，通过 cli 命令加载 bazaar MCP 工具
      this.log.info('Bazaar bridge started. MCP tools available via sman cli load.');

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

      case 'bazaar.leaderboard':
        this.fetchLeaderboard();
        break;

      case 'bazaar.config.update': {
        const config = this.deps.settingsManager.getConfig();
        const updated = { ...config.bazaar, ...payload } as import('../../shared/bazaar-types.js').BazaarConfig | undefined;
        this.deps.settingsManager.updateConfig({ ...config, bazaar: updated });
        this.log.info('Bazaar config updated', { mode: payload.mode });
        break;
      }

      case 'bazaar.world.move': {
        const identity = this.store.getIdentity();
        if (!identity) break;
        this.client.send({
          id: uuidv4(),
          type: 'world.move',
          payload: { agentId: identity.agentId, ...payload },
        });
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

      case 'world.agent_update':
      case 'world.agent_enter':
      case 'world.agent_leave':
      case 'world.zone_snapshot':
      case 'world.enter_zone':
      case 'world.leave_zone':
      case 'world.event':
        this.deps.broadcast(JSON.stringify({
          type: `bazaar.${msg.type}`,
          ...msg.payload,
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

  private getAgentDescription(): string {
    // Agent 注册到集市只给 name + description，不上传 skills/projects 详情
    // description 可从配置中获取，描述"我是谁、我能干什么"
    const config = this.deps.settingsManager.getConfig().bazaar;
    return config?.agentName ? `${config.agentName} 的 Sman Agent` : 'Sman Agent';
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

    if (rating >= 3) {
      const task = this.store.getTask(taskId);
      if (task) {
        const capability = task.question;
        const agentId = task.requesterAgentId ?? task.helperAgentId;
        const agentName = task.requesterName ?? task.helperName;
        if (agentId && agentName) {
          // Fire-and-forget async experience extraction
          this.extractExperience(taskId, capability, agentId, agentName).catch(() => {
            this.log.info('Experience extraction failed, saving route without experience');
            this.store.saveLearnedRoute({ capability, agentId, agentName });
          });
        }
      }
    }

    if (this.bazaarSession) {
      this.bazaarSession.completeCollaboration(taskId, rating, feedback);
    }
  }

  /**
   * Extract experience summary from conversation history (best-effort, non-blocking)
   * 30s timeout, silent degradation on failure
   */
  private async extractExperience(
    taskId: string,
    capability: string,
    agentId: string,
    agentName: string,
  ): Promise<void> {
    const messages = this.store.listChatMessages(taskId);
    if (messages.length === 0) {
      this.store.saveLearnedRoute({ capability, agentId, agentName });
      return;
    }

    const chatText = messages
      .map(m => `${m.from === 'local' ? '我' : agentName}: ${m.text}`)
      .join('\n');

    const experience = await this.callClaudeForExperience(chatText);
    this.store.saveLearnedRoute({ capability, agentId, agentName, experience });
  }

  /**
   * Call Claude API to extract experience summary
   * 30s timeout, returns empty string on failure
   */
  private async callClaudeForExperience(chatText: string): Promise<string> {
    try {
      const config = this.deps.settingsManager.getConfig();
      const apiKey = config.llm?.apiKey;
      const baseUrl = config.llm?.baseUrl || 'https://api.anthropic.com';
      const model = config.llm?.model || 'claude-haiku-4-5-20251001';

      if (!apiKey) return '';

      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 30_000);

      const response = await fetch(`${baseUrl}/v1/messages`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-api-key': apiKey,
          'anthropic-version': '2023-06-01',
        },
        body: JSON.stringify({
          model,
          max_tokens: 200,
          messages: [{
            role: 'user',
            content: `从以下协作对话中提取经验摘要（100字以内）：
- 解决了什么问题
- 用了什么方法
- 关键知识点

对话内容：
${chatText}

直接输出经验摘要，不要其他内容：`,
          }],
        }),
        signal: controller.signal,
      });

      clearTimeout(timer);

      if (!response.ok) return '';

      const data = await response.json() as any;
      const text = data.content?.[0]?.text ?? '';
      return text.slice(0, 200);
    } catch {
      return '';
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

  // ── 排行榜 ──

  private async fetchLeaderboard(): Promise<void> {
    const identity = this.store.getIdentity();
    if (!identity) return;

    try {
      const response = await fetch(`http://${identity.server}/api/leaderboard?limit=50`);
      const data = await response.json();
      this.deps.broadcast(JSON.stringify({
        type: 'bazaar.leaderboard.update',
        leaderboard: data,
      }));
    } catch (e) {
      this.log.error('Failed to fetch leaderboard', { error: String(e) });
    }
  }
}
