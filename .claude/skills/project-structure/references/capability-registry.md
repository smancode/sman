# Capability Registry (server/capabilities/registry.ts)

Dynamic capability loading system for extending SDK with project-specific tools.

## Capability Sources

1. **Project Scanner**: Scans workspace for capability files
2. **Experience Learner**: Learns from conversation patterns
3. **Manual Registration**: User explicitly adds capabilities

## Capability Format

Frontmatter in `.md` files:
```yaml
id: "my-capability"
name: "My Capability"
description: "What it does"
triggers:
  - "user mentions X"
tools:
  - name: "my_tool"
    description: "Tool description"
```

## Key Methods

- `searchCapabilities()`: Find capabilities matching query
- `loadCapability()`: Load capability by ID
- `registerCapability()`: Add new capability
- `matchCapabilities()`: Auto-match based on context

## Gateway MCP

Exposes capabilities as MCP tools injected into each SDK session:
- `capability_list`: List available capabilities
- `capability_load`: Load capability into session
- `capability_run`: Execute capability directly

## Experience Learning

Analyzes conversations to extract reusable patterns:
- "User always runs tests before deploy" → Create capability
- "User frequently checks GPU status" → Create capability

## Important

Capabilities are isolated per-session. Loading capability doesn't affect other sessions.
