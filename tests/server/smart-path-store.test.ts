import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { SmartPathStore } from '../../server/smart-path-store.js';

describe('SmartPathStore', () => {
  let tmpWs: string;
  let store: SmartPathStore;

  beforeEach(() => {
    tmpWs = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    store = new SmartPathStore();
  });

  afterEach(() => {
    fs.rmSync(tmpWs, { recursive: true, force: true });
  });

  it('should create and get a path', () => {
    const p = store.create({ name: 'Test', workspace: tmpWs, steps: '[]' });
    expect(p.id).toBeTruthy();
    expect(p.name).toBe('Test');

    const found = store.get(p.id, tmpWs);
    expect(found).toBeDefined();
    expect(found!.name).toBe('Test');
  });

  it('should list paths for a workspace', () => {
    store.create({ name: 'A', workspace: tmpWs, steps: '[]' });
    store.create({ name: 'B', workspace: tmpWs, steps: '[]' });
    const paths = store.list(tmpWs);
    expect(paths).toHaveLength(2);
  });

  it('should update a path', () => {
    const p = store.create({ name: 'Old', workspace: tmpWs, steps: '[]' });
    const updated = store.update(p.id, tmpWs, { name: 'New', status: 'ready' });
    expect(updated.name).toBe('New');
    expect(updated.status).toBe('ready');

    // name 变更后 ID 也会变
    const found = store.get(updated.id, tmpWs);
    expect(found!.name).toBe('New');
  });

  it('should delete a path', () => {
    const p = store.create({ name: 'Del', workspace: tmpWs, steps: '[]' });
    store.del(p.id, tmpWs);
    expect(store.get(p.id, tmpWs)).toBeUndefined();
  });

  it('should throw on create with empty name', () => {
    expect(() => store.create({ name: '', workspace: tmpWs, steps: '[]' })).toThrow('Missing name');
  });

  it('should throw on create with empty workspace', () => {
    expect(() => store.create({ name: 'X', workspace: '', steps: '[]' })).toThrow('Missing workspace');
  });

  it('should return undefined on get not found', () => {
    expect(store.get('nope', tmpWs)).toBeUndefined();
  });

  it('should throw on update/delete not found', () => {
    expect(() => store.update('nope', tmpWs, {})).toThrow('Path not found');
    expect(() => store.del('nope', tmpWs)).toThrow('Path not found');
  });

  it('should list paths across multiple workspaces', () => {
    const ws2 = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test2-'));
    store.create({ name: 'A', workspace: tmpWs, steps: '[]' });
    store.create({ name: 'B', workspace: ws2, steps: '[]' });
    const all = store.listAll([tmpWs, ws2]);
    expect(all).toHaveLength(2);
    fs.rmSync(ws2, { recursive: true, force: true });
  });

  it('should manage runs', () => {
    const p = store.create({ name: 'Run Test', workspace: tmpWs, steps: '[]' });
    const run = store.createRun(p.id, tmpWs);
    expect(run.status).toBe('running');

    store.updateRun(run.id, tmpWs, p.id, { status: 'completed', finishedAt: new Date().toISOString() });
    const runs = store.listRuns(p.id, tmpWs);
    expect(runs).toHaveLength(1);
    expect(runs[0].status).toBe('completed');
  });
});
