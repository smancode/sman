// src/core/skills/selector.ts
/**
 * Skill Selector
 *
 * Selects the most relevant skills based on user input.
 * Implements progressive disclosure to avoid token bloat.
 */

import type {
  SkillFile,
  SelectedSkill,
  SkillSelectionOptions,
  SkillSelectionResult,
} from "./types";

export class SkillSelector {
  private defaultOptions: Required<SkillSelectionOptions>;

  constructor(options?: SkillSelectionOptions) {
    this.defaultOptions = {
      maxSkills: options?.maxSkills ?? 5,
      maxTokens: options?.maxTokens ?? 8000,
      minRelevance: options?.minRelevance ?? 0.2,
      preferred: options?.preferred ?? [],
      excluded: options?.excluded ?? [],
    };
  }

  /**
   * Select most relevant skills for user input
   */
  select(
    userInput: string,
    skills: SkillFile[],
    options?: SkillSelectionOptions,
  ): SkillSelectionResult {
    const opts = { ...this.defaultOptions, ...options };

    // Filter out excluded skills
    const available = skills.filter((s) => !opts.excluded.includes(s.name));

    // Calculate relevance scores
    const scored = available.map((skill) => ({
      skill,
      relevance: this.calculateRelevance(userInput, skill, opts.preferred),
    }));

    // Filter by minimum relevance
    const relevant = scored.filter((s) => s.relevance >= opts.minRelevance);

    // Sort by relevance
    relevant.sort((a, b) => b.relevance - a.relevance);

    // Select top skills within token budget
    const selected: SelectedSkill[] = [];
    let totalTokens = 0;

    for (const { skill, relevance } of relevant) {
      if (selected.length >= opts.maxSkills) break;

      const skillTokens = this.estimateTokens(skill.content);
      if (totalTokens + skillTokens > opts.maxTokens) break;

      selected.push({
        name: skill.name,
        content: skill.content,
        relevance,
        source: skill.path.includes(".smanlocal") ? "user" : "project",
      });
      totalTokens += skillTokens;
    }

    return {
      selected,
      total: skills.length,
      filtered: available.length - relevant.length,
      tokens: totalTokens,
    };
  }

  /**
   * Quick check if any skill is relevant
   */
  hasRelevantSkill(userInput: string, skills: SkillFile[]): boolean {
    return skills.some(
      (skill) =>
        this.calculateRelevance(userInput, skill, []) >=
        this.defaultOptions.minRelevance,
    );
  }

  // Private helpers

  private calculateRelevance(
    userInput: string,
    skill: SkillFile,
    preferred: string[],
  ): number {
    const inputLower = userInput.toLowerCase();
    const contentLower = skill.content.toLowerCase();
    const nameLower = skill.name.toLowerCase();

    let score = 0;

    // 1. Name match (strongest signal)
    if (inputLower.includes(nameLower)) {
      score += 0.5;
    }
    if (nameLower.split(/[-_]/).some((part) => inputLower.includes(part))) {
      score += 0.3;
    }

    // 2. Keyword matching
    const keywords = this.extractKeywords(skill.content);
    const matchedKeywords = keywords.filter((kw) =>
      inputLower.includes(kw.toLowerCase()),
    );
    score += Math.min(0.3, matchedKeywords.length * 0.05);

    // 3. "When to Use" section match
    const whenSection = this.extractWhenToUse(skill.content);
    if (whenSection && this.hasOverlap(inputLower, whenSection.toLowerCase())) {
      score += 0.2;
    }

    // 4. Preferred boost
    if (preferred.includes(skill.name)) {
      score += 0.3;
    }

    return Math.min(score, 1);
  }

  private extractKeywords(content: string): string[] {
    // Extract from headings and bold text
    const headings = content.match(/^#+\s+(.+)$/gm) || [];
    const bold = content.match(/\*\*(.+?)\*\*/g) || [];
    const code = content.match(/`([^`]+)`/g) || [];

    const allText = [...headings, ...bold, ...code]
      .map((s) => s.replace(/[#*`]/g, "").trim())
      .join(" ");

    const words = allText.split(/\s+/).filter((w) => w.length > 3);
    return [...new Set(words)].slice(0, 20);
  }

  private extractWhenToUse(content: string): string | null {
    const match = content.match(/##?\s*When [Tt]o [Uu]se([\s\S]*?)(?=##|$)/);
    return match ? match[1].trim() : null;
  }

  private hasOverlap(text1: string, text2: string): boolean {
    const words1 = new Set(text1.split(/\s+/).filter((w) => w.length > 3));
    const words2 = text2.split(/\s+/).filter((w) => w.length > 3);
    return words2.some((w) => words1.has(w));
  }

  private estimateTokens(text: string): number {
    return Math.ceil(text.length / 3.5);
  }
}
