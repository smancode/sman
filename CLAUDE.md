# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SMAN is a desktop application for AI-assisted development, built with a three-layer architecture:
- **Frontend UI**: SvelteKit 5 + TypeScript + Tailwind CSS 4
- **Desktop Shell**: Tauri 2 (Rust)
- **AI Sidecar**: OpenClaw Gateway (WebSocket RPC, port 18790)

## Development Commands

```bash
# Frontend development (SvelteKit dev server)
npm run dev

# Run Tauri desktop app in development mode
npm run tauri:dev

# Build for production
npm run tauri:build

# Testing
npm run test              # Run tests once
npm run test:watch        # Watch mode

# Linting & Formatting
npm run lint              # Check formatting
npm run format            # Fix formatting
npm run typecheck         # TypeScript type checking

# Restart script (convenience)
./restart.sh              # or npm run restart
```

## Architecture

### Three-Layer Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     SvelteKit Frontend                       │
│  src/components/  src/routes/  src/lib/stores/              │
└──────────────────────────┬──────────────────────────────────┘
                           │ Tauri invoke() + Events
┌──────────────────────────▼──────────────────────────────────┐
│                     Tauri Rust Shell                         │
│  src-tauri/src/commands/ (sidecar, fs, settings, project)   │
└──────────────────────────┬──────────────────────────────────┘
                           │ WebSocket RPC (port 18790)
┌──────────────────────────▼──────────────────────────────────┐
│                   OpenClaw Gateway Sidecar                   │
│  ~/.smanlocal/openclaw-home/.openclaw/                      │
└─────────────────────────────────────────────────────────────┘
```

### Key Directories

| Directory | Purpose |
|-----------|---------|
| `src/components/` | Svelte UI components (chat/, layout/, project/, task/) |
| `src/core/openclaw/` | WebSocket client for OpenClaw Gateway |
| `src/bridge/` | Tauri bridge layer (events, commands, runtime-gateway) |
| `src/lib/stores/` | Svelte stores for state (projects, settings, tasks) |
| `src-tauri/src/commands/` | Tauri command handlers (Rust) |

### OpenClaw Integration

- **Port**: 18790 (isolated from local OpenClaw on 18789)
- **Config location**: `~/.smanlocal/openclaw-home/.openclaw/`
- **Protocol**: WebSocket RPC Gateway with token authentication
- **Client**: `src/core/openclaw/client-ws.ts` (OpenClawWSClient)

The OpenClaw sidecar is managed by `src-tauri/src/commands/sidecar.rs`:
- Starts OpenClaw Gateway with isolated HOME directory
- Pre-generates gateway token for authentication
- Configures LLM provider from SMAN settings

### WebSocket Protocol

Gateway message types (defined in `src/core/openclaw/types.ts`):
- `GatewayRequest`: `{ type: "req", id, method, params }`
- `GatewayResponse`: `{ type: "res", id, ok, payload?, error? }`
- `GatewayEvent`: `{ type: "event", event, payload?, seq? }`

Key methods:
- `connect`: Authenticate with gateway token
- `chat.send`: Send message to AI
- `chat.history`: Get conversation history
- `chat.abort`: Cancel running request

### Tauri Commands

All Tauri commands are registered in `src-tauri/src/lib.rs`:
- **Sidecar**: `start_openclaw_server`, `stop_openclaw_server`, `get_gateway_token`
- **FS**: `read_text_file`, `write_text_file`, `list_directory`, `file_exists`
- **Settings**: `get_app_settings`, `update_app_settings`, `test_llm_connection`
- **Project**: `get_projects`, `add_project`, `remove_project`
- **Conversation**: `list_conversations`, `create_conversation`, `send_message`

## Configuration

### Path Aliases (vitest.config.ts)
- `$lib` → `./src/lib`
- `$components` → `./src/components`

### CSP Policy
The Tauri security policy allows connections to:
- `ws://127.0.0.1:18790` (OpenClaw Gateway)
- LLM API endpoints (OpenAI, Anthropic)

## Important Notes

1. **Port Isolation**: SMAN uses port 18790 to avoid conflicts with local OpenClaw (18789). Never modify the local 18789 OpenClaw instance.

2. **Restart Flow**: Use `./restart.sh` to restart the project during development.

3. **Token Authentication**: The Gateway uses pre-generated tokens. Token flow:
   - `configure_openclaw_complete()` generates token before starting Gateway
   - Token is written to `~/.smanlocal/openclaw-home/.openclaw/openclaw.json`
   - Frontend retrieves token via `get_gateway_token` Tauri command

4. **Device Auth Disabled**: SMAN's Gateway is configured with `dangerouslyDisableDeviceAuth: true` for local development simplicity.
