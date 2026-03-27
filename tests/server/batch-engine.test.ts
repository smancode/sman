import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { BatchEngine } from '../../server/batch-engine.js';
import { BatchStore } from '../../server/batch-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

// Mock ClaudeSessionManager
const mockSendMessageForCron = vi.fn();
const mockCreateSessionWithId = vi.fn();
const mockAbort = vi.fn();

const mockSessionManager = {
  sendMessageForCron: mockSendMessageForCron,
  createSessionWithId: mockCreateSessionWithId,
  abort: mockAbort,
  updateConfig: vi.fn(),
  close: vi.fn(),
};

vi.mock('../../server/claude-session.js', () => ({
  ClaudeSessionManager: vi.fn().mockImplementation(() => mockSessionManager),
}));

// Mock SDK query
const mockQuery = vi.fn();
vi.mock('@anthropic-ai/claude-agent-sdk', () => ({
  query: (...args: unknown[]) => mockQuery(...args),
}));

describe('BatchEngine', () => {
  let engine: BatchEngine;
  let store: BatchStore;
  let dbPath: string;
  let tmpDir: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `batch-engine-test-${Date.now()}-${Math.random().toString(36).slice(2)}.db`);
    store = new BatchStore(dbPath);
    engine = new BatchEngine(store);
    engine.setSessionManager(mockSessionManager as any);
    engine.setConfig({ apiKey: 'test-api-key', model: 'claude-sonnet-4-20250514' });
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'batch-test-'));
    mockSendMessageForCron.mockReset();
    mockCreateSessionWithId.mockReset();
    mockAbort.mockReset();
    mockQuery.mockReset();
  });

  afterEach(() => {
    engine.close();
    store.close();
    if (fs.existsSync(dbPath)) {
      fs.unlinkSync(dbPath);
      for (const ext of ['-wal', '-shm']) {
        const f = dbPath + ext;
        if (fs.existsSync(f)) fs.unlinkSync(f);
      }
    }
    if (fs.existsSync(tmpDir)) fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  describe('generateCode', () => {
    it('should call query() with batch.md content and return code', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# Batch Config\n## 数据获取\n从数据库获取数据\n## 执行模板\n/test ${name}',
        execTemplate: '/test ${name}',
        envVars: { DB_HOST: 'localhost' },
      });

      mockQuery.mockReturnValue({
        async *[Symbol.asyncIterator]() {
          yield {
            type: 'assistant',
            message: {
              content: [{
                type: 'text',
                text: '```python\nimport mysql\nresult = [{"name": "test"}]\nprint(result)\n```',
              }],
            },
            session_id: 'sess-1',
            is_error: false,
          };
        },
      });

      const code = await engine.generateCode(task.id);
      expect(code).toContain('import mysql');
      expect(store.getTask(task.id)!.generatedCode).toBe(code);
      expect(store.getTask(task.id)!.status).toBe('generated');
    });

    it('should throw if task not found', async () => {
      await expect(engine.generateCode('non-existent')).rejects.toThrow('Task not found');
    });

    it('should revert to draft status on failure', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${name}',
      });

      mockQuery.mockReturnValue({
        async *[Symbol.asyncIterator]() {
          throw new Error('API error');
        },
      });

      await expect(engine.generateCode(task.id)).rejects.toThrow('API error');
      expect(store.getTask(task.id)!.status).toBe('draft');
    });
  });

  describe('testCode', () => {
    it('should execute generated code and return items', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${name}',
      });
      store.updateTask(task.id, {
        generatedCode: 'console.log(JSON.stringify([{name:"a"},{name:"b"}]))',
      });

      const result = await engine.testCode(task.id);
      expect(result.items).toHaveLength(2);
      expect(result.items[0]).toEqual({ name: 'a' });
      expect(result.preview).toBeDefined();
      expect(store.getTask(task.id)!.status).toBe('tested');
    });

    it('should reject if no generated code', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test',
      });

      await expect(engine.testCode(task.id)).rejects.toThrow('No generated code');
    });

    it('should reject if output is not valid JSON', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test',
      });
      store.updateTask(task.id, { generatedCode: 'console.log("not json")' });

      await expect(engine.testCode(task.id)).rejects.toThrow();
    });

    it('should reject if output exceeds MAX_ITEMS', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test',
      });
      const largeArray = Array.from({ length: 100_001 }, (_, i) => ({ name: `item-${i}` }));
      store.updateTask(task.id, {
        generatedCode: `console.log(JSON.stringify(${JSON.stringify(largeArray)}))`,
      });

      await expect(engine.testCode(task.id)).rejects.toThrow('数据量过大');
    });

    it('should revert to generated status on failure', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test',
      });
      store.updateTask(task.id, { generatedCode: 'console.log("not json")' });

      await expect(engine.testCode(task.id)).rejects.toThrow();
      expect(store.getTask(task.id)!.status).toBe('generated');
    });
  });

  describe('save', () => {
    it('should update task status to saved', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test',
      });
      store.updateTask(task.id, { generatedCode: 'console.log("hi")' });

      await engine.save(task.id);
      expect(store.getTask(task.id)!.status).toBe('saved');
    });

    it('should throw if no generated code', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test',
      });

      await expect(engine.save(task.id)).rejects.toThrow('No generated code');
    });
  });

  describe('execute', () => {
    it('should process all items with concurrency limit', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${name}',
        concurrency: 2,
      });
      store.bulkCreateItems(task.id, [
        { name: 'a' }, { name: 'b' }, { name: 'c' },
      ]);
      store.updateTask(task.id, { status: 'saved' });

      mockSendMessageForCron.mockResolvedValue(undefined);

      await engine.execute(task.id);

      expect(mockSendMessageForCron).toHaveBeenCalledTimes(3);
      const updated = store.getTask(task.id)!;
      expect(updated.status).toBe('completed');
      expect(updated.successCount).toBe(3);
      expect(updated.failedCount).toBe(0);
    });

    it('should use template to render prompts', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/analyze ${name} --days ${days}',
        concurrency: 1,
      });
      store.bulkCreateItems(task.id, [{ name: '贵州茅台', days: 30 }]);
      store.updateTask(task.id, { status: 'saved' });

      mockSendMessageForCron.mockResolvedValue(undefined);
      await engine.execute(task.id);

      expect(mockSendMessageForCron).toHaveBeenCalledWith(
        expect.any(String),
        '/analyze 贵州茅台 --days 30',
        expect.any(AbortController),
        expect.any(Function),
      );
    });

    it('should track individual item failures', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${name}',
        concurrency: 1,
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }, { name: 'c' }]);
      store.updateTask(task.id, { status: 'saved' });

      mockSendMessageForCron
        .mockRejectedValueOnce(new Error('timeout'))
        .mockResolvedValue(undefined);

      await engine.execute(task.id);

      const updated = store.getTask(task.id)!;
      expect(updated.successCount).toBe(2);
      expect(updated.failedCount).toBe(1);

      const failed = store.listItems(task.id, { status: 'failed' });
      expect(failed).toHaveLength(1);
      expect(failed[0].errorMessage).toBe('timeout');
    });

    it('should reject if task is not in saved status', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test',
      });
      await expect(engine.execute(task.id)).rejects.toThrow();
    });

    it('should reject if task not found', async () => {
      await expect(engine.execute('non-existent')).rejects.toThrow('Task not found');
    });
  });

  describe('pause/resume/cancel', () => {
    it('should pause execution and block new items', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${name}',
        concurrency: 1,
      });
      store.bulkCreateItems(task.id, Array.from({ length: 5 }, (_, i) => ({ name: `item-${i}` })));
      store.updateTask(task.id, { status: 'saved' });

      let callIndex = 0;
      let pauseCalled = false;
      mockSendMessageForCron.mockImplementation(async () => {
        callIndex++;
        if (callIndex === 1 && !pauseCalled) {
          pauseCalled = true;
          engine.pause(task.id);
        }
        await new Promise(r => setTimeout(r, 50));
      });

      // Start execute but don't await (it will block on pause)
      const execPromise = engine.execute(task.id);

      // Wait for first item to complete and pause to be called
      await new Promise(r => setTimeout(r, 100));

      // Verify task status is paused
      expect(store.getTask(task.id)!.status).toBe('paused');

      // Resume to let execution continue
      engine.resume(task.id);

      // Now execute should complete
      await execPromise;

      expect(store.getTask(task.id)!.status).toBe('completed');
    });

    it('should cancel execution', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${name}',
        concurrency: 1,
      });
      store.bulkCreateItems(task.id, Array.from({ length: 5 }, (_, i) => ({ name: `item-${i}` })));
      store.updateTask(task.id, { status: 'saved' });

      let callIndex = 0;
      mockSendMessageForCron.mockImplementation(async () => {
        callIndex++;
        if (callIndex === 1) {
          engine.cancel(task.id);
        }
        await new Promise(r => setTimeout(r, 50));
      });

      await engine.execute(task.id);

      const updated = store.getTask(task.id)!;
      expect(updated.status).toBe('failed');
    });

    it('should abort running session IDs on cancel', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test',
        concurrency: 1,
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }]);
      store.updateTask(task.id, { status: 'running' });
      store.updateItem(1, { status: 'running', sessionId: 'sess-123' });

      // Manually inject an active execution so cancel() finds it
      const { Semaphore } = await import('../../server/semaphore.js');
      const sem = new Semaphore(1);
      // Access private map via casting
      (engine as any).activeExecutions.set(task.id, { semaphore: sem, cancelled: false });

      engine.cancel(task.id);

      expect(mockAbort).toHaveBeenCalledWith('sess-123');
    });
  });

  describe('retryFailed', () => {
    it('should retry all failed items', async () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test ${name}',
        concurrency: 1,
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }, { name: 'b' }]);
      store.updateTask(task.id, { status: 'saved' });

      mockSendMessageForCron
        .mockRejectedValueOnce(new Error('fail a'))
        .mockRejectedValueOnce(new Error('fail b'));

      await engine.execute(task.id);

      expect(store.getTask(task.id)!.successCount).toBe(0);
      expect(store.getTask(task.id)!.failedCount).toBe(2);

      // Now retry — this time succeed
      mockSendMessageForCron.mockReset();
      mockSendMessageForCron.mockResolvedValue(undefined);

      await engine.retryFailed(task.id);

      const updated = store.getTask(task.id)!;
      expect(updated.successCount).toBe(2);
      expect(updated.failedCount).toBe(0);
    });
  });

  describe('start/stop lifecycle', () => {
    it('should reset orphaned running items on start', () => {
      const task = store.createTask({
        workspace: tmpDir,
        skillName: 'test',
        mdContent: '# md',
        execTemplate: '/test',
      });
      store.bulkCreateItems(task.id, [{ name: 'a' }]);
      store.updateItem(1, { status: 'running' });
      store.updateTask(task.id, { status: 'running' });

      engine.start();

      expect(store.getItem(1)!.status).toBe('failed');
      expect(store.getItem(1)!.errorMessage).toBe('进程异常终止');
      expect(store.getTask(task.id)!.status).toBe('failed');
    });
  });
});
