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
  let workspace: string;

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
    dbPath = path.join(os.tmpdir(), `smart-path-engine-test-${Date.now()}`);
    store = new SmartPathStore(dbPath);
    tmpSkillDir = fs.mkdtempSync(path.join(os.tmpdir(), 'skill-'));
    fs.writeFileSync(path.join(tmpSkillDir, 'SKILL.md'), '# Test Skill\nThis is a test skill.');
    engine = new SmartPathEngine(store, mockSkillsRegistry as any, mockSessionManager as any);

    // Create a workspace directory for tests
    workspace = path.join(dbPath, 'test-workspace');
    fs.mkdirSync(workspace, { recursive: true });
  });

  afterEach(() => {
    if (fs.existsSync(dbPath)) fs.rmSync(dbPath, { recursive: true, force: true });
    if (fs.existsSync(tmpSkillDir)) fs.rmSync(tmpSkillDir, { recursive: true, force: true });
    vi.clearAllMocks();
  });

  describe('runPath', () => {
    it('should execute serial steps and complete', async () => {
      const smartPath = store.createPath({
        name: 'Serial Test',
        workspace,
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

      // Engine sends one combined prompt via sendMessageForCron
      expect(mockSessionManager.sendMessageForCron).toHaveBeenCalledTimes(1);
    });

    it('should execute parallel steps and complete', async () => {
      const smartPath = store.createPath({
        name: 'Parallel Test',
        workspace,
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
      expect(mockSessionManager.sendMessageForCron).toHaveBeenCalledTimes(1);
    });

    it('should collect result from session history', async () => {
      const smartPath = store.createPath({
        name: 'Context Test',
        workspace,
        steps: JSON.stringify([
          {
            mode: 'serial',
            actions: [{ type: 'skill', skillId: 'test-skill' }],
          },
        ]),
      });

      await engine.runPath(smartPath.id);

      const runs = store.listRuns(smartPath.id);
      expect(runs[0].status).toBe('completed');
      const stepResults = JSON.parse(runs[0].stepResults);
      expect(stepResults).toHaveProperty('result');
      expect(stepResults.result).toBe('skill result');
    });

    it('should execute python action via session', async () => {
      mockSessionManager.getHistory.mockReturnValueOnce([
        { role: 'user', content: 'test', contentBlocks: [] },
        { role: 'assistant', content: 'result', contentBlocks: [{ type: 'text', text: '{"result": 42}' }] },
      ]);

      const smartPath = store.createPath({
        name: 'Python Test',
        workspace,
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
      expect(stepResults.result).toBe('{"result": 42}');
    });

    it('should throw when path not found', async () => {
      await expect(engine.runPath('non-existent')).rejects.toThrow('Path not found');
    });

    it('should throw when path has no steps', async () => {
      const smartPath = store.createPath({
        name: 'Empty',
        workspace,
        steps: '[]',
      });
      await expect(engine.runPath(smartPath.id)).rejects.toThrow('Path has no steps');
    });

    it('should mark path as failed on error', async () => {
      const smartPath = store.createPath({
        name: 'Fail Test',
        workspace,
        steps: JSON.stringify([
          {
            mode: 'serial',
            actions: [{ type: 'skill', skillId: 'non-existent-skill' }],
          },
        ]),
      });

      // Engine builds prompt before sending, skill lookup happens at build time
      // Since the engine doesn't resolve skills at run time anymore,
      // we need to make sendMessageForCron throw
      mockSessionManager.sendMessageForCron.mockRejectedValueOnce(new Error('Send failed'));

      await expect(engine.runPath(smartPath.id)).rejects.toThrow();

      const updatedPath = store.getPath(smartPath.id);
      expect(updatedPath!.status).toBe('failed');

      const runs = store.listRuns(smartPath.id);
      expect(runs[0].status).toBe('failed');
      expect(runs[0].errorMessage).toBeDefined();
    });

    it('should execute plan from .md file path', async () => {
      const planPath = store.savePlan({
        id: 'test-plan',
        name: '测试计划',
        workspace,
        steps: [{
          mode: 'serial',
          actions: [{
            type: 'python',
            code: 'import json\nprint(json.dumps({"result": 42}))',
          }],
        }],
        status: 'draft',
        createdAt: new Date().toISOString(),
      });

      await engine.runPath(planPath);

      const runs = store.listRuns('test-plan');
      expect(runs).toHaveLength(1);
      expect(runs[0].status).toBe('completed');
    });

    it('should support backward compatibility with plan IDs', async () => {
      store.savePlan({
        id: 'backward-compat-plan',
        name: '向后兼容测试',
        workspace,
        steps: [{
          mode: 'serial',
          actions: [{
            type: 'python',
            code: 'import json\nprint(json.dumps({"result": "backward-compatible"}))',
          }],
        }],
        status: 'draft',
        createdAt: new Date().toISOString(),
      });

      // Use plan ID instead of file path
      await engine.runPath('backward-compat-plan');

      const runs = store.listRuns('backward-compat-plan');
      expect(runs).toHaveLength(1);
      expect(runs[0].status).toBe('completed');
    });
  });
});
