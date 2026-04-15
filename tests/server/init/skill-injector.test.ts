import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { injectSkills } from '../../../server/init/skill-injector.js';

describe('SkillInjector', () => {
  let tmpDir: string;
  let pluginsDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-inject-test-'));
    pluginsDir = path.join(tmpDir, 'plugins');
    fs.mkdirSync(pluginsDir, { recursive: true });
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('copies SKILL.md from plugin to workspace .claude/skills/', () => {
    const pluginDir = path.join(pluginsDir, 'my-skill');
    fs.mkdirSync(pluginDir);
    fs.writeFileSync(path.join(pluginDir, 'SKILL.md'), '---\nname: my-skill\n---\nContent');

    const workspace = path.join(tmpDir, 'workspace');
    fs.mkdirSync(workspace);

    const result = injectSkills(
      [{ capabilityId: 'my-skill', pluginPath: 'my-skill' }],
      pluginsDir,
      workspace,
    );

    expect(result).toEqual(['my-skill']);
    const target = path.join(workspace, '.claude', 'skills', 'my-skill', 'SKILL.md');
    expect(fs.existsSync(target)).toBe(true);
    expect(fs.readFileSync(target, 'utf-8')).toContain('Content');
  });

  it('copies auxiliary files (.md, .py, .css, .json)', () => {
    const pluginDir = path.join(pluginsDir, 'my-skill');
    fs.mkdirSync(pluginDir);
    fs.writeFileSync(path.join(pluginDir, 'SKILL.md'), 'content');
    fs.writeFileSync(path.join(pluginDir, 'checklist.md'), '- item 1');
    fs.writeFileSync(path.join(pluginDir, 'template.json'), '{}');
    fs.writeFileSync(path.join(pluginDir, 'SKILL.md.tmpl'), 'template');

    const workspace = path.join(tmpDir, 'workspace');
    fs.mkdirSync(workspace);

    injectSkills(
      [{ capabilityId: 'my-skill', pluginPath: 'my-skill' }],
      pluginsDir,
      workspace,
    );

    const skillsDir = path.join(workspace, '.claude', 'skills', 'my-skill');
    expect(fs.existsSync(path.join(skillsDir, 'SKILL.md'))).toBe(true);
    expect(fs.existsSync(path.join(skillsDir, 'checklist.md'))).toBe(true);
    expect(fs.existsSync(path.join(skillsDir, 'template.json'))).toBe(true);
    expect(fs.existsSync(path.join(skillsDir, 'SKILL.md.tmpl'))).toBe(false);
  });

  it('does not overwrite existing skills', () => {
    const workspace = path.join(tmpDir, 'workspace');
    const existingDir = path.join(workspace, '.claude', 'skills', 'my-skill');
    fs.mkdirSync(existingDir, { recursive: true });
    fs.writeFileSync(path.join(existingDir, 'SKILL.md'), 'USER CUSTOM CONTENT');

    const pluginDir = path.join(pluginsDir, 'my-skill');
    fs.mkdirSync(pluginDir);
    fs.writeFileSync(path.join(pluginDir, 'SKILL.md'), 'PLUGIN CONTENT');

    injectSkills(
      [{ capabilityId: 'my-skill', pluginPath: 'my-skill' }],
      pluginsDir,
      workspace,
    );

    expect(fs.readFileSync(path.join(existingDir, 'SKILL.md'), 'utf-8')).toBe('USER CUSTOM CONTENT');
  });

  it('skips skills whose plugin directory does not exist', () => {
    const workspace = path.join(tmpDir, 'workspace');
    fs.mkdirSync(workspace);

    const result = injectSkills(
      [{ capabilityId: 'missing', pluginPath: 'nonexistent-plugin' }],
      pluginsDir,
      workspace,
    );

    expect(result).toEqual([]);
  });

  it('copies subdirectories (references/, templates/)', () => {
    const pluginDir = path.join(pluginsDir, 'my-skill');
    fs.mkdirSync(path.join(pluginDir, 'references'), { recursive: true });
    fs.mkdirSync(path.join(pluginDir, 'templates'), { recursive: true });
    fs.writeFileSync(path.join(pluginDir, 'SKILL.md'), 'content');
    fs.writeFileSync(path.join(pluginDir, 'references', 'taxonomy.md'), 'taxonomy');
    fs.writeFileSync(path.join(pluginDir, 'templates', 'report.md'), 'template');

    const workspace = path.join(tmpDir, 'workspace');
    fs.mkdirSync(workspace);

    injectSkills(
      [{ capabilityId: 'my-skill', pluginPath: 'my-skill' }],
      pluginsDir,
      workspace,
    );

    const skillsDir = path.join(workspace, '.claude', 'skills', 'my-skill');
    expect(fs.existsSync(path.join(skillsDir, 'references', 'taxonomy.md'))).toBe(true);
    expect(fs.existsSync(path.join(skillsDir, 'templates', 'report.md'))).toBe(true);
  });
});
