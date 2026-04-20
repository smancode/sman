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

    const ctx: Record<number, unknown> = {};

    try {
      for (let i = 0; i < steps.length; i++) {
        const step = steps[i];
        const result = await this.executeStep(step, ctx, smartPath.workspace, `${run.id}-${i}`);
        ctx[i] = result;
        if (onProgress) {
          onProgress({ stepIndex: i, totalSteps: steps.length, status: 'stepComplete' });
        }
      }

      this.store.updateRun(run.id, {
        status: 'completed',
        stepResults: JSON.stringify(ctx),
        finishedAt: isoNow(),
      });
      this.store.updatePath(planId, { status: 'completed' });
      this.log.info(`Path ${planId} completed`);
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      this.store.updateRun(run.id, {
        status: 'failed',
        errorMessage,
        stepResults: JSON.stringify(ctx),
        finishedAt: isoNow(),
      });
      this.store.updatePath(planId, { status: 'failed' });
      this.log.error(`Path ${planId} failed: ${errorMessage}`);
      throw err;
    }
  }

  private async executeStep(
    step: SmartPathStep,
    ctx: Record<number, unknown>,
    workspace: string,
    runKey: string,
  ): Promise<unknown> {
    if (!step.actions || step.actions.length === 0) {
      throw new Error('Step has no actions');
    }

    if (step.mode === 'serial') {
      let result: unknown = null;
      for (let j = 0; j < step.actions.length; j++) {
        const action = step.actions[j];
        const input = result ?? ctx;
        result = await this.executeAction(action, input, workspace, `${runKey}-${j}`);
      }
      return result;
    } else {
      const results = await Promise.all(
        step.actions.map((action, j) =>
          this.executeAction(action, ctx, workspace, `${runKey}-${j}`),
        ),
      );
      return results;
    }
  }

  private async executeAction(
    action: SmartPathAction,
    input: unknown,
    workspace: string,
    sessionKey: string,
  ): Promise<unknown> {
    if (action.type === 'skill') {
      if (!action.skillId) throw new Error('skillId required for skill action');
      return this.executeSkill(action.skillId, input, workspace, sessionKey);
    } else if (action.type === 'python') {
      if (!action.code) throw new Error('code required for python action');
      return this.executePython(action.code, input);
    }
    throw new Error(`Unknown action type: ${action.type}`);
  }

  private async executeSkill(
    skillId: string,
    input: unknown,
    workspace: string,
    sessionKey: string,
  ): Promise<string> {
    const skillDir = this.skillsRegistry.getSkillDir(skillId);
    const skillMdPath = path.join(skillDir, 'SKILL.md');
    if (!fs.existsSync(skillMdPath)) {
      throw new Error(`SKILL.md not found for skill: ${skillId}`);
    }
    const skillContent = fs.readFileSync(skillMdPath, 'utf-8');
    const prompt = this.buildSkillPrompt(skillContent, input);
    const sessionId = `smartpath-${sessionKey}`;

    this.sessionManager.createSessionWithId(workspace, sessionId);
    const abortController = new AbortController();

    try {
      await this.sessionManager.sendMessageForCron(sessionId, prompt, abortController, () => {});

      const history = this.sessionManager.getHistory(sessionId);
      const lastAssistant = [...history].reverse().find(m => m.role === 'assistant');
      if (!lastAssistant) throw new Error('No assistant response');

      return lastAssistant.contentBlocks
        ?.filter(b => b.type === 'text')
        .map(b => b.text)
        .join('') || '';
    } finally {
      // Session will be cleaned up by idle cleanup mechanism
      this.sessionManager.abort(sessionId);
    }
  }

  private buildSkillPrompt(skillContent: string, input: unknown): string {
    return `${skillContent}\n\n## Context\nPrevious results: ${JSON.stringify(input, null, 2)}`;
  }

  private async executePython(code: string, input: unknown): Promise<unknown> {
    const ctxJson = JSON.stringify(input);
    const wrappedCode = [
      'import json, sys',
      `ctx = json.loads(${JSON.stringify(ctxJson)})`,
      code,
    ].join('\n');

    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'smartpath-py-'));
    const scriptPath = path.join(tmpDir, 'script.py');
    fs.writeFileSync(scriptPath, wrappedCode);

    try {
      const { stdout } = await execFileAsync('python3', [scriptPath], {
        timeout: PYTHON_TIMEOUT_MS,
        maxBuffer: MAX_BUFFER,
      });
      const trimmed = stdout.trim();
      try {
        return JSON.parse(trimmed);
      } catch {
        return trimmed;
      }
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : String(err);
      throw new Error(`Python execution failed: ${errorMsg}`);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  }
}
