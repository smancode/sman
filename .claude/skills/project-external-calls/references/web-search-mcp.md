# Web Search MCP

## Call Method
External MCP Server via npx -y @anthropic-ai/mcp-server-{provider} (stdio)
Or internal MCP servers (server/web-search/*-mcp-server.ts)

## Config Source
- ~/.sman/config.json — webSearch.provider, webSearch.braveApiKey,
  webSearch.tavilyApiKey, webSearch.bingApiKey, webSearch.baiduApiKey
- API key passed as env var to MCP server process

## Provider / Env Var Mapping
| Provider | MCP Server | Env Var | Implementation |
|----------|-----------|---------|----------------|
| brave | @anthropic-ai/mcp-server-brave | BRAVE_API_KEY | External MCP |
| tavily | @anthropic-ai/mcp-server-tavily | TAVILY_API_KEY | External MCP |
| bing | @anthropic-ai/mcp-server-bing | BING_SEARCH_V7_SUBSCRIPTION_KEY | External MCP |
| baidu | (internal) | BAIDU_API_KEY | server/web-search/baidu-mcp-server.ts |
| builtin | (none) | - | server/web-search/mcp-server.ts (Claude Code search) |

## Call Locations
| File | Purpose |
|------|---------|
| server/mcp-config.ts | Builds MCP server config from settings |
| server/index.ts | Passes servers to Claude Agent SDK |
| server/web-search/baidu-mcp-server.ts | Baidu search implementation |
| server/web-search/tavily-mcp-server.ts | Tavily search implementation |
| server/web-search/brave-mcp-server.ts | Brave search implementation |
| server/web-search/mcp-server.ts | Built-in Claude Code search |

## Purpose
Web search via configurable MCP provider (Brave, Tavily, Bing, Baidu, or built-in Claude Code search).
