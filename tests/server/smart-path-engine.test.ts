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

  const mockSessionManager = {
    createSessionWithId: vi.fn(),
    sendMessageForCron: vi.fn().mockResolvedValue(undefined),
    getHistory: vi.fn().mockReturnValue([
      { role: 'user', content: 'test', contentBlocks: [] },
      { role: 'assistant', content: 'result', contentBlocks: [{ type: 'text', text: 'done' }] },
    ]),
  };

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `smart-path-engine-test-${Date.now()}`);
    workspace = path.join(dbPath, 'test-workspace');
    fs.mkdirSync(workspace, { recursive: true });
    store = new SmartPathStore();
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

    await engine.run(p.id, workspace);

    const updated = store.get(p.id, workspace);
    expect(updated!.status).toBe('completed');

    const runs = store.listRuns(p.id, workspace);
    expect(runs).toHaveLength(1);
    expect(runs[0].status).toBe('completed');
    expect(mockSessionManager.sendMessageForCron).toHaveBeenCalledTimes(1);
  });

  it('should collect result from session history', async () => {
    mockSessionManager.getHistory.mockReturnValueOnce([
      { role: 'user', content: 'test', contentBlocks: [] },
      { role: 'assistant', content: 'result', contentBlocks: [{ type: 'text', text: 'my result' }] },
    ]);

    const p = store.create({ name: 'Result Test', workspace, steps: JSON.stringify([{ userInput: 'do it' }]) });
    await engine.run(p.id, workspace);

    const runs = store.listRuns(p.id, workspace);
    expect(runs[0].status).toBe('completed');
    expect(JSON.parse(runs[0].stepResults).result).toBe('my result');
  });

  it('should throw when path not found', async () => {
    await expect(engine.run('nope', workspace)).rejects.toThrow('Path not found');
  });

  it('should throw when path has no steps', async () => {
    const p = store.create({ name: 'Empty', workspace, steps: '[]' });
    await expect(engine.run(p.id, workspace)).rejects.toThrow('Path has no steps');
  });

  it('should mark path as failed on error', async () => {
    mockSessionManager.sendMessageForCron.mockRejectedValueOnce(new Error('Send failed'));

    const p = store.create({ name: 'Fail', workspace, steps: JSON.stringify([{ userInput: 'step' }]) });
    await expect(engine.run(p.id, workspace)).rejects.toThrow();

    const updated = store.get(p.id, workspace);
    expect(updated!.status).toBe('failed');

    const runs = store.listRuns(p.id, workspace);
    expect(runs[0].status).toBe('failed');
  });
});
