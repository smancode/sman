import type { HubWsClient, HubWsInbound } from './hub-ws-client.js';
import type { ClaudeSessionManager } from '../claude-session.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('TaskWorker');
const MAX_CONCURRENT = 2;

interface TaskWorkerDeps {
  hubWsClient: HubWsClient;
  sessionManager: ClaudeSessionManager;
  getAgentId: (workspace: string) => string;
}

interface DispatchedAssignment {
  task: {
    id: string;
    room_id: string;
    title: string;
    description?: string;
    acceptance_criteria?: string;
    subtasks?: string;
  };
  assignment: {
    id: string;
    agent_id: string;
    workspace: string;
    subtask_ids: string;
    instructions: string | null;
  };
}

interface ActiveTask {
  taskId: string;
  assignmentId: string;
  sessionId: string;
  abortController: AbortController;
}

export class TaskWorker {
  private deps: TaskWorkerDeps;
  private activeTasks = new Map<string, ActiveTask>();
  private running = false;

  constructor(deps: TaskWorkerDeps) {
    this.deps = deps;
  }

  start(): void {
    this.running = true;
    log.info('TaskWorker started');
  }

  stop(): void {
    this.running = false;
    for (const [taskId, active] of this.activeTasks) {
      active.abortController.abort();
      this.deps.hubWsClient.send({
        type: 'task.fail',
        taskId,
        agentId: this.getAgentIdForTask(taskId),
        error: 'Worker shutdown',
      });
    }
    this.activeTasks.clear();
  }

  canAccept(): boolean {
    return this.running && this.activeTasks.size < MAX_CONCURRENT;
  }

  handleMessage(msg: HubWsInbound): void {
    if (msg.type === 'task.dispatched_to') {
      this.handleDispatchedTo(msg as unknown as DispatchedAssignment);
    }
  }

  private handleDispatchedTo(data: DispatchedAssignment): void {
    const { task, assignment } = data;
    const agentId = this.deps.getAgentId(assignment.workspace);

    if (agentId !== assignment.agent_id) return;
    if (!this.canAccept()) {
      log.warn(`Cannot accept dispatched task ${task.id}: at capacity`);
      return;
    }

    this.executeDispatchedTask(task, assignment, agentId).catch(err => {
      log.error(`Dispatched task ${task.id} error: ${(err as Error).message}`);
    });
  }

  private async executeDispatchedTask(
    task: DispatchedAssignment['task'],
    assignment: DispatchedAssignment['assignment'],
    agentId: string,
  ): Promise<void> {
    const taskId = task.id;
    const abortController = new AbortController();
    const sessionId = `hub-task-${taskId}-${assignment.id}`;
    this.activeTasks.set(taskId, {
      taskId,
      assignmentId: assignment.id,
      sessionId,
      abortController,
    });

    this.deps.hubWsClient.send({ type: 'task.start', taskId, agentId });

    const prompt = buildDispatchedPrompt(task, assignment);

    try {
      const result = await this.deps.sessionManager.sendMessageForStep(
        sessionId,
        prompt,
        abortController,
        (text: string) => {
          this.deps.hubWsClient.send({
            type: 'task.progress',
            taskId,
            agentId,
            progress: text.slice(-200),
          });
        },
      );

      this.deps.hubWsClient.send({
        type: 'task.complete',
        taskId,
        agentId,
        result: result?.slice(0, 10000) || '',
      });
    } catch (err) {
      const errMsg = (err as Error).message || 'Unknown error';
      log.error(`Task ${taskId} error: ${errMsg}`);
      this.deps.hubWsClient.send({
        type: 'task.fail',
        taskId,
        agentId,
        error: errMsg,
      });
    } finally {
      this.activeTasks.delete(taskId);
    }
  }

  // Legacy: direct claim-based execution
  async executeTask(taskId: string, title: string, description: string, context: string, agentId: string): Promise<void> {
    if (!this.canAccept()) {
      log.warn(`Cannot accept task ${taskId}: at capacity`);
      return;
    }

    const abortController = new AbortController();
    const sessionId = `hub-task-${taskId}`;
    this.activeTasks.set(taskId, { taskId, assignmentId: '', sessionId, abortController });

    this.deps.hubWsClient.send({ type: 'task.claim', taskId, agentId, maxConcurrent: MAX_CONCURRENT });
    this.deps.hubWsClient.send({ type: 'task.start', taskId, agentId });

    const prompt = buildTaskPrompt(title, description, context);

    try {
      const result = await this.deps.sessionManager.sendMessageForStep(
        sessionId,
        prompt,
        abortController,
        (text: string) => {
          this.deps.hubWsClient.send({
            type: 'task.progress',
            taskId,
            agentId,
            progress: text.slice(-200),
          });
        },
      );

      this.deps.hubWsClient.send({
        type: 'task.complete',
        taskId,
        agentId,
        result: result?.slice(0, 10000) || '',
      });
    } catch (err) {
      const errMsg = (err as Error).message || 'Unknown error';
      log.error(`Task ${taskId} error: ${errMsg}`);
      this.deps.hubWsClient.send({
        type: 'task.fail',
        taskId,
        agentId,
        error: errMsg,
      });
    } finally {
      this.activeTasks.delete(taskId);
    }
  }

  cancelTask(taskId: string): void {
    const active = this.activeTasks.get(taskId);
    if (active) {
      active.abortController.abort();
      this.activeTasks.delete(taskId);
    }
  }

  private getAgentIdForTask(taskId: string): string {
    const active = this.activeTasks.get(taskId);
    return active?.assignmentId || '';
  }
}

function buildDispatchedPrompt(task: DispatchedAssignment['task'], assignment: DispatchedAssignment['assignment']): string {
  let prompt = `# Task: ${task.title}\n`;
  if (task.description) prompt += `\n${task.description}\n`;
  if (task.acceptance_criteria) {
    prompt += `\n## Acceptance Criteria\n${task.acceptance_criteria}\n`;
  }

  // Parse subtask IDs to find which ones this assignment covers
  let assignedSubtaskIds: string[] = [];
  try {
    assignedSubtaskIds = JSON.parse(assignment.subtask_ids);
  } catch { /* ignore */ }

  if (task.subtasks) {
    try {
      const allSubtasks = JSON.parse(task.subtasks);
      if (Array.isArray(allSubtasks) && assignedSubtaskIds.length > 0) {
        const mySubtasks = allSubtasks.filter(
          (st: { id: string }) => assignedSubtaskIds.includes(st.id),
        );
        if (mySubtasks.length > 0) {
          prompt += '\n## Your Assigned Subtasks\n';
          for (const st of mySubtasks) {
            prompt += `- [${st.id}] ${st.name}${st.description ? ': ' + st.description : ''}\n`;
          }
        }
      }
    } catch { /* ignore */ }
  }

  if (assignment.instructions) {
    prompt += `\n## Instructions\n${assignment.instructions}\n`;
  }

  prompt += '\nExecute ONLY the subtasks assigned to you. Provide a clear summary of what was done.';
  return prompt;
}

function buildTaskPrompt(title: string, description: string, context: string): string {
  let prompt = `# Task: ${title}\n`;
  if (description) prompt += `\n${description}\n`;
  if (context) {
    try {
      const ctx = JSON.parse(context);
      if (ctx.instructions) prompt += `\n## Instructions\n${ctx.instructions}\n`;
    } catch { /* ignore */ }
  }
  prompt += '\nExecute this task. Provide a clear summary of what was done.';
  return prompt;
}
