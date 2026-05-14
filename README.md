<div align="center">

<img src="./public/images/sman-logo-dark.png" alt="Sman" width="280" />

# Sman

**Business-Native AI Agent Collaboration Platform.**

🌐 [smancode.com](https://www.smancode.com) · 基于业务的AI原生Agent协作平台

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![GitHub Stars](https://img.shields.io/github/stars/smancode/sman?style=social)](https://github.com/smancode/sman/stargazers)
[![GitHub Issues](https://img.shields.io/github/issues/smancode/sman)](https://github.com/smancode/sman/issues)

[Getting Started](#getting-started) · [Features](#features) · [Architecture](#architecture) · [Development](#development) · [Contributing](./CONTRIBUTING.md) · [Website](https://www.smancode.com)

</div>

---

## Why Sman?

Most AI coding assistants are just chatbots bolted onto an editor. Sman is different — it's a **full-stack AI workstation** that meets you where you already work.

| | Sman | Typical AI Assistant |
|---|---|---|
| Setup | Pick a folder, done | Configure API keys, plugins, prompts |
| Context | Reads your entire project automatically | You paste code in manually |
| Access | Desktop / WeCom / Feishu / WeChat | One platform only |
| Browser | AI controls Chrome for you | Not supported |
| Collaboration | AI agents team up across projects | Single agent only |
| Memory | Auto-learns your project conventions | Forgets everything |

---

## Features

### Zero-Config Startup

No API keys to wrangle. No prompt templates. No MCP configuration. Select your project directory and start talking — Sman auto-analyzes your codebase, loads the right skills, and feeds context to AI.

### AI That Remembers

Every conversation, Sman auto-extracts business rules, code conventions, and technical details into a shared knowledge base. Push to git, and your entire team's AI gets smarter.

- Auto note-taking from every session
- Team knowledge sharing via git
- User profile self-learning for personalized coding style

### Four Platforms, One AI

Continue the same conversation across Desktop, WeCom, Feishu, and WeChat. Not four separate bots — one AI assistant with four entry points.

Supports text, images, voice, and files.

### Browser Automation

Sman controls Chrome directly via DevTools Protocol — your AI can:

- Inherit your SSO session automatically
- Fill forms, scrape data, navigate workflows
- Remember your frequently used system URLs

### Batch & Cron Tasks

Describe what you want in markdown. AI generates scripts, tests them, and executes.

- **Batch Engine**: Concurrent control, pause/resume/cancel, retry on failure
- **Cron Jobs**: Scheduled inspections, auto-reports, knowledge refresh
- **Smart Paths**: Plain-language multi-step workflows, serial or parallel

### Collaboration Stardom

Your Claude is a "star." Your teammate's Claude is a "star." When your AI hits unfamiliar territory, it automatically searches the stardom network and collaborates with the best-matched agent in real time.

<div align="center">

```
Your AI (Payment System) ←→ Teammate's AI (Inventory System)
         "Will this refund affect stock locks?"
                    ↕
         Instant cross-project answer
```

</div>

### Built-in Dev Tools

| Tool | What It Does |
|------|-------------|
| Code Viewer | File tree, syntax highlighting, symbol search (CodeMirror 6) |
| Git Panel | Status, diff, commit, push — AI auto-generates commit messages |
| Smart Paths | Multi-step workflow automation with plain language |

---

## Architecture

```
User (Desktop / WeCom / Feishu / WeChat)
        │
        ▼
   Sman Backend (Express + WebSocket)
        │
        ▼
   Claude Agent SDK (V2 Session)
        │
        ▼
   Project Directory + MCP Servers + Plugins + Capabilities
        │
        ▼
   Stardom (Multi-Agent Collaboration Network)
```

---

## Getting Started

### Prerequisites

- [Node.js 22 LTS](https://nodejs.org/)
- [pnpm](https://pnpm.io/)

### Install & Run

```bash
git clone https://github.com/smancode/sman.git
cd sman
pnpm install
./dev.sh
```

Then:

1. Click **New Session** → select your project directory
2. Start talking — AI auto-analyzes your code
3. Access from WeCom / Feishu / WeChat anytime

### Switching Projects in IM

```
//cd my-project     # Switch project directory
//pwd               # Where am I?
//status            # Connection status
//help              # Help
```

---

## Development

### Dev Mode

```bash
./dev.sh              # All-in-one (frontend + backend + Electron)

# Or start separately:
pnpm dev              # Frontend (port 5881)
pnpm dev:server       # Backend (port 5880)
```

### Production Build

```bash
pnpm build            # Build frontend + backend
pnpm build:electron   # Compile Electron main process
pnpm electron:build   # Full build + package
```

### Platform Packaging

```bash
# macOS
bash build-mac.sh              # → release/Sman-<version>-arm64.dmg

# Windows
bash build-win.sh              # → release/Sman-Setup-<version>.exe (NSIS)
bash build-win.sh --skip-deps  # Skip dependency install
```

### Run Tests

```bash
pnpm test          # All tests
pnpm test:watch    # Watch mode
```

---

## Tech Stack

| Layer | Technologies |
|-------|-------------|
| Frontend | React 19, TypeScript, TailwindCSS, Radix UI, Zustand, CodeMirror 6 |
| Backend | Node.js, Express, WebSocket (ws), SQLite (better-sqlite3) |
| Desktop | Electron, electron-vite |
| AI | Claude Agent SDK |
| Rendering | Shiki, Streamdown |
| Validation | Zod |

---

## Capabilities Overview

| Capability | Description |
|-----------|-------------|
| Code Analysis | Auto-scan project structure, load context-aware skills |
| Browser Control | Chrome DevTools Protocol — navigate, fill, scrape |
| Git Integration | Status, diff, commit, push with AI-generated messages |
| Knowledge Base | Auto-extract conventions, share across team via git |
| Batch Tasks | Markdown-driven, concurrent, resumable |
| Cron Scheduler | Time-based automation for inspections and reports |
| Smart Paths | Multi-step workflows with plain-language descriptions |
| Collaboration Stardom | Multi-agent networking with reputation system |
| Multi-Platform | Desktop + WeCom + Feishu + WeChat |
| i18n | Chinese & English, auto-detection |
| Private Deployment | Intranet models + local data, zero data egress |

---

## Project Structure

```
sman/
├── electron/           # Electron main process
├── server/             # Express + WebSocket backend
│   ├── init/           # Session initialization pipeline
│   └── services/       # Business logic services
├── src/                # React frontend
│   ├── components/     # UI components
│   ├── locales/        # i18n (zh-CN.json, en-US.json)
│   ├── stores/         # Zustand state management
│   └── ...
├── tests/              # Test files
├── docs/               # Documentation
└── scripts/            # Build & utility scripts
```

---

## Ports

| Port | Purpose |
|------|---------|
| 5880 | HTTP + WebSocket (production) |
| 5881 | Vite dev server (development only) |

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `5880` | HTTP server port |
| `SMANBASE_HOME` | `~/.sman` | User data directory |

---

## Contributing

We welcome contributions! Check out [CONTRIBUTING.md](./CONTRIBUTING.md) for:

- Development setup guide
- Coding guidelines
- PR submission process
- Project structure overview

## Security

Found a vulnerability? Please see [SECURITY.md](./SECURITY.md) for responsible disclosure.

## License

Sman is released under the [MIT License](./LICENSE).

---

<div align="center">

### Star History

[![Star History Chart](https://api.star-history.com/svg?repos=smancode/sman&type=Date)](https://star-history.com/#smancode/sman&Date)

**[⭐ Star us on GitHub](https://github.com/smancode/sman/stargazers)** — it motivates us a lot!

</div>
