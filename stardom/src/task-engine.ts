// stardom/src/task-engine.ts
import { v4 as uuidv4 } from 'uuid';
import { createLogger, type Logger } from './utils/logger.js';
import type { TaskStore } from './task-store.js';
import type { AgentStore } from './agent-store.js';
import type { ReputationEngine } from './reputation.js';
import type WebSocket from 'ws';

type SendFn = (agentId: string, data: unknown) => void;

export class TaskEngine {
  private log: Logger;
  private taskStore: TaskStore;
  private agentStore: AgentStore;
  private connections: Map<string, WebSocket>;
  private sendTo: SendFn;
  private reputationEngine: ReputationEngine | null;

  constructor(
    taskStore: TaskStore,
    agentStore: AgentStore,
    connections: Map<string, WebSocket>,
    sendTo: SendFn,
    reputationEngine?: ReputationEngine,
  ) {
    this.taskStore = taskStore;
    this.agentStore = agentStore;
    this.connections = connections;
    this.sendTo = sendTo;
    this.reputationEngine = reputationEngine ?? null;
    this.log = createLogger('TaskEngine');
  }

  handleTaskCreate(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): {
    taskId: string;
    matches: Array<{ agentId: string; name: string; status: string; reputation: number }>;
  } {
    const question = msg.payload.question as string;
    const capabilityQuery = msg.payload.capabilityQuery as string;
    const taskId = uuidv4();

    // 创建任务
    this.taskStore.createTask({
      id: taskId,
      requesterId: fromAgentId,
      question,
      capabilityQuery,
      status: 'searching',
    });

    // 搜索有相关能力的在线 Agent
    // 策略：先查 agent_capabilities 表做领域匹配，回退到 name/description 关键词匹配
    const keyword = capabilityQuery.toLowerCase();
    const domainMatches = this.agentStore.findAgentsByDomain(keyword);

    // 如果 domain 匹配有结果，优先使用；否则回退到关键词匹配
    const allOnline = domainMatches.length > 0
      ? domainMatches
      : this.agentStore.listOnlineAgents()
          .filter(a => a.id !== fromAgentId)
          .filter(a => {
            return a.name.toLowerCase().includes(keyword)
              || (a as any).description?.toLowerCase().includes(keyword);
          })
          .map(a => ({ ...a, domainMatch: false }));

    const matches = allOnline
      .filter(a => a.id !== fromAgentId) // 排除自己
      .map(a => ({
        agentId: a.id,
        name: a.name,
        status: a.status,
        reputation: a.reputation,
        repo: '',
      }))
      .sort((a, b) => b.reputation - a.reputation); // 按声望排序

    this.agentStore.logAudit('task.created', fromAgentId, undefined, taskId, {
      question, matchCount: matches.length,
    });

    return { taskId, matches };
  }

  handleTaskOffer(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): { error?: string } {
    const taskId = msg.payload.taskId as string;
    const targetAgent = msg.payload.targetAgent as string;
    const task = this.taskStore.getTask(taskId);

    if (!task) {
      return { error: 'Task not found' };
    }
    if (task.requesterId !== fromAgentId) {
      return { error: 'Not your task' };
    }

    // 检查目标 Agent 的 slot（使用 taskStore 而非 agentStore）
    const activeCount = this.taskStore.getActiveTaskCount(targetAgent);
    const maxSlots = 3; // TODO: 从配置读取
    if (activeCount >= maxSlots) {
      return { error: `Agent ${targetAgent} is busy (slots full)` };
    }

    // 更新任务状态
    const targetAgentRow = this.agentStore.getAgent(targetAgent);
    this.taskStore.updateTaskStatus(taskId, 'offered', {
      helperId: targetAgent,
      helperName: targetAgentRow?.name,
    });

    // 发送邀请给目标 Agent
    const deadline = new Date(Date.now() + 5 * 60_000).toISOString(); // 5 分钟 deadline
    this.sendTo(targetAgent, {
      type: 'task.incoming',
      id: uuidv4(),
      payload: {
        taskId,
        from: fromAgentId,
        fromName: this.agentStore.getAgent(fromAgentId)?.name ?? '一位同事',
        question: task.question,
        deadline,
      },
    });

    this.agentStore.logAudit('task.offered', fromAgentId, targetAgent, taskId, {});
    return {};
  }

  handleTaskAccept(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): void {
    const taskId = msg.payload.taskId as string;
    const task = this.taskStore.getTask(taskId);
    if (!task) return;

    this.taskStore.updateTaskStatus(taskId, 'matched', {
      helperId: fromAgentId,
      helperName: this.agentStore.getAgent(fromAgentId)?.name,
    });

    const helper = this.agentStore.getAgent(fromAgentId);

    // 通知发起方
    this.sendTo(task.requesterId, {
      type: 'task.matched',
      id: uuidv4(),
      payload: {
        taskId,
        helper: { agentId: fromAgentId, name: helper?.name ?? '未知' },
      },
    });

    // 确认给协助方
    this.sendTo(fromAgentId, {
      type: 'task.matched',
      id: uuidv4(),
      payload: { taskId },
    });

    this.agentStore.logAudit('task.accepted', fromAgentId, task.requesterId, taskId, {});
  }

  handleTaskReject(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): void {
    const taskId = msg.payload.taskId as string;
    const task = this.taskStore.getTask(taskId);
    if (!task) return;

    // 回到 searching 状态
    this.taskStore.updateTaskStatus(taskId, 'searching');

    // 通知发起方
    this.sendTo(task.requesterId, {
      type: 'task.progress',
      id: uuidv4(),
      payload: { taskId, status: 'searching', detail: 'Helper rejected, searching for next candidate' },
    });

    this.agentStore.logAudit('task.rejected', fromAgentId, task.requesterId, taskId, {});
  }

  handleTaskChat(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): void {
    const taskId = msg.payload.taskId as string;
    const text = msg.payload.text as string;
    const task = this.taskStore.getTask(taskId);
    if (!task || (task.status !== 'chatting' && task.status !== 'matched')) return;

    // 如果状态是 matched，升级为 chatting
    if (task.status === 'matched') {
      this.taskStore.updateTaskStatus(taskId, 'chatting');
    }

    // 确定接收方
    const targetId = task.requesterId === fromAgentId ? task.helperId : task.requesterId;
    if (!targetId) return;

    // 保存消息
    this.taskStore.saveChatMessage(taskId, fromAgentId, text);

    // 转发给对方
    this.sendTo(targetId, {
      type: 'task.chat',
      id: uuidv4(),
      payload: { taskId, text, from: fromAgentId },
    });
  }

  handleTaskComplete(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): void {
    const taskId = msg.payload.taskId as string;
    const rating = msg.payload.rating as number;
    const feedback = msg.payload.feedback as string | undefined;
    const task = this.taskStore.getTask(taskId);
    if (!task) return;

    this.taskStore.updateTaskStatus(taskId, 'completed', { rating, feedback });

    // 声望计算
    let reputationDelta = 0;
    if (this.reputationEngine && task.requesterId && task.helperId) {
      const repResult = this.reputationEngine.onTaskComplete(
        taskId, task.requesterId, task.helperId, rating,
      );
      reputationDelta = repResult.helperDelta;
    }

    // 通知协助方结算
    if (task.helperId) {
      this.sendTo(task.helperId, {
        type: 'task.result',
        id: uuidv4(),
        payload: { taskId, reputationDelta },
      });
    }

    this.agentStore.logAudit('task.completed', fromAgentId, task.helperId ?? undefined, taskId, {
      rating, feedback, reputationDelta,
    });
  }

  /**
   * task.sync: Agent A reconnects and queries task status from B
   * Server forwards to B if online, otherwise replies with waiting_helper
   */
  handleTaskSync(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): { error?: string } {
    const taskId = msg.payload.taskId as string;
    const task = this.taskStore.getTask(taskId);
    if (!task) {
      return { error: 'Task not found' };
    }

    // Verify caller is a participant
    if (task.requesterId !== fromAgentId && task.helperId !== fromAgentId) {
      return { error: 'Not a task participant' };
    }

    // Determine the peer to forward to
    const peerId = task.requesterId === fromAgentId ? task.helperId : task.requesterId;
    if (!peerId) {
      return { error: 'No peer agent found for this task' };
    }

    // Check if peer has an active connection
    const peerWs = this.connections.get(peerId);
    if (!peerWs || peerWs.readyState !== 1) {
      // Peer is offline — inform the requester
      this.sendTo(fromAgentId, {
        type: 'task.progress',
        id: uuidv4(),
        payload: { taskId, status: 'waiting_helper', detail: `Agent ${peerId} is offline` },
      });
      return {};
    }

    // Forward task.sync to the peer
    this.sendTo(peerId, {
      type: 'task.sync',
      id: uuidv4(),
      payload: { taskId },
    });

    this.agentStore.logAudit('task.sync', fromAgentId, peerId, taskId, {});
    return {};
  }

  handleTaskCancel(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): void {
    const taskId = msg.payload.taskId as string;
    const reason = msg.payload.reason as string;
    const task = this.taskStore.getTask(taskId);
    if (!task) return;

    this.taskStore.updateTaskStatus(taskId, 'cancelled');

    // 通知双方
    const agents = [task.requesterId];
    if (task.helperId) agents.push(task.helperId);

    for (const agentId of agents) {
      this.sendTo(agentId, {
        type: 'task.cancelled',
        id: uuidv4(),
        payload: { taskId, reason, cancelledBy: fromAgentId },
      });
    }

    this.agentStore.logAudit('task.cancelled', fromAgentId, task.helperId ?? undefined, taskId, { reason });
  }

  // 超时检测：检查所有超时的 chatting 任务
  checkTimeouts(timeoutMinutes: number): void {
    const timedOut = this.taskStore.listTimedOutTasks(timeoutMinutes);
    for (const task of timedOut) {
      this.taskStore.updateTaskStatus(task.id, 'timeout');

      const agents = [task.requesterId];
      if (task.helperId) agents.push(task.helperId);

      for (const agentId of agents) {
        this.sendTo(agentId, {
          type: 'task.timeout',
          id: uuidv4(),
          payload: { taskId: task.id, reason: `No activity for ${timeoutMinutes} minutes` },
        });
      }

      this.log.info(`Task timed out: ${task.id}`);
    }
  }
}
