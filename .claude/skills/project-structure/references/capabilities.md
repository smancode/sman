# server/capabilities/ — Project Capability Scanner & Skill Generator

## Purpose
Automatically scans project code, infers capabilities, generates context-aware Skills, and stores learned interaction patterns. Feeds the Skills registry and Web Access experience store.

## Key Files

| File | Purpose |
|---|---|
| `registry.ts` | Central capability registry (load/dump/store) |
| `init-registry.ts` | Initialize registry from project scan |
| `project-scanner.ts` | Recursively scan project files, detect tech stack |
| `scanner-prompts.ts` | Prompt templates for LLM-based capability inference |
| `experience-learner.ts` | Learn URL interaction patterns (stores in SQLite) |
| `frontend-slides-runner.ts` | Generate slides from capability findings |
| `office-skills-runner.ts` | Generate Office document processing skills |
| `generic-instruction-runner.ts` | Execute generic LLM instruction tasks |
| `gateway-mcp-server.ts` | MCP server for capability discovery tools |
| `frontmatter-utils.ts` | Frontmatter parsing utilities |
| `types.ts` | Shared capability types |

## Capabilities Flow
1. `project-scanner.ts` scans `{workspace}/**/*` files
2. LLM infers capabilities from file contents
3. `skills-registry.ts` generates `.md` skill files in `{workspace}/.claude/skills/`
4. Experience learner (`experience-learner.ts`) stores URL interaction patterns

## Dependencies
- Imports `server/types.ts` for shared types
- Feeds `server/skills-registry.ts`
- Used by `server/capabilities/gateway-mcp-server.ts` (MCP tools)
