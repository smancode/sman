/**
 * Project Scanner — orchestrates parallel scanner agents to generate
 * lightweight knowledge files in {workspace}/.claude/knowledge/.
 */
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { getScannerPrompt, SCANNER_TYPES, type ScannerType } from './scanner-prompts.js';

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

export function isScanNeeded(workspace: string): boolean {
  const lockPath = path.join(workspace, '.claude', '.scanning');

  // Check if skill files already exist — if all 3 SKILL.md exist, skip
  const skillsDir = path.join(workspace, '.claude', 'skills');
  const allExist = SCANNER_TYPES.every(type =>
    fs.existsSync(path.join(skillsDir, `project-${type}`, 'SKILL.md')),
  );
  if (allExist) return false;

  if (fs.existsSync(lockPath)) {
    if (isLockStale(workspace)) {
      fs.unlinkSync(lockPath);
      return true;
    }
    return false;
  }

  return true;
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
  };
}

export class ProjectScanner {
  private log = { info: (...args: any[]) => console.log('[ProjectScanner]', ...args) };

  constructor(private options: ProjectScannerOptions) {}

  async scheduleScanIfNeeded(workspace: string): Promise<void> {
    if (!isScanNeeded(workspace)) return;
    this.log.info(`Scheduling scan for ${workspace}`);
    try { await this.scan(workspace); } catch (e: any) { this.log.info(`Scan failed: ${e.message}`); }
  }

  async scan(workspace: string): Promise<void> {
    acquireLock(workspace);
    const gitInfo = getGitInfo(workspace);
    const scannerStatuses: Record<string, ScannerStatus> = {};
    const scannerPromises: Promise<void>[] = [];

    for (const type of SCANNER_TYPES) {
      scannerStatuses[type] = { status: 'pending' };
      scannerPromises.push(
        this.runScanner(type, workspace)
          .then((count) => { scannerStatuses[type] = { status: 'done', filesWritten: count }; })
          .catch((e: any) => { scannerStatuses[type] = { status: 'error', error: e.message }; }),
      );
    }

    await Promise.all(scannerPromises);
    writeManifest(workspace, { version: '1.0', ...gitInfo, scannedAt: new Date().toISOString(), scanners: scannerStatuses });
    releaseLock(workspace);
    registerScannedWorkspace(this.options.homeDir, workspace);
    const doneCount = Object.values(scannerStatuses).filter(s => s.status === 'done').length;
    this.log.info(`Scan complete: ${doneCount}/3 scanners succeeded`);
  }

  private async runScanner(type: ScannerType, workspace: string): Promise<number> {
    const sessionId = `scanner-${type}-${Date.now()}`;
    const prompt = getScannerPrompt(type, workspace);
    this.options.sessionManager.createSessionWithId(workspace, sessionId, true, true);
    const abortController = new AbortController();
    let filesWritten = 0;
    try {
      await this.options.sessionManager.sendMessageForCron(sessionId, prompt, abortController, () => {});
      const outputDir = path.join(workspace, '.claude', 'skills', `project-${type}`);
      if (fs.existsSync(outputDir)) {
        filesWritten = fs.readdirSync(outputDir).filter(f => f.endsWith('.md')).length;
      }
    } finally {
      try { this.options.sessionManager.closeV2Session(sessionId); } catch { /* ignore */ }
    }
    return filesWritten;
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
        await this.scan(entry.path);
      }
    }
  }
}
