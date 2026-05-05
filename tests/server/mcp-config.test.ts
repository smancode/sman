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
      bingApiKey: '',
      baiduApiKey: '',
      maxUsesPerSession: 50,
    },
  };

  it('should return empty servers — all providers now use in-process SDK servers', () => {
    const servers = buildMcpServers(baseConfig);
    expect(Object.keys(servers)).toHaveLength(0);
  });

  it('should return empty even for brave provider — handled in-process', () => {
    const config: SmanConfig = {
      ...baseConfig,
      webSearch: {
        ...baseConfig.webSearch,
        provider: 'brave',
        braveApiKey: 'brave-key-123',
      },
    };

    const servers = buildMcpServers(config);
    expect(Object.keys(servers)).toHaveLength(0);
  });

  it('should return empty even for tavily provider — handled in-process', () => {
    const config: SmanConfig = {
      ...baseConfig,
      webSearch: {
        ...baseConfig.webSearch,
        provider: 'tavily',
        tavilyApiKey: 'tavily-key-456',
      },
    };

    const servers = buildMcpServers(config);
    expect(Object.keys(servers)).toHaveLength(0);
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
