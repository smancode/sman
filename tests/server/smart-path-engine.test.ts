import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { SmartPathEngine } from '../../server/smart-path-engine.js';
import { SmartPathStore } from '../../server/smart-path-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('SmartPathEngine', () => {
  let store: SmartPathStore;
  let engine: SmartPathEngine;
  let workspace: string;
  let dbPath: string;
  let mockSessionManager: {
    createEphemeralSessionWithId: ReturnType<typeof vi.fn>;
    createEphemeralSession: ReturnType<typeof vi.fn>;
    sendMessageForCron: ReturnType<typeof vi.fn>;
    sendMessageForStep: ReturnType<typeof vi.fn>;
    getHistory: ReturnType<typeof vi.fn>;
    closeV2Session: ReturnType<typeof vi.fn>;
    removeEphemeralSession: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `smart-path-engine-test-${Date.now()}`);
    workspace = path.join(dbPath, 'test-workspace');
    fs.mkdirSync(workspace, { recursive: true });
    store = new SmartPathStore();
    mockSessionManager = {
      createEphemeralSessionWithId: vi.fn(),
      createEphemeralSession: vi.fn().mockReturnValue('ephemeral-id'),
      sendMessageForCron: vi.fn().mockResolvedValue(undefined),
      sendMessageForStep: vi.fn().mockResolvedValue('step result'),
      getHistory: vi.fn().mockReturnValue([
        { role: 'user', content: 'test', contentBlocks: [] },
        { role: 'assistant', content: 'result', contentBlocks: [{ type: 'text', text: 'done' }] },
      ]),
      closeV2Session: vi.fn(),
      removeEphemeralSession: vi.fn(),
    };
    engine = new SmartPathEngine(store, mockSessionManager as any);
  });

  afterEach(() => {
    if (fs.existsSync(dbPath)) fs.rmSync(dbPath, { recursive: true, force: true });
    vi.clearAllMocks();
  });

  it('should execute steps and complete', async () => {
    const p = store.create({
      name: 'Test',
      workspace,
      steps: JSON.stringify([{ userInput: 'step 1' }, { userInput: 'step 2' }]),
    });

    await engine.run(p.id, workspace, vi.fn(), vi.fn());

    const updated = store.get(p.id, workspace);
    expect(updated!.status).toBe('completed');

    const runs = store.listRuns(p.id, workspace);
    expect(runs).toHaveLength(1);
    expect(runs[0].status).toBe('completed');
    // 2 step executions + 1 updateRunGuide call = 3
    expect(mockSessionManager.sendMessageForStep).toHaveBeenCalledTimes(3);
  });

  it('should collect result from session history', async () => {
    const p = store.create({ name: 'Result Test', workspace, steps: JSON.stringify([{ userInput: 'do it' }]) });
    await engine.run(p.id, workspace, vi.fn(), vi.fn());

    const runs = store.listRuns(p.id, workspace);
    expect(runs[0].status).toBe('completed');
    // sendMessageForStep mock returns 'step result' by default
    expect(JSON.parse(runs[0].stepResults)).toEqual(['step result']);
  });

  it('should throw when path not found', async () => {
    await expect(engine.run('nope', workspace, vi.fn(), vi.fn())).rejects.toThrow('Path not found');
  });

  it('should throw when path has no steps', async () => {
    const p = store.create({ name: 'Empty', workspace, steps: '[]' });
    await expect(engine.run(p.id, workspace, vi.fn(), vi.fn())).rejects.toThrow('Path has no steps');
  });

  it('should mark path as failed on error', async () => {
    mockSessionManager.sendMessageForStep.mockRejectedValueOnce(new Error('Send failed'));

    const p = store.create({ name: 'Fail', workspace, steps: JSON.stringify([{ userInput: 'step' }]) });
    await expect(engine.run(p.id, workspace, vi.fn(), vi.fn())).rejects.toThrow();

    const updated = store.get(p.id, workspace);
    expect(updated!.status).toBe('failed');

    const runs = store.listRuns(p.id, workspace);
    expect(runs[0].status).toBe('failed');
  });
});
