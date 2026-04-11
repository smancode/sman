# Web Search MCP

## Overview
Enables web search for Claude agent sessions via Model Context Protocol (MCP) servers. One provider active at a time based on user settings.

## Call Method
MCP via child process (npx -y) with stdio communication. Config passed as environment variables to the spawned process.

## Config Source
- webSearch.provider - builtin | brave | tavily | bing
- webSearch.braveApiKey - BRAVE_API_KEY env var
- webSearch.tavilyApiKey - TAVILY_API_KEY env var
- webSearch.bingApiKey - BING_SEARCH_V7_SUBSCRIPTION_KEY env var

Source: ~/.sman/config.json

## Providers

| Provider | Package | Env Var |
|----------|---------|---------|
| Brave | @anthropic-ai/mcp-server-brave | BRAVE_API_KEY |
| Tavily | @anthropic-ai/mcp-server-tavily | TAVILY_API_KEY |
| Bing | @anthropic-ai/mcp-server-bing | BING_SEARCH_V7_SUBSCRIPTION_KEY |
| Builtin | (none) | (none) |

## Call Locations
- server/mcp-config.ts - Builds MCP server config per provider, launches via npx -y

## Purpose
Provides search tool to Claude sessions for web search capability. builtin uses Claude Code built-in search (no MCP server).
