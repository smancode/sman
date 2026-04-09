/**
 * Project Scanner — orchestrates serial scanner agents to generate
 * lightweight knowledge files in {workspace}/.claude/skills/.
 */
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { createLogger, type Logger } from '../utils/logger.js';
import { getScannerPrompt, SCANNER_TYPES, type ScannerType } from './scanner-prompts.js';
import { validateSkillMd, parseFrontmatter } from './frontmatter-utils.js';

// ── Types ──

export interface ScanManifest {
  version: string;
  gitUrl: string | null;
  commitHash: string | null;
  branch: string | null;
  scannedAt: string;
  scanners: Record<string, ScannerStatus>;
}

export interface ScannerStatus {
  status: 'done' | 'error' | 'pending';
  filesWritten?: number;
  itemsCount?: number;
  error?: string;
}

interface ScannedWorkspaces {
  version: string;
  workspaces: Array<{ path: string; lastScannedAt: string }>;
}

// ── Utility Functions ──

export function sanitizeEndpointSlug(apiPath: string): string {
  return apiPath
    .replace(/^\//, '')
    .replace(/\//g, '-')
    .substring(0, 80);
}

export function getGitInfo(workspace: string): {
  commitHash: string | null;
  gitUrl: string | null;
  branch: string | null;
} {
  try {
    const commitHash = execSync('git rev-parse HEAD', { cwd: workspace, encoding: 'utf-8' }).trim();
    let gitUrl: string | null = null;
    try {
      gitUrl = execSync('git remote get-url origin', { cwd: workspace, encoding: 'utf-8' }).trim();
      gitUrl = gitUrl.replace(/:\/\/[^@]+@/, '://');
    } catch { /* no remote */ }
    let branch: string | null = null;
    try {
      branch = execSync('git rev-parse --abbrev-ref HEAD', { cwd: workspace, encoding: 'utf-8' }).trim();
    } catch { /* detached head */ }
    return { commitHash, gitUrl, branch };
  } catch {
    return { commitHash: null, gitUrl: null, branch: null };
  }
}

export function isScanNeeded(workspace: string, scannerType: ScannerType): { needed: boolean; reason: string } {
  const skillMdPath = path.join(workspace, '.claude', 'skills', `project-${scannerType}`, 'SKILL.md');

  if (!fs.existsSync(skillMdPath)) {
    return { needed: true, reason: 'SKILL.md not found' };
  }

  const content = fs.readFileSync(skillMdPath, 'utf-8');
  const frontmatter = parseFrontmatter(content);
  const scannedCommit = frontmatter?._scanned?.commitHash;

  let currentCommit: string;
  try {
    currentCommit = execSync('git rev-parse HEAD', { cwd: workspace, encoding: 'utf-8' }).trim();
  } catch {
    return { needed: true, reason: 'not a git repo' };
  }

  if (scannedCommit !== currentCommit) {
    return {
      needed: true,
      reason: scannedCommit
        ? `commit changed: ${scannedCommit} → ${currentCommit}`
        : `commit hash missing in SKILL.md`
    };
  }

  return { needed: false, reason: 'up to date' };
}

const LOCK_STALE_MS = 30 * 60 * 1000;

export function isLockStale(workspace: string): boolean {
  const lockPath = path.join(workspace, '.claude', '.scanning');
  if (!fs.existsSync(lockPath)) return false;
  try {
    const lock = JSON.parse(fs.readFileSync(lockPath, 'utf-8'));
    const startedAt = new Date(lock.startedAt).getTime();
    return Date.now() - startedAt > LOCK_STALE_MS;
  } catch {
    return true;
  }
}

export function acquireLock(workspace: string): void {
  const claudeDir = path.join(workspace, '.claude');
  fs.mkdirSync(claudeDir, { recursive: true });
  fs.writeFileSync(path.join(claudeDir, '.scanning'), JSON.stringify({
    pid: process.pid,
    startedAt: new Date().toISOString(),
    scanners: [...SCANNER_TYPES],
  }, null, 2));
}

export function releaseLock(workspace: string): void {
  const lockPath = path.join(workspace, '.claude', '.scanning');
  if (fs.existsSync(lockPath)) fs.unlinkSync(lockPath);
}

export function writeManifest(workspace: string, manifest: ScanManifest): void {
  const claudeDir = path.join(workspace, '.claude');
  fs.mkdirSync(claudeDir, { recursive: true });
  fs.writeFileSync(path.join(claudeDir, 'knowledge-manifest.json'), JSON.stringify(manifest, null, 2));
}

// ── Registry ──

export function getScannedWorkspacesPath(homeDir: string): string {
  return path.join(homeDir, 'scanned-workspaces.json');
}

export function registerScannedWorkspace(homeDir: string, workspace: string): void {
  const registryPath = getScannedWorkspacesPath(homeDir);
  let registry: ScannedWorkspaces = { version: '1.0', workspaces: [] };
  if (fs.existsSync(registryPath)) {
    try { registry = JSON.parse(fs.readFileSync(registryPath, 'utf-8')); } catch { /* corrupted */ }
  }
  const existing = registry.workspaces.findIndex(w => w.path === workspace);
  const entry = { path: workspace, lastScannedAt: new Date().toISOString() };
  if (existing >= 0) {
    registry.workspaces[existing] = entry;
  } else {
    registry.workspaces.push(entry);
  }
  fs.writeFileSync(registryPath, JSON.stringify(registry, null, 2));
}

export function listScannedWorkspaces(homeDir: string): ScannedWorkspaces {
  const registryPath = getScannedWorkspacesPath(homeDir);
  if (!fs.existsSync(registryPath)) return { version: '1.0', workspaces: [] };
  try { return JSON.parse(fs.readFileSync(registryPath, 'utf-8')); } catch { return { version: '1.0', workspaces: [] }; }
}

// ── Orchestrator ──

export interface ProjectScannerOptions {
  homeDir: string;
  sessionManager: {
    createSessionWithId(workspace: string, sessionId: string, isCron?: boolean, isScanner?: boolean): string;
    sendMessageForCron(sessionId: string, content: string, abortController: AbortController, onActivity: () => void): Promise<void>;
    closeV2Session(sessionId: string): void;
    hasActiveStreams(): boolean;
  };
}

function deleteScannerDir(workspace: string, scannerType: ScannerType): void {
  const dir = path.join(workspace, '.claude', 'skills', `project-${scannerType}`);
  if (fs.existsSync(dir)) { fs.rmSync(dir, { recursive: true, force: true }); }
}

export class ProjectScanner {
  private log: Logger;

  constructor(private options: ProjectScannerOptions) {
    this.log = createLogger('ProjectScanner');
  }

  async scheduleScanIfNeeded(workspace: string): Promise<void> {
    this.log.info(`Scheduling scan for ${workspace}`);
    try { await this.scanWorkspace(workspace); } catch (e: any) { this.log.error(`Scan failed: ${e.message}`); }
  }

  async scanWorkspace(workspace: string): Promise<void> {
    acquireLock(workspace);
    try {
      const gitInfo = getGitInfo(workspace);

      for (const type of SCANNER_TYPES) {
        const { needed, reason } = isScanNeeded(workspace, type);
        if (!needed) {
          this.log.info(`Scanner [${type}] skipped: ${reason}`);
          continue;
        }

        await this.waitUntilIdle();

        let success = false;
        for (let retry = 0; retry <= 2 && !success; retry++) {
          const result = await this.runScanner(type, workspace, gitInfo);
          if (result.success) {
            const skillMdPath = path.join(workspace, '.claude', 'skills', `project-${type}`, 'SKILL.md');
            if (validateSkillMd(skillMdPath)) {
              success = true;
              this.updateManifestScanner(workspace, type, { status: 'done', filesWritten: result.filesWritten });
            } else {
              this.log.warn(`Scanner [${type}] SKILL.md validation failed, retry ${retry + 1}/3`);
              deleteScannerDir(workspace, type);
            }
          } else {
            this.log.warn(`Scanner [${type}] failed: ${result.error}, retry ${retry + 1}/3`);
            deleteScannerDir(workspace, type);
          }
        }
        if (!success) {
          this.updateManifestScanner(workspace, type, { status: 'error', error: 'exhausted retries' });
        }
      }

      writeManifest(workspace, { version: '2.0', ...gitInfo, scannedAt: new Date().toISOString(), scanners: {} });
      registerScannedWorkspace(this.options.homeDir, workspace);
      this.log.info(`Scan complete for ${workspace}`);
    } finally {
      releaseLock(workspace);
    }
  }

  private async waitUntilIdle(): Promise<void> {
    while (this.options.sessionManager.hasActiveStreams()) {
      this.log.info('Waiting for active sessions to finish...');
      await new Promise(r => setTimeout(r, 30_000));
    }
  }

  private updateManifestScanner(workspace: string, type: ScannerType, status: ScannerStatus): void {
    const manifestPath = path.join(workspace, '.claude', 'knowledge-manifest.json');
    let manifest: ScanManifest = { version: '2.0', gitUrl: null, commitHash: null, branch: null, scannedAt: '', scanners: {} };
    if (fs.existsSync(manifestPath)) {
      try { manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf-8')); } catch { /* ignore */ }
    }
    manifest.scanners[type] = status;
    manifest.version = '2.0';
    fs.mkdirSync(path.join(workspace, '.claude'), { recursive: true });
    fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
  }

  private async runScanner(type: ScannerType, workspace: string, gitInfo: { commitHash: string | null; gitUrl: string | null; branch: string | null }): Promise<{ filesWritten: number; success: boolean; error?: string }> {
    const sessionId = `scanner-${type}-${Date.now()}`;
    const prompt = getScannerPrompt(type, workspace, {
      commitHash: gitInfo.commitHash ?? 'unknown',
      branch: gitInfo.branch ?? 'unknown',
    });
    this.options.sessionManager.createSessionWithId(workspace, sessionId, true, true);
    const abortController = new AbortController();
    const timeoutId = setTimeout(() => { abortController.abort(); }, 10 * 60 * 1000);
    try {
      this.log.info(`Scanner [${type}] starting for session ${sessionId}`);
      await this.options.sessionManager.sendMessageForCron(sessionId, prompt, abortController, () => {});
      clearTimeout(timeoutId);
      const outputDir = path.join(workspace, '.claude', 'skills', `project-${type}`);
      const skillFile = path.join(outputDir, 'SKILL.md');

      // Validate SKILL.md exists and is non-empty
      if (!fs.existsSync(skillFile)) {
        this.log.error(`Scanner [${type}] failed: SKILL.md not written`);
        return { filesWritten: 0, success: false, error: 'SKILL.md not written by agent' };
      }
      const content = fs.readFileSync(skillFile, 'utf-8').trim();
      if (content.length < 50) {
        this.log.error(`Scanner [${type}] failed: SKILL.md too short (${content.length} chars)`);
        return { filesWritten: 0, success: false, error: `SKILL.md too short: ${content.length} chars` };
      }

      const mdFiles = fs.readdirSync(outputDir).filter(f => f.endsWith('.md'));
      this.log.info(`Scanner [${type}] succeeded: ${mdFiles.length} files written`);
      return { filesWritten: mdFiles.length, success: true };
    } catch (e: any) {
      clearTimeout(timeoutId);
      if (e.name === 'AbortError') {
        this.log.error(`Scanner [${type}] timed out after 10 minutes`);
        return { filesWritten: 0, success: false, error: 'timeout after 10 minutes' };
      }
      this.log.error(`Scanner [${type}] error: ${e.message}`);
      return { filesWritten: 0, success: false, error: e.message };
    } finally {
      try { this.options.sessionManager.closeV2Session(sessionId); } catch { /* ignore */ }
    }
  }

  async nightlyRefresh(): Promise<void> {
    const registry = listScannedWorkspaces(this.options.homeDir);
    this.log.info(`Nightly refresh: checking ${registry.workspaces.length} workspaces`);
    for (const entry of registry.workspaces) {
      if (!fs.existsSync(entry.path)) continue;
      const manifestPath = path.join(entry.path, '.claude', 'knowledge-manifest.json');
      if (!fs.existsSync(manifestPath)) continue;
      let needsRescan = false;
      try {
        const manifest: ScanManifest = JSON.parse(fs.readFileSync(manifestPath, 'utf-8'));
        if (manifest.commitHash) {
          const currentHash = execSync('git rev-parse HEAD', { cwd: entry.path, encoding: 'utf-8' }).trim();
          needsRescan = currentHash !== manifest.commitHash;
        } else {
          needsRescan = Date.now() - new Date(manifest.scannedAt).getTime() > 7 * 24 * 60 * 60 * 1000;
        }
      } catch { needsRescan = true; }
      if (needsRescan) {
        // Delete old skill files to force full rescan
        const skillsDir = path.join(entry.path, '.claude', 'skills');
        for (const type of SCANNER_TYPES) {
          const skillDir = path.join(skillsDir, `project-${type}`);
          if (fs.existsSync(skillDir)) fs.rmSync(skillDir, { recursive: true, force: true });
        }
        await this.scanWorkspace(entry.path);
      }
    }
  }
}
