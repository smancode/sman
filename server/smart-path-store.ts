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

  /**
   * 生成路径 ID: {业务系统名}-{路径名}-{8位随机字符}
   * 业务系统名取 workspace basename，路径名做 sanitize
   */
  generatePathId(workspace: string, name: string): string {
    const project = path.basename(workspace).replace(/[^a-zA-Z0-9一-龥-_]/g, '');
    const safeName = name.replace(/[^a-zA-Z0-9一-龥-_]/g, '-').slice(0, 32);
    const suffix = generateId();
    return `${project}-${safeName}-${suffix}`;
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
      cronExpression: data.cron_expression || '',
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
        cron_expression: (p as any).cronExpression || '',
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
    const id = this.generatePathId(input.workspace, input.name);
    const p: SmartPath = {
      id,
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

  /** 更新路径（名称变更时同步迁移文件） */
  update(id: string, ws: string, updates: Partial<SmartPath>): SmartPath {
    const existing = this.get(id, ws);
    if (!existing) throw new Error(`Path not found: ${id}`);
    const merged = { ...existing, ...updates, updatedAt: new Date().toISOString() };

    // 如果名称变更了，需要迁移文件
    if (updates.name && updates.name !== existing.name) {
      const oldFile = this.file(ws, id);
      const newId = this.generatePathId(ws, updates.name);
      merged.id = newId;
      // 写新文件
      this.write(merged);
      // 迁移 runs 目录
      const oldRunsDir = path.join(this.dir(ws), id, 'runs');
      const newRunsDir = path.join(this.dir(ws), newId, 'runs');
      if (fs.existsSync(oldRunsDir)) {
        fs.mkdirSync(path.dirname(newRunsDir), { recursive: true });
        fs.renameSync(oldRunsDir, newRunsDir);
      }
      // 迁移 reports 目录
      const oldReportsDir = path.join(this.dir(ws), id, 'reports');
      const newReportsDir = path.join(this.dir(ws), newId, 'reports');
      if (fs.existsSync(oldReportsDir)) {
        fs.mkdirSync(path.dirname(newReportsDir), { recursive: true });
        fs.renameSync(oldReportsDir, newReportsDir);
      }
      // 删旧文件和旧目录
      const oldDir = path.join(this.dir(ws), id);
      if (fs.existsSync(oldFile)) fs.unlinkSync(oldFile);
      if (fs.existsSync(oldDir) && fs.readdirSync(oldDir).length === 0) fs.rmdirSync(oldDir);
      return merged;
    }

    this.write(merged);
    return merged;
  }

  /** 删除路径 */
  del(id: string, ws: string): void {
    const f = this.file(ws, id);
    if (!fs.existsSync(f)) throw new Error(`Path not found: ${id}`);
    fs.unlinkSync(f);
    // 清理子目录（runs, reports）
    const subDir = path.join(this.dir(ws), id);
    if (fs.existsSync(subDir)) fs.rmSync(subDir, { recursive: true });
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

  // ── Reports ──

  /** {workspace}/.sman/paths/{pathId}/reports/ */
  private reportsDir(ws: string, pathId: string): string {
    const d = path.join(this.dir(ws), pathId, 'reports');
    fs.mkdirSync(d, { recursive: true });
    return d;
  }

  /** 生成执行报告 MD 文件 */
  createReport(ws: string, pathId: string, pathName: string, steps: SmartPathStep[], runId: string, status: string, startedAt: string, finishedAt?: string): string {
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
      if (s.executionResult) {
        lines.push('**执行结果**：');
        lines.push('');
        lines.push(s.executionResult);
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

  /** 获取报告内容 */
  getReport(ws: string, pathId: string, reportFileName: string): string | null {
    const f = path.join(this.reportsDir(ws, pathId), reportFileName);
    if (!fs.existsSync(f)) return null;
    return fs.readFileSync(f, 'utf-8');
  }

  /** 列出所有报告 */
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
}
