/**
 * Experience Learner — learns reusable patterns from conversations via LLM.
 *
 * After a conversation turn, analyzes whether any reusable capability patterns emerged.
 * Learned capabilities are stored separately in user-capabilities.json,
 * completely isolated from standard capabilities.json.
 *
 * Uses the same fire-and-forget pattern as UserProfileManager.
 */

import type { CapabilityRegistry } from './registry.js';
import type { SemanticSearchLlmConfig, UserCapabilityEntry } from './types.js';

const LEARNING_SYSTEM_PROMPT = `You are a capability pattern extractor. Analyze the conversation and determine if any reusable capability patterns emerged.

Rules:
1. Only extract patterns that are genuinely reusable (the user will likely do similar tasks again)
2. Each pattern should capture: what the user wanted, what tools/capabilities were used, and any shortcuts
3. Do NOT extract one-off tasks or simple questions
4. Do NOT duplicate existing capabilities — only extract genuinely NEW patterns
5. Each capability needs: a unique id (kebab-case), a name, the pattern description, which standard capability it learned from, and shortcut keywords

Output format — respond with ONLY a JSON object:
{
  "newCapabilities": [
    {
      "id": "unique-kebab-id",
      "name": "Display name",
      "pattern": "Description of the reusable pattern",
      "learnedFrom": "standard-capability-id or null",
      "shortcuts": ["keyword1", "keyword2"]
    }
  ]
}

If no new patterns emerged, return: {"newCapabilities": []}`;

export async function learnFromConversation(
  registry: CapabilityRegistry,
  conversationText: string,
  llmConfig: SemanticSearchLlmConfig,
): Promise<void> {
  if (!conversationText.trim()) return;

  // Get existing user capabilities for context
  const existingCaps = registry.listUserCapabilities();
  const existingNames = existingCaps.map((c) => c.name).join(', ');

  const userMessage = `Existing user capabilities: ${existingNames || '(none)'}

Recent conversation:
${conversationText.slice(0, 4000)}

Analyze this conversation. Did any new reusable capability patterns emerge?
If yes, extract them. If the user's task was routine or one-off, return empty.`;

  try {
    const baseUrl = (llmConfig.baseUrl ?? 'https://api.anthropic.com').replace(/\/$/, '');
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
        max_tokens: 1024,
        messages: [{ role: 'user', content: userMessage }],
        system: LEARNING_SYSTEM_PROMPT,
      }),
    });

    if (!response.ok) return;

    const data = await response.json() as any;
    const text = data.content?.[0]?.text ?? '';

    // Extract JSON from response
    const jsonMatch = text.match(/\{[\s\S]*\}/);
    if (!jsonMatch) return;

    const parsed = JSON.parse(jsonMatch[0]);
    const newCapabilities = parsed.newCapabilities ?? [];

    if (!Array.isArray(newCapabilities) || newCapabilities.length === 0) return;

    const now = new Date().toISOString();
    for (const cap of newCapabilities) {
      if (!cap.id || !cap.name || !cap.pattern) continue;

      // Check if this capability already exists (by ID or by similar pattern)
      const existing = registry.getUserCapability(cap.id);
      if (existing) {
        // Update existing — increment usage count, update lastUsed
        registry.saveUserCapability({
          ...existing,
          lastUsed: now,
        });
        continue;
      }

      const entry: UserCapabilityEntry = {
        id: cap.id,
        name: cap.name,
        pattern: cap.pattern,
        learnedFrom: cap.learnedFrom ?? 'unknown',
        shortcuts: cap.shortcuts ?? [],
        usageCount: 1,
        createdAt: now,
        lastUsed: now,
      };

      registry.saveUserCapability(entry);
    }
  } catch {
    // Silently fail — this is fire-and-forget, never block the user
  }
}
