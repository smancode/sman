# 后端核心模块

**Purpose**: 后端入口和核心服务，提供 HTTP + WebSocket 服务

## Key Files
- `server/index.ts` — Backend entry, WebSocket server setup, all handler registration

## Responsibilities
1. HTTP & WebSocket server initialization (port 5880)
2. All WebSocket handler registration (`session.*`, `chat.*`, `settings.*`, `cron.*`, `batch.*`, `stardom.*`, `smartpath.*`)
3. Service initialization (settings, session store, skills registry, MCP config, cron scheduler, batch engine, etc.)
4. Auth middleware (Bearer token for `/api/*` routes)
5. Static file serving (React build output)

## Dependencies
- Express (HTTP server)
- ws (WebSocket)
- All server modules (claude-session, settings-manager, skills-registry, etc.)

## API Endpoints
- `GET /api/*` — Authenticated API routes
- `WS /ws` — WebSocket connection (desktop client)
- Static files — Frontend build output

## Notes
- Uses ESM (`"type": "module"`)
- Auth boundary: Only `/api/*` requires Bearer auth, static files are public
- Message isolation: All maps keyed by sessionId to prevent cross-session interference
