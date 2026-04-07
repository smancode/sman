/**
 * Capability Registry — loads and queries capabilities.
 *
 * Standard capabilities: ~/.sman/capabilities.json (system-generated, safe to overwrite)
 * User capabilities:     ~/.sman/user-capabilities.json (LLM-learned, never auto-overwritten)
 * Usage tracking:        ~/.sman/capability-usage.json  (frequency + success stats)
 */

import fs from 'node:fs';
import path from 'node:path';
import type {
  CapabilityManifest,
  CapabilityEntry,
  SemanticSearchLlmConfig,
  CapabilityUsageManifest,
  CapabilityUsageEntry,
  UserCapabilityManifest,
  UserCapabilityEntry,
} from './types.js';

export class CapabilityRegistry {
  private manifest: CapabilityManifest | null = null;
  private usageManifest: CapabilityUsageManifest | null = null;
  private userCapabilities: UserCapabilityManifest | null = null;
  private readonly homeDir: string;

  constructor(homeDir: string) {
    this.homeDir = homeDir;
  }

  // --- Manifest Loading ---

  /** Load (or return cached) standard capabilities manifest */
  load(): CapabilityManifest {
    if (this.manifest) return this.manifest;

    const registryPath = path.join(this.homeDir, 'capabilities.json');
    if (!fs.existsSync(registryPath)) {
      this.manifest = { version: '1.0', capabilities: {} };
      return this.manifest;
    }

    const raw = fs.readFileSync(registryPath, 'utf-8');
    this.manifest = JSON.parse(raw) as CapabilityManifest;
    return this.manifest;
  }

  /** Force reload from disk */
  reload(): CapabilityManifest {
    this.manifest = null;
    return this.load();
  }

  // --- Query Methods ---

  /** Get a single capability by ID */
  getCapability(id: string): CapabilityEntry | undefined {
    return this.load().capabilities[id];
  }

  /** List all enabled capabilities */
  listCapabilities(): CapabilityEntry[] {
    return Object.values(this.load().capabilities).filter((c) => c.enabled);
  }

  /** Legacy: single-keyword substring search (backward compat) */
  search(query: string): CapabilityEntry[] {
    const q = query.toLowerCase();
    return this.listCapabilities().filter((c) => {
      const haystack = [c.id, c.name, c.description, ...c.triggers].join(' ').toLowerCase();
      return haystack.includes(q);
    });
  }

  /**
   * Keyword OR search — match any keyword against triggers, name, description, id.
   * Returns deduplicated results ordered by usage frequency (most used first).
   */
  searchByKeywords(keywords: string[]): CapabilityEntry[] {
    if (keywords.length === 0) return [];

    const all = this.listCapabilities();
    const usage = this.loadUsage();
    const matched = new Map<string, CapabilityEntry>();

    for (const kw of keywords) {
      const q = kw.toLowerCase();
      for (const cap of all) {
        if (matched.has(cap.id)) continue;
        const haystack = [cap.id, cap.name, cap.description, ...cap.triggers].join(' ').toLowerCase();
        if (haystack.includes(q)) {
          matched.set(cap.id, cap);
        }
      }
    }

    // Also search user capabilities
    const userCaps = this.loadUserCapabilities();
    for (const kw of keywords) {
      const q = kw.toLowerCase();
      for (const [id, uc] of Object.entries(userCaps.userCapabilities)) {
        if (matched.has(id)) continue;
        const haystack = [id, uc.name, uc.pattern, ...uc.shortcuts].join(' ').toLowerCase();
        if (haystack.includes(q)) {
          // Convert user capability to a synthetic CapabilityEntry
          matched.set(id, {
            id,
            name: uc.name,
            description: uc.pattern,
            executionMode: 'instruction-inject' as const,
            triggers: uc.shortcuts,
            runnerModule: '',
            pluginPath: '',
            enabled: true,
            version: '1.0.0',
          });
        }
      }
    }

    // Sort by usage frequency (most used first)
    const results = Array.from(matched.values());
    results.sort((a, b) => {
      const aUsage = usage.usage[a.id]?.count ?? 0;
      const bUsage = usage.usage[b.id]?.count ?? 0;
      return bUsage - aUsage;
    });

    return results;
  }

  /**
   * Semantic search using LLM — for when keyword matching returns nothing.
   * Sends capability summaries + user's natural language description to LLM,
   * LLM returns matching capability IDs.
   */
  async searchSemantic(
    naturalLanguage: string,
    _keywords: string[],
    llmConfig: SemanticSearchLlmConfig,
  ): Promise<string[]> {
    if (!naturalLanguage.trim()) return [];

    const allCaps = this.listCapabilities();
    const userCaps = this.loadUserCapabilities();

    if (allCaps.length === 0 && Object.keys(userCaps.userCapabilities).length === 0) {
      return [];
    }

    // Build compact capability summary for the prompt
    const capSummaries = allCaps.map((c) => ({
      id: c.id,
      name: c.name,
      description: c.description,
      triggers: c.triggers.slice(0, 8),
    }));

    const userCapSummaries = Object.entries(userCaps.userCapabilities).map(([id, uc]) => ({
      id,
      name: uc.name,
      pattern: uc.pattern,
      shortcuts: uc.shortcuts,
      isUserLearned: true,
    }));

    const systemPrompt = `You are a capability matcher. Given a user's request, determine which capabilities (if any) match.

Available capabilities:
${JSON.stringify(capSummaries, null, 2)}

User-learned capabilities:
${JSON.stringify(userCapSummaries, null, 2)}

Rules:
- Return ONLY a JSON array of matching capability IDs, e.g. ["office-skills"]
- Return [] if no capability matches the request
- Match based on what the user wants to DO, not just keywords
- A capability matches if it can help accomplish the user's task
- Do NOT return capabilities that are unrelated to the request`;

    try {
      const baseUrl = llmConfig.baseUrl?.replace(/\/$/, '') ?? 'https://api.anthropic.com';
      const response = await fetch(`${baseUrl}/v1/messages`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-api-key': llmConfig.apiKey,
          'anthropic-version': '2023-06-01',
          'anthropic-dangerous-direct-browser-access': 'true',
        },
        body: JSON.stringify({
          model: llmConfig.model,
          max_tokens: 256,
          messages: [
            { role: 'user', content: `User request: "${naturalLanguage}"\n\nWhich capabilities match?` },
          ],
          system: systemPrompt,
        }),
      });

      if (!response.ok) {
        return [];
      }

      const data = await response.json() as any;
      const text = data.content?.[0]?.text ?? '[]';

      // Extract JSON array from response
      const match = text.match(/\[[\s\S]*?\]/);
      if (!match) return [];

      const ids: string[] = JSON.parse(match[0]);
      // Validate IDs exist
      const validIds = new Set([
        ...allCaps.map((c) => c.id),
        ...Object.keys(userCaps.userCapabilities),
      ]);
      return ids.filter((id) => validIds.has(id));
    } catch {
      return [];
    }
  }

  // --- Usage Tracking ---

  /** Record a capability usage event */
  recordUsage(capabilityId: string, task?: string, success?: boolean): void {
    const usage = this.loadUsage();

    if (!usage.usage[capabilityId]) {
      usage.usage[capabilityId] = {
        count: 0,
        lastUsed: null,
        successRate: 1.0,
        recentTasks: [],
      };
    }

    const entry = usage.usage[capabilityId];
    entry.count++;
    entry.lastUsed = new Date().toISOString();

    if (task) {
      entry.recentTasks = [task, ...entry.recentTasks].slice(0, 10);
    }

    if (success !== undefined) {
      // Running average approximation
      const totalAttempts = entry.count;
      const prevSuccesses = entry.successRate * (totalAttempts - 1);
      entry.successRate = (prevSuccesses + (success ? 1 : 0)) / totalAttempts;
    }

    this.saveUsage(usage);
  }

  /** Get usage stats for a specific capability */
  getUsage(capabilityId: string): CapabilityUsageEntry | undefined {
    return this.loadUsage().usage[capabilityId];
  }

  // --- User Capability Management ---

  /** Add or update a user-learned capability */
  saveUserCapability(entry: UserCapabilityEntry): void {
    const manifest = this.loadUserCapabilities();
    manifest.userCapabilities[entry.id] = entry;
    this.saveUserCapabilities(manifest);
  }

  /** Get all user capabilities */
  listUserCapabilities(): UserCapabilityEntry[] {
    return Object.values(this.loadUserCapabilities().userCapabilities);
  }

  /** Get a specific user capability */
  getUserCapability(id: string): UserCapabilityEntry | undefined {
    return this.loadUserCapabilities().userCapabilities[id];
  }

  // --- Private: File I/O ---

  private loadUsage(): CapabilityUsageManifest {
    if (this.usageManifest) return this.usageManifest;

    const usagePath = path.join(this.homeDir, 'capability-usage.json');
    if (!fs.existsSync(usagePath)) {
      this.usageManifest = { version: '1.0', usage: {} };
      return this.usageManifest;
    }

    try {
      const raw = fs.readFileSync(usagePath, 'utf-8');
      this.usageManifest = JSON.parse(raw) as CapabilityUsageManifest;
    } catch {
      this.usageManifest = { version: '1.0', usage: {} };
    }
    return this.usageManifest!;
  }

  private saveUsage(manifest: CapabilityUsageManifest): void {
    const usagePath = path.join(this.homeDir, 'capability-usage.json');
    fs.writeFileSync(usagePath, JSON.stringify(manifest, null, 2), 'utf-8');
    this.usageManifest = manifest;
  }

  private loadUserCapabilities(): UserCapabilityManifest {
    if (this.userCapabilities) return this.userCapabilities;

    const userCapPath = path.join(this.homeDir, 'user-capabilities.json');
    if (!fs.existsSync(userCapPath)) {
      this.userCapabilities = { version: '1.0', userCapabilities: {} };
      return this.userCapabilities;
    }

    try {
      const raw = fs.readFileSync(userCapPath, 'utf-8');
      this.userCapabilities = JSON.parse(raw) as UserCapabilityManifest;
    } catch {
      this.userCapabilities = { version: '1.0', userCapabilities: {} };
    }
    return this.userCapabilities!;
  }

  private saveUserCapabilities(manifest: UserCapabilityManifest): void {
    const userCapPath = path.join(this.homeDir, 'user-capabilities.json');
    fs.writeFileSync(userCapPath, JSON.stringify(manifest, null, 2), 'utf-8');
    this.userCapabilities = manifest;
  }
}
