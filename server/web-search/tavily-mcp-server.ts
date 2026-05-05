/**
 * Tavily Search MCP Server — in-process, no npx dependency.
 *
 * Endpoint: POST https://api.tavily.com/search
 * Auth: api_key in request body
 */

import { z } from 'zod';
import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import type { McpSdkServerConfigWithInstance } from '@anthropic-ai/claude-agent-sdk';
import { createLogger } from '../utils/logger.js';

const log = createLogger('tavily-search-mcp');

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

function textResult(text: string, isError = false): ToolResult {
  return { content: [{ type: 'text', text }], isError };
}

function errorResult(message: string): ToolResult {
  return textResult(message, true);
}

const TAVILY_SEARCH_URL = 'https://api.tavily.com/search';
const SEARCH_TIMEOUT = 15_000;

interface TavilyResult {
  title: string;
  url: string;
  content: string;
  score: number;
}

async function searchTavily(apiKey: string, query: string, maxResults = 10): Promise<{ answer?: string; results: TavilyResult[] }> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), SEARCH_TIMEOUT);

  try {
    const resp = await fetch(TAVILY_SEARCH_URL, {
      method: 'POST',
      signal: controller.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        api_key: apiKey,
        query,
        search_depth: 'basic',
        include_answer: true,
        max_results: maxResults,
      }),
    });

    if (!resp.ok) {
      const errText = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status}: ${errText.slice(0, 300)}`);
    }

    const data = await resp.json() as any;

    if (data.error) {
      throw new Error(`Tavily API error: ${data.error}`);
    }

    const results = (data.results || []).map((r: any) => ({
      title: r.title || '',
      url: r.url || '',
      content: r.content || '',
      score: r.score || 0,
    }));

    return { answer: data.answer || undefined, results };
  } finally {
    clearTimeout(timeout);
  }
}

export function createTavilySearchMcpServer(apiKey: string): McpSdkServerConfigWithInstance {
  const searchTool = tool(
    'web_search',
    'Search the web using Tavily AI Search. Returns an AI-generated answer with source references. '
    + 'Use this to find documentation, answers to questions, or current events. '
    + 'After finding relevant results, use web_fetch to read specific pages in detail.',
    {
      query: z.string().describe('Search query. Use concise, specific keywords for best results.'),
    },
    async (args: any) => {
      const { query } = args;
      log.info(`web_search (Tavily): "${query}"`);

      try {
        const { answer, results } = await searchTavily(apiKey, query);

        const parts: string[] = [];
        if (answer) parts.push(answer);

        if (results.length > 0) {
          parts.push('', '---', '**Sources:**', '');
          results.forEach((r, i) => {
            parts.push(`${i + 1}. **${r.title}** — ${r.url}`);
            if (r.content) parts.push(`   ${r.content.slice(0, 200)}`);
          });
          parts.push('', `Found ${results.length} sources. Use web_fetch to read any URL for details.`);
        }

        if (parts.length === 0) {
          return textResult(`No results found for "${query}". Please try a different query.`);
        }

        return textResult(parts.join('\n'));
      } catch (e: any) {
        log.error(`Tavily web_search failed: ${e.message}`);
        return errorResult(`Search failed: ${e.message}. Please check your Tavily API Key and try again.`);
      }
    },
  );

  return createSdkMcpServer({
    name: 'tavily-search',
    version: '1.0.0',
    tools: [searchTool],
  });
}
