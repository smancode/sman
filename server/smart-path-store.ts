import fs from 'fs';
import path from 'path';
import matter from 'gray-matter';
import { createLogger, type Logger } from './utils/logger.js';
import type { SmartPath, SmartPathRun } from './types.js';

export class SmartPathStore {
  private log: Logger;

  constructor(private basePath: string) {
    this.log = createLogger('SmartPathStore');
    if (!fs.existsSync(basePath)) {
      fs.mkdirSync(basePath, { recursive: true });
    }
  }

  private getPathsDir(workspace: string): string {
    if (!workspace || workspace.trim() === '') {
      throw new Error('Workspace is required');
    }

    const dir = path.join(workspace, '.sman', 'paths');
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    return dir;
  }

  private getPlanFilePath(workspace: string, id: string): string {
    return path.join(this.getPathsDir(workspace), `${id}.md`);
  }

  // === Alias methods for backward compatibility ===

  /** @deprecated Use listPlans */
  listPaths(): SmartPath[] {
    // List from all workspace subdirectories under basePath
    if (!fs.existsSync(this.basePath)) return [];
    const workspaces = fs.readdirSync(this.basePath);
    const all: SmartPath[] = [];
    for (const ws of workspaces) {
      const wsDir = path.join(this.basePath, ws, '.sman', 'paths');
      if (!fs.existsSync(wsDir)) continue;
      const files = fs.readdirSync(wsDir).filter(f => f.endsWith('.md'));
      for (const file of files) {
        try {
          all.push(this.loadPlan(path.join(wsDir, file)));
        } catch { /* skip corrupt files */ }
      }
    }
    return all.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  /** @deprecated Use savePlan */
  createPath(input: {
    name: string;
    workspace: string;
    steps: string;
    status?: SmartPath['status'];
  }): SmartPath {
    if (!input.name || input.name.trim() === '') {
      throw new Error('缺少 name 参数');
    }
    if (!input.workspace || input.workspace.trim() === '') {
      throw new Error('缺少 workspace 参数');
    }
    if (!input.steps || input.steps.trim() === '') {
      throw new Error('缺少 steps 参数');
    }

    const id = crypto.randomUUID();
    const now = new Date().toISOString();
    const plan: SmartPath = {
      id,
      name: input.name,
      workspace: input.workspace,
      steps: input.steps,
      status: input.status || 'draft',
      createdAt: now,
      updatedAt: now,
    };

    this.savePlan(plan);
    return plan;
  }

  getPath(id: string): SmartPath | undefined {
    // Search all workspace dirs for this id
    if (!fs.existsSync(this.basePath)) return undefined;
    const workspaces = fs.readdirSync(this.basePath);
    for (const ws of workspaces) {
      const filePath = path.join(this.basePath, ws, '.sman', 'paths', `${id}.md`);
      if (fs.existsSync(filePath)) {
        return this.loadPlan(filePath);
      }
    }
    return undefined;
  }

  updatePath(id: string, updates: Partial<SmartPath>): SmartPath {
    const existing = this.getPath(id);
    if (!existing) throw new Error(`Path not found: ${id}`);
    const merged = { ...existing, ...updates, updatedAt: new Date().toISOString() };
    this.savePlan(merged);
    return merged;
  }

  deletePath(id: string): void {
    const existing = this.getPath(id);
    if (!existing) throw new Error(`Path not found: ${id}`);
    const filePath = this.getPlanFilePath(existing.workspace, id);
    fs.unlinkSync(filePath);
    this.log.info(`Deleted plan: ${filePath}`);
  }

  // === Run methods ===

  private getRunsDir(workspace: string, pathId: string): string {
    const dir = path.join(this.getPathsDir(workspace), pathId, 'runs');
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    return dir;
  }

  createRun(pathId: string): SmartPathRun {
    const smartPath = this.getPath(pathId);
    if (!smartPath) throw new Error(`Path not found: ${pathId}`);

    const run: SmartPathRun = {
      id: crypto.randomUUID(),
      pathId,
      status: 'running',
      stepResults: '{}',
      startedAt: new Date().toISOString(),
    };

    const dir = this.getRunsDir(smartPath.workspace, pathId);
    const filePath = path.join(dir, `${run.id}.json`);
    fs.writeFileSync(filePath, JSON.stringify(run, null, 2), 'utf-8');
    this.log.info(`Created run ${run.id} for path ${pathId}`);
    return run;
  }

  updateRun(runId: string, updates: Partial<SmartPathRun>): void {
    // Search all run dirs for this runId
    if (!fs.existsSync(this.basePath)) return;
    const workspaces = fs.readdirSync(this.basePath);
    for (const ws of workspaces) {
      const pathsDir = path.join(this.basePath, ws, '.sman', 'paths');
      if (!fs.existsSync(pathsDir)) continue;
      const pathDirs = fs.readdirSync(pathsDir);
      for (const pd of pathDirs) {
        const runFile = path.join(pathsDir, pd, 'runs', `${runId}.json`);
        if (fs.existsSync(runFile)) {
          const existing: SmartPathRun = JSON.parse(fs.readFileSync(runFile, 'utf-8'));
          const merged = { ...existing, ...updates };
          fs.writeFileSync(runFile, JSON.stringify(merged, null, 2), 'utf-8');
          return;
        }
      }
    }
    throw new Error(`Run not found: ${runId}`);
  }

  listRuns(pathId: string): SmartPathRun[] {
    const smartPath = this.getPath(pathId);
    if (!smartPath) return [];

    const runsDir = path.join(this.getPathsDir(smartPath.workspace), pathId, 'runs');
    if (!fs.existsSync(runsDir)) return [];

    const files = fs.readdirSync(runsDir).filter(f => f.endsWith('.json'));
    const runs: SmartPathRun[] = [];
    for (const file of files) {
      try {
        const content = fs.readFileSync(path.join(runsDir, file), 'utf-8');
        runs.push(JSON.parse(content));
      } catch { /* skip corrupt files */ }
    }
    return runs.sort((a, b) => b.startedAt.localeCompare(a.startedAt));
  }

  // === Core file-based methods ===

  listPlans(workspace: string): SmartPath[] {
    const dir = this.getPathsDir(workspace);
    if (!fs.existsSync(dir)) return [];

    const files = fs.readdirSync(dir).filter(f => f.endsWith('.md'));
    const plans: SmartPath[] = [];
    for (const file of files) {
      try {
        plans.push(this.loadPlan(path.join(dir, file)));
      } catch { /* skip corrupt files */ }
    }
    return plans.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  }

  deletePlan(filePath: string): void {
    if (!fs.existsSync(filePath)) {
      throw new Error(`Plan file not found: ${filePath}`);
    }
    fs.unlinkSync(filePath);
    this.log.info(`Deleted plan: ${filePath}`);
  }

  savePlan(plan: SmartPath): string {
    if (!plan.name || plan.name.trim() === '') {
      throw new Error('Plan name is required');
    }
    if (!plan.workspace || plan.workspace.trim() === '') {
      throw new Error('Workspace is required');
    }

    const dir = this.getPathsDir(plan.workspace);
    const filePath = path.join(dir, `${plan.id}.md`);

    const stepsJson = typeof plan.steps === 'string' ? plan.steps : JSON.stringify(plan.steps);

    const content = matter.stringify(
      `# ${plan.name}\n\n${plan.description || '无描述'}\n`,
      {
        name: plan.name,
        description: plan.description || '',
        workspace: plan.workspace,
        created_at: plan.createdAt,
        updated_at: plan.updatedAt || plan.createdAt,
        status: plan.status,
        steps: stepsJson,
      },
    );

    fs.writeFileSync(filePath, content, 'utf-8');
    this.log.info(`Saved plan to ${filePath}`);
    return filePath;
  }

  loadPlan(filePath: string): SmartPath {
    if (!fs.existsSync(filePath)) {
      throw new Error(`Plan file not found: ${filePath}`);
    }

    const content = fs.readFileSync(filePath, 'utf-8');
    const { data } = matter(content);

    return {
      id: path.basename(filePath, '.md'),
      name: data.name || '',
      description: data.description || '',
      workspace: data.workspace || '',
      steps: typeof data.steps === 'string' ? data.steps : JSON.stringify(data.steps || []),
      status: data.status || 'draft',
      createdAt: data.created_at || new Date().toISOString(),
      updatedAt: data.updated_at || data.created_at || new Date().toISOString(),
    };
  }
}
