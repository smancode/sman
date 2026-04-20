import { execFile } from 'child_process';
import { promisify } from 'util';
import path from 'path';
import fs from 'fs';
import os from 'os';
import { createLogger, type Logger } from './utils/logger.js';
import type { SmartPathStore } from './smart-path-store.js';
import type { SkillsRegistry } from './skills-registry.js';
import type { ClaudeSessionManager } from './claude-session.js';
import type { SmartPathStep, SmartPathAction } from './types.js';

const execFileAsync = promisify(execFile);
const MAX_BUFFER = 10 * 1024 * 1024;
const PYTHON_TIMEOUT_MS = 60_000;

function isoNow(): string {
  return new Date().toISOString();
}

export class SmartPathEngine {
  private log: Logger;

  constructor(
    private store: SmartPathStore,
    private skillsRegistry: SkillsRegistry,
    private sessionManager: ClaudeSessionManager,
  ) {
    this.log = createLogger('SmartPathEngine');
  }

  async runPath(pathOrId: string, onProgress?: (data: {
    stepIndex: number;
    totalSteps: number;
    status: string;
  }) => void): Promise<void> {
    // 判断是文件路径还是 ID
    const isFilePath = pathOrId.endsWith('.md');
    const smartPath = isFilePath
      ? this.store.loadPlan(pathOrId)
      : this.store.getPath(pathOrId);

    if (!smartPath) throw new Error(`Path not found: ${pathOrId}`);
    if (!smartPath.steps || smartPath.steps.length === 0) {
      throw new Error('Path has no steps');
    }

    let steps: SmartPathStep[];
    try {
      steps = JSON.parse(smartPath.steps);
    } catch {
      throw new Error('Invalid steps JSON');
    }
    if (!Array.isArray(steps) || steps.length === 0) {
      throw new Error('Path has no steps');
    }

    const planId = smartPath.id;
    const run = this.store.createRun(planId);
    this.store.updatePath(planId, { status: 'running' });

    try {
      // Build execution prompt from all steps
      const prompt = this.buildExecutionPrompt(steps, smartPath.name);

      // Execute using session manager
      const sessionId = `smartpath-run-${run.id}`;
      this.sessionManager.createSessionWithId(smartPath.workspace, sessionId);
      const abortController = new AbortController();

      await this.sessionManager.sendMessageForCron(sessionId, prompt, abortController, () => {});

      // Get result from session history
      const history = this.sessionManager.getHistory(sessionId);
      const lastAssistant = [...history].reverse().find(m => m.role === 'assistant');
      const result = lastAssistant?.contentBlocks
        ?.filter(b => b.type === 'text')
        .map(b => b.text)
        .join('') || '';

      this.store.updateRun(run.id, {
        status: 'completed',
        stepResults: JSON.stringify({ result }),
        finishedAt: isoNow(),
      });
      this.store.updatePath(planId, { status: 'completed' });
      this.log.info(`Path ${planId} completed`);
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      this.store.updateRun(run.id, {
        status: 'failed',
        errorMessage,
        stepResults: JSON.stringify({ error: errorMessage }),
        finishedAt: isoNow(),
      });
      this.store.updatePath(planId, { status: 'failed' });
      this.log.error(`Path ${planId} failed: ${errorMessage}`);
      throw err;
    }
  }

  private buildExecutionPrompt(steps: SmartPathStep[], planName: string): string {
    const stepDescriptions = steps.flatMap((step, stepIndex) =>
      step.actions.map((action, actionIndex) => {
        const parts = [
          `## 步骤 ${stepIndex + 1}.${actionIndex + 1}`,
          action.userInput ? `**需求**: ${action.userInput}` : '',
        ];
        if (action.generatedContent) {
          parts.push(`**实现方案**:\n${action.generatedContent}`);
        }
        return parts.filter(Boolean).join('\n');
      })
    );

    return [
      `# 执行智能路径: ${planName}`,
      '',
      '请按照以下步骤依次执行任务，每个步骤都要考虑前面的结果。',
      '',
      ...stepDescriptions,
      '',
      '请开始执行，并在完成每个步骤后报告进度。',
    ].join('\n');
  }
}
