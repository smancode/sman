/**
 * MCP Server auto-configuration based on settings.
 *
 * Generates MCP server configs for web search providers
 * that can be passed to Claude Agent SDK options.
 */

import type { SmanConfig } from './types.js';
import type { McpServerConfig } from '@anthropic-ai/claude-agent-sdk';

export function buildMcpServers(config: SmanConfig): Record<string, McpServerConfig> {
  const servers: Record<string, McpServerConfig> = {};
  const { webSearch } = config;

  switch (webSearch.provider) {
    case 'brave': {
      if (webSearch.braveApiKey) {
        servers['brave-search'] = {
          type: 'stdio',
          command: 'npx',
          args: ['-y', '@anthropic-ai/mcp-server-brave'],
          env: { BRAVE_API_KEY: webSearch.braveApiKey },
        };
      }
      break;
    }
    case 'tavily': {
      if (webSearch.tavilyApiKey) {
        servers['tavily-search'] = {
          type: 'stdio',
          command: 'npx',
          args: ['-y', '@anthropic-ai/mcp-server-tavily'],
          env: { TAVILY_API_KEY: webSearch.tavilyApiKey },
        };
      }
      break;
    }
    case 'builtin':
    default:
      // Claude Code has built-in web search, no MCP server needed
      break;
  }

  return servers;
}

/**
 * Generate CLAUDE.md template content for a business system profile.
 * This gets stored in the profile's claudeMdTemplate field.
 */
export function generateClaudeMdTemplate(profile: {
  name: string;
  description: string;
  skills: string[];
}): string {
  const lines: string[] = [
    `# ${profile.name}`,
    '',
    profile.description,
    '',
  ];

  if (profile.skills.length > 0) {
    lines.push('## Skills');
    lines.push('The following skills are available for this system:');
    lines.push('');
    for (const skill of profile.skills) {
      lines.push(`- /${skill}`);
    }
    lines.push('');
  }

  lines.push('## Guidelines');
  lines.push('- Always respond in Simplified Chinese unless the user explicitly asks otherwise');
  lines.push('- Focus on the business domain of this system');
  lines.push('- Use available tools to inspect and modify code when asked');

  return lines.join('\n');
}
