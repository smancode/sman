/**
 * SmartPathEngine — 地球路径执行引擎
 * 逐步骤执行，前一步的执行结果作为下一步的输入上下文
 * 使用纯内存临时会话，不写 SQLite，不污染主会话列表
 */
import { createLogger, type Logger } from './utils/logger.js';
import type { SmartPathStore } from './smart-path-store.js';
import type { ClaudeSessionManager } from './claude-session.js';
import type { SmartPathStep } from './types.js';
import path from 'path';

function isoNow(): string {
  return new Date().toISOString();
}

const STEP_SYSTEM_PROMPT = [
  '[步骤执行模式 - 必须遵守]',
  '',
  '你正在执行一个自动化工作流的步骤。规则：',
  '1. 直接执行任务，给出简洁结果。不要询问用户，不要等待用户输入。',
  '2. 不要调用 dev-workflow 或任何需要多轮交互的流程。',
  '3. 能使用现有 skill/tool 直接完成就使用，不能的直接实现。',
  '4. 输出要简洁：执行了什么 + 结果。不要冗长解释。',
  '5. 最后用一行明确总结结果（以「执行结果：」开头）。',
].join('\n');

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

        const sessionId = `smartpath-ephemeral-${run.id}-step-${i}`;
        this.sessionManager.createEphemeralSessionWithId(workspace, sessionId);
        const abort = new AbortController();

        let stepFullContent = '';
        try {
          const stepReturned = await this.sessionManager.sendMessageForStep(
            sessionId,
            prompt,
            abort,
            (delta) => {
              stepFullContent += delta;
              onStepProgress(i, delta);
            },
            STEP_SYSTEM_PROMPT,
          );

          const stepResult = (stepReturned || stepFullContent).trim();
          previousResult = stepResult;

          // 保存每步执行结果
          steps[i] = { ...step, executionResult: stepResult };
          onStepResult(i, stepResult);
          onProgress?.({ stepIndex: i, totalSteps: steps.length, status: 'completed' });
        } finally {
          // 清理内存临时会话
          this.sessionManager.closeV2Session(sessionId);
          this.sessionManager.removeEphemeralSession(sessionId);
        }
      }

      // 保存所有步骤结果到 path
      this.store.update(pathId, workspace, {
        steps: JSON.stringify(steps),
        status: 'completed',
      });

      // 生成执行报告 MD
      const reportFileName = this.store.createReport(
        workspace, pathId, smartPath.name, steps, run.id,
        'completed', run.startedAt, isoNow(),
      );
      const reportBasename = path.basename(reportFileName);

      this.store.updateRun(run.id, workspace, pathId, {
        status: 'completed',
        stepResults: JSON.stringify(steps.map(s => s.executionResult)),
        finishedAt: isoNow(),
        reportFileName: reportBasename,
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
