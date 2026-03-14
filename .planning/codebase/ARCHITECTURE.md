# Architecture

**Analysis Date:** 2026-03-14

## Pattern Overview

**Overall:** Full-stack web application with frontend-backend separation, process management layer, and WebSocket-based gateway communication.

**Key Characteristics:**
- React SPA frontend with feature-based organization
- Express.js backend managing child processes and WebSocket proxying
- Zustand stores for client-side state management
- WebSocket-based RPC communication with OpenClaw Gateway
- Bundled external dependencies (OpenClaw, Claude Code, Skills) managed as separate processes

## Layers

### Frontend Layer
- Purpose: User interface for chat-based interaction with intelligent business system
- Location: `src/`
- Contains: React components, Zustand stores, gateway client, types
- Depends on: Backend API endpoints (`/api/config`, `/api/health`), WebSocket proxy (`/ws`)
- Used by: End users via browser

### Backend Layer
- Purpose: Serve static frontend, manage gateway process, proxy WebSocket connections
- Location: `server/`
- Contains: Express server, process manager, gateway proxy, utilities
- Depends on: Bundled OpenClaw in `bundled/openclaw/`
- Used by: Frontend via HTTP/WebSocket

### Bundled Dependencies Layer
- Purpose: Pre-packaged external tools and dependencies
- Location: `bundled/`
- Contains: OpenClaw distribution, Claude Code CLI, Skills
- Depends on: Node.js runtime
- Used by: Backend process manager

### Build/Bundle Layer
- Purpose: Scripts for packaging external dependencies during build
- Location: `scripts/`
- Contains: ZX scripts for bundling OpenClaw, skills, and Claude Code
- Depends on: External npm packages (openclaw, @anthropic-ai/claude-code, acpx)
- Used by: Build process (`pnpm build`)

## Data Flow

### Chat Message Flow:

1. User types message in `ChatInput` component
2. `useChatStore.sendMessage()` adds optimistic user message to local state
3. `GatewayClient.rpc('chat.send', ...)` sends message via WebSocket
4. OpenClaw Gateway processes message and emits `chat` events
5. `Chat` component receives events via `client.on('chat', ...)`
6. `useChatStore.handleChatEvent()` updates state (streaming, final, error)
7. `ChatMessage` components re-render with new data

### Gateway Connection Flow:

1. `Chat` component mounts, checks `useGatewayStore` for URL
2. If configured, calls `gatewayConnection.connect()`
3. `GatewayClient` opens WebSocket to `/ws` (proxied to internal gateway)
4. Gateway sends `connect.challenge` event
5. Client responds with `connect` method + auth token
6. On success, status changes to `connected`, chat history loads

### Session Management Flow:

1. `useChatStore.loadSessions()` calls `sessions.list` RPC
2. Server returns available sessions
3. User can switch sessions via `switchSession()`
4. `loadHistory()` fetches messages for current session

**State Management:**
- Zustand stores with persist middleware for gateway config
- Singleton `GatewayClient` instance shared across stores
- React hooks for store access (`useChatStore`, `useGatewayStore`)

## Key Abstractions

### GatewayClient
- Purpose: WebSocket-based RPC client for OpenClaw Gateway
- Examples: `src/lib/gateway-client.ts`
- Pattern: Request-response with pending promises, event subscription model

### Zustand Stores
- Purpose: Centralized reactive state management
- Examples: `src/stores/chat.ts`, `src/stores/gateway.ts`, `src/stores/gateway-connection.ts`
- Pattern: Creator functions with selectors, persist middleware for config

### ProcessManager
- Purpose: Manage child processes (OpenClaw Gateway) with health monitoring
- Examples: `server/process-manager.ts`
- Pattern: Spawn/monitor/restart lifecycle, exponential backoff for restarts

### Gateway Proxy
- Purpose: Bridge frontend WebSocket connections to internal gateway
- Examples: `server/gateway-proxy.ts`
- Pattern: WebSocket-to-WebSocket proxy with connection forwarding

## Entry Points

### Frontend Entry Point:
- Location: `src/main.tsx`
- Triggers: Browser loading the page
- Responsibilities: React root creation, renders `App` component

### Backend Entry Point:
- Location: `server/index.ts`
- Triggers: `pnpm start` or `pnpm dev:server`
- Responsibilities: Express app setup, process manager initialization, WebSocket proxy creation, graceful shutdown handling

### Vite Dev Server:
- Location: `vite.config.ts`
- Triggers: `pnpm dev`
- Responsibilities: Hot module replacement, TypeScript compilation, path alias resolution

## Error Handling

**Strategy:** Layered error handling with typed errors and user feedback

**Patterns:**
- `GatewayRequestError` class for typed gateway errors with code and details
- Optimistic updates with error rollback in chat store
- Error state in stores displayed via error bar in UI
- Try-catch with console warnings for non-critical failures (e.g., session loading)
- Server-side structured logging with `createLogger`

## Cross-Cutting Concerns

**Logging:** Server-side structured JSON logging via `server/utils/logger.ts`, client-side console logging for debugging

**Validation:** TypeScript strict mode, runtime validation at gateway boundaries

**Authentication:** Token-based auth passed to gateway on connect, stored in persisted Zustand store

**Configuration:** Dual mode - server-side config via `/api/config` (integrated mode) or client-side localStorage (standalone mode)

**Styling:** Tailwind CSS with shadcn/ui components, dark mode support

---

*Architecture analysis: 2026-03-14*
