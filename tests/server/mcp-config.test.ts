import { describe, it, expect } from 'vitest';
import { buildMcpServers, generateClaudeMdTemplate } from '../../server/mcp-config.js';
import type { SmanConfig } from '../../server/types.js';

describe('MCP Config', () => {
  const baseConfig: SmanConfig = {
    port: 5880,
    llm: { apiKey: 'test-key', model: 'claude-sonnet-4-6' },
    webSearch: {
      provider: 'builtin',
      braveApiKey: '',
      tavilyApiKey: '',
      maxUsesPerSession: 50,
    },
  };

  it('should return empty servers for builtin provider', () => {
    const servers = buildMcpServers(baseConfig);
    expect(Object.keys(servers)).toHaveLength(0);
  });

  it('should configure Brave Search MCP server', () => {
    const config: SmanConfig = {
      ...baseConfig,
      webSearch: {
        ...baseConfig.webSearch,
        provider: 'brave',
        braveApiKey: 'brave-key-123',
      },
    };

    const servers = buildMcpServers(config);
    expect(servers['brave-search']).toBeDefined();
    expect(servers['brave-search'].type).toBe('stdio');
    expect(servers['brave-search'].env?.BRAVE_API_KEY).toBe('brave-key-123');
  });

  it('should not configure Brave without API key', () => {
    const config: SmanConfig = {
      ...baseConfig,
      webSearch: {
        ...baseConfig.webSearch,
        provider: 'brave',
        braveApiKey: '',
      },
    };

    const servers = buildMcpServers(config);
    expect(Object.keys(servers)).toHaveLength(0);
  });

  it('should configure Tavily MCP server', () => {
    const config: SmanConfig = {
      ...baseConfig,
      webSearch: {
        ...baseConfig.webSearch,
        provider: 'tavily',
        tavilyApiKey: 'tavily-key-456',
      },
    };

    const servers = buildMcpServers(config);
    expect(servers['tavily-search']).toBeDefined();
    expect(servers['tavily-search'].type).toBe('stdio');
    expect(servers['tavily-search'].env?.TAVILY_API_KEY).toBe('tavily-key-456');
  });
});

describe('generateClaudeMdTemplate', () => {
  it('should generate basic template', () => {
    const template = generateClaudeMdTemplate({
      name: 'Test Project',
      description: 'A test project',
      skills: [],
    });

    expect(template).toContain('# Test Project');
    expect(template).toContain('A test project');
    expect(template).not.toContain('## Skills');
  });

  it('should include skills section when skills exist', () => {
    const template = generateClaudeMdTemplate({
      name: 'My App',
      description: 'My application',
      skills: ['scanner', 'deploy'],
    });

    expect(template).toContain('## Skills');
    expect(template).toContain('- /scanner');
    expect(template).toContain('- /deploy');
  });

  it('should include guidelines', () => {
    const template = generateClaudeMdTemplate({
      name: 'App',
      description: 'Desc',
      skills: [],
    });

    expect(template).toContain('## Guidelines');
    expect(template).toContain('Simplified Chinese');
  });
});
