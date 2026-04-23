// server/stardom/stardom-bridge.ts
import { v4 as uuidv4 } from 'uuid';
import os from 'os';
import fs from 'fs';
import { createLogger, type Logger } from '../utils/logger.js';
import { StardomClient } from './stardom-client.js';
import { StardomStore } from './stardom-store.js';
import { StardomSession } from './stardom-session.js';
import type { BridgeDeps } from './types.js';
import type { StardomConfig } from '../../shared/stardom-types.js';

/**
 * 能力查找顺序引导 — 注入到 Claude 对话上下文
 * 通过 sman cli --with-stardom 加载时使用
 */
export const CAPABILITY_LOOKUP_GUIDE = `[能力查找顺序]
当你遇到无法完成的任务时，按以下顺序查找帮助：
1. 先自己尝试解决
2. 运行 \`sman capabilities list\` 检查本地已安装的能力包
3. 使用 stardom_search MCP 工具搜索集市上其他 Agent 寻求协作
4. 运行 \`sman capabilities search <关键词>\` 搜索集市能力包
5. 都找不到时，告知用户需要什么能力`;

export class StardomBridge {
  private log: Logger;
  private client: StardomClient;
  private store: StardomStore;
  private deps: BridgeDeps;
  private stardomSession: StardomSession | null = null;
  private notifyTimeouts = new Map<string, ReturnType<typeof setTimeout>>();

  constructor(deps: BridgeDeps) {
    this.deps = deps;
    this.log = createLogger('StardomBridge');
    const dbPath = `${deps.homeDir}/stardom.db`;
    this.store = new StardomStore(dbPath);
    this.client = new StardomClient(this.store, {
      getAgentDescription: () => this.getAgentDescription(),
      getAgentDomains: () => this.getAgentDomains(),
    });

    // 集市消息 → 前端推送
    this.client.onMessage = (msg) => this.handleStardomMessage(msg);

    // 重连后同步活跃任务
    this.client.onReconnect = () => this.syncActiveTasks();
  }

  async start(): Promise<void> {
    const config = this.deps.settingsManager.getConfig();
    if (!config.stardom?.server) {
      this.log.info('Stardom not configured, bridge not started');
      return;
    }

    // 确保有 Agent 身份
    this.ensureIdentity(config.stardom);

    try {
      await this.client.connect();
      this.log.info('Stardom bridge started');

      // 初始化协作 Session 管理器
      this.stardomSession = new StardomSession({
        sessionManager: this.deps.sessionManager,
        client: this.client,
        store: this.store,
        broadcast: this.deps.broadcast,
        homeDir: this.deps.homeDir,
        maxConcurrentTasks: config.stardom?.maxConcurrentTasks ?? 3,
      });

      // MCP Server 通过 sman cli 按需加载，不自动注入到 Claude Session
      // 当用户需要 stardom 协作能力时，通过 cli 命令加载 stardom MCP 工具
      this.log.info('Stardom bridge started. MCP tools available via sman cli load.');
      this.log.info('Capability lookup guide available via MCP tools');

      // 推送初始连接状态给前端
      const identity = this.store.getIdentity();
      this.deps.broadcast(JSON.stringify({
        type: 'stardom.status',
        event: 'connected',
        agentId: identity?.agentId,
        agentName: identity?.name,
        reputation: 0,
        activeSlots: 0,
        maxSlots: config.stardom?.maxConcurrentTasks ?? 3,
      }));
    } catch (err) {
      this.log.error('Failed to connect to stardom', { error: String(err) });
    }
  }

  stop(): void {
    // 清理所有活跃协作
    this.stardomSession?.stopAll();
    this.stardomSession = null;

    // 清理所有 notify 超时
    for (const timer of this.notifyTimeouts.values()) {
      clearTimeout(timer);
    }
    this.notifyTimeouts.clear();

    this.client.disconnect();
    this.log.info('Stardom bridge stopped');
  }

  // ── 前端 → Bridge 消息处理 ──

  handleFrontendMessage(type: string, payload: Record<string, unknown>, ws: unknown): void {
    switch (type) {
      case 'stardom.task.list':
        this.deps.broadcast(JSON.stringify({
          type: 'stardom.task.list.update',
          tasks: this.store.listTasks(),
        }));
        break;

      case 'stardom.task.accept': {
        const taskId = payload.taskId as string;
        this.clearNotifyTimeout(taskId);
        this.client.send({
          id: uuidv4(),
          type: 'task.accept',
          payload: { taskId },
        });
        break;
      }

      case 'stardom.task.reject': {
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

      case 'stardom.task.cancel': {
        const taskId = payload.taskId as string;
        // 中止协作 Session
        this.stardomSession?.abortCollaboration(taskId);
        this.client.send({
          id: uuidv4(),
          type: 'task.cancel',
          payload: { taskId, reason: 'user_manual_cancel' },
        });
        break;
      }

      case 'stardom.leaderboard':
        this.fetchLeaderboard();
        break;

      case 'stardom.config.update': {
        const config = this.deps.settingsManager.getConfig();
        const updated = { ...config.stardom, ...payload } as import('../../shared/stardom-types.js').StardomConfig | undefined;
        this.deps.settingsManager.updateConfig({ ...config, stardom: updated });
        this.log.info('Stardom config updated', { mode: payload.mode });
        break;
      }

      case 'stardom.world.move': {
        const identity = this.store.getIdentity();
        if (!identity) break;
        this.client.send({
          id: uuidv4(),
          type: 'world.move',
          payload: { agentId: identity.agentId, ...payload },
        });
        break;
      }

      case 'stardom.capabilities.list':
        this.deps.broadcast(JSON.stringify({
          type: 'stardom.capabilities.update',
          capabilities: this.store.listLearnedRoutes(),
        }));
        break;

      default:
        this.log.warn(`Unknown frontend message type: ${type}`);
    }
  }

  // ── 集市 → Bridge 消息处理 ──

  private handleStardomMessage(msg: { type: string; payload: Record<string, unknown> }): void {
    this.log.info(`Stardom message: ${msg.type}`);

    switch (msg.type) {
      case 'task.incoming':
        this.handleIncomingTask(msg.payload);
        break;

      case 'task.chat':
        this.handleIncomingChat(msg.payload);
        break;

      case 'task.sync':
        this.handleIncomingSync(msg.payload);
        break;

      case 'task.accept':
        this.handleTaskAccepted(msg.payload);
        break;

      case 'task.complete':
        this.handleTaskComplete(msg.payload);
        break;

      case 'task.matched':
        this.deps.broadcast(JSON.stringify({
          type: 'stardom.status',
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
          type: 'stardom.status',
          event: msg.type,
          taskId: msg.payload.taskId,
        }));
        break;

      case 'reputation.update':
        this.deps.broadcast(JSON.stringify({
          type: 'stardom.status',
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
          type: `stardom.${msg.type}`,
          ...msg.payload,
        }));
        break;

      default:
        // 其他消息直接推送前端
        this.deps.broadcast(JSON.stringify({
          type: 'stardom.status',
          event: msg.type,
          payload: msg.payload,
        }));
    }
  }

  private handleIncomingTask(payload: Record<string, unknown>): void {
    const config = this.deps.settingsManager.getConfig().stardom;
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
        type: 'stardom.notify',
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
        type: 'stardom.notify',
        taskId,
        from: helperName,
        question,
        mode,
      }));
    }
  }

  private ensureIdentity(stardomConfig: StardomConfig): void {
    let identity = this.store.getIdentity();
    if (!identity) {
      identity = {
        agentId: uuidv4(),
        hostname: os.hostname(),
        username: os.userInfo().username,
        name: stardomConfig.agentName ?? os.userInfo().username,
        server: stardomConfig.server,
      };
      this.store.saveIdentity(identity);
      this.log.info(`New agent identity created: ${identity.agentId}`);
    } else {
      // 更新服务器地址（可能变了）
      identity.server = stardomConfig.server;
      if (stardomConfig.agentName) identity.name = stardomConfig.agentName;
      this.store.saveIdentity(identity);
    }
  }

  private getAgentDescription(): string {
    const config = this.deps.settingsManager.getConfig().stardom;
    return config?.agentName ? `${config.agentName} 的 Sman Agent` : 'Sman Agent';
  }

  /**
   * 从活跃 workspace 的 INIT.md 提取能力标签
   * INIT.md 由 init-manager 在新建会话时自动生成，包含 projectType、techStack、skills 等信息
   */
  private getAgentDomains(): string[] {
    const domains = new Set<string>();
    try {
      const sessions = this.deps.sessionManager.listSessions();
      for (const session of sessions) {
        const initPath = `${session.workspace}/.sman/INIT.md`;
        try {
          const content = fs.readFileSync(initPath, 'utf-8');
          // 提取 techStack 行中的技术标签
          const techMatch = content.match(/tech[_-]?stack[:\s]+(.+)/i);
          if (techMatch) {
            techMatch[1].split(/[,，、\s]+/).forEach(t => {
              const trimmed = t.trim().toLowerCase();
              if (trimmed && trimmed.length > 1 && trimmed.length < 30) {
                domains.add(trimmed);
              }
            });
          }
          // 提取 projectType
          const typeMatch = content.match(/project[_-]?type[:\s]+(.+)/i);
          if (typeMatch) {
            const t = typeMatch[1].trim().toLowerCase();
            if (t) domains.add(t);
          }
          // 提取 skills 列表中的能力名
          const skillsMatch = content.match(/skills[:\s]+(.+)/i);
          if (skillsMatch) {
            skillsMatch[1].split(/[,，、\s]+/).forEach(s => {
              const trimmed = s.trim().toLowerCase();
              if (trimmed && trimmed.length > 2 && trimmed.length < 30) {
                domains.add(trimmed);
              }
            });
          }
        } catch {
          // INIT.md 不存在，跳过
        }
      }
    } catch {
      // sessionManager.listSessions 不可用，静默降级
    }
    return Array.from(domains);
  }

  // ── 协作 Session 相关处理 ──

  private handleTaskAccepted(payload: Record<string, unknown>): void {
    if (!this.stardomSession) return;

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

    // 构建协作上下文（如果有历史记录）
    const partnerId = task.requesterAgentId ?? task.helperAgentId;
    const partnerName = task.requesterName ?? task.helperName;
    let collaborationContext = '';
    if (partnerId) {
      const pair = this.store.getPairHistory(partnerId);
      if (pair && pair.taskCount >= 1) {
        collaborationContext = `\n[协作上下文]\n你之前和 Agent「${partnerName}」协作过 ${pair.taskCount} 次，平均评分 ${pair.avgRating}。\n`;
        // 查找该 Agent 的最近经验路由
        const allRoutes = this.store.listLearnedRoutes().filter(r => r.agentId === partnerId);
        if (allRoutes.length > 0) {
          collaborationContext += `上次协作解决了"${allRoutes[0].capability}"的问题。\n`;
        }
      }
    }

    // 启动协作 Session
    const workspace = this.deps.homeDir;
    this.stardomSession.startCollaboration(
      taskId,
      task.question + collaborationContext,
      task.requesterAgentId ?? 'unknown',
      task.requesterName ?? '一位同事',
      workspace,
    ).catch((err) => {
      this.log.error(`Failed to start collaboration for ${taskId}`, { error: String(err) });
    });
  }

  private handleIncomingChat(payload: Record<string, unknown>): void {
    if (!this.stardomSession) return;

    const taskId = payload.taskId as string;
    const from = payload.from as string;
    const text = payload.text as string;

    // 推送到前端
    this.deps.broadcast(JSON.stringify({
      type: 'stardom.task.chat.delta',
      taskId,
      from,
      text,
    }));

    // 如果是对方发来的消息，注入协作 Session
    if (from !== 'local') {
      this.stardomSession.sendCollaborationMessage(taskId, text).catch((err) => {
        this.log.error(`Failed to send collaboration message for ${taskId}`, { error: String(err) });
      });
    }

    // 缓存本地 Agent 的回复（用于异步 task.sync 恢复）
    if (from === 'local') {
      const task = this.store.getTask(taskId);
      if (task && ['chatting', 'matched'].includes(task.status)) {
        this.store.saveCachedResult({ taskId, resultText: text, fromAgent: 'local' });
      }
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

          // Update pair familiarity
          this.store.savePairHistory({ partnerId: agentId, partnerName: agentName, rating });
        }
      }
    }

    if (this.stardomSession) {
      this.stardomSession.completeCollaboration(taskId, rating, feedback);
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

  // ── 任务同步（断线重连恢复） ──

  /**
   * 重连后对每个活跃任务发送 task.sync
   * 对端（如果在线）会检查缓存并回复结果
   */
  private syncActiveTasks(): void {
    const activeTasks = this.store.listActiveTasks();
    if (activeTasks.length === 0) return;

    this.log.info(`Reconnected, syncing ${activeTasks.length} active tasks`);
    for (const task of activeTasks) {
      this.client.sendTaskSync(task.taskId);
    }
  }

  /**
   * 处理收到的 task.sync 请求
   * 对端重连后查询任务状态，我们检查本地缓存并回复
   */
  private handleIncomingSync(payload: Record<string, unknown>): void {
    const taskId = payload.taskId as string;
    const task = this.store.getTask(taskId);
    if (!task) return;

    // 检查是否有缓存的回复结果
    const cached = this.store.getCachedResult(taskId);
    if (cached) {
      // 有缓存：通过 task.chat 发给对端，然后清除缓存
      this.client.send({
        id: uuidv4(),
        type: 'task.chat',
        payload: { taskId, text: cached.resultText, from: 'local' },
      });
      this.store.deleteCachedResult(taskId);
      this.log.info(`Delivered cached result for task ${taskId}`);
    } else {
      // 没有缓存，检查是否还在处理中
      if (task.status === 'chatting' || task.status === 'matched') {
        this.client.send({
          id: uuidv4(),
          type: 'task.chat',
          payload: { taskId, text: '[系统] 对方重连，当前协作仍在处理中', from: 'local' },
        });
      }
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
        type: 'stardom.leaderboard.update',
        leaderboard: data,
      }));
    } catch (e) {
      this.log.error('Failed to fetch leaderboard', { error: String(e) });
    }
  }
}
