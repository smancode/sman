// src/core/learning/analyzer.ts
/**
 * Learning Analyzer
 *
 * Analyzes completed conversations and extracts learnable content.
 */

import type { ChatMessage } from "../openclaw/types";
import { buildLearningPrompt } from "./prompts";

/**
 * Learning update to be applied
 */
export interface LearningUpdate {
  type: "habit" | "memory" | "skill";
  path: string;
  content: string;
  reason: string;
}

/**
 * File system interface for learning analyzer
 */
export interface LearningFs {
  readFile(path: string): Promise<string>;
  writeFile(path: string, content: string): Promise<void>;
  appendFile(path: string, content: string): Promise<void>;
  exists(path: string): Promise<boolean>;
}

/**
 * LLM client interface
 */
export interface LLMClient {
  chat(messages: ChatMessage[]): Promise<{ content: string }>;
}

/**
 * Learning Analyzer
 *
 * Uses LLM to analyze conversations and extract learnable content.
 */
export class LearningAnalyzer {
  constructor(
    private llmClient: LLMClient,
    private fs: LearningFs,
    private homePath: string,
  ) {}

  /**
   * Analyze a conversation for learning opportunities
   */
  async analyze(
    conversation: ChatMessage[],
    projectPath: string,
    projectContext: string,
  ): Promise<LearningUpdate[]> {
    // Build the analysis prompt
    const prompt = buildLearningPrompt(conversation, projectContext);

    // Ask LLM to analyze
    const response = await this.llmClient.chat([
      { role: "user", content: prompt },
    ]);

    // Parse the response
    return this.parseResponse(response.content);
  }

  /**
   * Apply learning updates to file system
   */
  async applyUpdates(
    updates: LearningUpdate[],
    projectPath: string,
  ): Promise<void> {
    for (const update of updates) {
      const resolvedPath = this.resolvePath(update.path, projectPath);

      switch (update.type) {
        case "habit":
          // Append to habits file
          await this.appendWithSeparator(resolvedPath, update.content);
          break;

        case "memory":
          // Append to memory file
          await this.appendWithSeparator(resolvedPath, update.content);
          break;

        case "skill":
          // Create or overwrite skill file
          await this.fs.writeFile(resolvedPath, update.content);
          break;
      }
    }
  }

  /**
   * Analyze and apply in one step
   */
  async analyzeAndApply(
    conversation: ChatMessage[],
    projectPath: string,
    projectContext: string,
  ): Promise<LearningUpdate[]> {
    const updates = await this.analyze(
      conversation,
      projectPath,
      projectContext,
    );

    if (updates.length > 0) {
      await this.applyUpdates(updates, projectPath);
    }

    return updates;
  }

  // Private helpers

  private parseResponse(response: string): LearningUpdate[] {
    // Check for no-update signal
    if (response.includes("NO_UPDATE")) {
      return [];
    }

    // Extract JSON block
    const jsonMatch = response.match(/```json\s*([\s\S]*?)\s*```/);
    if (!jsonMatch) {
      return [];
    }

    try {
      const parsed = JSON.parse(jsonMatch[1]);

      if (!Array.isArray(parsed)) {
        return [];
      }

      // Validate and filter
      return parsed.filter(
        (u) =>
          u.type &&
          u.path &&
          u.content &&
          u.reason &&
          ["habit", "memory", "skill"].includes(u.type),
      ) as LearningUpdate[];
    } catch {
      return [];
    }
  }

  private resolvePath(path: string, projectPath: string): string {
    // Handle home directory
    if (path.startsWith("~/")) {
      return path.replace("~", this.homePath);
    }

    // Handle project-relative paths
    if (path.startsWith("./") || path.startsWith(".sman/")) {
      return `${projectPath}/${path.replace(/^\.\//, "")}`;
    }

    // Absolute path
    return path;
  }

  private async appendWithSeparator(
    path: string,
    content: string,
  ): Promise<void> {
    const separator = "\n\n---\n\n";
    const timestamp = new Date().toISOString();

    const formattedContent = `<!-- Added ${timestamp} -->\n${content}`;

    const exists = await this.fs.exists(path);
    if (exists) {
      await this.fs.appendFile(path, separator + formattedContent);
    } else {
      await this.fs.writeFile(path, formattedContent);
    }
  }
}
