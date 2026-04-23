/**
 * SmartPathStore — 地球路径存储
 *
 * 所有方法都通过 workspace 参数定位文件，不依赖 basePath 遍历。
 * 文件存储在 {workspace}/.sman/paths/{id}.md
 */
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import matter from 'gray-matter';
import { createLogger, type Logger } from './utils/logger.js';
import type { SmartPath, SmartPathStep, SmartPathRun } from './types.js';

export class SmartPathStore {
  private log: Logger;

  constructor() {
    this.log = createLogger('SmartPathStore');
  }

  // ── 路径工具 ──

  /** {workspace}/.sman/paths/ */
  private dir(ws: string): string {
    const d = path.join(ws, '.sman', 'paths');
    fs.mkdirSync(d, { recursive: true });
    return d;
  }

  /** {workspace}/.sman/paths/{id}.md */
  private file(ws: string, id: string): string {
    return path.join(this.dir(ws), `${id}.md`);
  }

  /** {workspace}/.sman/paths/{pathId}/runs/ */
  private runsDir(ws: string, pathId: string): string {
    const d = path.join(this.dir(ws), pathId, 'runs');
    fs.mkdirSync(d, { recursive: true });
    return d;
  }

  // ── 文件读写 ──

  private read(filePath: string): SmartPath {
    const raw = fs.readFileSync(filePath, 'utf-8');
    const { data } = matter(raw);
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

  private write(p: SmartPath): void {
    if (!p.name?.trim()) throw new Error('Plan name is required');
    if (!p.workspace) throw new Error('Workspace is required');
    const steps = (() => { try { return JSON.parse(p.steps); } catch { return []; } })();
    const content = matter.stringify(
      `# ${p.name}\n\n${(p as any).description || ''}\n`,
      {
        name: p.name,
        description: (p as any).description || '',
        workspace: p.workspace,
        created_at: p.createdAt,
        updated_at: p.updatedAt || p.createdAt,
        status: p.status,
        steps,
      },
    );
    const filePath = this.file(p.workspace, p.id);
    fs.writeFileSync(filePath, content, 'utf-8');
    this.log.info(`Saved: ${filePath}`);
  }

  // ── CRUD ──

  /** 列出指定 workspace 下的所有路径 */
  list(ws: string): SmartPath[] {
    const d = this.dir(ws);
    if (!fs.existsSync(d)) return [];
    return fs.readdirSync(d)
      .filter(f => f.endsWith('.md'))
      .map(f => { try { return this.read(path.join(d, f)); } catch { return null; } })
      .filter((p): p is SmartPath => p !== null)
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  }

  /** 列出多个 workspace 下的所有路径 */
  listAll(workspaces: string[]): SmartPath[] {
    return workspaces.flatMap(ws => this.list(ws))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  /** 获取单个路径，workspace 必传 */
  get(id: string, ws: string): SmartPath | undefined {
    const f = this.file(ws, id);
    return fs.existsSync(f) ? this.read(f) : undefined;
  }

  /** 创建路径 */
  create(input: { name: string; workspace: string; steps: string }): SmartPath {
    if (!input.name?.trim()) throw new Error('Missing name');
    if (!input.workspace?.trim()) throw new Error('Missing workspace');
    if (input.steps === undefined || input.steps === null) throw new Error('Missing steps');
    const now = new Date().toISOString();
    const p: SmartPath = {
      id: crypto.randomUUID(),
      name: input.name,
      workspace: input.workspace,
      steps: input.steps,
      status: 'draft',
      createdAt: now,
      updatedAt: now,
    };
    this.write(p);
    return p;
  }

  /** 更新路径 */
  update(id: string, ws: string, updates: Partial<SmartPath>): SmartPath {
    const existing = this.get(id, ws);
    if (!existing) throw new Error(`Path not found: ${id}`);
    const merged = { ...existing, ...updates, updatedAt: new Date().toISOString() };
    this.write(merged);
    return merged;
  }

  /** 删除路径 */
  del(id: string, ws: string): void {
    const f = this.file(ws, id);
    if (!fs.existsSync(f)) throw new Error(`Path not found: ${id}`);
    fs.unlinkSync(f);
    // 清理 runs
    const rd = this.runsDir(ws, id);
    if (fs.existsSync(rd)) fs.rmSync(rd, { recursive: true });
    this.log.info(`Deleted: ${id}`);
  }

  // ── Runs ──

  createRun(pathId: string, ws: string): SmartPathRun {
    const run: SmartPathRun = {
      id: crypto.randomUUID(),
      pathId,
      status: 'running',
      stepResults: '{}',
      startedAt: new Date().toISOString(),
    };
    fs.writeFileSync(
      path.join(this.runsDir(ws, pathId), `${run.id}.json`),
      JSON.stringify(run, null, 2), 'utf-8',
    );
    return run;
  }

  updateRun(runId: string, ws: string, pathId: string, updates: Partial<SmartPathRun>): void {
    const f = path.join(this.runsDir(ws, pathId), `${runId}.json`);
    if (!fs.existsSync(f)) throw new Error(`Run not found: ${runId}`);
    const existing: SmartPathRun = JSON.parse(fs.readFileSync(f, 'utf-8'));
    fs.writeFileSync(f, JSON.stringify({ ...existing, ...updates }, null, 2), 'utf-8');
  }

  listRuns(pathId: string, ws: string): SmartPathRun[] {
    const rd = this.runsDir(ws, pathId);
    if (!fs.existsSync(rd)) return [];
    return fs.readdirSync(rd)
      .filter(f => f.endsWith('.json'))
      .map(f => { try { return JSON.parse(fs.readFileSync(path.join(rd, f), 'utf-8')); } catch { return null; } })
      .filter((r): r is SmartPathRun => r !== null)
      .sort((a, b) => b.startedAt.localeCompare(a.startedAt));
  }
}
