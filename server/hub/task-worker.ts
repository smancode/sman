import type { HubWsClient } from './hub-ws-client.js';
import type { ClaudeSessionManager } from '../claude-session.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('TaskWorker');
const MAX_CONCURRENT = 2;

interface TaskWorkerDeps {
  hubWsClient: HubWsClient;
  sessionManager: ClaudeSessionManager;
  agentId: string;
  workspace: string;
}

interface ActiveTask {
  taskId: string;
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
    log.info(`TaskWorker started for agent ${this.deps.agentId}`);
  }

  stop(): void {
    this.running = false;
    for (const [taskId, active] of this.activeTasks) {
      active.abortController.abort();
      this.deps.hubWsClient.send({
        type: 'task.fail',
        taskId,
        agentId: this.deps.agentId,
        error: 'Worker shutdown',
      });
    }
    this.activeTasks.clear();
  }

  canAccept(): boolean {
    return this.running && this.activeTasks.size < MAX_CONCURRENT;
  }

  async executeTask(taskId: string, title: string, description: string, context: string): Promise<void> {
    if (!this.canAccept()) {
      log.warn(`Cannot accept task ${taskId}: at capacity`);
      return;
    }

    const agentId = this.deps.agentId;
    this.deps.hubWsClient.send({ type: 'task.claim', taskId, agentId, maxConcurrent: MAX_CONCURRENT });
    this.deps.hubWsClient.send({ type: 'task.start', taskId, agentId });

    const abortController = new AbortController();
    const sessionId = `hub-task-${taskId}`;
    this.activeTasks.set(taskId, { taskId, sessionId, abortController });

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
