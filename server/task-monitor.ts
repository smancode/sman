/**
 * TaskMonitor - Scans workspace docs/tasks/*.json periodically, executes plans via Claude Agent SDK
 *
 * Simplified architecture for SmanBase:
 * - No GatewayRpc, uses callback-based notifications
 * - Uses Claude Agent SDK query() instead of spawning CLI
 * - File-based task management with process locking
 */

import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from './utils/logger.js';
import type { SmanConfig } from './types.js';
import { buildMcpServers } from './mcp-config.js';

interface TaskJson {
  id: string;
  workspace: string;
  status: 'pending' | 'in_progress' | 'completed' | 'failed';
  startedAt: string | null;
  completedAt: string | null;
  lastChecked: string | null;
  mdFile: string;
  claudePid: number | null;
}

interface TaskCheckResult {
  completed: string[];
  failed: string[];
  inProgress: string[];
}

export type TaskNotifyCallback = (message: { type: string; task: TaskJson; content: string }) => void;

export class TaskMonitor {
  private running = false;
  private intervalTimer: ReturnType<typeof setInterval> | null = null;
  private initialTimer: ReturnType<typeof setTimeout> | null = null;
  private log: Logger;
  private claudeLock = false;
  private runningTaskPid: number | null = null;
  private config: SmanConfig | null = null;

  constructor(
    private workspacePath: string,
    private intervalMs: number = 60000,
    private notify?: TaskNotifyCallback,
  ) {
    this.log = createLogger('TaskMonitor');
  }

  updateConfig(config: SmanConfig): void {
    this.config = config;
  }

  private buildQueryEnv(): Record<string, string | undefined> {
    const env: Record<string, string | undefined> = { ...process.env as Record<string, string | undefined> };
    if (this.config?.llm?.apiKey) {
      env['ANTHROPIC_API_KEY'] = this.config.llm.apiKey;
    }
    if (this.config?.llm?.baseUrl) {
      env['ANTHROPIC_BASE_URL'] = this.config.llm.baseUrl;
    }
    return env;
  }

  start(): void {
    this.log.info('Starting task monitor', { intervalMs: this.intervalMs, workspace: this.workspacePath });
    this.intervalTimer = setInterval(() => {
      this.check().catch((err) => {
        this.log.error('Check failed', { error: String(err) });
      });
    }, this.intervalMs);
    // Initial check after short delay
    this.initialTimer = setTimeout(() => {
      this.check().catch((err) => {
        this.log.error('Initial check failed', { error: String(err) });
      });
    }, 5000);
  }

  stop(): void {
    if (this.intervalTimer) { clearInterval(this.intervalTimer); this.intervalTimer = null; }
    if (this.initialTimer) { clearTimeout(this.initialTimer); this.initialTimer = null; }
    this.log.info('Task monitor stopped');
  }

  async check(): Promise<TaskCheckResult> {
    if (this.running) {
      this.log.debug('Check skipped: previous check still running');
      return { completed: [], failed: [], inProgress: [] };
    }

    this.running = true;
    const result: TaskCheckResult = { completed: [], failed: [], inProgress: [] };

    try {
      const tasksDir = path.join(this.workspacePath, 'docs', 'tasks');
      const donetasksDir = path.join(this.workspacePath, 'docs', 'donetasks');

      if (!fs.existsSync(tasksDir)) {
        return result;
      }

      fs.mkdirSync(donetasksDir, { recursive: true });

      const files = fs.readdirSync(tasksDir).filter(f => f.endsWith('.json') && f !== '.gitkeep');
      this.log.debug(`Found ${files.length} task file(s)`);

      if (files.length === 0) return result;

      for (const file of files) {
        try {
          if (this.claudeLock && this.isProcessAlive(this.runningTaskPid)) {
            this.log.debug(`Skipping ${file}: claude is running`);
            continue;
          }
          if (this.claudeLock) {
            this.releaseLock();
          }

          const jsonPath = path.join(tasksDir, file);
          const task = this.readTaskJson(jsonPath);

          if (!task.workspace) {
            this.log.warn(`No workspace defined for ${task.id}`);
            continue;
          }

          const mdPath = path.isAbsolute(task.mdFile)
            ? task.mdFile
            : path.join(task.workspace, task.mdFile);

          if (!fs.existsSync(mdPath)) {
            this.log.warn(`MD file not found: ${mdPath}`);
            continue;
          }

          const mdContent = fs.readFileSync(mdPath, 'utf-8');

          if (task.status === 'pending') {
            await this.triggerExecution(task, jsonPath, mdPath);
            result.inProgress.push(task.id);
          } else if (task.status === 'in_progress') {
            const { hasConclusion, isSuccess } = this.checkConclusion(mdContent);
            const processAlive = this.isProcessAlive(task.claudePid);

            if (hasConclusion) {
              if (isSuccess) {
                this.log.info(`Task completed: ${task.id}`);
                await this.completeTask(task, tasksDir, donetasksDir, file);
                result.completed.push(task.id);
              } else {
                this.log.info(`Task failed: ${task.id}`);
                await this.failTask(task, tasksDir, donetasksDir, file, mdContent);
                result.failed.push(task.id);
              }
            } else if (!processAlive) {
              this.log.info(`Claude process dead, triggering verification: ${task.id}`);
              await this.triggerVerification(task, jsonPath, mdPath);
              result.inProgress.push(task.id);
            } else {
              task.lastChecked = new Date().toISOString();
              fs.writeFileSync(jsonPath, JSON.stringify(task, null, 2), 'utf-8');
              result.inProgress.push(task.id);
            }
          }
        } catch (err) {
          this.log.error(`Error processing task: ${file}`, { error: String(err) });
        }
      }

      this.log.info('Check complete', result as unknown as Record<string, unknown>);
    } finally {
      this.running = false;
    }

    return result;
  }

  private readTaskJson(filePath: string): TaskJson {
    const content = fs.readFileSync(filePath, 'utf-8');
    const task = JSON.parse(content);
    if (!task.id || !task.mdFile) {
      throw new Error('Invalid task JSON: missing id or mdFile');
    }
    return task as TaskJson;
  }

  private isProcessAlive(pid: number | null): boolean {
    if (!pid) return false;
    try {
      process.kill(pid, 0);
      return true;
    } catch {
      return false;
    }
  }

  private releaseLock(): void {
    this.claudeLock = false;
    this.runningTaskPid = null;
  }

  private checkConclusion(mdContent: string): { hasConclusion: boolean; isSuccess: boolean } {
    const match = mdContent.match(/##\s*最终结论[\s\S]*?(?=\n## |\n# |$)/i);
    if (!match) return { hasConclusion: false, isSuccess: false };
    const text = match[0];
    const isSuccess = /成功|completed|✅/i.test(text) && !/失败|failed|❌/i.test(text);
    return { hasConclusion: true, isSuccess };
  }

  private async triggerExecution(
    task: TaskJson,
    jsonPath: string,
    mdPath: string,
  ): Promise<void> {
    this.claudeLock = true;
    this.runningTaskPid = null;

    const prompt = `读取并执行 ${mdPath}，充分利用 task 和 subagent 等能力，要求：1.逐步将完成的细项由[ ]改为[x] 2.在执行结果部分填写执行结果 3.在完成确认部分填写完成时间(格式:YYYY-MM-DD HH:MM)`;

    // Dynamic import to avoid hard dependency when not used
    const { query } = await import('@anthropic-ai/claude-agent-sdk');

    const abortController = new AbortController();
    const taskEnv = this.buildQueryEnv();
    const taskModel = this.config?.llm?.model;
    const taskMcpServers = this.config ? buildMcpServers(this.config) : undefined;

    const q = query({
      prompt,
      options: {
        cwd: task.workspace,
        abortController,
        permissionMode: 'bypassPermissions',
        allowDangerouslySkipPermissions: true,
        additionalDirectories: [path.join(task.workspace, 'docs')],
        env: taskEnv,
        ...(taskModel ? { model: taskModel } : {}),
        ...(taskMcpServers && Object.keys(taskMcpServers).length > 0 ? { mcpServers: taskMcpServers } : {}),
      },
    });

    // Consume the async generator in background
    (async () => {
      try {
        for await (const _msg of q) {
          if (abortController.signal.aborted) break;
        }
      } catch (err: any) {
        if (err?.name !== 'AbortError') {
          this.log.error('Task execution error', { error: String(err) });
        }
      } finally {
        setTimeout(() => this.releaseLock(), 5000);
      }
    })();

    this.runningTaskPid = process.pid;

    task.status = 'in_progress';
    task.lastChecked = new Date().toISOString();
    fs.writeFileSync(jsonPath, JSON.stringify(task, null, 2), 'utf-8');

    this.notify?.({
      type: 'task.started',
      task,
      content: `Task ${task.id} started`,
    });
  }

  private async triggerVerification(
    task: TaskJson,
    jsonPath: string,
    mdPath: string,
  ): Promise<void> {
    const verifyPrompt = `你是一个任务验收专家。请阅读并验收任务：${mdPath}

你的职责：
1. 读取 MD 文件，了解任务目标、验收标准、执行结果
2. 判断任务是否真正完成
3. 如果发现异常但可以修复，执行修复
4. 在 MD 文件的 ## 完成确认 章节后添加最终结论：

## 最终结论
状态：成功/失败
说明：...

注意：不要写太多废话，直接给出结论`;

    const { query } = await import('@anthropic-ai/claude-agent-sdk');

    const abortController = new AbortController();
    const taskEnv = this.buildQueryEnv();
    const taskModel = this.config?.llm?.model;

    const q = query({
      prompt: verifyPrompt,
      options: {
        cwd: task.workspace,
        abortController,
        permissionMode: 'bypassPermissions',
        allowDangerouslySkipPermissions: true,
        additionalDirectories: [task.workspace],
        env: taskEnv,
        ...(taskModel ? { model: taskModel } : {}),
      },
    });

    this.claudeLock = true;
    this.runningTaskPid = process.pid;

    task.claudePid = process.pid;
    fs.writeFileSync(jsonPath, JSON.stringify(task, null, 2), 'utf-8');

    (async () => {
      try {
        for await (const _msg of q) {
          if (abortController.signal.aborted) break;
        }
      } catch (err: any) {
        if (err?.name !== 'AbortError') {
          this.log.error('Verification error', { error: String(err) });
        }
      } finally {
        setTimeout(() => this.releaseLock(), 5000);
      }
    })();

    this.notify?.({
      type: 'task.verifying',
      task,
      content: `Verifying task ${task.id}`,
    });
  }

  private async completeTask(
    task: TaskJson,
    tasksDir: string,
    donetasksDir: string,
    file: string,
  ): Promise<void> {
    task.status = 'completed';
    task.completedAt = new Date().toISOString();
    task.lastChecked = new Date().toISOString();

    const srcJson = path.join(tasksDir, file);
    const dstJson = path.join(donetasksDir, file);
    fs.writeFileSync(dstJson, JSON.stringify(task, null, 2), 'utf-8');
    fs.unlinkSync(srcJson);

    this.notify?.({
      type: 'task.completed',
      task,
      content: `Task ${task.id} completed successfully`,
    });
  }

  private async failTask(
    task: TaskJson,
    tasksDir: string,
    donetasksDir: string,
    file: string,
    mdContent: string,
  ): Promise<void> {
    task.status = 'failed';
    task.completedAt = new Date().toISOString();
    task.lastChecked = new Date().toISOString();

    const srcJson = path.join(tasksDir, file);
    const dstJson = path.join(donetasksDir, file);
    fs.writeFileSync(dstJson, JSON.stringify(task, null, 2), 'utf-8');
    fs.unlinkSync(srcJson);

    this.notify?.({
      type: 'task.failed',
      task,
      content: `Task ${task.id} failed`,
    });
  }
}
