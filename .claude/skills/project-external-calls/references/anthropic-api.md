# Anthropic API

## Call Method
Native `fetch()` (Node.js built-in). LLM inference is handled by `@anthropic-ai/claude-agent-sdk` which forwards `ANTHROPIC_API_KEY` + `ANTHROPIC_BASE_URL` as env vars to the SDK subprocess. Model capability probing uses direct `fetch()` calls.

## Config Source
- Env vars: `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL` (defaults to `https://api.anthropic.com`)
- Also configurable via `llm.apiKey` and `llm.baseUrl` in `~/.sman/config.json`

## Call Locations
- `server/claude-session.ts` — SDK env injection (lines 160, 162, 246, 248)
- `server/batch-engine.ts` — batch query env injection (lines 119, 122)
- `server/model-capabilities.ts` — model list + capability probe (lines 122, 193, 283)
- `server/capabilities/registry.ts` — base URL default (line 183)
- `server/capabilities/experience-learner.ts` — base URL default (line 58)
- `server/user-profile.ts` — base URL default (line 202)

## Purpose
Primary LLM inference for all chat sessions, batch tasks, and capability probing.
