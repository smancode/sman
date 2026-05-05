# 星域桥接层

**Purpose**: Sman 与星域服务器之间的桥接（连接管理、经验提取、协作）

## Key Files
- `server/stardom/stardom-bridge.ts` — Main bridge logic
- `server/stardom/stardom-client.ts` — WebSocket client
- `server/stardom/stardom-mcp.ts` — MCP tools (stardom_search, stardom_collaborate)
- `server/stardom/stardom-session.ts` — Collaboration session management
- `server/stardom/stardom-store.ts` — Local SQLite storage

## Responsibilities
1. **Connection Management**
   - Auto-connect to Stardom server
   - Heartbeat & reconnect
   - Connection status sync to frontend

2. **Message Routing**
   - Handle incoming Stardom messages (tasks, notifications, chat)
   - Route messages to appropriate handlers

3. **Experience Extraction**
   - Extract collaboration experience from conversations
   - Update learned routes (agent effectiveness)
   - Maintain pair history (collaboration history)

4. **Agent Selection**
   - Search & rank agents (old partners > historical collaboration > experienced > remote)
   - Reputation scoring
   - Agent discovery

5. **MCP Tools**
   - `stardom_search` — Search for collaborators
   - `stardom_collaborate` — Request collaboration

## Dependencies
- ws (WebSocket client)
- `stardom-store.ts` (SQLite)
- Shared types: `shared/stardom-types.ts`

## Storage
- `tasks` — Collaboration tasks
- `learned_routes` — Agent experience (who's good at what)
- `pair_history` — Collaboration history (who worked with whom)
- `chat` — Collaboration chat history

## Notes
- Three-layer architecture: Frontend → Bridge → Stardom Server
- Agent evolution: Experience auto-extracted → pair history → search ranking
- Full-screen mode: `/stardom` route hides sidebar for pixel world
