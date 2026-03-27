import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from './utils/logger.js';
import type { SkillEntry, Registry } from './types.js';

export interface ProjectSkill {
  id: string;
  name: string;
  description: string;
  content: string;
}

export class SkillsRegistry {
  private homeDir: string;
  private registryPath: string;
  private registry: Registry | null = null;
  private log: Logger;

  constructor(homeDir: string) {
    this.homeDir = homeDir;
    this.registryPath = path.join(homeDir, 'registry.json');
    this.log = createLogger('SkillsRegistry');
  }

  private load(): Registry {
    if (this.registry) return this.registry;

    if (!fs.existsSync(this.registryPath)) {
      this.log.warn('registry.json not found, creating empty');
      this.registry = { version: '1.0', skills: {} };
      return this.registry;
    }

    const raw = fs.readFileSync(this.registryPath, 'utf-8');
    this.registry = JSON.parse(raw) as Registry;
    this.log.info(`Loaded registry with ${Object.keys(this.registry.skills).length} skills`);
    return this.registry;
  }

  listSkills(): (SkillEntry & { id: string })[] {
    const reg = this.load();
    return Object.entries(reg.skills).map(([id, skill]) => ({
      id,
      ...skill,
    }));
  }

  getSkill(id: string): SkillEntry | undefined {
    return this.load().skills[id];
  }

  hasSkill(id: string): boolean {
    return id in this.load().skills;
  }

  getSkillDir(id: string): string {
    const skill = this.getSkill(id);
    if (!skill) throw new Error(`Skill not found: ${id}`);
    return path.join(this.homeDir, skill.path);
  }

  getSkillDirs(skillIds: string[]): string[] {
    return skillIds
      .filter(id => this.hasSkill(id))
      .map(id => this.getSkillDir(id));
  }

  /**
   * Get all skill directories for a workspace:
   * 1. Global skills from ~/.sman/skills/
   * 2. Project-specific skills from {workspace}/.claude/skills/
   */
  getAllSkillDirs(workspace: string): string[] {
    const dirs: string[] = [];

    // 1. Global skills directory
    const globalSkillsDir = path.join(this.homeDir, 'skills');
    if (fs.existsSync(globalSkillsDir)) {
      dirs.push(globalSkillsDir);
    }

    // 2. Project-specific skills directory
    const projectSkillsDir = path.join(workspace, '.claude', 'skills');
    if (fs.existsSync(projectSkillsDir)) {
      dirs.push(projectSkillsDir);
    }

    return dirs;
  }

  /**
   * Get project-specific skills from {workspace}/.claude/skills/
   * Reads skill.md frontmatter for description (first ~10 lines max).
   */
  getProjectSkills(workspace: string): ProjectSkill[] {
    const projectSkillsDir = path.join(workspace, '.claude', 'skills');
    if (!fs.existsSync(projectSkillsDir)) {
      return [];
    }

    const skills: ProjectSkill[] = [];
    const entries = fs.readdirSync(projectSkillsDir, { withFileTypes: true });

    for (const entry of entries) {
      if (entry.isDirectory()) {
        const skillId = entry.name;
        const skillMdPath = path.join(projectSkillsDir, skillId, 'skill.md');
        let description = '';

        if (fs.existsSync(skillMdPath)) {
          // Read only the first 10 lines to parse frontmatter
          const lines = fs.readFileSync(skillMdPath, 'utf-8').split('\n').slice(0, 10);
          for (const line of lines) {
            const match = line.match(/^description:\s*(.+)/);
            if (match) {
              description = match[1].trim();
              break;
            }
          }
        }

        skills.push({
          id: skillId,
          name: skillId,
          description,
          content: '',
        });
      }
    }

    return skills;
  }
}
