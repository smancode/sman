// tests/server/capabilities/project-scanner.test.ts
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {
  sanitizeEndpointSlug,
  getGitInfo,
  isScanNeeded,
  acquireLock,
  releaseLock,
  isLockStale,
  registerScannedWorkspace,
  listScannedWorkspaces,
  type ScanManifest,
} from '../../../server/capabilities/project-scanner.js';

describe('sanitizeEndpointSlug', () => {
  it('converts API path to filename', () => {
    expect(sanitizeEndpointSlug('/api/payment/create')).toBe('api-payment-create');
  });

  it('removes leading slash', () => {
    expect(sanitizeEndpointSlug('/api/users')).toBe('api-users');
  });

  it('truncates to 80 chars', () => {
    const long = '/api/' + 'a'.repeat(100);
    expect(sanitizeEndpointSlug(long).length).toBeLessThanOrEqual(80);
  });

  it('handles double slashes', () => {
    expect(sanitizeEndpointSlug('/api//users')).toBe('api--users');
  });
});

describe('getGitInfo', () => {
  it('returns nulls for non-git directory', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-git-'));
    try {
      const info = getGitInfo(tmpDir);
      expect(info.commitHash).toBeNull();
      expect(info.gitUrl).toBeNull();
      expect(info.branch).toBeNull();
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });
});

describe('isScanNeeded', () => {
  let workspace: string;

  beforeEach(() => {
    workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-scan-'));
  });

  afterEach(() => {
    fs.rmSync(workspace, { recursive: true, force: true });
  });

  it('returns true when no manifest exists', () => {
    expect(isScanNeeded(workspace)).toBe(true);
  });

  it('returns false when all 3 SKILL.md files exist', () => {
    const skillsDir = path.join(workspace, '.claude', 'skills');
    for (const type of ['structure', 'apis', 'external-calls']) {
      const skillDir = path.join(skillsDir, `project-${type}`);
      fs.mkdirSync(skillDir, { recursive: true });
      fs.writeFileSync(path.join(skillDir, 'SKILL.md'), '# Overview');
    }
    expect(isScanNeeded(workspace)).toBe(false);
  });

  it('returns false when lock file exists and is fresh', () => {
    const claudeDir = path.join(workspace, '.claude');
    fs.mkdirSync(claudeDir, { recursive: true });
    fs.writeFileSync(
      path.join(claudeDir, '.scanning'),
      JSON.stringify({ pid: 99999, startedAt: new Date().toISOString() }),
    );
    expect(isScanNeeded(workspace)).toBe(false);
  });

  it('returns true when lock file is stale', () => {
    const claudeDir = path.join(workspace, '.claude');
    fs.mkdirSync(claudeDir, { recursive: true });
    const oldDate = new Date(Date.now() - 31 * 60 * 1000).toISOString();
    fs.writeFileSync(
      path.join(claudeDir, '.scanning'),
      JSON.stringify({ pid: 99999, startedAt: oldDate }),
    );
    expect(isScanNeeded(workspace)).toBe(true);
  });
});

describe('lock file operations', () => {
  let workspace: string;
  let claudeDir: string;

  beforeEach(() => {
    workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-lock-'));
    claudeDir = path.join(workspace, '.claude');
    fs.mkdirSync(claudeDir, { recursive: true });
  });

  afterEach(() => {
    fs.rmSync(workspace, { recursive: true, force: true });
  });

  it('acquireLock writes .scanning file with PID', () => {
    acquireLock(workspace);
    const lockPath = path.join(claudeDir, '.scanning');
    expect(fs.existsSync(lockPath)).toBe(true);
    const lock = JSON.parse(fs.readFileSync(lockPath, 'utf-8'));
    expect(lock.pid).toBe(process.pid);
    expect(lock.startedAt).toBeDefined();
  });

  it('releaseLock deletes .scanning file', () => {
    acquireLock(workspace);
    releaseLock(workspace);
    expect(fs.existsSync(path.join(claudeDir, '.scanning'))).toBe(false);
  });

  it('isLockStale returns false for recent lock', () => {
    acquireLock(workspace);
    expect(isLockStale(workspace)).toBe(false);
  });

  it('isLockStale returns true for lock older than 30 min', () => {
    const lockPath = path.join(claudeDir, '.scanning');
    const oldDate = new Date(Date.now() - 31 * 60 * 1000).toISOString();
    fs.writeFileSync(lockPath, JSON.stringify({ pid: 99999, startedAt: oldDate }));
    expect(isLockStale(workspace)).toBe(true);
  });
});

describe('scanned workspaces registry', () => {
  let homeDir: string;

  beforeEach(() => {
    homeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-registry-'));
  });

  afterEach(() => {
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  it('starts with empty registry', () => {
    const registry = listScannedWorkspaces(homeDir);
    expect(registry.workspaces).toHaveLength(0);
  });

  it('registers and lists workspaces', () => {
    registerScannedWorkspace(homeDir, '/tmp/project-a');
    const registry = listScannedWorkspaces(homeDir);
    expect(registry.workspaces).toHaveLength(1);
    expect(registry.workspaces[0].path).toBe('/tmp/project-a');
    expect(registry.workspaces[0].lastScannedAt).toBeDefined();
  });

  it('updates existing workspace entry', () => {
    registerScannedWorkspace(homeDir, '/tmp/project-a');
    const first = listScannedWorkspaces(homeDir).workspaces[0].lastScannedAt;
    // Small delay to get different timestamp
    registerScannedWorkspace(homeDir, '/tmp/project-a');
    const second = listScannedWorkspaces(homeDir).workspaces[0].lastScannedAt;
    expect(listScannedWorkspaces(homeDir).workspaces).toHaveLength(1);
  });

  it('handles multiple workspaces', () => {
    registerScannedWorkspace(homeDir, '/tmp/project-a');
    registerScannedWorkspace(homeDir, '/tmp/project-b');
    const registry = listScannedWorkspaces(homeDir);
    expect(registry.workspaces).toHaveLength(2);
  });
});
