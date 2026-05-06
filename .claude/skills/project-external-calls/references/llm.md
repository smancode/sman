# LLM (Anthropic-compatible API)

## Call Method
- Native fetch() HTTP calls (no SDK wrapper for user-profile, experience-learner, capability-detection, knowledge-extractor, stardom-bridge)
- Claude Agent SDK uses ANTHROPIC_API_KEY + ANTHROPIC_BASE_URL env vars for agent sessions

## Config Source
- ~/.sman/config.json — llm.apiKey, llm.baseUrl, llm.model, llm.profileModel
- Env vars injected at session start: ANTHROPIC_API_KEY, ANTHROPIC_BASE_URL
- Custom baseUrl supported for Anthropic-compatible proxies

## Call Locations
| File | Purpose |
|------|---------|
| server/claude-session.ts | SDK env vars, session creation/resume |
| server/user-profile.ts | User profile analysis via /v1/messages |
| server/model-capabilities.ts | /v1/models/{model} capability probe + /v1/messages fallback |
| server/capabilities/experience-learner.ts | Experience learning via /v1/messages |
| server/capabilities/registry.ts | Semantic capability search via /v1/messages |
| server/knowledge-extractor.ts | Knowledge extraction via /v1/messages |
| server/stardom/stardom-bridge.ts | Agent analysis via /v1/messages |

## Purpose
Anthropic Claude API for AI conversation (via SDK) and 6 internal LLM calls:
1. User profile analysis (user-profile.ts)
2. Model capability detection (model-capabilities.ts)
3. Experience learning (experience-learner.ts)
4. Capability semantic search (registry.ts)
5. Knowledge extraction (knowledge-extractor.ts)
6. Agent analysis (stardom-bridge.ts)

## API Endpoints Used
- POST /v1/messages - Chat completions with streaming
- GET /v1/models/{model} - Model capability lookup (fallback)
