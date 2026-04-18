import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { SmartPathEngine } from '../../server/smart-path-engine.js';
import { SmartPathStore } from '../../server/smart-path-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('SmartPathEngine', () => {
  let store: SmartPathStore;
  let engine: SmartPathEngine;
  let dbPath: string;
  let tmpSkillDir: string;

  const mockSkillsRegistry = {
    getSkillDir: (id: string) => {
      if (id === 'test-skill') return tmpSkillDir;
      throw new Error(`Skill not found: ${id}`);
    },
  };

  const mockSessionManager = {
    createSessionWithId: vi.fn(),
    sendMessageForCron: vi.fn().mockResolvedValue(undefined),
    getHistory: vi.fn().mockReturnValue([
      { role: 'user', content: 'test', contentBlocks: [] },
      { role: 'assistant', content: 'result', contentBlocks: [{ type: 'text', text: 'skill result' }] },
    ]),
    deleteSession: vi.fn(),
    abort: vi.fn(),
  };

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `smart-path-engine-test-${Date.now()}.db`);
    store = new SmartPathStore(dbPath);
    tmpSkillDir = fs.mkdtempSync(path.join(os.tmpdir(), 'skill-'));
    fs.writeFileSync(path.join(tmpSkillDir, 'SKILL.md'), '# Test Skill\nThis is a test skill.');
    engine = new SmartPathEngine(store, mockSkillsRegistry as any, mockSessionManager as any);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
    if (fs.existsSync(tmpSkillDir)) fs.rmSync(tmpSkillDir, { recursive: true, force: true });
    vi.clearAllMocks();
  });

  describe('runPath', () => {
    it('should execute serial step sequentially', async () => {
      const smartPath = store.createPath({
        name: 'Serial Test',
        workspace: '/tmp',
        steps: JSON.stringify([
          {
            mode: 'serial',
            actions: [
              { type: 'skill', skillId: 'test-skill' },
              { type: 'skill', skillId: 'test-skill' },
            ],
          },
        ]),
      });

      await engine.runPath(smartPath.id);

      const updatedPath = store.getPath(smartPath.id);
      expect(updatedPath!.status).toBe('completed');

      const runs = store.listRuns(smartPath.id);
      expect(runs.length).toBe(1);
      expect(runs[0].status).toBe('completed');

      expect(mockSessionManager.sendMessageForCron).toHaveBeenCalledTimes(2);
    });

    it('should execute parallel step concurrently', async () => {
      const smartPath = store.createPath({
        name: 'Parallel Test',
        workspace: '/tmp',
        steps: JSON.stringify([
          {
            mode: 'parallel',
            actions: [
              { type: 'skill', skillId: 'test-skill' },
              { type: 'skill', skillId: 'test-skill' },
            ],
          },
        ]),
      });

      await engine.runPath(smartPath.id);

      const runs = store.listRuns(smartPath.id);
      expect(runs[0].status).toBe('completed');
      expect(mockSessionManager.sendMessageForCron).toHaveBeenCalledTimes(2);
    });

    it('should pass context between steps', async () => {
      const smartPath = store.createPath({
        name: 'Context Test',
        workspace: '/tmp',
        steps: JSON.stringify([
          {
            mode: 'serial',
            actions: [{ type: 'skill', skillId: 'test-skill' }],
          },
          {
            mode: 'serial',
            actions: [{ type: 'skill', skillId: 'test-skill' }],
          },
        ]),
      });

      await engine.runPath(smartPath.id);

      const runs = store.listRuns(smartPath.id);
      const stepResults = JSON.parse(runs[0].stepResults);
      expect(stepResults).toHaveProperty('0');
      expect(stepResults).toHaveProperty('1');
    });

    it('should execute python action', async () => {
      const smartPath = store.createPath({
        name: 'Python Test',
        workspace: '/tmp',
        steps: JSON.stringify([
          {
            mode: 'serial',
            actions: [
              { type: 'python', code: 'print(json.dumps({"result": 42}))' },
            ],
          },
        ]),
      });

      await engine.runPath(smartPath.id);

      const runs = store.listRuns(smartPath.id);
      expect(runs[0].status).toBe('completed');
      const stepResults = JSON.parse(runs[0].stepResults);
      expect(stepResults['0']).toEqual({ result: 42 });
    });

    it('should inject ctx into python', async () => {
      const smartPath = store.createPath({
        name: 'Python Ctx Test',
        workspace: '/tmp',
        steps: JSON.stringify([
          {
            mode: 'serial',
            actions: [
              { type: 'python', code: 'print(json.dumps({"input": ctx}))' },
            ],
          },
        ]),
      });

      await engine.runPath(smartPath.id);

      const runs = store.listRuns(smartPath.id);
      const stepResults = JSON.parse(runs[0].stepResults);
      expect(stepResults['0']).toEqual({ input: {} });
    });

    it('should throw when path not found', async () => {
      await expect(engine.runPath('non-existent')).rejects.toThrow('Path not found');
    });

    it('should throw when path has no steps', async () => {
      const smartPath = store.createPath({
        name: 'Empty',
        workspace: '/tmp',
        steps: '[]',
      });
      await expect(engine.runPath(smartPath.id)).rejects.toThrow('Path has no steps');
    });

    it('should mark path as failed on error', async () => {
      const smartPath = store.createPath({
        name: 'Fail Test',
        workspace: '/tmp',
        steps: JSON.stringify([
          {
            mode: 'serial',
            actions: [{ type: 'skill', skillId: 'non-existent-skill' }],
          },
        ]),
      });

      await expect(engine.runPath(smartPath.id)).rejects.toThrow();

      const updatedPath = store.getPath(smartPath.id);
      expect(updatedPath!.status).toBe('failed');

      const runs = store.listRuns(smartPath.id);
      expect(runs[0].status).toBe('failed');
      expect(runs[0].errorMessage).toBeDefined();
    });

    it('should call onProgress for each step', async () => {
      const onProgress = vi.fn();
      const smartPath = store.createPath({
        name: 'Progress Test',
        workspace: '/tmp',
        steps: JSON.stringify([
          { mode: 'serial', actions: [{ type: 'skill', skillId: 'test-skill' }] },
          { mode: 'serial', actions: [{ type: 'skill', skillId: 'test-skill' }] },
        ]),
      });

      await engine.runPath(smartPath.id, onProgress);

      expect(onProgress).toHaveBeenCalledTimes(2);
      expect(onProgress).toHaveBeenNthCalledWith(1, { stepIndex: 0, totalSteps: 2, status: 'stepComplete' });
      expect(onProgress).toHaveBeenNthCalledWith(2, { stepIndex: 1, totalSteps: 2, status: 'stepComplete' });
    });
  });
});
