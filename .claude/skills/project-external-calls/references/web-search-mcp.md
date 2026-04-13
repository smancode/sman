# Web Search MCP

## Call Method
External MCP Server via npx -y @anthropic-ai/mcp-server-{provider} (stdio)

## Config Source
- ~/.sman/config.json — webSearch.provider, webSearch.braveApiKey,
  webSearch.tavilyApiKey, webSearch.bingApiKey
- API key passed as env var to MCP server process

## Provider / Env Var Mapping
| Provider | MCP Server | Env Var |
|----------|-----------|---------|
| brave | @anthropic-ai/mcp-server-brave | BRAVE_API_KEY |
| tavily | @anthropic-ai/mcp-server-tavily | TAVILY_API_KEY |
| bing | @anthropic-ai/mcp-server-bing | BING_SEARCH_V7_SUBSCRIPTION_KEY |
| builtin | (none) | - |

## Call Locations
| File | Purpose |
|------|---------|
| server/mcp-config.ts | Builds MCP server config from settings |
| server/index.ts | Passes servers to Claude Agent SDK |

## Purpose
Web search via configurable MCP provider (Brave, Tavily, Bing, or built-in Claude Code search).
