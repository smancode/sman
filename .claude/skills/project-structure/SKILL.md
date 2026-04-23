---
name: project-structure
description: "smanbase directory layout, tech stack, module organization, and build instructions. Consult when you need to understand project structure, find where code lives, or determine how to build/run."
_scanned:
  commitHash: "31436986727ac0aa3ede77643e017c3a42f71d3c"
  scannedAt: "2026-04-14T00:00:00.000Z"
  branch: "base"
---

# Smanbase — Project Structure

## Tech Stack
TypeScript + React 19 + TailwindCSS + Radix UI + Zustand (frontend) | Node.js + Express + ws + better-sqlite3 (backend) | Electron + electron-vite (desktop) | Claude Agent SDK v0.1 + Claude Code v2.1 (AI) | Vite + tsc + pnpm (build) | vitest (tests)

## Directory Tree (top levels)
```
server/        # Node.js backend (ESM): WebSocket/HTTP, Claude sessions, SQLite, Skills, Cron, Batch, Chatbot, WebAccess
  chatbot/     # WeCom / 飞书 / Weixin Bot handlers
  web-access/   # Chrome DevTools Protocol browser automation
  capabilities/ # Model capability registry
  utils/
src/           # React frontend: App, features (chat/settings/cron-tasks/batch-tasks/stardom), components, stores (Zustand), lib
electron/      # Electron main.ts + preload.ts (TypeScript)
plugins/       # Claude Code plugins (web-access, superpowers, gstack, ...)
stardom/       # Standalone stardom server (independent package)
shared/        # stardom-types.ts
scripts/       # init-skills, init-system, patch-sdk
docs/          # windows-packaging.md, superpowers specs
tests/server/  # vitest unit tests
```

## Module Index
| Module | Path | Purpose |
|---|---|---|
| Backend entry | `server/index.ts` | HTTP + WebSocket server, all handler registrations |
| Claude sessions | `server/claude-session.ts` | SDK V2 session lifecycle, idle cleanup, resume |
| SQLite stores | `server/session-store.ts` | Sessions + messages persistence |
| Skills registry | `server/skills-registry.ts` | Load global/project/plugin Skills |
| Settings | `server/settings-manager.ts` | `~/.sman/config.json` read/write |
| Web Search MCP | `server/mcp-config.ts` | Auto-config Brave/Tavily/Bing MCP |
| Cron | `server/cron-scheduler.ts` + `cron-executor.ts` | cron-parser scheduling, Claude execution |
| Batch engine | `server/batch-engine.ts` + `batch-store.ts` | Semaphore-controlled batch task runner |
| Chatbot | `server/chatbot/` | WeCom/飞书/Weixin Bot WS connections + routing |
| Web Access | `server/web-access/` | CDP automation, MCP server (9 tools) |
| Capabilities | `server/capabilities/` | Model capability registry |
| Frontend | `src/app/App.tsx` + `routes.tsx` | Root component + React Router |
| Chat UI | `src/features/chat/` | Messages, input, toolbar, markdown |
| Settings UI | `src/features/settings/` | LLMSettings, WebSearchSettings, ChatbotSettings... |
| State stores | `src/stores/` | Zustand: chat, settings, cron, batch, ws-connection, stardom |
| Electron | `electron/main.ts` | Window mgmt, IPC, backend lifecycle |
| Marketplace | `stardom/src/` | Standalone agent stardom server |

## Build & Run
```bash
./dev.sh              # Dev: Electron desktop (build electron → backend+frontend+electron)
pnpm dev              # Frontend Vite (5881)
pnpm dev:server       # Backend tsx watch (5880)
pnpm build            # vite build + tsc server + ESM package.json
pnpm build:electron   # electron-vite build
pnpm electron:build   # build + build:electron + electron-builder
pnpm test             # vitest run
# Ports: HTTP/WS=5880, Vite=5881
```

## Key Files
`server/index.ts` — WS handlers, service wiring | `server/claude-session.ts` — SDK V2 stream, session persistence | `src/features/chat/index.tsx` — Chat page | `src/stores/chat.ts` — Zustand chat state | `src/lib/ws-client.ts` — WS client auto-reconnect | `electron/main.ts` — `app.disableHardwareAcceleration()` for Windows VDI
