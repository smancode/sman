/**
 * MCP Server auto-configuration based on settings.
 *
 * All search providers now use in-process SDK servers (no npx dependency).
 * Search MCP servers are injected directly in claude-session.ts via createSdkMcpServer.
 */

import type { SmanConfig } from './types.js';
import type { McpServerConfig } from '@anthropic-ai/claude-agent-sdk';

export function buildMcpServers(_config: SmanConfig): Record<string, McpServerConfig> {
  // All web search providers now use in-process SDK servers.
  // See claude-session.ts for injection logic.
  return {};
}

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
