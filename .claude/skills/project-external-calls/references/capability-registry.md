# Capability Registry

## Call Method
Native `fetch()` — calls the configured LLM API (Anthropic by default) to query a capabilities registry service.

## Config Source
- `llm.baseUrl` in `~/.sman/config.json` (used as registry base URL)
- Falls back to `https://api.anthropic.com`

## Call Locations
- `server/capabilities/registry.ts` — registry lookup (line 183)
- `server/capabilities/experience-learner.ts` — experience learning (line 58)
- `server/capabilities/gateway-mcp-server.ts` — capability tools + dynamic MCP injection (lines 210, 235, 267)

## Purpose
Internal capability registry for the superpowers plugin — exposes `capability_list`, `capability_load`, `capability_run` as MCP tools, enabling dynamic injection of capability-specific MCP servers into live Claude sessions.
