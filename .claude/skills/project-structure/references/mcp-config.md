# MCP Config (server/mcp-config.ts)

Auto-configures Web Search MCP servers based on user settings.

## Supported Providers

- **Brave Search**: Requires `braveApiKey` in config
- **Tavily**: Requires `tavilyApiKey` in config
- **Bing**: Requires `bingApiKey` in config
- **Baidu**: Requires `baiduApiKey` in config
- **Anthropic Builtin**: No API key required (rate-limited)

## Key Function

`buildMcpServers(config, webAccessService)`: Returns array of MCP server configs

## Provider Selection

User selects provider in Settings UI. Only selected provider's MCP is injected.

## Max Uses Per Session

Configurable limit (default 50) to prevent runaway API usage.

## Important

MCP servers are created per-session, not globally. Each SDK session gets fresh MCP instances.
