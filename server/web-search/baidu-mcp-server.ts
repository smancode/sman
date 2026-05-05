/**
 * Baidu AI Search MCP Server — provides web_search and web_search_deep tools.
 *
 * web_search_deep: POST /v2/ai_search/chat/completions (AI summary + references, 100 free/day)
 * web_search:      POST /v2/ai_search/web_search (pure search results, 100 free/day)
 *
 * Strategy: prefer web_search_deep, fallback to web_search on rate limit.
 * Auth: Bearer <API Key>
 * Docs: https://ai.baidu.com/ai-doc/AppBuilder/amaxd2det
 */

import { z } from 'zod';
import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import type { McpSdkServerConfigWithInstance } from '@anthropic-ai/claude-agent-sdk';
import { createLogger } from '../utils/logger.js';

const log = createLogger('baidu-search-mcp');

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

function textResult(text: string, isError = false): ToolResult {
  return { content: [{ type: 'text', text }], isError };
}

function errorResult(message: string): ToolResult {
  return textResult(message, true);
}

function isRateLimitError(e: any): boolean {
  const msg = (e.message || '').toLowerCase();
  return msg.includes('429') || msg.includes('rate') || msg.includes('limit') || msg.includes('quota');
}

const WEB_SEARCH_URL = 'https://qianfan.baidubce.com/v2/ai_search/web_search';
const CHAT_SEARCH_URL = 'https://qianfan.baidubce.com/v2/ai_search/chat/completions';
const SEARCH_TIMEOUT = 30_000;

interface BaiduReference {
  id: number;
  title: string;
  url: string;
  content: string;
  snippet: string;
  date: string;
  type: string;
  website: string;
}

function formatReferences(references: BaiduReference[]): string {
  return references.map((r, i) => {
    const parts = [`${i + 1}. **${r.title}**`];
    parts.push(`   URL: ${r.url}`);
    if (r.date) parts.push(`   Date: ${r.date}`);
    const snippet = r.snippet || r.content || '';
    if (snippet) parts.push(`   ${snippet.slice(0, 300)}`);
    return parts.join('\n');
  }).join('\n\n');
}

/** web_search_deep: AI summary + references */
async function deepSearch(apiKey: string, query: string, maxResults = 10): Promise<{ answer: string; references: BaiduReference[] }> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), SEARCH_TIMEOUT);

  try {
    const resp = await fetch(CHAT_SEARCH_URL, {
      method: 'POST',
      signal: controller.signal,
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        messages: [{ role: 'user', content: query }],
        model: 'ernie-4.5-turbo-128k',
        max_completion_tokens: 12288,
        search_source: 'baidu_search_v2',
        resource_type_filter: [{ type: 'web', top_k: maxResults }],
        stream: false,
        search_mode: 'auto',
        enable_corner_markers: true,
      }),
    });

    if (!resp.ok) {
      const errText = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status}: ${errText.slice(0, 300)}`);
    }

    const data = await resp.json() as any;

    if (data.code || data.error_code) {
      throw new Error(`Baidu API error ${data.code || data.error_code}: ${data.message || data.error_msg || 'Unknown error'}`);
    }

    const answer = data.choices?.[0]?.message?.content || '';
    const references = (data.references || []) as BaiduReference[];
    return { answer, references };
  } finally {
    clearTimeout(timeout);
  }
}

/** web_search: pure search results */
async function plainSearch(apiKey: string, query: string, maxResults = 10): Promise<BaiduReference[]> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), SEARCH_TIMEOUT);

  try {
    const resp = await fetch(WEB_SEARCH_URL, {
      method: 'POST',
      signal: controller.signal,
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        messages: [{ role: 'user', content: query }],
        resource_type_filter: [{ type: 'web', top_k: maxResults }],
        edition: 'standard',
        search_recency_filter: 'noTimeLimit',
      }),
    });

    if (!resp.ok) {
      const errText = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status}: ${errText.slice(0, 300)}`);
    }

    const data = await resp.json() as any;

    if (data.code) {
      throw new Error(`Baidu API error ${data.code}: ${data.message || 'Unknown error'}`);
    }

    return (data.references || []) as BaiduReference[];
  } finally {
    clearTimeout(timeout);
  }
}

export function createBaiduSearchMcpServer(apiKey: string): McpSdkServerConfigWithInstance {
  const deepSearchTool = tool(
    'web_search',
    'Search the web using Baidu AI Search. Returns an AI-generated summary with source references. '
    + 'If rate-limited, automatically falls back to a pure search endpoint. '
    + 'Use this to find documentation, answers to questions, or current events. '
    + 'After finding relevant results, use web_fetch to read specific pages in detail.',
    {
      query: z.string().describe('Search query. Use concise, specific keywords for best results.'),
    },
    async (args: any) => {
      const { query } = args;
      log.info(`web_search (Baidu deep): "${query}"`);

      try {
        const { answer, references } = await deepSearch(apiKey, query);

        const parts: string[] = [];
        if (answer) parts.push(answer);

        if (references.length > 0) {
          parts.push('', '---', '**Sources:**', '', formatReferences(references));
          parts.push('', `Found ${references.length} sources. Use web_fetch to read any URL for details.`);
        }

        if (parts.length === 0) {
          return textResult(`No results found for "${query}". Please try a different query.`);
        }

        return textResult(parts.join('\n'));
      } catch (deepError: any) {
        // Rate limited — fallback to plain search
        if (isRateLimitError(deepError)) {
          log.warn(`web_search_deep rate limited, falling back to web_search: ${deepError.message}`);
        } else {
          log.warn(`web_search_deep failed (${deepError.message}), trying web_search fallback`);
        }

        try {
          const references = await plainSearch(apiKey, query);

          if (references.length === 0) {
            return textResult(`No results found for "${query}". Please try a different query.`);
          }

          const lines = formatReferences(references);
          return textResult(lines + '\n\n' + `Found ${references.length} results. Use web_fetch to read any URL for details.`);
        } catch (e: any) {
          log.error(`Baidu web_search (both endpoints) failed: ${e.message}`);
          return errorResult(`Search failed: ${e.message}. Please check your Baidu API Key and try again.`);
        }
      }
    },
  );

  return createSdkMcpServer({
    name: 'baidu-search',
    version: '1.0.0',
    tools: [deepSearchTool],
  });
}
