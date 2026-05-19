/**
 * SmartPathEngine — 地球路径执行引擎
 *
 * 执行流程：
 * 1. 主编分析：在一个 session 里理解整个 path 的目标、每步的作用，产出执行方案
 * 2. 逐步执行：每步用独立 ephemeral session（子 agent），包含全局目标 + 修正指令 + 前序关键信息
 * 3. 经验沉淀：把主编分析 + 执行结果 + 修正记录写入 run.md
 */
import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from './utils/logger.js';
import type { SmartPathStore } from './smart-path-store.js';
import type { ClaudeSessionManager } from './claude-session.js';
import type { SmartPathStep, StepPlan, PathBlueprint } from './types.js';

function readSkillContent(workspace: string, skillId: string): string | null {
  const skillMdPath = path.join(workspace, '.claude', 'skills', skillId, 'SKILL.md');
  try {
    if (fs.existsSync(skillMdPath)) {
      return fs.readFileSync(skillMdPath, 'utf-8');
    }
  } catch { /* ignore */ }
  return null;
}

function buildSkillsContext(workspace: string, skills: string[] | undefined): string {
  if (!skills || skills.length === 0) return '';
  const parts: string[] = [];
  for (const skillId of skills) {
    const content = readSkillContent(workspace, skillId);
    if (content) {
      parts.push(`### Skill: ${skillId}\n${content}`);
    }
  }
  return parts.length > 0
    ? `[可使用的 Skills — 严格按以下 skill 的指令执行]\n${parts.join('\n\n')}`
    : '';
}

export interface StepExecutionResult {
  result: string;
  deliveryCheckPassed?: boolean;
  deliveryCheckReason?: string;
  retried?: boolean;
}

function isoNow(): string {
  return new Date().toISOString();
}

// ── 脚本文件扩展名白名单 ──
const SCRIPT_EXTENSIONS = new Set([
  '.py', '.sh', '.bash', '.zsh', '.js', '.ts', '.mjs', '.cjs',
  '.bat', '.cmd', '.ps1', '.sql', '.r', '.rb', '.go', '.java',
  '.pl', '.lua', '.php', '.rs', '.dart', '.kt', '.scala', '.clj',
]);

function isScriptFile(fileName: string): boolean {
  const ext = path.extname(fileName).toLowerCase();
  return SCRIPT_EXTENSIONS.has(ext);
}

const ORCHESTRATOR_SYSTEM_PROMPT = `你是一个自动化工作流的"主编"。
1. 理解整个工作流的目标
2. 分析每个步骤在整个流程中的作用
3. 对模糊的步骤描述进行修正，变成具体可执行的指令
4. 确定每个步骤应产出什么关键信息，以及需要从前面步骤获得什么

输出 JSON（用 \`\`\`json \`\`\` 包裹）：
{"goal":"全局目标","stepPlans":[{"revisedInput":"修正后的指令","roleDescription":"步骤作用","expectedOutputs":"应产出的关键信息","dependenciesOnPrior":"需要前序步骤的什么"}],"modifications":[{"step":0,"original":"原始","revised":"修正后","reason":"原因"}]}

规则：已清晰的步骤 revisedInput 可不变；第一步 dependenciesOnPrior 填"无"；modifications 只记录实际修正的。`;

const STEP_SYSTEM_PROMPT = '';

function buildTmpRules(workspace: string, pathId: string): string {
  const basePath = `.sman/paths/${pathId}`;
  return [
    '',
    '临时文件规则：',
    `1. 每个步骤的临时输出必须放在 ${basePath}/tmp/ 目录（相对于工作区根目录）`,
    '2. tmp/ 里的文件不在步骤间复用，每次运行开始时自动清空',
    `3. 要跨步骤复用的文件必须保存到 ${basePath}/references/ 目录，用 [REFERENCE:filename] 标记`,
    `4. 脚本、配置文件等可复用资源必须放到 ${basePath}/references/，禁止放 tmp/`,
  ].join('\n');
}

const SUMMARY_SYSTEM_PROMPT = `从步骤结果中提炼关键信息给后续步骤。只保留关键信息，去掉冗余，不超过200字。直接输出提炼结果。`;

// ── 辅助函数 ──

function buildOrchestratorPrompt(
  name: string, description: string, steps: SmartPathStep[],
  referencesContext: string, args?: string,
): string {
  const parts: string[] = [];
  parts.push(`# 工作流: ${name}`);
  if (description) parts.push(`描述: ${description}`);
  if (args) parts.push(`用户传入参数: ${args}`);
  parts.push('');

  if (referencesContext) {
    parts.push('## 可复用资源（已有资源优先复用，不要重新生成）');
    parts.push(referencesContext);
    parts.push('');
  }

  parts.push('## 步骤列表');
  steps.forEach((s, i) => {
    parts.push(`步骤 ${i + 1}: ${s.name || '(未命名)'}`);
    parts.push(`  用户输入: ${s.userInput}`);
    if (s.deliveryCheck) parts.push(`  交付检查: ${s.deliveryCheck}`);
  });

  parts.push('');
  parts.push('请分析以上工作流，输出 JSON 执行方案。');
  return parts.join('\n');
}

function buildStepPrompt(
  stepPlan: StepPlan, globalGoal: string, stepIndex: number, totalSteps: number,
  priorKeyOutputs: string[], referencesContext: string, args?: string, deliveryCheck?: string,
  workspace?: string, pathId?: string, skills?: string[], guideContent?: string,
): string {
  const parts: string[] = [];

  parts.push(`[全局目标] ${globalGoal}`);
  parts.push(`[当前步骤] 第 ${stepIndex + 1}/${totalSteps} 步`);
  parts.push(`[本步骤作用] ${stepPlan.roleDescription}`);
  parts.push('');

  if (priorKeyOutputs.length > 0) {
    parts.push('[前序步骤的关键信息]');
    parts.push(priorKeyOutputs.join('\n'));
    parts.push('');
  }

  if (referencesContext) {
    parts.push('[可复用资源 — 优先使用这些资源，不要重新生成]');
    parts.push(referencesContext);
    parts.push('');
  }

  if (guideContent) {
    parts.push('[步骤操作指南 — 必须严格按此指南执行]');
    parts.push(guideContent);
    parts.push('');
  }

  parts.push('[执行指令]');
  parts.push(stepPlan.revisedInput);

  parts.push('');
  parts.push('[规则]');
  parts.push('1. 直接执行，给出简洁结果。不要询问用户。');
  parts.push('2. 专注于本步骤目标，不要越界。');
  parts.push('3. 能用 tool 完成就用，不能的直接实现。');
  parts.push('4. 最后用「执行结果：」开头总结。');
  parts.push('5. 可复用文件用 [REFERENCE:filename.ext] 包裹内容标注。');
  if (skills && skills.length > 0) {
    const skillsCtx = buildSkillsContext(workspace || '', skills);
    if (skillsCtx) {
      parts.push('');
      parts.push(skillsCtx);
    }
  } else {
    parts.push('6. 不使用 workspace/.claude/skills 中的 skill。');
  }
  parts.push('7. [REFERENCE:filename.ext] 只保存脚本文件（.py, .sh, .js, .ts, .bat, .sql, .r, .rb, .go, .java, .ps1 等），禁止保存 .json, .csv, .txt, .xlsx, .xml, .yaml, .yml 等数据文件。脚本中不能耦合数据，数据应放在 tmp/ 中。');

  if (deliveryCheck) {
    parts.push('');
    parts.push('[交付检查 — 你的执行结果必须满足以下要求]');
    parts.push(deliveryCheck);
    parts.push('执行完成后请自行对照检查，确保交付物符合以上要求。');
  }

  if (workspace && pathId) {
    parts.push(buildTmpRules(workspace, pathId));
  }

  if (stepIndex === 0 && args) {
    parts.push(`\n用户参数为:{${args}}，请根据任务需要使用。`);
  }

  return parts.join('\n');
}

function extractReferences(text: string): Array<{ fileName: string; content: string }> {
  const refs: Array<{ fileName: string; content: string }> = [];
  const regex = /\[REFERENCE:([^\]]+)\]\s*\n```\s*\n([\s\S]*?)```/g;
  let match;
  while ((match = regex.exec(text)) !== null) {
    const fileName = match[1].trim();
    if (isScriptFile(fileName)) {
      refs.push({ fileName, content: match[2].trim() });
    }
  }
  return refs;
}

function buildDefaultBlueprint(steps: SmartPathStep[]): PathBlueprint {
  return {
    goal: '执行用户定义的工作流',
    stepPlans: steps.map(s => ({
      revisedInput: s.userInput,
      roleDescription: '执行用户指定的步骤',
      expectedOutputs: '步骤执行结果',
      dependenciesOnPrior: '前序步骤的结果',
    })),
    modifications: [],
  };
}

function parseBlueprintFromLLM(raw: string, steps: SmartPathStep[]): PathBlueprint {
  const jsonMatch = raw.match(/```json\s*([\s\S]*?)```/);
  if (!jsonMatch) return buildDefaultBlueprint(steps);

  try {
    const parsed = JSON.parse(jsonMatch[1]);
    if (!parsed.stepPlans || !Array.isArray(parsed.stepPlans)) return buildDefaultBlueprint(steps);
    return {
      goal: parsed.goal || '执行用户定义的工作流',
      stepPlans: parsed.stepPlans.slice(0, steps.length),
      modifications: Array.isArray(parsed.modifications) ? parsed.modifications : [],
    };
  } catch {
    return buildDefaultBlueprint(steps);
  }
}

// ── Engine ──

interface ActiveRun {
  abortController: AbortController;
  sessionIds: string[];
}

export class SmartPathEngine {
  private log: Logger;
  private activeRuns = new Map<string, ActiveRun>();

  constructor(
    private store: SmartPathStore,
    private sessionManager: ClaudeSessionManager,
  ) {
    this.log = createLogger('SmartPathEngine');
  }

  abort(pathId: string): void {
    const run = this.activeRuns.get(pathId);
    if (!run) return;
    run.abortController.abort();
    for (const sid of run.sessionIds) {
      try { this.sessionManager.abort(sid, '用户中止路径执行'); } catch { /* ignore */ }
    }
    this.activeRuns.delete(pathId);
    this.log.info(`Path ${pathId} aborted by user`);
  }

  isRunning(pathId: string): boolean {
    return this.activeRuns.has(pathId);
  }

  async run(
    pathId: string,
    workspace: string,
    onStepProgress: (stepIndex: number, delta: string) => void,
    onStepResult: (stepIndex: number, result: string) => void,
    onProgress?: (data: { stepIndex: number; totalSteps: number; status: string }) => void,
    args?: string,
    useRefs?: boolean,
  ): Promise<void> {
    const result = await this.runWithResults(
      pathId, workspace, args,
      (stepIndex, delta) => {
        onStepProgress(stepIndex, delta);
        if (stepIndex === -1) onProgress?.({ stepIndex: -1, totalSteps: 0, status: 'analyzing' });
      },
      useRefs,
    );
    // 路径页面需要逐步回调
    let steps: SmartPathStep[];
    try { steps = JSON.parse(this.store.get(pathId, workspace)!.steps); } catch { steps = []; }
    steps.forEach((_, i) => {
      if (result.stepResults[i]) {
        onStepResult(i, result.stepResults[i]);
        onProgress?.({ stepIndex: i, totalSteps: steps.length, status: 'completed' });
      }
    });
  }

  /** 协调阶段：主编分析 → 返回蓝图 + 创建 run + 清空 tmp */
  async orchestrateOnly(
    pathId: string,
    workspace: string,
    args: string | undefined,
    onStepProgress: (stepIndex: number, delta: string) => void,
    useRefs?: boolean,
  ): Promise<{ blueprint: PathBlueprint; runId: string }> {
    const smartPath = this.store.get(pathId, workspace);
    if (!smartPath) throw new Error(`Path not found: ${pathId}`);

    let steps: SmartPathStep[];
    try { steps = JSON.parse(smartPath.steps); } catch { throw new Error('Invalid steps JSON'); }
    if (!Array.isArray(steps) || steps.length === 0) throw new Error('Path has no steps');

    const run = this.store.createRun(pathId, workspace);
    this.store.update(pathId, workspace, { status: 'running' });
    this.store.clearTmpDir(workspace, pathId);

    const abortController = new AbortController();
    const sessionIds: string[] = [];
    this.activeRuns.set(pathId, { abortController, sessionIds });

    const referencesContext = useRefs ? this.buildReferencesContext(workspace, smartPath.id) : '';

    let blueprint: PathBlueprint;
    try {
      blueprint = await this.orchestrate(smartPath, steps, referencesContext, workspace, args, abortController, sessionIds, onStepProgress);
    } catch (err) {
      this.log.warn(`Orchestration failed, using default blueprint: ${err}`);
      blueprint = buildDefaultBlueprint(steps);
    }

    return { blueprint, runId: run.id };
  }

  /** 单步执行：接收蓝图、步骤索引、前序结果，返回步骤结果 */
  async runSingleStep(
    pathId: string,
    workspace: string,
    runId: string,
    blueprint: PathBlueprint,
    stepIndex: number,
    totalSteps: number,
    priorResults: string[],
    args: string | undefined,
    onStepProgress: (stepIndex: number, delta: string) => void,
    deliveryCheck?: string,
    useRefs?: boolean,
    skills?: string[],
  ): Promise<StepExecutionResult> {
    const smartPath = this.store.get(pathId, workspace);
    if (!smartPath) throw new Error(`Path not found: ${pathId}`);

    const plan = blueprint.stepPlans[stepIndex] || blueprint.stepPlans[0];
    const referencesContext = useRefs ? this.buildReferencesContext(workspace, smartPath.id) : '';
    const guideContent = this.store.getGuide(workspace, pathId, stepIndex) || undefined;

    const prompt = buildStepPrompt(
      plan, blueprint.goal, stepIndex, totalSteps,
      priorResults, referencesContext,
      stepIndex === 0 ? args : undefined,
      deliveryCheck,
      workspace, pathId, skills, guideContent,
    );

    const active = this.activeRuns.get(pathId);
    const abortController = active?.abortController || new AbortController();
    const sessionIds = active?.sessionIds || [];

    const sessionId = `smartpath-ephemeral-${runId}-step-${stepIndex}-${Date.now()}`;
    this.sessionManager.createEphemeralSessionWithId(workspace, sessionId);
    sessionIds.push(sessionId);

    let stepFullContent = '';
    try {
      const stepReturned = await this.sessionManager.sendMessageForStep(
        sessionId, prompt, abortController,
        (delta) => {
          stepFullContent += delta;
          onStepProgress(stepIndex, delta);
        },
        STEP_SYSTEM_PROMPT,
      );

      const stepResult = (stepReturned || stepFullContent).trim();

      const refs = extractReferences(stepResult);
      for (const ref of refs) {
        this.store.saveReference(workspace, pathId, ref.fileName, ref.content);
      }

      // 交付检查
      if (deliveryCheck) {
        const checkResult = await this.runDeliveryCheck(
          stepResult, deliveryCheck, workspace, pathId, runId, stepIndex,
          abortController, onStepProgress,
        );

        if (!checkResult.passed) {
          // 自动重试一次
          onStepProgress(stepIndex, '\n\n[交付检查未通过，自动重试...]\n');
          const retrySessionId = `smartpath-ephemeral-${runId}-step-${stepIndex}-retry-${Date.now()}`;
          this.sessionManager.createEphemeralSessionWithId(workspace, retrySessionId);

          let retryContent = '';
          try {
            const retryPrompt = `${prompt}\n\n[重要提示：上次执行未通过交付检查]\n检查标准：${deliveryCheck}\n未通过原因：${checkResult.reason}\n请根据以上反馈重新执行，确保满足交付检查要求。`;
            const retryReturned = await this.sessionManager.sendMessageForStep(
              retrySessionId, retryPrompt, abortController,
              (delta) => {
                retryContent += delta;
                onStepProgress(stepIndex, delta);
              },
              STEP_SYSTEM_PROMPT,
            );
            const retryResult = (retryReturned || retryContent).trim();

            // 重试后再检查一次
            const retryCheckResult = await this.runDeliveryCheck(
              retryResult, deliveryCheck, workspace, pathId, runId, stepIndex,
              abortController, onStepProgress,
            );

            const retryRefs = extractReferences(retryResult);
            for (const ref of retryRefs) {
              this.store.saveReference(workspace, pathId, ref.fileName, ref.content);
            }

            return {
              result: retryResult,
              deliveryCheckPassed: retryCheckResult.passed,
              deliveryCheckReason: retryCheckResult.passed ? undefined : retryCheckResult.reason,
              retried: true,
            };
          } finally {
            this.sessionManager.closeV2Session(retrySessionId);
            this.sessionManager.removeEphemeralSession(retrySessionId);
          }
        }
      }

      return {
        result: stepResult,
        deliveryCheckPassed: deliveryCheck ? true : undefined,
      };
    } finally {
      this.sessionManager.closeV2Session(sessionId);
      this.sessionManager.removeEphemeralSession(sessionId);
    }
  }

  /** 收尾：生成报告 + 更新 run.md + 更新状态 */
  async finalize(
    pathId: string,
    workspace: string,
    runId: string,
    blueprint: PathBlueprint,
    stepResults: string[],
  ): Promise<void> {
    const smartPath = this.store.get(pathId, workspace);
    if (!smartPath) throw new Error(`Path not found: ${pathId}`);

    let steps: SmartPathStep[];
    try { steps = JSON.parse(smartPath.steps); } catch { throw new Error('Invalid steps JSON'); }

    this.store.update(pathId, workspace, { status: 'completed' });
    const reportFileName = this.store.createReport(
      workspace, pathId, smartPath.name, steps, stepResults, runId,
      'completed', isoNow(), isoNow(),
    );
    this.store.updateRun(runId, workspace, pathId, {
      status: 'completed',
      stepResults: JSON.stringify(stepResults),
      finishedAt: isoNow(),
      reportFileName: path.basename(reportFileName),
    });

    await this.updateRunGuide(workspace, pathId, steps, stepResults, blueprint);

    this.activeRuns.delete(pathId);
  }

  /** 指南对话：初始确认或后续多轮调整 */
  async guideChat(
    workspace: string,
    stepIndex: number,
    stepResult: string,
    sessionId: string,
    userMessage: string | undefined,
    pathName: string,
    stepInput: string,
    existingGuide: string | null,
    onDelta: (delta: string) => void,
  ): Promise<string> {
    let prompt: string;
    if (!userMessage) {
      // 初始消息：确认结果并生成指南
      const guideSection = existingGuide
        ? `\n已有指南:\n${existingGuide}\n\n请在已有指南基础上优化。`
        : '';
      prompt = [
        `# 步骤操作指南生成`,
        ``,
        `工作流步骤: ${stepInput}`,
        guideSection ? `步骤名称: ${pathName}` : '',
        ``,
        `步骤执行结果:`,
        stepResult,
        ``,
        `请确认以上步骤执行结果是否正确，然后生成一份详细的操作指南。`,
        guideSection,
        ``,
        `指南要求：`,
        `1. 包含具体的操作步骤和参数`,
        `2. 包含注意事项和常见问题`,
        `3. 格式为 Markdown`,
        `4. 简洁实用，方便后续自动执行参考`,
        `5. 输出规则：`,
        `   - 不要输出大段代码。如果需要写脚本，用 bash 或 write 工具直接执行/保存，给用户看的结果里只写简短的摘要和文件路径`,
        `   - 代码块最多展示 10 行，超过的必须折叠或只展示关键片段`,
        `   - 面向用户输出以文字说明为主，代码作为辅助参考`,
        ``,
        `直接输出操作指南内容，不需要确认。`,
      ].filter(Boolean).join('\n');
    } else {
      // 多轮对话：用户调整
      prompt = userMessage;
    }

    let fullContent = '';
    const stepReturned = await this.sessionManager.sendMessageForStep(
      sessionId, prompt, new AbortController(),
      (delta) => {
        fullContent += delta;
        onDelta(delta);
      },
      STEP_SYSTEM_PROMPT,
    );

    return (stepReturned || fullContent).trim();
  }

  /** 保存指南到 references/guide{n}.md */
  saveGuide(
    pathId: string,
    workspace: string,
    stepIndex: number,
    guideContent: string,
  ): string {
    return this.store.saveGuideFile(workspace, pathId, stepIndex, guideContent);
  }

  /** 核心执行：主编分析 + 每步独立 session（task），返回结果 */
  async runWithResults(
    pathId: string,
    workspace: string,
    args: string | undefined,
    onStepProgress: (stepIndex: number, delta: string) => void,
    useRefs?: boolean,
  ): Promise<{ stepResults: string[]; blueprint: PathBlueprint }> {
    const smartPath = this.store.get(pathId, workspace);
    if (!smartPath) throw new Error(`Path not found: ${pathId}`);

    let steps: SmartPathStep[];
    try { steps = JSON.parse(smartPath.steps); } catch { throw new Error('Invalid steps JSON'); }
    if (!Array.isArray(steps) || steps.length === 0) throw new Error('Path has no steps');

    const run = this.store.createRun(pathId, workspace);
    this.store.update(pathId, workspace, { status: 'running' });

    const abortController = new AbortController();
    const sessionIds: string[] = [];
    this.activeRuns.set(pathId, { abortController, sessionIds });

    const referencesContext = useRefs ? this.buildReferencesContext(workspace, smartPath.id) : '';

    try {
      // 主编分析
      let blueprint: PathBlueprint;
      try {
        blueprint = await this.orchestrate(smartPath, steps, referencesContext, workspace, args, abortController, sessionIds, onStepProgress);
      } catch (err) {
        this.log.warn(`Orchestration failed, using default blueprint: ${err}`);
        blueprint = buildDefaultBlueprint(steps);
      }

      // 逐步执行（每步独立 session）
      const stepResults: string[] = [];
      const keyOutputs: string[] = [];

      for (let i = 0; i < steps.length; i++) {
        if (abortController.signal.aborted) throw new Error('用户中止执行');

        const plan = blueprint.stepPlans[i] || blueprint.stepPlans[0];
        const stepDeliveryCheck = steps[i]?.deliveryCheck;
        const stepSkills = steps[i]?.skills;
        const guideContent = this.store.getGuide(workspace, pathId, i) || undefined;
        const prompt = buildStepPrompt(
          plan, blueprint.goal, i, steps.length,
          keyOutputs, referencesContext,
          i === 0 ? args : undefined,
          stepDeliveryCheck,
          workspace, pathId, stepSkills, guideContent,
        );

        const sessionId = `smartpath-ephemeral-${run.id}-step-${i}`;
        this.sessionManager.createEphemeralSessionWithId(workspace, sessionId);
        sessionIds.push(sessionId);

        let stepFullContent = '';
        try {
          const stepReturned = await this.sessionManager.sendMessageForStep(
            sessionId, prompt, abortController,
            (delta) => {
              stepFullContent += delta;
              onStepProgress(i, delta);
            },
            STEP_SYSTEM_PROMPT,
          );

          let stepResult = (stepReturned || stepFullContent).trim();

          // 交付检查（自动执行模式）
          if (stepDeliveryCheck) {
            const checkResult = await this.runDeliveryCheck(
              stepResult, stepDeliveryCheck, workspace, pathId, run.id, i,
              abortController, onStepProgress,
            );

            if (!checkResult.passed) {
              // 自动重试一次
              onStepProgress(i, '\n\n[交付检查未通过，自动重试...]\n');
              const retrySessionId = `smartpath-ephemeral-${run.id}-step-${i}-retry`;
              this.sessionManager.createEphemeralSessionWithId(workspace, retrySessionId);
              let retryContent = '';
              try {
                const retryPrompt = `${prompt}\n\n[重要提示：上次执行未通过交付检查]\n检查标准：${stepDeliveryCheck}\n未通过原因：${checkResult.reason}\n请根据以上反馈重新执行，确保满足交付检查要求。`;
                const retryReturned = await this.sessionManager.sendMessageForStep(
                  retrySessionId, retryPrompt, abortController,
                  (delta) => { retryContent += delta; onStepProgress(i, delta); },
                  STEP_SYSTEM_PROMPT,
                );
                stepResult = (retryReturned || retryContent).trim();
              } finally {
                this.sessionManager.closeV2Session(retrySessionId);
                this.sessionManager.removeEphemeralSession(retrySessionId);
              }
            }
          }

          stepResults.push(stepResult);

          const refs = extractReferences(stepResult);
          for (const ref of refs) {
            this.store.saveReference(workspace, pathId, ref.fileName, ref.content);
          }

          if (i < steps.length - 1) {
            const summary = await this.extractKeyOutputs(
              stepResult, plan.expectedOutputs, workspace, abortController, sessionIds,
            );
            keyOutputs.push(summary);
          }
        } finally {
          this.sessionManager.closeV2Session(sessionId);
          this.sessionManager.removeEphemeralSession(sessionId);
        }
      }

      // 完成报告 + 经验沉淀
      this.store.update(pathId, workspace, { status: 'completed' });
      const reportFileName = this.store.createReport(
        workspace, pathId, smartPath.name, steps, stepResults, run.id,
        'completed', run.startedAt, isoNow(),
      );
      this.store.updateRun(run.id, workspace, pathId, {
        status: 'completed',
        stepResults: JSON.stringify(stepResults),
        finishedAt: isoNow(),
        reportFileName: path.basename(reportFileName),
      });
      await this.updateRunGuide(workspace, pathId, steps, stepResults, blueprint);

      return { stepResults, blueprint };
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.store.updateRun(run.id, workspace, pathId, {
        status: 'failed',
        errorMessage: msg,
        stepResults: JSON.stringify({ error: msg }),
        finishedAt: isoNow(),
      });
      this.store.update(pathId, workspace, { status: 'failed' });
      throw err;
    } finally {
      this.activeRuns.delete(pathId);
    }
  }

  /** 主编分析：理解整个 path，产出 PathBlueprint */
  private async orchestrate(
    smartPath: { name: string; description?: string },
    steps: SmartPathStep[],
    referencesContext: string,
    workspace: string,
    args: string | undefined,
    abortController: AbortController,
    sessionIds: string[],
    onStepProgress: (stepIndex: number, delta: string) => void,
  ): Promise<PathBlueprint> {
    const sessionId = `smartpath-plan-${Date.now()}`;
    this.sessionManager.createEphemeralSessionWithId(workspace, sessionId);
    sessionIds.push(sessionId);

    try {
      const prompt = buildOrchestratorPrompt(
        smartPath.name, smartPath.description || '', steps, referencesContext, args,
      );
      const raw = await this.sessionManager.sendMessageForStep(
        sessionId, prompt, abortController,
        (delta) => onStepProgress(-1, delta),
        ORCHESTRATOR_SYSTEM_PROMPT,
      );
      return parseBlueprintFromLLM(raw || '', steps);
    } finally {
      this.sessionManager.closeV2Session(sessionId);
      this.sessionManager.removeEphemeralSession(sessionId);
    }
  }

  /** 交付检查：用 LLM 核对步骤结果是否满足交付标准 */
  private async runDeliveryCheck(
    stepResult: string,
    deliveryCheck: string,
    workspace: string,
    pathId: string,
    runId: string,
    stepIndex: number,
    abortController: AbortController,
    onStepProgress: (stepIndex: number, delta: string) => void,
  ): Promise<{ passed: boolean; reason?: string }> {
    const checkSessionId = `smartpath-check-${runId}-step-${stepIndex}-${Date.now()}`;
    this.sessionManager.createEphemeralSessionWithId(workspace, checkSessionId);

    try {
      const checkPrompt = [
        '你是交付检查员。请严格核对以下执行结果是否满足交付检查标准。',
        '',
        '## 交付检查标准',
        deliveryCheck,
        '',
        '## 步骤执行结果',
        stepResult,
        '',
        '请判断结果是否满足交付标准。输出格式（严格遵守）：',
        '- 如果满足：只输出 PASS',
        '- 如果不满足：输出 FAIL: 后面跟具体不满足的原因',
      ].join('\n');

      let checkContent = '';
      const checkReturned = await this.sessionManager.sendMessageForStep(
        checkSessionId, checkPrompt, abortController,
        (delta) => { checkContent += delta; },
        '你是严格的交付检查员，只根据标准判断通过或不通过。',
      );

      const checkResponse = (checkReturned || checkContent).trim();

      if (checkResponse.startsWith('FAIL:') || checkResponse.toUpperCase().startsWith('FAIL')) {
        const reason = checkResponse.replace(/^FAIL:\s*/i, '').trim();
        return { passed: false, reason: reason || '未通过交付检查' };
      }

      return { passed: true };
    } catch {
      return { passed: true };
    } finally {
      this.sessionManager.closeV2Session(checkSessionId);
      this.sessionManager.removeEphemeralSession(checkSessionId);
    }
  }

  /** 从步骤结果中提炼关键信息给后续步骤 */
  private async extractKeyOutputs(
    stepResult: string,
    expectedOutputs: string,
    workspace: string,
    abortController: AbortController,
    sessionIds: string[],
  ): Promise<string> {
    if (stepResult.length < 500) return stepResult;

    const sessionId = `smartpath-summary-${Date.now()}`;
    this.sessionManager.createEphemeralSessionWithId(workspace, sessionId);
    sessionIds.push(sessionId);

    try {
      const prompt = [
        `期望产出: ${expectedOutputs}`,
        '',
        '步骤执行结果:',
        stepResult.length > 3000 ? stepResult.slice(0, 3000) + '\n...(truncated)' : stepResult,
        '',
        '请提炼关键信息。',
      ].join('\n');

      const summary = await this.sessionManager.sendMessageForStep(
        sessionId, prompt, abortController, () => {},
        SUMMARY_SYSTEM_PROMPT,
      );
      return summary?.trim() || stepResult.slice(0, 500);
    } catch {
      return stepResult.slice(0, 500);
    } finally {
      this.sessionManager.closeV2Session(sessionId);
      this.sessionManager.removeEphemeralSession(sessionId);
    }
  }

  /** 构建 references 上下文 */
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

  /** 执行完后更新 references/run.md（经验沉淀） */
  private async updateRunGuide(
    ws: string,
    pathId: string,
    steps: SmartPathStep[],
    results: string[],
    blueprint: PathBlueprint,
  ): Promise<void> {
    const existingGuide = this.store.getRunGuide(ws, pathId) || '';
    const existingRefs = this.store.listReferences(ws, pathId)
      .filter(r => r.fileName !== 'run.md')
      .map(r => r.fileName);

    const modLines = blueprint.modifications.length > 0
      ? blueprint.modifications.map(m => `- 步骤 ${m.step + 1}: "${m.original}" → "${m.revised}"（${m.reason}）`)
      : ['- 无修正'];

    const prompt = [
      '根据本次执行更新 run.md（复用指南与经验沉淀）。',
      '',
      `工作流目标: ${blueprint.goal}`,
      `现有指南: ${existingGuide || '(无)'}`,
      `复用文件: ${existingRefs.length > 0 ? existingRefs.join(', ') : '(无)'}`,
      `步骤修正: ${modLines.join('; ')}`,
      '',
      '执行摘要:',
      ...steps.map((s, i) => `${i + 1}. ${s.userInput} → ${(results[i] || '').slice(0, 200)}`),
      '',
      '输出 run.md 完整内容，包含：# 复用指南与经验沉淀 → 路径概述 → 执行策略 → 步骤修正记录 → 可复用资源（必须列出每个文件的完整路径 .sman/paths/{pathId}/references/{filename} 和用途说明）→ 注意事项 → 最佳实践。简洁实用。',
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
      this.log.warn(`Failed to update run.md via LLM, using fallback: ${err}`);
      this.store.updateRunGuide(ws, pathId, this.generateFallbackRunGuide(existingRefs, blueprint));
    } finally {
      try {
        this.sessionManager.closeV2Session(sessionId);
        this.sessionManager.removeEphemeralSession(sessionId);
      } catch { /* cleanup failed, ignore */ }
    }
  }

  private generateFallbackRunGuide(refs: string[], blueprint: PathBlueprint): string {
    const lines = [
      '# 复用指南与经验沉淀', '',
      '## 路径概述',
      `- 目标: ${blueprint.goal}`, '',
      '## 可复用资源',
    ];
    if (refs.length === 0) {
      lines.push('(暂无可复用资源)');
    } else {
      for (const ref of refs) lines.push(`- ${ref}: 上次执行生成，可检查后复用`);
    }
    if (blueprint.modifications.length > 0) {
      lines.push('', '## 步骤修正记录');
      for (const m of blueprint.modifications) {
        lines.push(`- 步骤 ${m.step + 1}: "${m.original}" → "${m.revised}"（${m.reason}）`);
      }
    }
    lines.push('', '## 注意事项', '- 如果需求有变化，应重新生成对应资源');
    return lines.join('\n');
  }
}
