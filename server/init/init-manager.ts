import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import type { WebSocket } from 'ws';
import { createLogger, type Logger } from '../utils/logger.js';
import { scanWorkspace } from './workspace-scanner.js';
import { matchCapabilities } from './capability-matcher.js';
import { injectSkills } from './skill-injector.js';
import { generateClaudeMd } from './claude-init-runner.js';
import type { InitResult, InitCard, CapabilityMatchResult } from './init-types.js';
import type { CapabilityRegistry } from '../capabilities/registry.js';
import type { SemanticSearchLlmConfig } from '../capabilities/types.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const SMAN_VERSION = '1.0.0'; // TODO: read from package.json
const LOCK_STALE_MS = 5 * 60 * 1000; // 5 minutes

export class InitManager {
  private log: Logger;
  private pluginsDir: string;
  private capabilityRegistry: CapabilityRegistry | null;
  private llmConfig: () => SemanticSearchLlmConfig | null;

  constructor(deps: {
    pluginsDir: string;
    capabilityRegistry: CapabilityRegistry | null;
    llmConfig: () => SemanticSearchLlmConfig | null;
  }) {
    this.log = createLogger('InitManager');
    this.pluginsDir = deps.pluginsDir;
    this.capabilityRegistry = deps.capabilityRegistry;
    this.llmConfig = deps.llmConfig;
  }

  isInitialized(workspace: string): boolean {
    const initPath = path.join(workspace, '.sman', 'INIT.md');
    if (!fs.existsSync(initPath)) return false;

    try {
      const content = fs.readFileSync(initPath, 'utf-8');
      const versionMatch = content.match(/smanVersion:\s*["']?([^"'\n]+)/);
      if (versionMatch && versionMatch[1] !== SMAN_VERSION) {
        return false; // Version mismatch, needs re-init
      }
    } catch {
      return false;
    }

    return true;
  }

  acquireLock(workspace: string): boolean {
    const smanDir = path.join(workspace, '.sman');
    const lockPath = path.join(smanDir, '.initializing');

    fs.mkdirSync(smanDir, { recursive: true });

    if (fs.existsSync(lockPath)) {
      try {
        const stat = fs.statSync(lockPath);
        if (Date.now() - stat.mtimeMs < LOCK_STALE_MS) {
          return false; // Lock is active
        }
        // Stale lock, remove it
        fs.unlinkSync(lockPath);
      } catch {
        return false;
      }
    }

    fs.writeFileSync(lockPath, JSON.stringify({
      pid: process.pid,
      startedAt: new Date().toISOString(),
    }, null, 2));

    return true;
  }

  releaseLock(workspace: string): void {
    const lockPath = path.join(workspace, '.sman', '.initializing');
    try {
      fs.unlinkSync(lockPath);
    } catch { /* ignore */ }
  }

  async handleSessionCreate(
    workspace: string,
    sessionId: string,
    ws: WebSocket,
  ): Promise<void> {
    try {
      // LLM unavailable → skip init entirely, no card, no pretending
      const llmConfig = this.llmConfig();
      if (!llmConfig) {
        this.log.info(`LLM not configured, skipping init for ${workspace}`);
        return;
      }

      if (this.isInitialized(workspace)) {
        const initMd = fs.readFileSync(path.join(workspace, '.sman', 'INIT.md'), 'utf-8');
        const card = this.parseInitMdToCard(initMd, workspace);
        this.sendCard(ws, sessionId, card);
        return;
      }

      // Send "initializing" card — phase: scanning
      this.sendCard(ws, sessionId, {
        type: 'initializing',
        workspace,
        phase: 'scanning',
      });

      if (!this.acquireLock(workspace)) {
        this.log.info(`Init already running for ${workspace}, skipping`);
        return;
      }

      try {
        const result = await this.initializeWithProgress(workspace, ws, sessionId, llmConfig);
        this.sendCard(ws, sessionId, {
          type: 'complete',
          workspace,
          projectSummary: result.matchResult.projectSummary,
          techStack: result.matchResult.techStack,
          injectedSkills: result.injectedSkills.map(id => {
            const cap = this.capabilityRegistry?.getCapability(id);
            return { id, name: cap?.name || id };
          }),
        });
      } finally {
        this.releaseLock(workspace);
      }
    } catch (err: any) {
      this.log.warn(`Init failed for ${workspace}: ${err.message}`);
      this.sendCard(ws, sessionId, {
        type: 'error',
        workspace,
        error: err.message,
      });
    }
  }

  /** Initialize with progress updates — global timeout 180s */
  private async initializeWithProgress(
    workspace: string,
    ws: WebSocket,
    sessionId: string,
    llmConfig: SemanticSearchLlmConfig,
  ): Promise<InitResult> {
    const GLOBAL_TIMEOUT_MS = 180_000;

    return Promise.race([
      (async () => {
        // Step 1: Scan (< 1s)
        const scanResult = scanWorkspace(workspace);

        // Step 2: AI Match — update phase card
        this.sendCard(ws, sessionId, {
          type: 'initializing',
          workspace,
          phase: 'matching',
        });

        const capabilities = this.capabilityRegistry?.listCapabilities() || [];
        const matchResult = await matchCapabilities(scanResult, capabilities, llmConfig);

        // Step 3: Inject skills
        this.sendCard(ws, sessionId, {
          type: 'initializing',
          workspace,
          phase: 'injecting',
        });

        const skillSources = matchResult.matches.map(m => {
          const cap = this.capabilityRegistry?.getCapability(m.capabilityId);
          return { capabilityId: m.capabilityId, pluginPath: cap?.pluginPath || m.capabilityId };
        });

        const injectedSkills = injectSkills(skillSources, this.pluginsDir, workspace);
        this.injectMetaSkills(workspace);

        // Step 4: CLAUDE.md — silent background, no progress card
        let claudeMdGenerated = false;
        if (!scanResult.hasClaudeMd) {
          claudeMdGenerated = await generateClaudeMd(workspace);
        }

        // Step 5: Write INIT.md
        this.writeInitMd(workspace, scanResult, matchResult, injectedSkills, claudeMdGenerated);

        return { success: true, scanResult, matchResult, injectedSkills, claudeMdGenerated };
      })(),
      new Promise<never>((_, reject) =>
        setTimeout(() => reject(new Error('初始化超时，请稍后重试')), GLOBAL_TIMEOUT_MS)
      ),
    ]);
  }

  private static readonly META_SKILLS = [
    'skill-auto-updater',
    'project-structure',
    'project-apis',
    'project-external-calls',
    'knowledge-business',
    'knowledge-conventions',
    'knowledge-technical',
  ];

  private injectMetaSkills(workspace: string): void {
    const templatesDir = path.join(__dirname, 'templates');
    for (const skillName of InitManager.META_SKILLS) {
      const targetDir = path.join(workspace, '.claude', 'skills', skillName);
      if (fs.existsSync(path.join(targetDir, 'SKILL.md'))) continue; // Don't overwrite

      const templateDir = path.join(templatesDir, skillName);
      if (!fs.existsSync(templateDir)) {
        this.log.warn(`Meta skill template not found: ${skillName}`);
        continue;
      }

      fs.mkdirSync(targetDir, { recursive: true });
      for (const file of fs.readdirSync(templateDir)) {
        fs.copyFileSync(
          path.join(templateDir, file),
          path.join(targetDir, file),
        );
      }
    }
  }

  private writeInitMd(
    workspace: string,
    scanResult: ReturnType<typeof scanWorkspace>,
    matchResult: CapabilityMatchResult,
    injectedSkills: string[],
    claudeMdGenerated: boolean,
  ): void {
    const smanDir = path.join(workspace, '.sman');
    fs.mkdirSync(smanDir, { recursive: true });

    const lines = [
      `---`,
      `smanVersion: "${SMAN_VERSION}"`,
      `initializedAt: "${new Date().toISOString()}"`,
      `---`,
      ``,
      `# Project Init`,
      ``,
      `**Type:** ${scanResult.types.join(', ')}`,
      `**Tech Stack:** ${matchResult.techStack.join(', ')}`,
      `**Summary:** ${matchResult.projectSummary}`,
      `**Files:** ${scanResult.fileCount}`,
      `**Git:** ${scanResult.isGitRepo ? 'yes' : 'no'}`,
      `**CLAUDE.md:** ${scanResult.hasClaudeMd ? 'existing' : claudeMdGenerated ? 'generated' : 'missing'}`,
      ``,
      `## Injected Skills`,
      ...injectedSkills.map(id => `- ${id}`),
      ``,
      `## Match Reasons`,
      ...matchResult.matches.map(m => `- **${m.capabilityId}**: ${m.reason}`),
    ];

    fs.writeFileSync(path.join(smanDir, 'INIT.md'), lines.join('\n'), 'utf-8');
  }

  sendCard(ws: WebSocket, sessionId: string, card: InitCard): void {
    try {
      ws.send(JSON.stringify({
        type: 'init.card',
        sessionId,
        card,
      }));
    } catch (err: any) {
      this.log.warn(`Failed to send init card: ${err.message}`);
    }
  }

  private parseInitMdToCard(content: string, workspace: string): InitCard {
    const summary = content.match(/\*\*Summary:\*\* (.+)/)?.[1] || '';
    const techStack = content.match(/\*\*Tech Stack:\*\* (.+)/)?.[1]?.split(', ') || [];
    const initializedAt = content.match(/initializedAt: "([^"]+)"/)?.[1] || '';
    const skills = [...content.matchAll(/^- (.+)$/gm)].map(m => m[1]);

    return {
      type: 'already',
      workspace,
      projectSummary: summary,
      techStack,
      injectedSkills: skills.map(id => ({ id, name: id })),
      initializedAt,
    };
  }
}
