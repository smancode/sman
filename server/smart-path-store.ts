import fs from 'fs';
import path from 'path';
import matter from 'gray-matter';
import { createLogger, type Logger } from './utils/logger.js';
import type { SmartPath, SmartPathStep, SmartPathRun } from './types.js';

export class SmartPathStore {
  private log: Logger;

  constructor(private basePath: string) {
    this.log = createLogger('SmartPathStore');
    if (!fs.existsSync(basePath)) {
      fs.mkdirSync(basePath, { recursive: true });
    }
  }

  getPathsDir(workspace: string): string {
    if (!workspace || workspace.trim() === '') {
      throw new Error('Workspace is required');
    }

    const dir = path.join(workspace, '.sman', 'paths');
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    return dir;
  }

  savePlan(plan: Omit<SmartPath, 'steps'> & { steps: SmartPathStep[] }): string {
    if (!plan.name || !plan.name.trim()) {
      throw new Error('Plan name is required');
    }
    if (!plan.workspace) {
      throw new Error('Workspace is required');
    }

    const dir = this.getPathsDir(plan.workspace);
    const filename = `${plan.id}.md`;
    const filePath = path.join(dir, filename);

    const content = matter.stringify(
      `# ${plan.name}\n\n${plan.description || '无描述'}\n`,
      {
        name: plan.name,
        description: plan.description || '',
        workspace: plan.workspace,
        created_at: plan.createdAt,
        updated_at: plan.updatedAt || plan.createdAt,
        status: plan.status,
        steps: plan.steps,
      },
    );

    fs.writeFileSync(filePath, content, 'utf-8');
    this.log.info(`Saved plan to ${filePath}`);
    return filePath;
  }

  listPlans(workspace: string): SmartPath[] {
    const dir = this.getPathsDir(workspace);
    if (!fs.existsSync(dir)) {
      return [];
    }

    const files = fs.readdirSync(dir).filter(f => f.endsWith('.md'));
    const plans: SmartPath[] = [];

    for (const file of files) {
      try {
        const filePath = path.join(dir, file);
        const plan = this.loadPlan(filePath);
        plans.push(plan);
      } catch (err) {
        this.log.error(`Failed to load plan ${file}: ${err}`);
      }
    }

    return plans.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
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
      steps: data.steps ? JSON.stringify(data.steps) : '[]',
      status: data.status || 'draft',
      createdAt: data.created_at || new Date().toISOString(),
      updatedAt: data.updated_at || data.created_at || new Date().toISOString(),
    };
  }

  deletePlan(filePath: string): void {
    if (!fs.existsSync(filePath)) {
      throw new Error(`Plan file not found: ${filePath}`);
    }
    fs.unlinkSync(filePath);
    this.log.info(`Deleted plan: ${filePath}`);
  }

  updatePlan(filePath: string, updates: Partial<SmartPath>): void {
    const existing = this.loadPlan(filePath);
    const merged = { ...existing, ...updates, updatedAt: new Date().toISOString() };
    const stepsData = existing.steps ? JSON.parse(existing.steps) : [];
    this.savePlan({ ...merged, steps: stepsData } as Omit<SmartPath, 'steps'> & { steps: SmartPathStep[] });
  }

  // === Backward compatibility (file I/O wrappers only) ===

  listPaths(): SmartPath[] {
    if (!fs.existsSync(this.basePath)) return [];
    const all: SmartPath[] = [];
    for (const ws of fs.readdirSync(this.basePath)) {
      const wsDir = path.join(this.basePath, ws, '.sman', 'paths');
      if (!fs.existsSync(wsDir)) continue;
      for (const file of fs.readdirSync(wsDir).filter(f => f.endsWith('.md'))) {
        try { all.push(this.loadPlan(path.join(wsDir, file))); } catch {}
      }
    }
    return all.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  createPath(input: { name: string; workspace: string; steps: string; status?: SmartPath['status'] }): SmartPath {
    if (!input.name?.trim()) throw new Error('缺少 name 参数');
    if (!input.workspace?.trim()) throw new Error('缺少 workspace 参数');
    if (!input.steps?.trim()) throw new Error('缺少 steps 参数');
    const id = crypto.randomUUID();
    const now = new Date().toISOString();
    const plan: SmartPath = { id, name: input.name, workspace: input.workspace, steps: input.steps, status: input.status || 'draft', createdAt: now, updatedAt: now };
    this.savePlan({ ...plan, steps: JSON.parse(input.steps) });
    return plan;
  }

  getPath(id: string): SmartPath | undefined {
    if (!fs.existsSync(this.basePath)) return undefined;
    for (const ws of fs.readdirSync(this.basePath)) {
      const filePath = path.join(this.basePath, ws, '.sman', 'paths', `${id}.md`);
      if (fs.existsSync(filePath)) return this.loadPlan(filePath);
    }
    return undefined;
  }

  updatePath(id: string, updates: Partial<SmartPath>): SmartPath {
    const existing = this.getPath(id);
    if (!existing) throw new Error(`Path not found: ${id}`);
    this.updatePlan(path.join(this.getPathsDir(existing.workspace), `${id}.md`), updates);
    return { ...existing, ...updates, updatedAt: new Date().toISOString() };
  }

  deletePath(id: string): void {
    const existing = this.getPath(id);
    if (!existing) throw new Error(`Path not found: ${id}`);
    this.deletePlan(path.join(this.getPathsDir(existing.workspace), `${id}.md`));
  }

  // === Run methods (file I/O only) ===

  private getRunsDir(workspace: string, pathId: string): string {
    const dir = path.join(this.getPathsDir(workspace), pathId, 'runs');
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    return dir;
  }

  createRun(pathId: string): SmartPathRun {
    const smartPath = this.getPath(pathId);
    if (!smartPath) throw new Error(`Path not found: ${pathId}`);
    const run: SmartPathRun = { id: crypto.randomUUID(), pathId, status: 'running', stepResults: '{}', startedAt: new Date().toISOString() };
    const filePath = path.join(this.getRunsDir(smartPath.workspace, pathId), `${run.id}.json`);
    fs.writeFileSync(filePath, JSON.stringify(run, null, 2), 'utf-8');
    this.log.info(`Created run ${run.id} for path ${pathId}`);
    return run;
  }

  updateRun(runId: string, updates: Partial<SmartPathRun>): void {
    if (!fs.existsSync(this.basePath)) return;
    for (const ws of fs.readdirSync(this.basePath)) {
      const pathsDir = path.join(this.basePath, ws, '.sman', 'paths');
      if (!fs.existsSync(pathsDir)) continue;
      for (const pd of fs.readdirSync(pathsDir)) {
        const runFile = path.join(pathsDir, pd, 'runs', `${runId}.json`);
        if (fs.existsSync(runFile)) {
          const existing: SmartPathRun = JSON.parse(fs.readFileSync(runFile, 'utf-8'));
          fs.writeFileSync(runFile, JSON.stringify({ ...existing, ...updates }, null, 2), 'utf-8');
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
    const runs: SmartPathRun[] = [];
    for (const file of fs.readdirSync(runsDir).filter(f => f.endsWith('.json'))) {
      try { runs.push(JSON.parse(fs.readFileSync(path.join(runsDir, file), 'utf-8'))); } catch {}
    }
    return runs.sort((a, b) => b.startedAt.localeCompare(a.startedAt));
  }
}
