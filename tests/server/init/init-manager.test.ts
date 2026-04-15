import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { InitManager } from '../../../server/init/init-manager.js';

describe('InitManager', () => {
  let tmpDir: string;
  let workspace: string;
  let pluginsDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-manager-test-'));
    workspace = path.join(tmpDir, 'workspace');
    pluginsDir = path.join(tmpDir, 'plugins');
    fs.mkdirSync(workspace, { recursive: true });
    fs.mkdirSync(pluginsDir, { recursive: true });
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('detects uninitialized workspace', () => {
    const manager = new InitManager({
      pluginsDir,
      capabilityRegistry: null as any,
      llmConfig: () => null,
    });
    expect(manager.isInitialized(workspace)).toBe(false);
  });

  it('detects initialized workspace from INIT.md', () => {
    fs.mkdirSync(path.join(workspace, '.sman'), { recursive: true });
    fs.writeFileSync(path.join(workspace, '.sman', 'INIT.md'), '---\nsmanVersion: "1.0.0"\n---\nInitialized');

    const manager = new InitManager({
      pluginsDir,
      capabilityRegistry: null as any,
      llmConfig: () => null,
    });
    expect(manager.isInitialized(workspace)).toBe(true);
  });

  it('detects stale version as uninitialized', () => {
    fs.mkdirSync(path.join(workspace, '.sman'), { recursive: true });
    fs.writeFileSync(path.join(workspace, '.sman', 'INIT.md'), '---\nsmanVersion: "0.9.0"\n---\nOld');

    const manager = new InitManager({
      pluginsDir,
      capabilityRegistry: null as any,
      llmConfig: () => null,
    });
    expect(manager.isInitialized(workspace)).toBe(false);
  });

  it('acquires and releases lock', () => {
    const manager = new InitManager({
      pluginsDir,
      capabilityRegistry: null as any,
      llmConfig: () => null,
    });

    const lockAcquired = manager.acquireLock(workspace);
    expect(lockAcquired).toBe(true);
    expect(fs.existsSync(path.join(workspace, '.sman', '.initializing'))).toBe(true);

    // Second lock should fail
    const secondAttempt = manager.acquireLock(workspace);
    expect(secondAttempt).toBe(false);

    manager.releaseLock(workspace);
    expect(fs.existsSync(path.join(workspace, '.sman', '.initializing'))).toBe(false);
  });
});
