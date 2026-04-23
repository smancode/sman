// server/stardom/stardom-session.ts
import { v4 as uuidv4 } from 'uuid';
import { createLogger, type Logger } from '../utils/logger.js';
import type { ActiveCollaboration, StardomSessionDeps } from './types.js';

const SESSION_ID_PREFIX = 'stardom-';

/**
 * 协作 Session 管理器
 *
 * 负责为每个协作任务创建独立的 Claude Session，管理生命周期：
 * - 创建：收到 task.incoming + accept 后调用 startCollaboration
 * - 对话：通过 sendCollaborationMessage 注入集市发来的消息
 * - 回复：从 sendMessageForCron 的流式回调中提取 Claude 回复，发回集市
 * - 结束：complete/abort/timeout 时清理资源
 *
 * Note: sendMessageForCron 的 onActivity 是 () => void（无回复内容），
 * 当前通过 sendClaudeReplyToStardom() 方法由外部调用来发送回复。
 * 未来可以通过 stream hook 提取 Claude 回复自动调用。
 */
export class StardomSession {
  private log: Logger;
  private deps: StardomSessionDeps;
  private activeCollaborations = new Map<string, ActiveCollaboration>();

  constructor(deps: StardomSessionDeps) {
    this.deps = deps;
    this.log = createLogger('StardomSession');
  }

  /**
   * 启动协作：创建 Claude Session 并发送初始问题
   */
  async startCollaboration(
    taskId: string,
    question: string,
    helperAgentId: string,
    helperName: string,
    workspace: string,
  ): Promise<void> {
    if (!this.hasAvailableSlot()) {
      throw new Error(`协作槽位已满 (${this.activeCollaborations.size}/${this.deps.maxConcurrentTasks})`);
    }

    if (this.activeCollaborations.has(taskId)) {
      this.log.warn(`Collaboration already active for task ${taskId}`);
      return;
    }

    const sessionId = `${SESSION_ID_PREFIX}${taskId}`;
    const abortController = new AbortController();
    const now = new Date().toISOString();

    const collaboration: ActiveCollaboration = {
      taskId,
      helperAgentId,
      helperName,
      question,
      sessionId,
      abortController,
      startedAt: now,
      lastActivityAt: now,
    };

    // 创建 Claude Session
    this.deps.sessionManager.createSessionWithId(workspace, sessionId, true);
    this.activeCollaborations.set(taskId, collaboration);

    // 保存系统消息到本地存储
    this.deps.store.saveChatMessage({
      taskId,
      from: 'system',
      text: `[协作请求 - 来自 Agent「${helperName}」]\n\n${question}`,
    });

    // 构造发送给 Claude 的内容
    const content = [
      `[协作请求 - 来自 Agent「${helperName}」]`,
      '',
      question,
      '',
      '请用中文回复。这是来自其他 Agent 的协作请求，请尽可能清晰地回答问题。如果你不确定答案，请如实说明。',
    ].join('\n');

    // 发送消息到 Claude Session
    try {
      await this.deps.sessionManager.sendMessageForCron(
        sessionId,
        content,
        abortController,
        () => this.handleClaudeActivity(taskId),
      );
    } catch (err) {
      this.log.info(`Collaboration session completed for task ${taskId}`, {
        error: err instanceof Error ? err.message : undefined,
      });
    }

    // 推送状态更新给前端
    this.deps.broadcast(JSON.stringify({
      type: 'stardom.status',
      event: 'collaboration_started',
      taskId,
      helperName,
      activeSlots: this.activeCollaborations.size,
      maxSlots: this.deps.maxConcurrentTasks,
    }));

    this.log.info(`Collaboration started: task=${taskId}, helper=${helperName}, session=${sessionId}`);
  }

  /**
   * 向已有协作 Session 发送消息（来自集市中继的对方消息）
   */
  async sendCollaborationMessage(taskId: string, text: string): Promise<void> {
    const collab = this.activeCollaborations.get(taskId);
    if (!collab) {
      throw new Error(`没有活跃的协作: ${taskId}`);
    }

    // 保存到本地聊天记录
    this.deps.store.saveChatMessage({
      taskId,
      from: 'remote',
      text,
    });

    // 构造转发内容
    const content = `[对方 Agent「${collab.helperName}」追问]\n\n${text}`;

    // 创建新的 AbortController（复用同一 session 的新一轮对话）
    const newAbortController = new AbortController();
    collab.abortController = newAbortController;
    collab.lastActivityAt = new Date().toISOString();

    await this.deps.sessionManager.sendMessageForCron(
      collab.sessionId,
      content,
      newAbortController,
      () => this.handleClaudeActivity(taskId),
    );
  }

  /**
   * 中止协作
   */
  abortCollaboration(taskId: string): void {
    const collab = this.activeCollaborations.get(taskId);
    if (!collab) {
      this.log.warn(`No active collaboration to abort: ${taskId}`);
      return;
    }

    collab.abortController.abort();
    this.deps.sessionManager.abort(collab.sessionId);
    this.activeCollaborations.delete(taskId);

    this.deps.broadcast(JSON.stringify({
      type: 'stardom.status',
      event: 'collaboration_aborted',
      taskId,
      activeSlots: this.activeCollaborations.size,
    }));

    this.log.info(`Collaboration aborted: task=${taskId}`);
  }

  /**
   * 完成协作：评分 + 清理 + 通知集市
   */
  completeCollaboration(taskId: string, rating: number, feedback: string): void {
    const collab = this.activeCollaborations.get(taskId);
    if (!collab) {
      this.log.warn(`No active collaboration to complete: ${taskId}`);
      return;
    }

    // 中止 Claude Session
    collab.abortController.abort();
    this.deps.sessionManager.abort(collab.sessionId);
    this.activeCollaborations.delete(taskId);

    // 更新本地任务状态
    this.deps.store.updateTaskStatus(taskId, 'completed', rating, new Date().toISOString());

    // 发送 task.complete 到集市
    this.deps.client.send({
      id: uuidv4(),
      type: 'task.complete',
      payload: { taskId, rating, feedback },
    });

    this.deps.broadcast(JSON.stringify({
      type: 'stardom.status',
      event: 'collaboration_completed',
      taskId,
      rating,
      activeSlots: this.activeCollaborations.size,
    }));

    this.log.info(`Collaboration completed: task=${taskId}, rating=${rating}`);
  }

  /**
   * Claude 产生回复时的活动回调
   * 用于心跳检测（sendMessageForCron 的 onActivity 是 () => void）
   */
  private handleClaudeActivity(taskId: string): void {
    const collab = this.activeCollaborations.get(taskId);
    if (collab) {
      collab.lastActivityAt = new Date().toISOString();
    }
  }

  /**
   * 提取 Claude 最终回复并发送回集市
   * 当前由外部调用，未来可改为从 stream hook 自动提取
   */
  sendClaudeReplyToStardom(taskId: string, replyText: string): void {
    if (!replyText.trim()) return;

    // 保存到本地聊天记录
    this.deps.store.saveChatMessage({
      taskId,
      from: 'local',
      text: replyText,
    });

    // 发送 task.chat 到集市
    this.deps.client.send({
      id: uuidv4(),
      type: 'task.chat',
      payload: { taskId, text: replyText },
    });

    // 推送给前端
    this.deps.broadcast(JSON.stringify({
      type: 'stardom.task.chat.delta',
      taskId,
      from: 'local',
      text: replyText,
    }));

    this.log.info(`Sent Claude reply to stardom for task ${taskId} (${replyText.length} chars)`);
  }

  // ── 查询方法 ──

  hasAvailableSlot(): boolean {
    return this.activeCollaborations.size < this.deps.maxConcurrentTasks;
  }

  getActiveCount(): number {
    return this.activeCollaborations.size;
  }

  getActiveCollaboration(taskId: string): ActiveCollaboration | undefined {
    return this.activeCollaborations.get(taskId);
  }

  listActiveTasks(): string[] {
    return Array.from(this.activeCollaborations.keys());
  }

  /**
   * 停止所有活跃协作（进程退出时调用）
   */
  stopAll(): void {
    for (const [taskId, collab] of this.activeCollaborations) {
      collab.abortController.abort();
      this.deps.sessionManager.abort(collab.sessionId);
      this.log.info(`Stopped collaboration: task=${taskId}`);
    }
    this.activeCollaborations.clear();
  }
}
