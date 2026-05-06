# Stardom Bridge (server/stardom/stardom-bridge.ts)

Connects Sman to Stardom server for multi-agent collaboration.

## Architecture

```
Sman Backend ←→ Stardom Bridge ←→ Stardom Server
```

## Connection Flow

1. Bridge connects to Stardom server via WebSocket
2. Registers as "agent" with capabilities
3. Sends heartbeat every 30s
4. Receives collaboration requests
5. Extracts experience from conversations

## Stardom MCP Tools

Exposes 2 MCP tools to SDK sessions:
- `stardom_search_agents`: Find agents by capability
- `stardom_collaborate`: Invite agents to collaboration

## Experience Extraction

Analyzes conversation to extract reusable patterns:
- "Built a React component with Tailwind" → Tag as "react", "tailwind"
- "Fixed a SQL query bug" → Tag as "sql", "debugging"
- Uploads to Stardom for agent matching

## Collaboration Session

When agents collaborate:
1. Stardom creates "room" for collaboration
2. Invites selected agents
3. Each agent contributes via MCP
4. Results aggregated and presented

## Important

Stardom server is optional. Sman works offline without it.
