/**
 * Token Counter
 *
 * Uses Anthropic's official tokenizer to count tokens in text.
 * This allows Sman to calculate context usage independently of SDK's usage reports.
 */
import { countTokens } from '@anthropic-ai/tokenizer';
import { createLogger } from './utils/logger.js';

const log = createLogger('TokenCounter');

/**
 * Count tokens in a single text string.
 */
export function countTextTokens(text: string): number {
  try {
    return countTokens(text);
  } catch (err) {
    log.warn(`Token counting failed: ${err instanceof Error ? err.message : String(err)}`);
    // Fallback: rough estimate (1 token ≈ 4 chars for English, 1-2 chars for CJK)
    return Math.ceil(text.length / 3);
  }
}

/**
 * Count tokens in a message (content + contentBlocks).
 */
export function countMessageTokens(content: string, contentBlocks?: Array<{ type: string; text?: string; thinking?: string }>): number {
  let total = countTextTokens(content);
  if (contentBlocks) {
    for (const block of contentBlocks) {
      if (block.type === 'text' && block.text) {
        total += countTextTokens(block.text);
      } else if (block.type === 'thinking' && block.thinking) {
        total += countTextTokens(block.thinking);
      }
    }
  }
  return total;
}

/**
 * Estimate system prompt tokens.
 * This is a rough estimate since we don't have exact system prompt text.
 */
export function estimateSystemPromptTokens(): number {
  // Typical system prompt is ~500-2000 tokens
  // We'll use a conservative estimate
  return 1000;
}
