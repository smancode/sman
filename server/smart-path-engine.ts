/**
 * SmartPathEngine — 地球路径执行引擎
 * 逐步骤执行，前一步的执行结果作为下一步的输入上下文
 */
import { createLogger, type Logger } from './utils/logger.js';
import type { SmartPathStore } from './smart-path-store.js';
import type { ClaudeSessionManager } from './claude-session.js';
import type { SmartPathStep } from './types.js';

function isoNow(): string {
  return new Date().toISOString();
}

function buildStepPrompt(userInput: string, previousResult?: string): string {
  if (previousResult) {
    return `上个步骤执行结果：「${previousResult}」\n本步骤输入：「${userInput}」`;
  }
  return `本步骤输入：「${userInput}」`;
}

export class SmartPathEngine {
  private log: Logger;

  constructor(
    private store: SmartPathStore,
    private sessionManager: ClaudeSessionManager,
  ) {
    this.log = createLogger('SmartPathEngine');
  }

  async run(
    pathId: string,
    workspace: string,
    onStepProgress: (stepIndex: number, delta: string) => void,
    onStepResult: (stepIndex: number, result: string) => void,
    onProgress?: (data: { stepIndex: number; totalSteps: number; status: string }) => void,
  ): Promise<void> {
    const smartPath = this.store.get(pathId, workspace);
    if (!smartPath) throw new Error(`Path not found: ${pathId}`);

    let steps: SmartPathStep[];
    try { steps = JSON.parse(smartPath.steps); } catch { throw new Error('Invalid steps JSON'); }
    if (!Array.isArray(steps) || steps.length === 0) throw new Error('Path has no steps');

    const run = this.store.createRun(pathId, workspace);
    this.store.update(pathId, workspace, { status: 'running' });

    try {
      let previousResult = '';

      for (let i = 0; i < steps.length; i++) {
        const step = steps[i];
        const prompt = buildStepPrompt(step.userInput, previousResult || undefined);

        const sessionId = `smartpath-run-${run.id}-step-${i}`;
        this.sessionManager.createSessionWithId(workspace, sessionId);
        const abort = new AbortController();

        let stepFullContent = '';
        const stepReturned = await this.sessionManager.sendMessageForStep(
          sessionId,
          prompt,
          abort,
          (delta) => {
            stepFullContent += delta;
            onStepProgress(i, delta);
          },
        );

        const stepResult = (stepReturned || stepFullContent).trim();
        previousResult = stepResult;

        // 保存每步执行结果
        steps[i] = { ...step, executionResult: stepResult };
        onStepResult(i, stepResult);
        onProgress?.({ stepIndex: i, totalSteps: steps.length, status: 'completed' });
      }

      // 保存所有步骤结果到 path
      this.store.update(pathId, workspace, {
        steps: JSON.stringify(steps),
        status: 'completed',
      });
      this.store.updateRun(run.id, workspace, pathId, {
        status: 'completed',
        stepResults: JSON.stringify(steps.map(s => s.executionResult)),
        finishedAt: isoNow(),
      });
      this.log.info(`Path ${pathId} completed`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.store.updateRun(run.id, workspace, pathId, {
        status: 'failed',
        errorMessage: msg,
        stepResults: JSON.stringify({ error: msg }),
        finishedAt: isoNow(),
      });
      this.store.update(pathId, workspace, { status: 'failed' });
      this.log.error(`Path ${pathId} failed: ${msg}`);
      throw err;
    }
  }
}
