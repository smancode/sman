/**
 * SmartPathEngine — 地球路径执行引擎
 */
import { createLogger, type Logger } from './utils/logger.js';
import type { SmartPathStore } from './smart-path-store.js';
import type { ClaudeSessionManager } from './claude-session.js';
import type { SmartPathStep } from './types.js';

function isoNow(): string {
  return new Date().toISOString();
}

export class SmartPathEngine {
  private log: Logger;

  constructor(
    private store: SmartPathStore,
    private sessionManager: ClaudeSessionManager,
  ) {
    this.log = createLogger('SmartPathEngine');
  }

  async run(pathId: string, workspace: string, onProgress?: (data: { stepIndex: number; totalSteps: number; status: string }) => void): Promise<void> {
    const smartPath = this.store.get(pathId, workspace);
    if (!smartPath) throw new Error(`Path not found: ${pathId}`);

    let steps: SmartPathStep[];
    try { steps = JSON.parse(smartPath.steps); } catch { throw new Error('Invalid steps JSON'); }
    if (!Array.isArray(steps) || steps.length === 0) throw new Error('Path has no steps');

    const run = this.store.createRun(pathId, workspace);
    this.store.update(pathId, workspace, { status: 'running' });

    try {
      const prompt = this.buildPrompt(steps, smartPath.name);
      const sessionId = `smartpath-run-${run.id}`;
      this.sessionManager.createSessionWithId(workspace, sessionId);
      const abort = new AbortController();

      await this.sessionManager.sendMessageForCron(sessionId, prompt, abort, () => {});

      const history = this.sessionManager.getHistory(sessionId);
      const last = [...history].reverse().find(m => m.role === 'assistant');
      const result = last?.contentBlocks?.filter(b => b.type === 'text').map(b => b.text).join('') || '';

      this.store.updateRun(run.id, workspace, pathId, { status: 'completed', stepResults: JSON.stringify({ result }), finishedAt: isoNow() });
      this.store.update(pathId, workspace, { status: 'completed' });
      this.log.info(`Path ${pathId} completed`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.store.updateRun(run.id, workspace, pathId, { status: 'failed', errorMessage: msg, stepResults: JSON.stringify({ error: msg }), finishedAt: isoNow() });
      this.store.update(pathId, workspace, { status: 'failed' });
      this.log.error(`Path ${pathId} failed: ${msg}`);
      throw err;
    }
  }

  private buildPrompt(steps: SmartPathStep[], name: string): string {
    const lines = steps.map((s, i) => {
      const parts = [`## 步骤 ${i + 1}`];
      if (s.userInput) parts.push(`**需求**: ${s.userInput}`);
      if (s.generatedContent) parts.push(`**实现方案**:\n${s.generatedContent}`);
      return parts.join('\n');
    });
    return [
      `# 执行智能路径: ${name}`,
      '',
      '请按照以下步骤依次执行任务，每个步骤都要考虑前面的结果。',
      '',
      ...lines,
      '',
      '请开始执行，并在完成每个步骤后报告进度。',
    ].join('\n');
  }
}
