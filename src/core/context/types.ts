// src/core/context/types.ts
/**
 * Context Management Types
 *
 * SMAN manages context from multiple sources with clear priority:
 * Personal habits > Project config > OpenClaw defaults
 */

/**
 * Project-level context from .sman/ directory
 */
export interface ProjectContext {
  path: string;
  name: string;
  /** .sman/SOUL.md - Project mission/values */
  soul?: string;
  /** .sman/AGENTS.md - Role definitions */
  agents?: string;
  /** .sman/skills/*.md - Domain-specific skills */
  skills: ContextFile[];
  /** .sman/memory/*.md - Project knowledge */
  memory: ContextFile[];
}

/**
 * User-level habits from ~/.smanlocal/ directory
 */
export interface UserHabits {
  /** ~/.smanlocal/habits.md - User preferences */
  preferences: string;
  /** ~/.smanlocal/skills/*.md - Personal skills */
  globalSkills: ContextFile[];
}

/**
 * Generic file content holder
 */
export interface ContextFile {
  name: string;
  path: string;
  content: string;
}

/**
 * Configuration for context loading
 */
export interface ContextConfig {
  projectPath: string;
  homePath: string;
  /** Maximum skills to include in prompt */
  maxSkills?: number;
  /** Maximum tokens for skills section */
  maxSkillTokens?: number;
}

/**
 * Built system prompt ready for LLM
 */
export interface BuiltPrompt {
  systemPrompt: string;
  includedSkills: string[];
  tokenEstimate: number;
  sources: {
    hasSoul: boolean;
    hasAgents: boolean;
    hasMemory: boolean;
    hasHabits: boolean;
    skillCount: number;
  };
}
