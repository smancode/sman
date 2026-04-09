# Brave Search

## Call Method
stdio MCP server — spawned as child `npx` process. Registered via `buildMcpServers()` in `server/mcp-config.ts`.

## Config Source
- `webSearch.provider = 'brave'` and `webSearch.braveApiKey` in `~/.sman/config.json`

## Call Locations
- `server/mcp-config.ts` — server config (lines 16-25)
- `server/claude-session.ts` — SDK registration (line 208-209)

## Purpose
Web search capability exposed as MCP tool to Claude Agent.
