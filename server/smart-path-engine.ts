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
  '6. 如果执行过程中生成了可复用的脚本或文档，在结果末尾用以下格式标注：',
  '   [REFERENCE:filename.ext]',
  '   ```',
  '   文件内容',
  '   ```',
  '   可以标注多个文件。这些文件会被保存到复用资源库，下次执行时可以直接复用。',
].join('\n');

function buildStepPrompt(userInput: string, previousResult?: string, referencesContext?: string): string {
  const parts: string[] = [];
  if (referencesContext) {
    parts.push('[可复用资源 — 优先使用这些资源，不要重新生成]');
    parts.push(referencesContext);
    parts.push('');
  }
  if (previousResult) {
    parts.push(`上个步骤执行结果：「${previousResult}」`);
  }
  parts.push(`本步骤输入：「${userInput}」`);
  return parts.join('\n');
}

/** 从步骤执行结果中提取 [REFERENCE:filename.ext] 标注的文件 */
function extractReferences(text: string): Array<{ fileName: string; content: string }> {
  const refs: Array<{ fileName: string; content: string }> = [];
  const regex = /\[REFERENCE:([^\]]+)\]\s*\n```\s*\n([\s\S]*?)```/g;
  let match;
  while ((match = regex.exec(text)) !== null) {
    refs.push({ fileName: match[1].trim(), content: match[2].trim() });
  }
  return refs;
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

    // 加载 references 上下文
    const referencesContext = this.buildReferencesContext(workspace, smartPath.id);

    try {
      const stepResults: string[] = [];

      for (let i = 0; i < steps.length; i++) {
        const step = steps[i];
        const prompt = buildStepPrompt(step.userInput, stepResults.length > 0 ? stepResults[stepResults.length - 1] : undefined, referencesContext);

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
          stepResults.push(stepResult);

          // 提取并保存 reference 文件
          const refs = extractReferences(stepResult);
          for (const ref of refs) {
            this.store.saveReference(workspace, pathId, ref.fileName, ref.content);
          }

          onStepResult(i, stepResult);
          onProgress?.({ stepIndex: i, totalSteps: steps.length, status: 'completed' });
        } finally {
          this.sessionManager.closeV2Session(sessionId);
          this.sessionManager.removeEphemeralSession(sessionId);
        }
      }

      // 只更新状态，不回写 executionResult 到 path 定义
      this.store.update(pathId, workspace, { status: 'completed' });

      // 生成执行报告
      const reportFileName = this.store.createReport(
        workspace, pathId, smartPath.name, steps, stepResults, run.id,
        'completed', run.startedAt, isoNow(),
      );
      const reportBasename = path.basename(reportFileName);

      this.store.updateRun(run.id, workspace, pathId, {
        status: 'completed',
        stepResults: JSON.stringify(stepResults),
        finishedAt: isoNow(),
        reportFileName: reportBasename,
      });

      // 更新 references/run.md
      await this.updateRunGuide(workspace, pathId, steps, stepResults);

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

  /** 构建 references 上下文字符串，注入到步骤 prompt */
  private buildReferencesContext(ws: string, pathId: string): string {
    const runGuide = this.store.getRunGuide(ws, pathId);
    const refs = this.store.listReferences(ws, pathId)
      .filter(r => r.fileName !== 'run.md');

    if (!runGuide && refs.length === 0) return '';

    const parts: string[] = [];
    if (runGuide) {
      parts.push('## 复用指南 (run.md)');
      parts.push(runGuide);
      parts.push('');
    }

    for (const ref of refs) {
      const content = this.store.getReference(ws, pathId, ref.fileName);
      if (content) {
        parts.push(`## ${ref.fileName}`);
        parts.push(content.length > 2000 ? content.slice(0, 2000) + '\n...(truncated)' : content);
        parts.push('');
      }
    }

    return parts.join('\n');
  }

  /** 执行完路径后，让 LLM 更新 references/run.md */
  private async updateRunGuide(
    ws: string,
    pathId: string,
    steps: SmartPathStep[],
    results: string[],
  ): Promise<void> {
    const existingGuide = this.store.getRunGuide(ws, pathId) || '';
    const existingRefs = this.store.listReferences(ws, pathId)
      .filter(r => r.fileName !== 'run.md')
      .map(r => r.fileName);

    const prompt = [
      '请根据本次执行结果，更新路径的复用指南 (run.md)。',
      '',
      '现有复用指南：',
      existingGuide || '(无)',
      '',
      '现有复用文件：',
      existingRefs.length > 0 ? existingRefs.join(', ') : '(无)',
      '',
      '本次执行步骤和结果摘要：',
      ...steps.map((s, i) => `步骤${i + 1}: ${s.userInput}\n结果摘要: ${(results[i] || '').slice(0, 300)}`),
      '',
      '请直接输出 run.md 的完整内容，格式如下：',
      '# 复用指南',
      '',
      '## 可复用资源',
      '- 文件名: 用途说明, 何时复用',
      '',
      '## 注意事项',
      '- 何时需要重新生成（不要复用旧版本）',
      '',
      '## 最佳实践',
      '- 执行建议',
      '',
      '要求：简洁实用，不要废话。',
    ].join('\n');

    const sessionId = `smartpath-ref-${pathId}-${Date.now()}`;
    try {
      this.sessionManager.createEphemeralSessionWithId(ws, sessionId);
      const result = await this.sessionManager.sendMessageForStep(
        sessionId, prompt, new AbortController(), () => {},
      );
      if (result?.trim()) {
        this.store.updateRunGuide(ws, pathId, result.trim());
      }
    } catch (err) {
      // LLM 更新失败时用 fallback
      this.log.warn(`Failed to update run.md via LLM, using fallback: ${err}`);
      this.store.updateRunGuide(ws, pathId, this.generateFallbackRunGuide(existingRefs));
    } finally {
      try {
        this.sessionManager.closeV2Session(sessionId);
        this.sessionManager.removeEphemeralSession(sessionId);
      } catch { /* cleanup failed, ignore */ }
    }
  }

  private generateFallbackRunGuide(refs: string[]): string {
    const lines = ['# 复用指南', '', '## 可复用资源'];
    if (refs.length === 0) {
      lines.push('(暂无可复用资源)');
    } else {
      for (const ref of refs) {
        lines.push(`- ${ref}: 上次执行生成，可检查后复用`);
      }
    }
    lines.push('', '## 注意事项');
    lines.push('- 如果需求有变化，应重新生成对应资源');
    return lines.join('\n');
  }
}
