---
name: project-structure
description: "smanbase directory layout, tech stack, module organization, and build/run instructions. Consult this when you need to understand project structure, find where code lives, or determine how to build/run."
_scanned:
  commitHash: "244ad7730e49cf49ebc53f3129b49b391c9c3999"
  scannedAt: "2026-04-11T08:21:49.263Z"
  branch: "base"
---
# Smanbase — Project Structure

## Tech Stack

| Layer | Tech |
|---|---|
| Frontend | React 19 + TypeScript + TailwindCSS + Radix UI + Zustand |
| Backend | Node.js + Express + TypeScript + WebSocket (ws) |
| Desktop | Electron + electron-vite |
| Database | SQLite (better-sqlite3) |
| AI | @anthropic-ai/claude-agent-sdk v0.1 + @anthropic-ai/claude-code v2.1 |
| Package manager | pnpm |


```
smanbase/
├── server/            # Node.js backend (TS, ESM)
│   ├── chatbot/       # WeCom + 飞书 + 微信 Bot connections
│   ├── web-access/    # Chrome CDP + MCP server (9 tools)
│   ├── utils/         # logger, content-blocks
│   ├── index.ts       # Entry: HTTP + WebSocket + all handlers
│   ├── claude-session.ts     # SDK V2 session lifecycle
│   ├── session-store.ts      # SQLite: sessions + messages
│   ├── settings-manager.ts   # ~/.sman/config.json
│   ├── skills-registry.ts   # Skills load/dispatch
│   ├── mcp-config.ts        # Web Search MCP auto-config
│   ├── cron-scheduler.ts     # Cron job scheduler
│   ├── batch-engine.ts       # Batch task engine (semaphore)
│   └── types.ts             # Shared TS types
├── src/               # React frontend (Vite SPA)
│   ├── app/           # App.tsx + routes.tsx
│   ├── features/     # chat, settings, cron-tasks, batch-tasks
│   ├── components/   # SessionTree, DirectorySelector, SkillPicker
│   ├── stores/       # Zustand (chat, settings, cron, batch, ws)
│   ├── lib/          # ws-client, auth, utils
│   └── types/        # TS type mirrors
├── electron/          # Desktop main (main.ts, preload.ts)
├── plugins/           # web-access, superpowers, gstack (symlink)
├── tests/server/     # vitest (30+ test files)
├── scripts/           # init-skills, init-system, patch-sdk
└── dev.sh            # Dev mode launcher
```


| Name | Path | Purpose |
|---|---|---|
| server | `server/` | Express HTTP + WebSocket server, all backend logic |
| chatbot | `server/chatbot/` | WeCom/飞书/微信 Bot connections + command routing |
| web-access | `server/web-access/` | Chrome CDP browser automation + 9 MCP tools |
| capabilities | `server/capabilities/` | Project-scanner, skill-registry, experience-learning |
| frontend | `src/` | React SPA: chat UI, settings panels, Zustand stores |
| electron | `electron/` | Desktop window, IPC, backend process spawn |
| plugins | `plugins/` | Claude Code plugins (web-access, superpowers, gstack) |
| bazaar | `bazaar/` | P2P capability marketplace: agent registry, task routing, reputation |
| tests | `tests/server/` | Vitest unit tests (30+ files) |
| scripts | `scripts/` | Build/utility scripts (init-skills, init-system, patch-sdk) |
| shared | `shared/` | Cross-module type definitions (bazaar protocol) |

## Build & Run

```bash
./dev.sh           # Dev: all-in-one (Electron + frontend + backend)
pnpm dev           # Dev: frontend only (port 5881)
pnpm dev:server    # Dev: backend only (port 5880, auto-restart)
pnpm build         # Build: frontend + backend → dist/
pnpm build:electron # Build: Electron main process
pnpm electron:build # Package: all platforms
pnpm test          # Run vitest
```

**Ports**: backend 5880 (prod fixed), frontend dev 5881. **User data**: `~/.sman/`.
