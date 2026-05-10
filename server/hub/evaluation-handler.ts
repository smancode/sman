import type { HubWsClient, HubWsInbound } from './hub-ws-client.js';
import type { ClaudeSessionManager } from '../claude-session.js';
import type { SessionStore } from '../session-store.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('EvaluationHandler');

interface EvaluationHandlerDeps {
  hubWsClient: HubWsClient;
  sessionManager: ClaudeSessionManager;
  sessionStore: SessionStore;
  getAgentId: (workspace: string) => string;
  getRoomId: () => string | undefined;
}

interface EvaluationTask {
  taskId: string;
  agentId: string;
  workspace: string;
  abortController: AbortController;
}

export class EvaluationHandler {
  private deps: EvaluationHandlerDeps;
  private pending = new Map<string, EvaluationTask>();

  constructor(deps: EvaluationHandlerDeps) {
    this.deps = deps;
  }

  start(): void {
    log.info('EvaluationHandler started');
  }

  handleMessage(msg: HubWsInbound): void {
    if (msg.type === 'task.created' && msg.task) {
      this.handleTaskCreated(msg.task as TaskCreatedMsg);
    }
  }

  private async handleTaskCreated(task: TaskCreatedMsg): Promise<void> {
    const roomId = this.deps.getRoomId();
    if (!roomId || task.room_id !== roomId) return;

    if (task.status !== 'evaluating') return;

    const workspaces = this.deps.sessionStore.getActiveWorkspaces();
    if (workspaces.length === 0) {
      log.info('No active workspaces, skipping evaluation');
      return;
    }

    for (const workspace of workspaces) {
      const agentId = this.deps.getAgentId(workspace);
      this.evaluateForWorkspace(task, workspace, agentId).catch(err => {
        log.error(`Evaluation failed for ${workspace}: ${(err as Error).message}`);
      });
    }
  }

  private async evaluateForWorkspace(task: TaskCreatedMsg, workspace: string, agentId: string): Promise<void> {
    const taskId = task.id;
    const key = `${taskId}:${agentId}`;

    if (this.pending.has(key)) return;

    const abortController = new AbortController();
    this.pending.set(key, { taskId, agentId, workspace, abortController });

    const prompt = buildEvaluationPrompt(task);

    const sessionId = `hub-eval-${taskId}-${agentId}`;

    try {
      const result = await this.deps.sessionManager.sendMessageForStep(
        sessionId,
        prompt,
        abortController,
        () => {},
      );

      const parsed = parseEvaluationResponse(result);
      if (!parsed) {
        log.warn(`Could not parse evaluation response for task ${taskId}`);
        return;
      }

      this.deps.hubWsClient.send({
        type: 'evaluation.submit',
        taskId,
        agentId,
        workspace,
        claimedSubtasks: parsed.claimedSubtasks,
        approach: parsed.approach,
        complexity: parsed.complexity,
        dependencies: parsed.dependencies,
        rawResponse: result,
      });
    } catch (err) {
      log.error(`Evaluation error for ${workspace}: ${(err as Error).message}`);
    } finally {
      this.pending.delete(key);
    }
  }

  stop(): void {
    for (const [, evalTask] of this.pending) {
      evalTask.abortController.abort();
    }
    this.pending.clear();
  }
}

interface TaskCreatedMsg {
  id: string;
  room_id: string;
  title: string;
  description?: string;
  acceptance_criteria?: string;
  subtasks?: string;
  status: string;
}

interface ParsedEvaluation {
  claimedSubtasks: string[];
  approach: string;
  complexity: string;
  dependencies: string[];
}

function buildEvaluationPrompt(task: TaskCreatedMsg): string {
  let prompt = `Analyze the following task and determine what you can do based on the current codebase.

## Task: ${task.title}
`;
  if (task.description) {
    prompt += `\n${task.description}\n`;
  }
  if (task.acceptance_criteria) {
    prompt += `\n## Acceptance Criteria\n${task.acceptance_criteria}\n`;
  }
  if (task.subtasks) {
    try {
      const subtasks = JSON.parse(task.subtasks);
      if (Array.isArray(subtasks) && subtasks.length > 0) {
        prompt += '\n## Subtasks\n';
        for (const st of subtasks) {
          prompt += `- [${st.id}] ${st.name}${st.description ? ': ' + st.description : ''}\n`;
        }
      }
    } catch { /* ignore */ }
  }

  prompt += `
Based on your understanding of the current codebase, answer:
1. Which subtasks can you handle? (list IDs)
2. What is your approach for each?
3. Overall complexity: simple, medium, or complex
4. Any dependencies on other systems?

Reply in JSON format only:
{"claimedSubtasks":["id1"],"approach":"brief approach description","complexity":"medium","dependencies":["dep1"]}

If you cannot handle any subtask, return:
{"claimedSubtasks":[],"approach":"","complexity":"medium","dependencies":[]}
`;
  return prompt;
}

function parseEvaluationResponse(raw: string): ParsedEvaluation | null {
  try {
    // Try to extract JSON from the response (may be wrapped in markdown code block)
    const jsonMatch = raw.match(/\{[\s\S]*\}/);
    if (!jsonMatch) return null;

    const parsed = JSON.parse(jsonMatch[0]);
    return {
      claimedSubtasks: Array.isArray(parsed.claimedSubtasks) ? parsed.claimedSubtasks : [],
      approach: typeof parsed.approach === 'string' ? parsed.approach : '',
      complexity: ['simple', 'medium', 'complex'].includes(parsed.complexity) ? parsed.complexity : 'medium',
      dependencies: Array.isArray(parsed.dependencies) ? parsed.dependencies : [],
    };
  } catch {
    return null;
  }
}
