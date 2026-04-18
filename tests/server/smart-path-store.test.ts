import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { SmartPathStore } from '../../server/smart-path-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('SmartPathStore', () => {
  let store: SmartPathStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `smart-path-test-${Date.now()}.db`);
    store = new SmartPathStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  describe('createPath', () => {
    it('should create a path with default status', () => {
      const path = store.createPath({
        name: 'Test Path',
        workspace: '/tmp/test',
        steps: JSON.stringify([{ mode: 'serial', actions: [{ type: 'skill', skillId: 'test' }] }]),
      });
      expect(path.name).toBe('Test Path');
      expect(path.workspace).toBe('/tmp/test');
      expect(path.status).toBe('draft');
      expect(path.id).toBeDefined();
    });

    it('should throw when name is missing', () => {
      expect(() =>
        store.createPath({
          name: '',
          workspace: '/tmp/test',
          steps: '[]',
        }),
      ).toThrow('缺少 name 参数');
    });

    it('should throw when workspace is missing', () => {
      expect(() =>
        store.createPath({
          name: 'Test',
          workspace: '',
          steps: '[]',
        }),
      ).toThrow('缺少 workspace 参数');
    });

    it('should throw when steps is missing', () => {
      expect(() =>
        store.createPath({
          name: 'Test',
          workspace: '/tmp/test',
          steps: '',
        }),
      ).toThrow('缺少 steps 参数');
    });
  });

  describe('getPath', () => {
    it('should return undefined for non-existent id', () => {
      expect(store.getPath('non-existent')).toBeUndefined();
    });

    it('should throw when id is missing', () => {
      expect(() => store.getPath('')).toThrow('缺少 id 参数');
    });
  });

  describe('listPaths', () => {
    it('should return empty array when no paths', () => {
      expect(store.listPaths()).toEqual([]);
    });

    it('should return paths ordered by created_at DESC', async () => {
      store.createPath({ name: 'Path A', workspace: '/tmp/a', steps: '[]' });
      await new Promise(r => setTimeout(r, 10));
      store.createPath({ name: 'Path B', workspace: '/tmp/b', steps: '[]' });
      const paths = store.listPaths();
      expect(paths.length).toBe(2);
      expect(paths[0].name).toBe('Path B');
    });
  });

  describe('updatePath', () => {
    it('should update path fields', () => {
      const path = store.createPath({ name: 'Old', workspace: '/tmp', steps: '[]' });
      const updated = store.updatePath(path.id, { name: 'New', status: 'ready' });
      expect(updated!.name).toBe('New');
      expect(updated!.status).toBe('ready');
    });

    it('should throw when id is missing', () => {
      expect(() => store.updatePath('', { name: 'New' })).toThrow('缺少 id 参数');
    });
  });

  describe('deletePath', () => {
    it('should delete path and its runs', () => {
      const path = store.createPath({ name: 'ToDelete', workspace: '/tmp', steps: '[]' });
      const run = store.createRun(path.id);
      store.deletePath(path.id);
      expect(store.getPath(path.id)).toBeUndefined();
      expect(store.getRun(run.id)).toBeUndefined();
    });
  });

  describe('createRun', () => {
    it('should create a run with running status', () => {
      const path = store.createPath({ name: 'Test', workspace: '/tmp', steps: '[]' });
      const run = store.createRun(path.id);
      expect(run.status).toBe('running');
      expect(run.pathId).toBe(path.id);
      expect(run.stepResults).toBe('{}');
    });

    it('should throw when pathId is missing', () => {
      expect(() => store.createRun('')).toThrow('缺少 pathId 参数');
    });
  });

  describe('updateRun', () => {
    it('should update run fields', () => {
      const path = store.createPath({ name: 'Test', workspace: '/tmp', steps: '[]' });
      const run = store.createRun(path.id);
      const updated = store.updateRun(run.id, {
        status: 'completed',
        stepResults: JSON.stringify({ 0: 'result' }),
        finishedAt: new Date().toISOString(),
      });
      expect(updated!.status).toBe('completed');
      expect(updated!.stepResults).toBe('{"0":"result"}');
    });
  });

  describe('listRuns', () => {
    it('should return runs for a path ordered by started_at DESC', () => {
      const path = store.createPath({ name: 'Test', workspace: '/tmp', steps: '[]' });
      store.createRun(path.id);
      store.createRun(path.id);
      const runs = store.listRuns(path.id);
      expect(runs.length).toBe(2);
    });

    it('should throw when pathId is missing', () => {
      expect(() => store.listRuns('')).toThrow('缺少 pathId 参数');
    });
  });
});
