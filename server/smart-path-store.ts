/**
 * SmartPathStore — 地球路径存储
 *
 * 所有方法都通过 workspace 参数定位文件，不依赖 basePath 遍历。
 * 新存储结构：{workspace}/.sman/paths/{pathId}/path.md + runs/ + reports/ + references/
 */
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import matter from 'gray-matter';
import { createLogger, type Logger } from './utils/logger.js';
import type { SmartPath, SmartPathStep, SmartPathRun, SmartPathReference } from './types.js';

/** 生成 8 位随机 ID（大小写字母+数字） */
function generateId(): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let id = '';
  const bytes = crypto.randomBytes(8);
  for (let i = 0; i < 8; i++) id += chars[bytes[i] % chars.length];
  return id;
}

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

  /** {workspace}/.sman/paths/{pathId}/ — 路径根目录 */
  private pathDir(ws: string, id: string): string {
    return path.join(this.dir(ws), id);
  }

  /** {workspace}/.sman/paths/{pathId}/path.md — 新位置 */
  private pathFile(ws: string, id: string): string {
    return path.join(this.pathDir(ws, id), 'path.md');
  }

  /** {workspace}/.sman/paths/{id}.md — 旧位置（用于迁移） */
  private legacyFile(ws: string, id: string): string {
    return path.join(this.dir(ws), `${id}.md`);
  }

  /** {workspace}/.sman/paths/{pathId}/runs/ */
  private runsDir(ws: string, pathId: string): string {
    const d = path.join(this.pathDir(ws, pathId), 'runs');
    fs.mkdirSync(d, { recursive: true });
    return d;
  }

  /** {workspace}/.sman/paths/{pathId}/reports/ */
  private reportsDir(ws: string, pathId: string): string {
    const d = path.join(this.pathDir(ws, pathId), 'reports');
    fs.mkdirSync(d, { recursive: true });
    return d;
  }

  /** {workspace}/.sman/paths/{pathId}/references/ */
  private referencesDir(ws: string, pathId: string): string {
    const d = path.join(this.pathDir(ws, pathId), 'references');
    fs.mkdirSync(d, { recursive: true });
    return d;
  }

  /**
   * 生成路径 ID: {业务系统名}-{路径名}-{8位随机字符}
   */
  generatePathId(workspace: string, name: string): string {
    const project = path.basename(workspace).replace(/[^a-zA-Z0-9一-龥-_]/g, '');
    const safeName = name.replace(/[^a-zA-Z0-9一-龥-_]/g, '-').slice(0, 32);
    const suffix = generateId();
    return `${project}-${safeName}-${suffix}`;
  }

  // ── 迁移 ──

  /** 惰性迁移：旧 {id}.md → 新 {id}/path.md */
  private migrateIfNeeded(ws: string, id: string): void {
    const newPath = this.pathFile(ws, id);
    const oldPath = this.legacyFile(ws, id);

    if (fs.existsSync(newPath)) return;
    if (!fs.existsSync(oldPath)) return;

    const raw = fs.readFileSync(oldPath, 'utf-8');
    const { data, content } = matter(raw);

    // 剥离 executionResult（设计时和运行时解耦）
    if (data.steps && Array.isArray(data.steps)) {
      data.steps = data.steps.map((s: Record<string, unknown>) => {
        const { executionResult, ...designOnly } = s;
        return designOnly;
      });
    }

    fs.mkdirSync(path.dirname(newPath), { recursive: true });
    fs.writeFileSync(newPath, matter.stringify(content, data), 'utf-8');

    // 确保 references 目录存在
    this.referencesDir(ws, id);

    // 初始化 run.md
    const runMd = path.join(this.referencesDir(ws, id), 'run.md');
    if (!fs.existsSync(runMd)) {
      fs.writeFileSync(runMd, '# 复用指南\n\n> 首次执行后将自动维护此文件\n', 'utf-8');
    }

    fs.unlinkSync(oldPath);
    this.log.info(`Migrated path ${id} to new structure`);
  }

  // ── 文件读写 ──

  /**
   * 从文件路径反推当前系统的实际 workspace 路径。
   * 文件路径格式: {ws}/.sman/paths/{id}/path.md → 向上 3 级得到 ws
   * 支持跨平台：无论 front matter 中存储的是 Windows/Linux/macOS 路径，
   * 始终返回当前系统上文件实际所在的 workspace 路径。
   */
  private resolveActualWorkspace(filePath: string): string {
    // filePath = {ws}/.sman/paths/{id}/path.md → dirname = {ws}/.sman/paths/{id}
    const idDir = path.dirname(filePath);
    // → dirname = {ws}/.sman/paths
    const pathsDir = path.dirname(idDir);
    // → dirname = {ws}/.sman
    const smanDir = path.dirname(pathsDir);
    // → dirname = {ws}
    return path.dirname(smanDir);
  }

  private read(filePath: string): SmartPath {
    const raw = fs.readFileSync(filePath, 'utf-8');
    const { data } = matter(raw);
    const actualWs = this.resolveActualWorkspace(filePath);
    return {
      id: path.basename(path.dirname(filePath)),
      name: data.name || '',
      description: data.description || '',
      workspace: actualWs,
      steps: data.steps ? JSON.stringify(data.steps) : '[]',
      status: data.status || 'draft',
      cronExpression: data.cron_expression || '',
      defaultArgs: data.default_args || '',
      createdAt: data.created_at || new Date().toISOString(),
      updatedAt: data.updated_at || data.created_at || new Date().toISOString(),
    };
  }

  private write(p: SmartPath): void {
    if (!p.name?.trim()) throw new Error('Plan name is required');
    if (!p.workspace) throw new Error('Workspace is required');
    const steps = (() => { try { return JSON.parse(p.steps); } catch { return []; } })();
    // 使用类似 SKILL.md 的 YAML front matter 格式
    const frontmatter = {
      name: p.name,
      description: p.description || '',
      workspace: p.workspace,
      created_at: p.createdAt,
      updated_at: p.updatedAt || p.createdAt,
      status: p.status,
      cron_expression: p.cronExpression || '',
      default_args: p.defaultArgs || '',
      steps,
    };
    const contentBody = `# ${p.name}\n\n${p.description || ''}\n`;
    const content = matter.stringify(contentBody, frontmatter);
    const filePath = this.pathFile(p.workspace, p.id);
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, content, 'utf-8');
    this.log.info(`Saved: ${filePath}`);
  }

  // ── CRUD ──

  list(ws: string): SmartPath[] {
    const d = this.dir(ws);
    if (!fs.existsSync(d)) return [];

    const results: SmartPath[] = [];
    const entries = fs.readdirSync(d);

    for (const entry of entries) {
      if (entry.endsWith('.md')) {
        // 旧结构: {id}.md
        const id = path.basename(entry, '.md');
        this.migrateIfNeeded(ws, id);
        try { results.push(this.read(this.pathFile(ws, id))); } catch { /* skip */ }
      } else if (fs.statSync(path.join(d, entry)).isDirectory()) {
        // 新结构: {id}/path.md
        const subPath = path.join(d, entry, 'path.md');
        if (fs.existsSync(subPath)) {
          try { results.push(this.read(subPath)); } catch { /* skip */ }
        }
      }
    }

    return results.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  }

  listAll(workspaces: string[]): SmartPath[] {
    return workspaces.flatMap(ws => this.list(ws))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  /**
   * 跨 workspace 查找路径：先用传入的 ws 定位，找不到则遍历所有 workspace。
   * 解决跨平台场景（如 Windows 创建的路径在 macOS 上编辑）。
   */
  private findInWorkspaces<T>(id: string, ws: string, workspaces: string[], fn: (resolvedWs: string) => T | undefined): T | undefined {
    // 1. 先用传入的 ws 直接查找
    this.migrateIfNeeded(ws, id);
    const result = fn(ws);
    if (result !== undefined) return result;

    // 2. 回退：遍历所有 workspace 查找
    for (const altWs of workspaces) {
      if (altWs === ws) continue;
      this.migrateIfNeeded(altWs, id);
      const altResult = fn(altWs);
      if (altResult !== undefined) return altResult;
    }
    return undefined;
  }

  get(id: string, ws: string, workspaces?: string[]): SmartPath | undefined {
    if (workspaces && workspaces.length > 0) {
      return this.findInWorkspaces(id, ws, workspaces, (resolvedWs) => {
        const f = this.pathFile(resolvedWs, id);
        return fs.existsSync(f) ? this.read(f) : undefined;
      });
    }
    this.migrateIfNeeded(ws, id);
    const f = this.pathFile(ws, id);
    return fs.existsSync(f) ? this.read(f) : undefined;
  }

  create(input: { name: string; description?: string; workspace: string; steps: string }): SmartPath {
    if (!input.name?.trim()) throw new Error('Missing name');
    if (!input.workspace?.trim()) throw new Error('Missing workspace');
    if (input.steps === undefined || input.steps === null) throw new Error('Missing steps');
    const now = new Date().toISOString();
    const id = this.generatePathId(input.workspace, input.name);
    const p: SmartPath = {
      id,
      name: input.name,
      description: input.description ?? '',
      workspace: input.workspace,
      steps: input.steps,
      status: 'draft',
      createdAt: now,
      updatedAt: now,
    };
    this.write(p);
    // 确保 references 目录存在
    this.referencesDir(input.workspace, id);
    return p;
  }

  update(id: string, ws: string, updates: Partial<SmartPath>, workspaces?: string[]): SmartPath {
    const existing = this.get(id, ws, workspaces);
    if (!existing) throw new Error(`Path not found: ${id}`);
    // 使用路径实际存储的 workspace（解决跨平台路径不一致问题）
    const actualWs = existing.workspace;
    const merged = { ...existing, ...updates, updatedAt: new Date().toISOString() };

    // 名称变更 → 迁移整个目录
    if (updates.name && updates.name !== existing.name) {
      const newId = this.generatePathId(actualWs, updates.name);
      merged.id = newId;

      const oldDir = this.pathDir(actualWs, id);
      const newDir = this.pathDir(actualWs, newId);
      if (fs.existsSync(oldDir)) {
        fs.mkdirSync(path.dirname(newDir), { recursive: true });
        fs.renameSync(oldDir, newDir);
      }

      // 重命名目录内的 path.md 内容
      this.write({ ...merged, id: newId });

      // 清理旧平铺文件
      const oldFile = this.legacyFile(actualWs, id);
      if (fs.existsSync(oldFile)) fs.unlinkSync(oldFile);

      return merged;
    }

    this.write(merged);
    return merged;
  }

  del(id: string, ws: string, workspaces?: string[]): void {
    // 尝试在传入的 ws 或备选 workspace 中定位路径
    const found = this.get(id, ws, workspaces);
    if (!found) throw new Error(`Path not found: ${id}`);
    const actualWs = found.workspace;

    const subDir = this.pathDir(actualWs, id);
    const legacyF = this.legacyFile(actualWs, id);

    // 删除整个 {id}/ 目录
    if (fs.existsSync(subDir)) fs.rmSync(subDir, { recursive: true });

    // 清理旧平铺文件
    if (fs.existsSync(legacyF)) fs.unlinkSync(legacyF);

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
    this.cleanupOldRuns(ws, pathId);
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

  // ── Reports ──

  createReport(ws: string, pathId: string, pathName: string, steps: SmartPathStep[], stepResults: string[], runId: string, status: string, startedAt: string, finishedAt?: string): string {
    const lines: string[] = [];
    lines.push(`# 执行报告：${pathName}`);
    lines.push('');
    lines.push(`| 项目 | 值 |`);
    lines.push(`|------|-----|`);
    lines.push(`| 路径 | ${pathName} |`);
    lines.push(`| 状态 | ${status === 'completed' ? '✅ 成功' : '❌ 失败'} |`);
    lines.push(`| 开始时间 | ${new Date(startedAt).toLocaleString('zh-CN')} |`);
    if (finishedAt) lines.push(`| 结束时间 | ${new Date(finishedAt).toLocaleString('zh-CN')} |`);
    lines.push('');
    lines.push('---');
    lines.push('');

    for (let i = 0; i < steps.length; i++) {
      const s = steps[i];
      lines.push(`## 步骤 ${i + 1}${s.name ? `：${s.name}` : ''}`);
      lines.push('');
      lines.push(`**输入**：${s.userInput}`);
      lines.push('');
      const result = stepResults[i];
      if (result) {
        lines.push('**执行结果**：');
        lines.push('');
        lines.push(result);
        lines.push('');
      }
      lines.push('---');
      lines.push('');
    }

    const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const reportFileName = `report-${timestamp}.md`;
    const reportPath = path.join(this.reportsDir(ws, pathId), reportFileName);
    fs.writeFileSync(reportPath, lines.join('\n'), 'utf-8');
    this.log.info(`Report saved: ${reportPath}`);
    return reportPath;
  }

  getReport(ws: string, pathId: string, reportFileName: string): string | null {
    const f = path.join(this.reportsDir(ws, pathId), reportFileName);
    if (!fs.existsSync(f)) return null;
    return fs.readFileSync(f, 'utf-8');
  }

  listReports(pathId: string, ws: string): Array<{ fileName: string; createdAt: string }> {
    const rd = this.reportsDir(ws, pathId);
    if (!fs.existsSync(rd)) return [];
    return fs.readdirSync(rd)
      .filter(f => f.startsWith('report-') && f.endsWith('.md'))
      .map(f => ({
        fileName: f,
        createdAt: fs.statSync(path.join(rd, f)).mtime.toISOString(),
      }))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  // ── References ──

  listReferences(ws: string, pathId: string): SmartPathReference[] {
    const rd = this.referencesDir(ws, pathId);
    if (!fs.existsSync(rd)) return [];
    return fs.readdirSync(rd)
      .filter(f => !f.startsWith('.'))
      .map(f => ({
        fileName: f,
        updatedAt: fs.statSync(path.join(rd, f)).mtime.toISOString(),
      }))
      .sort((a, b) => a.fileName.localeCompare(b.fileName));
  }

  getReference(ws: string, pathId: string, fileName: string): string | null {
    const f = path.join(this.referencesDir(ws, pathId), fileName);
    if (!fs.existsSync(f)) return null;
    return fs.readFileSync(f, 'utf-8');
  }

  saveReference(ws: string, pathId: string, fileName: string, content: string): void {
    const f = path.join(this.referencesDir(ws, pathId), fileName);
    fs.writeFileSync(f, content, 'utf-8');
    this.log.info(`Reference saved: ${f}`);
  }

  getRunGuide(ws: string, pathId: string): string | null {
    return this.getReference(ws, pathId, 'run.md');
  }

  updateRunGuide(ws: string, pathId: string, content: string): void {
    this.saveReference(ws, pathId, 'run.md', content);
  }

  /** 清空并重建 tmp/ 目录 */
  clearTmpDir(ws: string, pathId: string): void {
    const tmpDir = path.join(this.pathDir(ws, pathId), 'tmp');
    if (fs.existsSync(tmpDir)) {
      fs.rmSync(tmpDir, { recursive: true });
    }
    fs.mkdirSync(tmpDir, { recursive: true });
    this.log.info(`Cleared tmp dir: ${tmpDir}`);
  }

  /** 只保留最近 MAX_RUNS 次执行记录，删除更早的 runs 和对应 reports */
  private cleanupOldRuns(ws: string, pathId: string, maxRuns = 5): void {
    try {
      const rd = this.runsDir(ws, pathId);
      if (!fs.existsSync(rd)) return;
      const runs = fs.readdirSync(rd)
        .filter(f => f.endsWith('.json'))
        .map(f => {
          try {
            const r = JSON.parse(fs.readFileSync(path.join(rd, f), 'utf-8'));
            return { file: f, startedAt: r.startedAt || '', reportFileName: r.reportFileName || '' };
          } catch { return null; }
        })
        .filter((r): r is { file: string; startedAt: string; reportFileName: string } => r !== null)
        .sort((a, b) => b.startedAt.localeCompare(a.startedAt));

      if (runs.length <= maxRuns) return;

      for (let i = maxRuns; i < runs.length; i++) {
        const runFile = path.join(rd, runs[i].file);
        fs.unlinkSync(runFile);
        if (runs[i].reportFileName) {
          const reportFile = path.join(this.reportsDir(ws, pathId), runs[i].reportFileName);
          if (fs.existsSync(reportFile)) fs.unlinkSync(reportFile);
        }
        this.log.info(`Cleaned up old run: ${runs[i].file}`);
      }
    } catch (err) {
      this.log.warn(`Failed to cleanup old runs: ${err}`);
    }
  }
}
