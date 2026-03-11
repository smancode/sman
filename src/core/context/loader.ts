// src/core/context/loader.ts
/**
 * Context Loader
 *
 * Loads context from:
 * 1. ~/.smanlocal/ - User habits (highest priority)
 * 2. project/.sman/ - Project context
 * 3. project/.claude/ - ClaudeCode compatibility
 */

import type {
  ProjectContext,
  UserHabits,
  ContextFile,
  ContextConfig,
} from "./types";

const SMAN_DIR = ".sman";
const SMAN_LOCAL_DIR = ".smanlocal";
const CLAUDE_DIR = ".claude";

/**
 * File system interface for dependency injection
 * Allows different implementations for Tauri vs Node vs Browser
 */
export interface FileSystem {
  readFile(path: string): Promise<string>;
  listFiles(dir: string): Promise<string[]>;
  exists(path: string): Promise<boolean>;
}

export class ContextLoader {
  private fs: FileSystem;
  private homePath: string;

  constructor(fs: FileSystem, homePath: string) {
    this.fs = fs;
    this.homePath = homePath;
  }

  /**
   * Load project context from .sman/ directory
   */
  async loadProjectContext(projectPath: string): Promise<ProjectContext> {
    const smanPath = `${projectPath}/${SMAN_DIR}`;

    const [soul, agents, skills, memory] = await Promise.all([
      this.tryReadFile(`${smanPath}/SOUL.md`),
      this.tryReadFile(`${smanPath}/AGENTS.md`),
      this.loadMarkdownFiles(`${smanPath}/skills`),
      this.loadMarkdownFiles(`${smanPath}/memory`),
    ]);

    return {
      path: projectPath,
      name: this.extractProjectName(projectPath),
      soul,
      agents,
      skills,
      memory,
    };
  }

  /**
   * Load user habits from ~/.smanlocal/
   */
  async loadUserHabits(): Promise<UserHabits> {
    const localPath = `${this.homePath}/${SMAN_LOCAL_DIR}`;

    const [preferences, globalSkills] = await Promise.all([
      this.tryReadFile(`${localPath}/habits.md`),
      this.loadMarkdownFiles(`${localPath}/skills`),
    ]);

    return {
      preferences: preferences || "",
      globalSkills,
    };
  }

  /**
   * Check if project has .sman directory
   */
  async hasSmanContext(projectPath: string): Promise<boolean> {
    return this.fs.exists(`${projectPath}/${SMAN_DIR}`);
  }

  /**
   * Load ClaudeCode skills for compatibility
   */
  async loadClaudeSkills(projectPath: string): Promise<ContextFile[]> {
    return this.loadMarkdownFiles(`${projectPath}/${CLAUDE_DIR}/skills`);
  }

  // Private helpers

  private extractProjectName(projectPath: string): string {
    const parts = projectPath.split("/");
    return parts[parts.length - 1] || projectPath;
  }

  private async loadMarkdownFiles(dir: string): Promise<ContextFile[]> {
    try {
      const files = await this.fs.listFiles(dir);
      const mdFiles = files.filter((f) => f.endsWith(".md"));

      const results = await Promise.all(
        mdFiles.map(async (fileName) => {
          const filePath = `${dir}/${fileName}`;
          const content = await this.tryReadFile(filePath);
          if (content) {
            return {
              name: fileName.replace(".md", ""),
              path: filePath,
              content,
            };
          }
          return null;
        }),
      );

      return results.filter((r): r is NonNullable<typeof r> => r !== null);
    } catch {
      return [];
    }
  }

  private async tryReadFile(path: string): Promise<string | undefined> {
    try {
      return await this.fs.readFile(path);
    } catch {
      return undefined;
    }
  }
}

/**
 * Tauri-based file system implementation
 */
export class TauriFileSystem implements FileSystem {
  async readFile(path: string): Promise<string> {
    const { invoke } = await import("@tauri-apps/api/core");
    return invoke("read_text_file", { path });
  }

  async listFiles(dir: string): Promise<string[]> {
    const { invoke } = await import("@tauri-apps/api/core");
    return invoke("list_directory", { path: dir });
  }

  async exists(path: string): Promise<boolean> {
    const { invoke } = await import("@tauri-apps/api/core");
    return invoke("file_exists", { path });
  }
}

/**
 * Create a context loader for Tauri environment
 */
export function createTauriContextLoader(): ContextLoader {
  return new ContextLoader(new TauriFileSystem(), "");
}
