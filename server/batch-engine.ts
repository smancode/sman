import { execFile } from 'child_process';
import { promisify } from 'util';
import path from 'path';
import fs from 'fs';
import os from 'os';
import { createLogger, type Logger } from './utils/logger';
import type { BatchStore } from './batch-store';
import type { ClaudeSessionManager } from './claude-session';
import { Semaphore, SemaphoreStoppedError } from './semaphore';
import { renderTemplate, detectInterpreter } from './batch-utils';

const execFileAsync = promisify(execFile);

const MAX_ITEMS = 100_000;
const TEST_TIMEOUT_MS = 30_000;
const MAX_BUFFER = 10 * 1024 * 1024; // 10MB
const DRAIN_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes

function isoNow(): string {
  return new Date().toISOString();
}

function extractTextFromMessage(msg: any): string {
  if (msg.type === 'assistant') {
    const content = msg.message?.content;
    if (Array.isArray(content)) {
      return content
        .filter((block: any) => block.type === 'text')
        .map((block: any) => block.text)
        .join('');
    }
  }
  if (msg.type === 'stream_event') {
    return msg.event?.delta?.type === 'text_delta' ? msg.event.delta.text : '';
  }
  return '';
}

export class BatchEngine {
  private log: Logger;
  private activeExecutions = new Map<string, {
    semaphore: Semaphore;
    cancelled: boolean;
  }>();
  private _sessionManager: ClaudeSessionManager | null = null;
  private _config: { apiKey: string; model: string; baseUrl?: string } | null = null;
  private onProgressCallback: ((taskId: string, data: object) => void) | null = null;

  constructor(private store: BatchStore) {
    this.log = createLogger('BatchEngine');
  }

  setSessionManager(sm: ClaudeSessionManager): void {
    this._sessionManager = sm;
  }

  setConfig(config: { apiKey: string; model: string; baseUrl?: string }): void {
    this._config = config;
  }

  private get config(): { apiKey: string; model: string; baseUrl?: string } {
    if (!this._config) throw new Error('Config not set');
    return this._config;
  }

  private get sessionManager(): ClaudeSessionManager {
    if (!this._sessionManager) throw new Error('SessionManager not set');
    return this._sessionManager;
  }

  setOnProgress(callback: (taskId: string, data: object) => void): void {
    this.onProgressCallback = callback;
  }

  private emitProgress(taskId: string): void {
    if (!this.onProgressCallback) return;
    const task = this.store.getTask(taskId);
    if (!task) return;
    this.onProgressCallback(taskId, {
      successCount: task.successCount,
      failedCount: task.failedCount,
      totalItems: task.totalItems,
      totalCost: task.totalCost,
    });
  }

  // === Code Generation ===

  async generateCode(taskId: string): Promise<string> {
    const task = this.store.getTask(taskId);
    if (!task) throw new Error(`Task not found: ${taskId}`);

    this.store.updateTask(taskId, { status: 'generating' });

    const envKeys = Object.keys(JSON.parse(task.envVars));
    const prompt = [
      '# Batch Data Fetch Script Generator',
      '',
      "## User's batch.md configuration:",
      task.mdContent,
      '',
      '## Environment variable keys (values will be provided at runtime):',
      ...envKeys.map(k => `- ${k}`),
      '',
      '## Instructions:',
      '- Generate a self-contained script that fetches the data described in batch.md',
      '- Use the environment variables listed above for connections',
      '- Output a JSON array to stdout (nothing else to stdout)',
      '- The script should be executable with the appropriate interpreter',
      '- Do not include any interactive input prompts',
    ].join('\n');

    try {
      const { query } = await import('@anthropic-ai/claude-agent-sdk');

      // Build env with API key and base URL (same pattern as ClaudeSessionManager)
      const env: Record<string, string | undefined> = { ...process.env as Record<string, string | undefined> };
      if (this.config.apiKey) {
        env['ANTHROPIC_API_KEY'] = this.config.apiKey;
      }
      if (this.config.baseUrl) {
        env['ANTHROPIC_BASE_URL'] = this.config.baseUrl;
      }

      const sdkOptions: Record<string, unknown> = {
        cwd: task.workspace,
        model: this.config.model,
        permissionMode: 'bypassPermissions' as const,
        allowDangerouslySkipPermissions: true,
        env,
        systemPrompt: {
          type: 'preset' as const,
          preset: 'claude_code' as const,
          append: 'You are a data fetching script generator. Generate ONLY the script code, wrapped in a code block. The output must be a JSON array printed to stdout.',
        },
      };
      const q = query({
        prompt,
        options: sdkOptions as any,
      });

      let fullText = '';
      for await (const msg of q) {
        fullText += extractTextFromMessage(msg);
      }

      const codeMatch = fullText.match(/```(?:python|javascript|node|js|sh|bash)?\n([\s\S]*?)```/);
      if (!codeMatch) throw new Error('No code block found in Claude response');

      const code = codeMatch[1].trim();
      this.store.updateTask(taskId, { status: 'generated', generatedCode: code });
      this.log.info(`Code generated for task ${taskId}, length: ${code.length}`);
      return code;
    } catch (err) {
      this.store.updateTask(taskId, { status: 'draft' });
      throw err;
    }
  }

  // === Test ===

  async testCode(taskId: string): Promise<{ items: Record<string, unknown>[]; preview: string }> {
    const task = this.store.getTask(taskId);
    if (!task) throw new Error(`Task not found: ${taskId}`);
    if (!task.generatedCode) throw new Error('No generated code for task');

    this.store.updateTask(taskId, { status: 'testing' });

    const interpreter = detectInterpreter(task.generatedCode);
    const envVars = JSON.parse(task.envVars) as Record<string, string>;

    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), `batch-test-${taskId}-`));
    const extMap: Record<string, string> = { python3: 'py', node: 'js', bash: 'sh' };
    const scriptPath = path.join(tmpDir, `fetch.${extMap[interpreter] || 'sh'}`);
    fs.writeFileSync(scriptPath, task.generatedCode);

    try {
      const { stdout } = await execFileAsync(interpreter, [scriptPath], {
        cwd: tmpDir,
        timeout: TEST_TIMEOUT_MS,
        env: { ...process.env as Record<string, string>, ...envVars },
        maxBuffer: MAX_BUFFER,
      });

      const items = JSON.parse(stdout.trim());
      if (!Array.isArray(items)) throw new Error('Output is not a JSON array');
      if (items.length > MAX_ITEMS) throw new Error(`数据量过大: ${items.length} 条，上限 ${MAX_ITEMS} 条`);

      this.store.updateTask(taskId, { status: 'tested' });

      return {
        items,
        preview: items.slice(0, 10).map(i => JSON.stringify(i)).join('\n'),
      };
    } catch (err) {
      this.store.updateTask(taskId, { status: 'generated' });
      throw err;
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  }

  // === Save ===

  async save(taskId: string): Promise<void> {
    const task = this.store.getTask(taskId);
    if (!task) throw new Error(`Task not found: ${taskId}`);
    if (!task.generatedCode) throw new Error('No generated code to save');

    this.store.updateTask(taskId, { status: 'saved' });
    this.log.info(`Batch task ${taskId} saved`);
  }

  // === Execute ===

  async execute(taskId: string): Promise<void> {
    await this.executeInternal(taskId, true);
  }

  private async executeInternal(taskId: string, resetAll: boolean): Promise<void> {
    const task = this.store.getTask(taskId);
    if (!task) throw new Error(`Task not found: ${taskId}`);
    if (task.status !== 'saved') throw new Error(`Task ${taskId} is not in saved status (current: ${task.status})`);

    // Reset counters
    this.store.updateTask(taskId, {
      status: 'running',
      successCount: 0,
      failedCount: 0,
      totalCost: 0,
      startedAt: isoNow(),
      finishedAt: undefined,
    });

    // Reset items
    if (resetAll) {
      this.store.resetItemsForExecution(taskId);
    }

    const pendingItems = this.store.listItems(taskId);
    if (pendingItems.length === 0) {
      this.store.updateTask(taskId, { status: 'completed', finishedAt: isoNow() });
      return;
    }

    const semaphore = new Semaphore(task.concurrency);
    const exec = { semaphore, cancelled: false };
    this.activeExecutions.set(taskId, exec);

    const processItem = async (item: any) => {
      if (exec.cancelled) {
        this.store.updateItem(item.id, { status: 'skipped' });
        return;
      }

      const sessionId = `batch-${task.id}-${item.id}`;
      this.store.updateItem(item.id, { status: 'running', startedAt: isoNow(), sessionId });

      const itemData = JSON.parse(item.itemData);
      const prompt = renderTemplate(task.execTemplate, itemData);
      const abortController = new AbortController();

      try {
        this.sessionManager.createSessionWithId(task.workspace, sessionId);
        await this.sessionManager.sendMessageForCron(sessionId, prompt, abortController, () => {});
        this.store.updateItem(item.id, { status: 'success', finishedAt: isoNow() });
        this.store.incrementSuccessCount(taskId);
      } catch (err) {
        const errorMsg = err instanceof Error ? err.message : String(err);
        this.store.updateItem(item.id, {
          status: 'failed',
          errorMessage: errorMsg,
          finishedAt: isoNow(),
        });
        this.store.incrementFailedCount(taskId);
      } finally {
        this.emitProgress(taskId);
      }
    };

    try {
      for (const item of pendingItems) {
        if (exec.cancelled) {
          this.store.updateItem(item.id, { status: 'skipped' });
          continue;
        }

        try {
          await semaphore.acquire();
        } catch (err) {
          if (err instanceof SemaphoreStoppedError) {
            this.store.updateItem(item.id, { status: 'skipped' });
            continue;
          }
          throw err;
        }

        processItem(item).finally(() => semaphore.release());
      }

      await this.drainActiveItems(taskId);
    } finally {
      this.activeExecutions.delete(taskId);

      const finalTask = this.store.getTask(taskId)!;
      this.store.updateTask(taskId, {
        finishedAt: isoNow(),
        status: exec.cancelled ? 'failed' : 'completed',
      });
    }
  }

  private async drainActiveItems(taskId: string): Promise<void> {
    const start = Date.now();
    while (true) {
      const counts = this.store.getItemCounts(taskId);
      if (counts.running === 0) break;
      if (Date.now() - start > DRAIN_TIMEOUT_MS) {
        this.log.warn(`drainActiveItems timeout for ${taskId}, forcing completion`);
        break;
      }
      await new Promise(r => setTimeout(r, 200));
    }
  }

  // === Control ===

  pause(taskId: string): void {
    const exec = this.activeExecutions.get(taskId);
    if (!exec) return;
    exec.semaphore.pause();
    this.store.updateTask(taskId, { status: 'paused' });
    this.log.info(`Batch task ${taskId} paused`);
  }

  async resume(taskId: string): Promise<void> {
    const task = this.store.getTask(taskId);
    if (!task || task.status !== 'paused') return;

    this.store.updateTask(taskId, { status: 'running' });

    const exec = this.activeExecutions.get(taskId);
    if (exec) {
      exec.semaphore.resume();
    }
    this.log.info(`Batch task ${taskId} resumed`);
  }

  cancel(taskId: string): void {
    const exec = this.activeExecutions.get(taskId);
    if (!exec) return;
    exec.cancelled = true;
    exec.semaphore.stop();

    // Abort all running item sessions
    const runningItems = this.store.listItems(taskId, { status: 'running' });
    for (const item of runningItems) {
      if (item.sessionId) {
        this.sessionManager.abort(item.sessionId);
      }
    }

    this.log.info(`Batch task ${taskId} cancelled, aborted ${runningItems.length} sessions`);
  }

  async retryFailed(taskId: string): Promise<void> {
    const task = this.store.getTask(taskId);
    if (!task) throw new Error(`Task not found: ${taskId}`);

    // Reset only failed items to pending, increment retries
    const failedItems = this.store.listItems(taskId, { status: 'failed' });
    for (const item of failedItems) {
      this.store.updateItem(item.id, {
        status: 'pending',
        errorMessage: undefined,
        retries: item.retries + 1,
      });
    }

    // Set status to saved so executeInternal passes status check
    this.store.updateTask(taskId, { status: 'saved' });

    // Execute without resetting all items (only failed ones were reset)
    await this.executeInternal(taskId, false);
  }

  // === Lifecycle ===

  start(): void {
    const orphaned = this.store.getOrphanedItems();
    if (orphaned.length > 0) {
      this.store.resetRunningItems('进程异常终止');
      this.log.warn(`Reset ${orphaned.length} orphaned items`);
    }
  }

  stop(): void {
    for (const [taskId, exec] of this.activeExecutions) {
      exec.cancelled = true;
      exec.semaphore.stop();
    }
  }

  close(): void {
    this.stop();
  }
}
