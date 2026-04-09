// tests/server/capabilities/scanner-v2-integration.test.ts
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execSync } from 'node:child_process';
import { isScanNeeded } from '../../../server/capabilities/project-scanner.js';
import { validateSkillMd, parseFrontmatter } from '../../../server/capabilities/frontmatter-utils.js';
import { getScannerPrompt, SCANNER_TYPES } from '../../../server/capabilities/scanner-prompts.js';

describe('isScanNeeded integration', () => {
  let workspace: string;

  beforeEach(() => {
    workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-integration-'));
    // Init a real git repo
    execSync('git init', { cwd: workspace, encoding: 'utf-8' });
    execSync('git config user.email "test@test.com"', { cwd: workspace, encoding: 'utf-8' });
    execSync('git config user.name "Test"', { cwd: workspace, encoding: 'utf-8' });
    fs.writeFileSync(path.join(workspace, 'README.md'), 'test project');
    execSync('git add README.md', { cwd: workspace, encoding: 'utf-8' });
    execSync('git commit -m "initial"', { cwd: workspace, encoding: 'utf-8' });
  });

  afterEach(() => {
    fs.rmSync(workspace, { recursive: true, force: true });
  });

  it('returns needed=true reason="SKILL.md not found" when no SKILL.md exists', () => {
    const result = isScanNeeded(workspace, 'structure');
    expect(result.needed).toBe(true);
    expect(result.reason).toBe('SKILL.md not found');
  });

  it('returns needed=true reason="SKILL.md not found" for apis scanner type', () => {
    const result = isScanNeeded(workspace, 'apis');
    expect(result.needed).toBe(true);
    expect(result.reason).toBe('SKILL.md not found');
  });

  it('returns needed=false when commit hash matches for all scanner types', () => {
    const currentCommit = execSync('git rev-parse HEAD', { cwd: workspace, encoding: 'utf-8' }).trim();

    // Create valid SKILL.md with matching commit hash for each scanner type
    for (const type of SCANNER_TYPES) {
      const skillDir = path.join(workspace, '.claude', 'skills', `project-${type}`);
      fs.mkdirSync(skillDir, { recursive: true });
      fs.writeFileSync(path.join(skillDir, 'SKILL.md'), `---\nname: project-${type}\ndescription: "Test description for ${type} scanner"\n_scanned:\n  commitHash: "${currentCommit}"\n  scannedAt: "2026-04-09T10:00:00Z"\n  branch: main\n---\n\n# Content`);
    }

    for (const type of SCANNER_TYPES) {
      const result = isScanNeeded(workspace, type);
      expect(result.needed).toBe(false);
      expect(result.reason).toBe('up to date');
    }
  });

  it('returns needed=true when commit hash differs', () => {
    const originalCommit = execSync('git rev-parse HEAD', { cwd: workspace, encoding: 'utf-8' }).trim();

    // Create SKILL.md with original commit hash
    const skillDir = path.join(workspace, '.claude', 'skills', 'project-structure');
    fs.mkdirSync(skillDir, { recursive: true });
    fs.writeFileSync(path.join(skillDir, 'SKILL.md'), `---\nname: project-structure\ndescription: "Test description for scanner"\n_scanned:\n  commitHash: "${originalCommit}"\n  scannedAt: "2026-04-09T10:00:00Z"\n  branch: main\n---\n\n# Content`);

    // Make a new commit
    fs.writeFileSync(path.join(workspace, 'NEWFILE.md'), 'another file');
    execSync('git add NEWFILE.md', { cwd: workspace, encoding: 'utf-8' });
    execSync('git commit -m "new commit"', { cwd: workspace, encoding: 'utf-8' });

    const result = isScanNeeded(workspace, 'structure');
    expect(result.needed).toBe(true);
    expect(result.reason).toContain('commit changed');
  });

  it('returns needed=true reason="commit hash missing in SKILL.md" when _scanned.commitHash is absent', () => {
    const skillDir = path.join(workspace, '.claude', 'skills', 'project-structure');
    fs.mkdirSync(skillDir, { recursive: true });
    fs.writeFileSync(path.join(skillDir, 'SKILL.md'), `---\nname: project-structure\ndescription: "Test description"\n_scanned:\n  scannedAt: "2026-04-09T10:00:00Z"\n  branch: main\n---\n\n# Content`);

    const result = isScanNeeded(workspace, 'structure');
    expect(result.needed).toBe(true);
    expect(result.reason).toBe('commit hash missing in SKILL.md');
  });

  it('returns needed=true for not-a-git-repo when SKILL.md exists but git fails', () => {
    const nonGit = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-nongit-'));
    try {
      // Create a SKILL.md with valid frontmatter so we reach the git check
      const skillDir = path.join(nonGit, '.claude', 'skills', 'project-structure');
      fs.mkdirSync(skillDir, { recursive: true });
      fs.writeFileSync(path.join(skillDir, 'SKILL.md'), `---\nname: project-structure\ndescription: "Test description for scanner"\n_scanned:\n  commitHash: "abc123"\n  scannedAt: "2026-04-09T10:00:00Z"\n  branch: main\n---\n\n# Content`);

      const result = isScanNeeded(nonGit, 'structure');
      expect(result.needed).toBe(true);
      expect(result.reason).toBe('not a git repo');
    } finally {
      fs.rmSync(nonGit, { recursive: true, force: true });
    }
  });
});

describe('validateSkillMd integration', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-validate-'));
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('returns true for SKILL.md with valid frontmatter and _scanned.commitHash', () => {
    const filePath = path.join(tmpDir, 'SKILL.md');
    fs.writeFileSync(filePath, `---\nname: project-structure\ndescription: "Project structure skill with enough chars"\n_scanned:\n  commitHash: "abc123"\n  scannedAt: "2026-04-09T10:00:00Z"\n  branch: main\n---\n\n# Content\n| Module | Path | Purpose |\n|---|---|---|\n`);
    expect(validateSkillMd(filePath)).toBe(true);
  });

  it('returns false for SKILL.md missing frontmatter', () => {
    const filePath = path.join(tmpDir, 'SKILL.md');
    fs.writeFileSync(filePath, `# No frontmatter here\n\n# Content`);
    expect(validateSkillMd(filePath)).toBe(false);
  });

  it('returns false for SKILL.md with frontmatter but no _scanned.commitHash', () => {
    const filePath = path.join(tmpDir, 'SKILL.md');
    fs.writeFileSync(filePath, `---\nname: project-apis\ndescription: "Valid description here"\n---\n\n# Content`);
    expect(validateSkillMd(filePath)).toBe(false);
  });

  it('returns false for SKILL.md with description shorter than 5 chars', () => {
    const filePath = path.join(tmpDir, 'SKILL.md');
    fs.writeFileSync(filePath, `---\nname: project-structure\ndescription: "ab"\n_scanned:\n  commitHash: "abc"\n---\n\n# Content`);
    expect(validateSkillMd(filePath)).toBe(false);
  });
});

describe('getScannerPrompt integration', () => {
  it('injects _scanned placeholders into prompt for structure scanner', () => {
    const prompt = getScannerPrompt('structure', '/tmp/test-workspace');
    expect(prompt).toContain('_scanned:');
    expect(prompt).toContain('commitHash');
    expect(prompt).toContain('scannedAt');
    expect(prompt).toContain('branch');
  });

  it('injects gitInfo values into _scanned placeholders', () => {
    const prompt = getScannerPrompt('structure', '/tmp/test-workspace', {
      commitHash: 'abc123def',
      branch: 'feature/test',
    });
    expect(prompt).toContain('abc123def');
    expect(prompt).toContain('feature/test');
  });

  it('returns different prompts for different scanner types', () => {
    const structurePrompt = getScannerPrompt('structure', '/tmp/test');
    const apisPrompt = getScannerPrompt('apis', '/tmp/test');
    const externalCallsPrompt = getScannerPrompt('external-calls', '/tmp/test');

    expect(structurePrompt).not.toBe(apisPrompt);
    expect(apisPrompt).not.toBe(externalCallsPrompt);
  });

  it('injects workspace path into prompt', () => {
    const prompt = getScannerPrompt('apis', '/my/custom/project');
    expect(prompt).toContain('/my/custom/project');
  });

  it('defaults to "unknown" for missing gitInfo', () => {
    const prompt = getScannerPrompt('structure', '/tmp/test');
    // The placeholders are replaced with 'unknown' when gitInfo is missing
    // The {COMMIT_HASH} and {BRANCH} placeholders get replaced with 'unknown'
    expect(prompt).toContain('unknown');
  });
});

describe('parseFrontmatter integration', () => {
  it('parses frontmatter with nested _scanned object', () => {
    const content = `---\nname: project-apis\ndescription: "API endpoints catalog"\n_scanned:\n  commitHash: "abc123"\n  scannedAt: "2026-04-09T10:00:00Z"\n  branch: main\n---\n\n# Content`;
    const result = parseFrontmatter(content);
    expect(result).not.toBeNull();
    expect(result?._scanned?.commitHash).toBe('abc123');
    expect(result?._scanned?.branch).toBe('main');
  });

  it('returns null when frontmatter is unclosed', () => {
    const content = `---\nname: test\ndescription: "No closing"`;
    expect(parseFrontmatter(content)).toBeNull();
  });

  it('returns null for content without any frontmatter marker', () => {
    const content = `# Just a title\n\nSome content`;
    expect(parseFrontmatter(content)).toBeNull();
  });
});
