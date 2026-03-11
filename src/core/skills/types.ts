// src/core/skills/types.ts
/**
 * Skill Selection Types
 *
 * Types for skill discovery, filtering, and selection.
 */

/**
 * Skill file with metadata
 */
export interface SkillFile {
  name: string;
  path: string;
  content: string;
  /** When the skill was last modified */
  updatedAt?: number;
}

/**
 * Skill selected for inclusion in prompt
 */
export interface SelectedSkill {
  name: string;
  content: string;
  /** Relevance score 0-1 */
  relevance: number;
  /** Source of the skill */
  source: "project" | "user" | "claude";
}

/**
 * Options for skill selection
 */
export interface SkillSelectionOptions {
  /** Maximum number of skills to select */
  maxSkills?: number;
  /** Maximum total tokens for skills */
  maxTokens?: number;
  /** Minimum relevance threshold */
  minRelevance?: number;
  /** Preferred skills (boosted relevance) */
  preferred?: string[];
  /** Excluded skills */
  excluded?: string[];
}

/**
 * Result of skill selection
 */
export interface SkillSelectionResult {
  selected: SelectedSkill[];
  total: number;
  filtered: number;
  tokens: number;
}
