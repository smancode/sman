// src/core/skills/scanner.ts
/**
 * Skill Scanner
 *
 * Scans directories for skill files (.md files with frontmatter)
 */

import type { SkillFile } from "./types";

export interface SkillScannerFs {
  readFile(path: string): Promise<string>;
  listFiles(dir: string): Promise<string[]>;
  exists(path: string): Promise<boolean>;
}

export class SkillScanner {
  constructor(private fs: SkillScannerFs) {}

  /**
   * Scan a directory for skill files
   */
  async scanDirectory(dir: string): Promise<SkillFile[]> {
    try {
      const exists = await this.fs.exists(dir);
      if (!exists) return [];

      const files = await this.fs.listFiles(dir);
      const skillFiles = files.filter((f) => f.endsWith(".md"));

      const results = await Promise.all(
        skillFiles.map(async (fileName) => {
          const filePath = `${dir}/${fileName}`;
          try {
            const content = await this.fs.readFile(filePath);
            return {
              name: fileName.replace(".md", ""),
              path: filePath,
              content,
            };
          } catch {
            return null;
          }
        }),
      );

      return results.filter((r): r is NonNullable<typeof r> => r !== null);
    } catch {
      return [];
    }
  }

  /**
   * Scan multiple directories for skills
   */
  async scanDirectories(dirs: string[]): Promise<SkillFile[]> {
    const allSkills = await Promise.all(
      dirs.map((dir) => this.scanDirectory(dir)),
    );

    // Deduplicate by name (later directories override earlier ones)
    const skillMap = new Map<string, SkillFile>();
    for (const skills of allSkills) {
      for (const skill of skills) {
        skillMap.set(skill.name, skill);
      }
    }

    return Array.from(skillMap.values());
  }
}
