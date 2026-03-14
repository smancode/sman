# External Integrations

**Analysis Date:** 2026-03-14

## APIs & External Services

**AI Agent Platform:**
- OpenClaw Gateway - Core AI agent orchestration
  - SDK/Client: Custom WebSocket client at `src/lib/gateway-client.ts`
  - Protocol: Custom RPC over WebSocket (JSON-based)
  - Auth: Token-based (`GATEWAY_TOKEN` env var)
  - Bundled location: `bundled/openclaw/`

**Claude Code Execution:**
- `@anthropic-ai/claude-code` - Anthropic's Claude Code CLI
  - Bundled location: `bundled/claude-code/`
  - Entry: `cli.js`
  - Used for: Code generation, refactoring, debugging via OpenClaw

**Agent Protocol:**
- acpx - Agent control protocol extension
  - Version: 0.3.0
  - Purpose: Extended agent capabilities

## Data Storage

**Databases:**
- None (stateless application)

**File Storage:**
- Local filesystem only
  - Session data: `/app/data` (Docker volume)
  - Business system mount: `/app/business-system` (read-only)
  - Claude credentials: `/root/.claude` (Docker volume)

**Caching:**
- None (stateless)

## Authentication & Identity

**Auth Provider:**
- Custom token-based
  - Implementation: Simple token validation in OpenClaw Gateway
  - Token passed via `auth.token` in WebSocket connect frame
  - Environment variable: `GATEWAY_TOKEN`

**Client Authentication Flow:**
1. Client receives challenge via `connect.challenge` event
2. Client sends connect frame with token
3. Gateway validates and returns `GatewayHello` with features

## Monitoring & Observability

**Error Tracking:**
- None configured

**Logs:**
- Custom logger at `server/utils/logger.ts`
- Console-based output with structured logging
- Process stdout/stderr captured by ProcessManager

**Health Checks:**
- HTTP endpoint: `GET /api/health`
- Returns process status and gateway health
- Docker healthcheck: wget spider check every 30s

## CI/CD & Deployment

**Hosting:**
- Docker containers (primary)
- Manual Node.js deployment (alternative)

**CI Pipeline:**
- None detected

**Build Process:**
1. `pnpm build` triggers:
   - Vite frontend build
   - OpenClaw bundling (`scripts/bundle-openclaw.mjs`)
   - Skills bundling (`scripts/bundle-skills.mjs`)
   - Claude Code bundling (`scripts/bundle-claude-code.mjs`)
   - Backend TypeScript compilation

## Environment Configuration

**Required env vars:**
- `PORT` - Server HTTP port (default: 3000)
- `GATEWAY_PORT` - Internal gateway port (default: 18789)
- `GATEWAY_TOKEN` - Authentication token (required for production)

**Optional env vars:**
- `VITE_API_URL` - API gateway URL for frontend
- `VITE_DEBUG` - Debug mode toggle
- `VITE_APP_TITLE` - Application title
- `NODE_ENV` - Environment mode

**Secrets location:**
- `.env` file (gitignored)
- Docker secrets via environment variables
- Claude Code credentials in `/root/.claude` volume

## Webhooks & Callbacks

**Incoming:**
- None

**Outgoing:**
- None (WebSocket client only)

## WebSocket Protocol

**Gateway Protocol (version 3):**
- Transport: WebSocket
- Message format: JSON

**Message Types:**
- `req` - Request (id, method, params)
- `res` - Response (id, ok, payload/error)
- `event` - Event (event, payload, seq)

**Client Methods:**
- `connect` - Authenticate with gateway
- Custom RPC methods exposed by gateway

**Events:**
- `connect.challenge` - Authentication challenge
- Custom events from gateway skills

**Reconnection:**
- Auto-reconnect with exponential backoff (800ms base, 15s max)
- Configurable via `GatewayConfig.autoReconnect`

## Bundled Dependencies

**OpenClaw Bundle:**
- Location: `bundled/openclaw/`
- Includes: OpenClaw package + all transitive dependencies
- Entry: `openclaw.mjs gateway --port <port> --token <token>`
- Cleanup: Dev artifacts, source maps, type definitions removed
- Patches: `node-domexception` and proxy agent packages patched for Node 22 ESM compatibility

**Skills Bundle:**
- Location: `bundled/skills/`
- Source: GitHub repos via sparse checkout
- Manifest: `resources/skills/manifest.json`
- Lock file: `bundled/skills/.skills-lock.json`

**Claude Code Bundle:**
- Location: `bundled/claude-code/`
- Size: ~59MB
- Entry: `cli.js`

---

*Integration audit: 2026-03-14*
