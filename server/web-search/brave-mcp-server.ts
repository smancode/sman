/**
 * Brave Search MCP Server — in-process, no npx dependency.
 *
 * Endpoint: GET https://api.search.brave.com/res/v1/web/search
 * Auth: X-Subscription-Token header
 */

import { z } from 'zod';
import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import type { McpSdkServerConfigWithInstance } from '@anthropic-ai/claude-agent-sdk';
import { createLogger } from '../utils/logger.js';

const log = createLogger('brave-search-mcp');

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

function textResult(text: string, isError = false): ToolResult {
  return { content: [{ type: 'text', text }], isError };
}

function errorResult(message: string): ToolResult {
  return textResult(message, true);
}

const BRAVE_SEARCH_URL = 'https://api.search.brave.com/res/v1/web/search';
const SEARCH_TIMEOUT = 15_000;

interface BraveResult {
  title: string;
  url: string;
  description?: string;
  age?: string;
}

async function searchBrave(apiKey: string, query: string, maxResults = 10): Promise<BraveResult[]> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), SEARCH_TIMEOUT);

  try {
    const params = new URLSearchParams({ q: query, count: String(maxResults) });
    const resp = await fetch(`${BRAVE_SEARCH_URL}?${params}`, {
      signal: controller.signal,
      headers: {
        'Accept': 'application/json',
        'X-Subscription-Token': apiKey,
      },
    });

    if (!resp.ok) {
      const errText = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status}: ${errText.slice(0, 300)}`);
    }

    const data = await resp.json() as any;
    const webResults = data.web?.results || data.results || [];
    return webResults.map((r: any) => ({
      title: r.title || '',
      url: r.url || '',
      description: r.description || '',
      age: r.age || '',
    }));
  } finally {
    clearTimeout(timeout);
  }
}

export function createBraveSearchMcpServer(apiKey: string): McpSdkServerConfigWithInstance {
  const searchTool = tool(
    'web_search',
    'Search the web using Brave Search. Returns search results with titles, descriptions, and URLs. '
    + 'Use this to find documentation, answers to questions, or current events. '
    + 'After finding relevant results, use web_fetch to read specific pages in detail.',
    {
      query: z.string().describe('Search query. Use concise, specific keywords for best results.'),
    },
    async (args: any) => {
      const { query } = args;
      log.info(`web_search (Brave): "${query}"`);

      try {
        const results = await searchBrave(apiKey, query);

        if (results.length === 0) {
          return textResult(`No results found for "${query}". Please try a different query.`);
        }

        const lines = results.map((r, i) => {
          const parts = [`${i + 1}. **${r.title}**`];
          parts.push(`   URL: ${r.url}`);
          if (r.age) parts.push(`   Date: ${r.age}`);
          if (r.description) parts.push(`   ${r.description.slice(0, 300)}`);
          return parts.join('\n');
        });
        lines.push('', `Found ${results.length} results. Use web_fetch to read any URL for details.`);
        return textResult(lines.join('\n\n'));
      } catch (e: any) {
        log.error(`Brave web_search failed: ${e.message}`);
        return errorResult(`Search failed: ${e.message}. Please check your Brave API Key and try again.`);
      }
    },
  );

  return createSdkMcpServer({
    name: 'brave-search',
    version: '1.0.0',
    tools: [searchTool],
  });
}
