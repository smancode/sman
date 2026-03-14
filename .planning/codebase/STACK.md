# Technology Stack

**Analysis Date:** 2026-03-14

## Languages

**Primary:**
- TypeScript 5.9.3 - Used throughout frontend and backend
- JavaScript (ESM) - Build scripts and bundled outputs

**Secondary:**
- Shell scripts - Deployment automation (`restart.sh`, `stop.sh`)

## Runtime

**Environment:**
- Node.js 22+ (Alpine Linux for Docker)
- ES Module format (`"type": "module"` in package.json)

**Package Manager:**
- pnpm (latest via corepack)
- Lockfile: `pnpm-lock.yaml` (present)

## Frameworks

**Core:**
- React 19.2.4 - UI framework
- React Router DOM 7.13.1 - Client-side routing
- Express 4.21.0 - Backend HTTP server

**State Management:**
- Zustand 5.0.11 - Global state with persistence middleware

**Styling:**
- Tailwind CSS 3.4.19 - Utility-first CSS
- tailwindcss-animate 1.0.7 - Animation plugin
- PostCSS 8.5.8 with autoprefixer 10.4.27

**Testing:**
- None detected (no test framework configured)

**Build/Dev:**
- Vite 8.0.0 - Frontend bundler and dev server
- TypeScript compiler (tsc) - Backend compilation
- zx 8.8.5 - Shell scripting in JS for bundling scripts
- tsx 4.19.0 - TypeScript execution for dev server
- concurrently 9.0.0 - Parallel dev server execution

## Key Dependencies

**Critical:**
- `openclaw` 2026.3.12 - AI agent platform (bundled gateway)
- `@anthropic-ai/claude-code` 2.1.76 - Claude Code CLI integration
- `acpx` 0.3.0 - Agent control protocol extension
- `ws` 8.19.0 - WebSocket server and client

**UI Components:**
- `@radix-ui/*` - Headless UI primitives (label, progress, select, separator, slot, switch, tooltip)
- `lucide-react` 0.577.0 - Icon library
- `class-variance-authority` 0.7.1 - CSS variant management
- `clsx` 2.1.1 + `tailwind-merge` 3.5.0 - Class name utilities

**Content Rendering:**
- `react-markdown` 10.1.0 - Markdown rendering
- `remark-gfm` 4.0.1 - GitHub Flavored Markdown support
- `i18next` 25.8.18 + `react-i18next` 16.5.8 - Internationalization (installed but not actively used in codebase)

**Utilities:**
- `uuid` 10.0.0 - UUID generation

## Configuration

**Environment:**
- `.env` file (gitignored, `.env.example` provided)
- Environment variables: `VITE_API_URL`, `VITE_DEBUG`, `VITE_APP_TITLE`
- Server config: `PORT`, `GATEWAY_PORT`, `GATEWAY_TOKEN`

**Build:**
- `vite.config.ts` - Frontend build config with `@` path alias
- `tsconfig.json` - Composite TypeScript config
- `tsconfig.app.json` - Frontend TypeScript (ES2023, bundler resolution)
- `tsconfig.node.json` - Node scripts TypeScript
- `server/tsconfig.json` - Backend TypeScript (ES2022, NodeNext modules)
- `eslint.config.js` - ESLint 9 flat config with TypeScript + React rules
- `tailwind.config.js` - Tailwind with shadcn/ui color variables

## Platform Requirements

**Development:**
- Node.js 20+ (22+ recommended)
- pnpm (via corepack)
- Git (for skills bundling via sparse checkout)

**Production:**
- Docker (recommended)
- Node.js 22+ (for manual deployment)
- Business system code mounted at `/app/business-system`

---

*Stack analysis: 2026-03-14*
