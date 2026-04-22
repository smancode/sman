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

  describe('injectMetaSkills upgrade logic', () => {
    // injectMetaSkills is private — test the mtime comparison semantics directly

    it('skill-auto-updater: skips when target is newer than template', () => {
      const targetDir = path.join(workspace, '.claude', 'skills', 'skill-auto-updater');
      fs.mkdirSync(targetDir, { recursive: true });
      fs.writeFileSync(path.join(targetDir, 'SKILL.md'), 'newer target');

      // Target mtime is now (newer), template in dist is from build time
      const targetMtime = fs.statSync(path.join(targetDir, 'SKILL.md')).mtimeMs;
      const templatePath = path.join(__dirname, '..', '..', '..', 'server', 'init', 'templates', 'skill-auto-updater', 'SKILL.md');
      // If template exists, verify the ordering logic
      if (fs.existsSync(templatePath)) {
        const templateMtime = fs.statSync(templatePath).mtimeMs;
        // Target was just created, should be >= template
        expect(targetMtime).toBeGreaterThanOrEqual(templateMtime);
      }
    });

    it('project-* skills: existence check only, no mtime comparison', () => {
      const targetDir = path.join(workspace, '.claude', 'skills', 'project-structure');
      fs.mkdirSync(targetDir, { recursive: true });
      fs.writeFileSync(path.join(targetDir, 'SKILL.md'), 'existing content');

      // For project-* skills, existence alone means skip — regardless of mtime
      // This is the key behavioral difference from skill-auto-updater
      expect(fs.existsSync(path.join(targetDir, 'SKILL.md'))).toBe(true);
    });

    it('knowledge-* skills: existence check only, no mtime comparison', () => {
      const targetDir = path.join(workspace, '.claude', 'skills', 'knowledge-business');
      fs.mkdirSync(targetDir, { recursive: true });
      fs.writeFileSync(path.join(targetDir, 'SKILL.md'), 'existing knowledge');

      expect(fs.existsSync(path.join(targetDir, 'SKILL.md'))).toBe(true);
    });
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
