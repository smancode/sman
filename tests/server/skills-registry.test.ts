import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { SkillsRegistry } from '../../server/skills-registry.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('SkillsRegistry', () => {
  let registry: SkillsRegistry;
  let homeDir: string;

  beforeEach(() => {
    homeDir = path.join(os.tmpdir(), `smanbase-reg-${Date.now()}`);
    fs.mkdirSync(homeDir, { recursive: true });
    fs.mkdirSync(path.join(homeDir, 'skills'), { recursive: true });

    fs.mkdirSync(path.join(homeDir, 'skills', 'java-scanner'), { recursive: true });
    fs.writeFileSync(
      path.join(homeDir, 'skills', 'java-scanner', 'skill.md'),
      '# Java Scanner\nScans Java projects.'
    );

    const registryJson = {
      version: '1.0',
      skills: {
        'java-scanner': {
          name: 'Java Scanner',
          description: 'Scans Java projects',
          version: '1.0.0',
          path: 'skills/java-scanner',
          triggers: ['auto-on-init', 'manual'],
          tags: ['java'],
        },
      },
    };
    fs.writeFileSync(
      path.join(homeDir, 'registry.json'),
      JSON.stringify(registryJson, null, 2)
    );

    registry = new SkillsRegistry(homeDir);
  });

  afterEach(() => {
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  it('should list all available skills', () => {
    const skills = registry.listSkills();
    expect(skills).toHaveLength(1);
    expect(skills[0].name).toBe('Java Scanner');
  });

  it('should get a specific skill', () => {
    const skill = registry.getSkill('java-scanner');
    expect(skill).toBeDefined();
    expect(skill!.version).toBe('1.0.0');
  });

  it('should return undefined for non-existent skill', () => {
    const skill = registry.getSkill('non-existent');
    expect(skill).toBeUndefined();
  });

  it('should get skill directory path', () => {
    const dir = registry.getSkillDir('java-scanner');
    expect(fs.existsSync(dir)).toBe(true);
  });

  it('should check if skill exists', () => {
    expect(registry.hasSkill('java-scanner')).toBe(true);
    expect(registry.hasSkill('non-existent')).toBe(false);
  });
});
