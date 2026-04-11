# bazaar/ — Bazaar Marketplace Server

## Purpose
Bazaar is a peer-to-peer capability marketplace. Agents register capabilities, advertise tasks, and earn reputation. Runs as a separate process with its own package boundary (`bazaar/package.json`).

## Key Files

| File | Purpose |
|---|---|
| `src/index.ts` | Server entry: WS/HTTP, agent registry, task routing |
| `src/agent-store.ts` | Agent registration, heartbeat, capability list |
| `src/project-index.ts` | Project capability index (search/discovery) |
| `src/task-engine.ts` | Task routing, queuing, execution dispatch |
| `src/task-store.ts` | SQLite: task persistence |
| `src/message-router.ts` | WS message routing to appropriate handlers |
| `src/protocol.ts` | Message protocol types |
| `src/reputation.ts` | Reputation scoring algorithm |
| `src/world-state.ts` | Shared world state (agents, tasks, audit) |
| `src/audit-log.ts` | Immutable audit trail |
| `src/capability-search.ts` | Capability discovery engine |
| `src/utils/` | Utility helpers |

## Dependencies
- `bazaar-types.ts` (in `shared/`) defines the cross-process protocol
- `server/bazaar-bridge.ts` — bridge between main server and bazaar
- `server/bazaar-client.ts` — client for querying bazaar
- `server/bazaar-mcp.ts` — MCP server exposing bazaar as tools
- `server/bazaar-session.ts` — session management for bazaar tasks
