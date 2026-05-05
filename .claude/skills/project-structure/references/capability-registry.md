# Capability Registry

**Purpose**: Capability discovery, matching, and loading system (on-demand skill injection)

## Key Files
- `server/capabilities/registry.ts` — Registry main logic
- `server/capabilities/project-scanner.ts` — Project scanning
- `server/capabilities/gateway-mcp-server.ts` — Gateway MCP (injected to each session)
- `server/capabilities/experience-learner.ts` — Learn from conversations

## Responsibilities
1. **Capability Discovery**
   - Scan project structure (tech stack, frameworks)
   - Search capabilities by keywords or natural language
   - Match capabilities to project context

2. **Capability Loading**
   - Load capability by ID (inject MCP or instructions)
   - Run capability directly (for simple commands)

3. **Gateway MCP**
   - Inject `capability_list`, `capability_load`, `capability_run` tools to each session
   - Capability discovery and execution proxy

4. **Experience Learning**
   - Extract knowledge from conversations
   - Update capability usage tracking
   - Learn new patterns

## Dependencies
- `server/knowledge-extractor.ts` (knowledge extraction)
- `server/mcp-config.ts` (MCP server management)
- Capability storage: `~/.sman/capabilities/`

## Key Methods
- `searchCapabilities(keywords, natural_language)` — Find matching capabilities
- `loadCapability(capability_id, session_id)` — Load capability into session
- `runCapability(capability_id, command)` — Run capability directly

## Capability Types
- Office skills (Word, Excel, PPT)
- Frontend slides generator
- Generic instruction runner
- Custom project-specific capabilities

## Notes
- Capabilities are **on-demand** (only loaded when needed)
- Gateway MCP is **auto-injected** to every session
- Usage tracking: Records which capabilities are used most
