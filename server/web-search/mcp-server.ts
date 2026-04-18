/**
 * WebSearch MCP Server — provides web_search and web_fetch tools.
 *
 * Injected when webSearch provider is 'builtin' and the user is on a
 * non-Anthropic proxy (where Claude Code's built-in WebSearch doesn't work).
 *
 * Uses SearXNG public instances for search (no API key required).
 * Falls back to Google HTML scraping if SearXNG is unavailable.
 * Requires network access to search engines (direct or via proxy).
 */

import { z } from 'zod';
import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import type { McpSdkServerConfigWithInstance } from '@anthropic-ai/claude-agent-sdk';
import { createLogger } from '../utils/logger.js';

const log = createLogger('web-search-mcp');

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

function textResult(text: string, isError = false): ToolResult {
  return { content: [{ type: 'text', text }], isError };
}

function errorResult(message: string): ToolResult {
  return textResult(message, true);
}

interface SearchResult {
  title: string;
  snippet: string;
  url: string;
}

const SEARCH_TIMEOUT = 15_000;

/**
 * Search using SearXNG public API.
 * Try multiple instances for resilience.
 */
async function searchSearXNG(query: string, maxResults = 8): Promise<SearchResult[]> {
  const instances = [
    'https://searx.be',
    'https://search.sapti.me',
    'https://searxng.ch',
    'https://search.bus-hit.me',
    'https://searx.tiekoetter.com',
  ];

  for (const baseUrl of instances) {
    try {
      const url = `${baseUrl}/search?q=${encodeURIComponent(query)}&format=json&categories=general&language=zh-CN`;
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), SEARCH_TIMEOUT);

      const resp = await fetch(url, {
        signal: controller.signal,
        headers: {
          'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36',
          'Accept': 'application/json',
        },
      });
      clearTimeout(timeout);

      if (!resp.ok) continue;

      const data = await resp.json() as any;
      if (!data?.results?.length) continue;

      log.info(`SearXNG search via ${baseUrl}: ${data.results.length} results`);

      return data.results
        .slice(0, maxResults)
        .map((r: any) => ({
          title: (r.title || '').replace(/<[^>]*>/g, ''),
          snippet: (r.content || '').replace(/<[^>]*>/g, ''),
          url: r.url || '',
        }))
        .filter((r: SearchResult) => r.title && r.url);
    } catch {
      continue;
    }
  }

  return [];
}

/**
 * Fetch a URL and extract its text content.
 */
async function fetchUrlContent(url: string, maxLength = 5000): Promise<string> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15_000);

  try {
    const resp = await fetch(url, {
      signal: controller.signal,
      headers: {
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36',
        'Accept': 'text/html,text/plain,application/json',
      },
    });

    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);

    const contentType = resp.headers.get('content-type') || '';
    const text = await resp.text();

    if (contentType.includes('application/json')) {
      try {
        const formatted = JSON.stringify(JSON.parse(text), null, 2);
        return formatted.slice(0, maxLength);
      } catch {
        return text.slice(0, maxLength);
      }
    }

    const cleaned = text
      .replace(/<script[\s\S]*?<\/script>/gi, '')
      .replace(/<style[\s\S]*?<\/style>/gi, '')
      .replace(/<[^>]+>/g, ' ')
      .replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, '&')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&#\d+;/g, '')
      .replace(/\s+/g, ' ')
      .trim();

    return cleaned.slice(0, maxLength);
  } finally {
    clearTimeout(timeout);
  }
}

export function isAnthropicFirstParty(baseUrl?: string): boolean {
  if (!baseUrl) return true;
  const lower = baseUrl.toLowerCase();
  return lower.includes('anthropic.com');
}

export function createWebSearchMcpServer(): McpSdkServerConfigWithInstance {
  const searchTool = tool(
    'web_search',
    'Search the web for current information. Returns search results with titles, snippets, and URLs. '
    + 'Use this to find documentation, answers to questions, or current events. '
    + 'After finding relevant results, use web_fetch to read specific pages in detail.',
    {
      query: z.string().describe('Search query. Use concise, specific keywords for best results.'),
    },
    async (args: any) => {
      const { query } = args;
      log.info(`web_search: "${query}"`);

      try {
        const results = await searchSearXNG(query);

        if (results.length === 0) {
          return textResult(
            `No results found for "${query}". `
            + 'This may be due to network issues — ensure the server can reach search engines (directly or via proxy). '
            + 'Alternatively, configure a search provider (Brave/Tavily) in Settings.',
          );
        }

        const lines = results.map((r, i) =>
          `${i + 1}. **${r.title}**\n   URL: ${r.url}\n   ${r.snippet}`
        );
        lines.push('', `Found ${results.length} results. Use web_fetch to read any URL for details.`);
        return textResult(lines.join('\n\n'));
      } catch (e: any) {
        log.error(`web_search failed: ${e.message}`);
        return errorResult(`Search failed: ${e.message}. Please try again or use a different query.`);
      }
    },
  );

  const fetchTool = tool(
    'web_fetch',
    'Fetch and extract the text content of a web page. Use this to read the full content of search results '
    + 'or any public URL. Returns cleaned text with HTML tags removed.',
    {
      url: z.string().describe('The URL to fetch'),
      max_length: z.number().optional().describe('Maximum characters to return (default 5000)'),
    },
    async (args: any) => {
      const url = args.url as string;
      const maxLength = (args.max_length as number) || 5000;
      log.info(`web_fetch: ${url}`);

      try {
        const content = await fetchUrlContent(url, maxLength);
        if (!content) {
          return errorResult(`No content returned from ${url}`);
        }
        return textResult(`Content from ${url}:\n\n${content}`);
      } catch (e: any) {
        log.error(`web_fetch failed: ${e.message}`);
        return errorResult(`Failed to fetch ${url}: ${e.message}`);
      }
    },
  );

  return createSdkMcpServer({
    name: 'web-search',
    version: '1.0.0',
    tools: [searchTool, fetchTool],
  });
}

/**
 * Should we inject the fallback web-search MCP server?
 *
 * Claude Code's built-in WebSearch uses Anthropic's server_tool_use protocol (web_search_20250305),
 * executed by Anthropic's backend. Third-party proxies (Kimi, Zhipu, etc.) don't support this
 * protocol and return errors like 'function "web_search" not found'.
 *
 * Inject our own MCP-based web_search + web_fetch tools when:
 * 1. webSearch provider is 'builtin' (user hasn't configured Brave/Tavily/Bing)
 * 2. AND the API is likely NOT Anthropic first-party
 */
export function needsFallbackWebSearch(config: { llm?: { baseUrl?: string }; webSearch?: { provider: string } }): boolean {
  if (!config.webSearch || config.webSearch.provider !== 'builtin') return false;
  return !isAnthropicFirstParty(config.llm?.baseUrl);
}
