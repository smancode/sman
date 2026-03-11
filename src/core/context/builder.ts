// src/core/context/builder.ts
/**
 * System Prompt Builder
 *
 * Builds the final system prompt from multiple context sources.
 * Priority: User habits > Project skills > OpenClaw defaults
 */

import type { ProjectContext, UserHabits, BuiltPrompt } from "./types";
import type { SelectedSkill } from "../skills/types";

export class SystemPromptBuilder {
  private maxSkillTokens: number;

  constructor(maxSkillTokens: number = 8000) {
    this.maxSkillTokens = maxSkillTokens;
  }

  /**
   * Build the complete system prompt
   */
  build(
    projectContext: ProjectContext | null,
    userHabits: UserHabits | null,
    selectedSkills: SelectedSkill[],
  ): BuiltPrompt {
    const parts: string[] = [];
    let tokenEstimate = 0;
    const includedSkills: string[] = [];

    // 1. Project Soul (Mission/Values)
    if (projectContext?.soul) {
      const section = this.formatSection("项目使命", projectContext.soul);
      parts.push(section);
      tokenEstimate += this.estimateTokens(section);
    }

    // 2. Project Agents (Role definitions)
    if (projectContext?.agents) {
      const section = this.formatSection("角色定义", projectContext.agents);
      parts.push(section);
      tokenEstimate += this.estimateTokens(section);
    }

    // 3. Selected Skills (Token-limited)
    const skillSection = this.buildSkillSection(
      selectedSkills,
      this.maxSkillTokens,
    );
    if (skillSection.content) {
      parts.push(skillSection.content);
      tokenEstimate += skillSection.tokens;
      includedSkills.push(...skillSection.included);
    }

    // 4. Project Memory (Knowledge base)
    if (projectContext?.memory && projectContext.memory.length > 0) {
      const memoryContent = projectContext.memory
        .map((m) => `### ${m.name}\n\n${m.content}`)
        .join("\n\n");
      const section = this.formatSection("项目知识", memoryContent);
      parts.push(section);
      tokenEstimate += this.estimateTokens(section);
    }

    // 5. User Habits (Highest priority, goes last)
    if (userHabits?.preferences) {
      const section = this.formatSection("用户习惯", userHabits.preferences);
      parts.push(section);
      tokenEstimate += this.estimateTokens(section);
    }

    return {
      systemPrompt: parts.join("\n\n---\n\n"),
      includedSkills,
      tokenEstimate,
      sources: {
        hasSoul: !!projectContext?.soul,
        hasAgents: !!projectContext?.agents,
        hasMemory: !!(
          projectContext?.memory && projectContext.memory.length > 0
        ),
        hasHabits: !!userHabits?.preferences,
        skillCount: includedSkills.length,
      },
    };
  }

  /**
   * Build minimal prompt for learning analysis
   */
  buildMinimalPrompt(
    projectContext: ProjectContext | null,
    userHabits: UserHabits | null,
  ): string {
    const parts: string[] = [];

    if (projectContext?.soul) {
      parts.push(`项目: ${projectContext.name}`);
      parts.push(`使命: ${projectContext.soul.slice(0, 200)}...`);
    }

    if (projectContext?.memory && projectContext.memory.length > 0) {
      const memoryNames = projectContext.memory.map((m) => m.name).join(", ");
      parts.push(`已有知识: ${memoryNames}`);
    }

    if (userHabits?.preferences) {
      parts.push(`用户习惯: ${userHabits.preferences.slice(0, 100)}...`);
    }

    return parts.join("\n");
  }

  // Private helpers

  private formatSection(title: string, content: string): string {
    return `## ${title}\n\n${content}`;
  }

  private buildSkillSection(
    skills: SelectedSkill[],
    maxTokens: number,
  ): { content: string; tokens: number; included: string[] } {
    // Sort by relevance
    const sorted = [...skills].sort((a, b) => b.relevance - a.relevance);

    const included: string[] = [];
    let content = "";
    let tokens = 0;

    for (const skill of sorted) {
      const skillContent = `### ${skill.name}\n\n${skill.content}`;
      const skillTokens = this.estimateTokens(skillContent);

      if (tokens + skillTokens > maxTokens) {
        break;
      }

      if (content) content += "\n\n";
      content += skillContent;
      tokens += skillTokens;
      included.push(skill.name);
    }

    if (included.length > 0) {
      content = `## 可用技能\n\n${content}`;
      tokens += this.estimateTokens("## 可用技能\n\n");
    }

    return { content, tokens, included };
  }

  private estimateTokens(text: string): number {
    // Rough estimate: ~4 characters per token for mixed Chinese/English
    return Math.ceil(text.length / 3.5);
  }
}
