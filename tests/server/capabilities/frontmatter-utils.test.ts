import { describe, it, expect } from 'vitest';
import { parseFrontmatter, validateSkillMd } from '../../../server/capabilities/frontmatter-utils.js';
import fs from 'node:fs';
import path from 'path';
import os from 'node:os';

describe('parseFrontmatter', () => {
  it('parses valid frontmatter', () => {
    const content = `---\nname: project-structure\ndescription: "Test description"\n---\n\n# Content`;
    const result = parseFrontmatter(content);
    expect(result.name).toBe('project-structure');
    expect(result.description).toBe('Test description');
  });

  it('returns null for missing frontmatter', () => {
    const content = `# No frontmatter\nContent`;
    expect(parseFrontmatter(content)).toBeNull();
  });

  it('parses _scanned fields', () => {
    const content = `---\nname: project-apis\ndescription: "APIs"\n_scanned:\n  commitHash: "abc123"\n  scannedAt: "2026-04-09T10:00:00Z"\n  branch: main\n---\n\n# Content`;
    const result = parseFrontmatter(content);
    expect(result._scanned?.commitHash).toBe('abc123');
    expect(result._scanned?.scannedAt).toBe('2026-04-09T10:00:00Z');
    expect(result._scanned?.branch).toBe('main');
  });

  it('returns null for unclosed frontmatter', () => {
    const content = `---\nname: test\n# missing closing ---`;
    expect(parseFrontmatter(content)).toBeNull();
  });

  it('parses empty frontmatter block', () => {
    const content = `---\n---\n\n# Content`;
    const result = parseFrontmatter(content);
    expect(result).toEqual({});
  });
});

describe('validateSkillMd', () => {
  it('returns true for valid SKILL.md', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    const filePath = path.join(tmpDir, 'SKILL.md');
    fs.writeFileSync(filePath, `---\nname: project-structure\ndescription: "Project structure skill with enough chars"\n_scanned:\n  commitHash: "abc123"\n  scannedAt: "2026-04-09T10:00:00Z"\n---\n\n# Content\nTable here`);
    try {
      expect(validateSkillMd(filePath)).toBe(true);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  it('returns false for missing name', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    const filePath = path.join(tmpDir, 'SKILL.md');
    fs.writeFileSync(filePath, `---\ndescription: "No name field"\n_scanned:\n  commitHash: "abc"\n---\n\n# Content`);
    try {
      expect(validateSkillMd(filePath)).toBe(false);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  it('returns false for short description', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    const filePath = path.join(tmpDir, 'SKILL.md');
    fs.writeFileSync(filePath, `---\nname: test\ndescription: "ab"\n_scanned:\n  commitHash: "abc"\n---\n\n# Content`);
    try {
      expect(validateSkillMd(filePath)).toBe(false);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  it('returns false for missing commitHash', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    const filePath = path.join(tmpDir, 'SKILL.md');
    fs.writeFileSync(filePath, `---\nname: test\ndescription: "Valid description here"\n---\n\n# Content`);
    try {
      expect(validateSkillMd(filePath)).toBe(false);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  it('returns false when file exceeds 80 lines', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    const filePath = path.join(tmpDir, 'SKILL.md');
    const lines = ['---\nname: test\ndescription: "Valid description with enough text"\n_scanned:\n  commitHash: "abc"\n---\n'];
    for (let i = 0; i < 81; i++) lines.push(`# Line ${i}`);
    fs.writeFileSync(filePath, lines.join('\n'));
    try {
      expect(validateSkillMd(filePath)).toBe(false);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  it('returns false for non-existent file', () => {
    expect(validateSkillMd('/nonexistent/SKILL.md')).toBe(false);
  });
});