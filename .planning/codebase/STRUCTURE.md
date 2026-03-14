# Codebase Structure

**Analysis Date:** 2026-03-14

## Directory Layout

```
smanweb/
├── src/                    # Frontend source code (React SPA)
│   ├── app/                # App-level components (routes, App)
│   ├── components/         # Reusable UI components
│   │   ├── common/         # Shared components (ErrorBoundary, LoadingSpinner)
│   │   ├── layout/         # Layout components (MainLayout, Sidebar)
│   │   └── ui/             # shadcn/ui primitives
│   ├── features/           # Feature-based modules
│   │   ├── chat/           # Chat feature (messages, input, toolbar)
│   │   └── settings/       # Settings feature (connection config)
│   ├── lib/                # Core utilities and clients
│   ├── stores/             # Zustand state stores
│   └── types/              # TypeScript type definitions
├── server/                 # Backend server (Node.js/Express)
│   ├── utils/              # Server utilities (logger, ports)
│   ├── gateway-proxy.ts    # WebSocket proxy to gateway
│   ├── index.ts            # Server entry point
│   └── process-manager.ts  # Child process management
├── scripts/                # Build/bundle scripts (ZX)
├── bundled/                # Bundled external dependencies
│   ├── claude-code/        # Claude Code CLI package
│   └── openclaw/           # OpenClaw distribution
├── public/                 # Static assets (favicons)
├── resources/              # Additional resources (skills)
├── docs/                   # Documentation
├── dist/                   # Build output (frontend + server)
└── build/                  # Intermediate build files
```

## Directory Purposes

### `src/`
- Purpose: All frontend source code
- Contains: React components, stores, types, utilities
- Key files: `main.tsx` (entry), `app/App.tsx`, `app/routes.tsx`

### `src/app/`
- Purpose: Application-level configuration and routing
- Contains: App component, route definitions
- Key files: `App.tsx`, `routes.tsx`

### `src/components/`
- Purpose: Reusable UI components organized by category
- Contains: UI primitives (shadcn), layout, common components
- Key files: `layout/MainLayout.tsx`, `layout/Sidebar.tsx`

### `src/features/`
- Purpose: Feature-based module organization
- Contains: Self-contained feature modules with their components
- Key files: `chat/index.tsx`, `settings/index.tsx`

### `src/stores/`
- Purpose: Zustand state management stores
- Contains: Global state stores with actions
- Key files: `chat.ts`, `gateway.ts`, `gateway-connection.ts`

### `src/lib/`
- Purpose: Core utilities and external service clients
- Contains: Gateway client, utility functions
- Key files: `gateway-client.ts`, `utils.ts`

### `src/types/`
- Purpose: TypeScript type definitions
- Contains: Interface definitions for gateway, chat, etc.
- Key files: `gateway.ts`, `chat.ts`

### `server/`
- Purpose: Node.js backend server
- Contains: Express server, process management, WebSocket proxy
- Key files: `index.ts`, `process-manager.ts`, `gateway-proxy.ts`

### `scripts/`
- Purpose: Build scripts for bundling external dependencies
- Contains: ZX scripts for packaging OpenClaw, Claude Code, Skills
- Key files: `bundle-openclaw.mjs`, `bundle-claude-code.mjs`, `bundle-skills.mjs`

### `bundled/`
- Purpose: Pre-packaged external tools
- Contains: OpenClaw distribution, Claude Code with native vendors
- Generated: Yes (by build scripts)
- Committed: Yes

## Key File Locations

### Entry Points:
- `src/main.tsx`: Frontend React entry point
- `server/index.ts`: Backend Express server entry point
- `index.html`: HTML template for Vite

### Configuration:
- `vite.config.ts`: Vite build configuration with path aliases
- `tsconfig.json`: TypeScript project references
- `tsconfig.app.json`: Frontend TypeScript config
- `package.json`: Dependencies and scripts
- `tailwind.config.js`: Tailwind CSS configuration

### Core Logic:
- `src/lib/gateway-client.ts`: WebSocket RPC client
- `src/stores/chat.ts`: Chat state and actions
- `server/process-manager.ts`: Child process lifecycle management
- `server/gateway-proxy.ts`: WebSocket proxy implementation

### Testing:
- `test-gateway-connection.cjs`: Gateway connection test script

## Naming Conventions

### Files:
- React components: PascalCase with `.tsx` extension (e.g., `ChatMessage.tsx`)
- Utilities: camelCase with `.ts` extension (e.g., `message-utils.ts`)
- Stores: camelCase with `.ts` extension (e.g., `chat.ts`)
- Scripts: kebab-case with `.mjs` extension (e.g., `bundle-openclaw.mjs`)
- Index files: `index.ts` or `index.tsx` for barrel exports

### Directories:
- Feature modules: lowercase (e.g., `chat/`, `settings/`)
- Component categories: lowercase (e.g., `ui/`, `common/`, `layout/`)
- Server modules: lowercase (e.g., `utils/`)

### Path Aliases:
- `@/*` maps to `./src/*` (e.g., `@/stores/chat`, `@/components/ui/button`)

## Where to Add New Code

### New Feature:
- Primary code: `src/features/{feature-name}/`
- Create index.tsx for main component
- Add route in `src/app/routes.tsx`
- Add nav item in `src/components/layout/Sidebar.tsx`

### New Component/Module:
- UI primitive: `src/components/ui/`
- Common component: `src/components/common/`
- Feature-specific: `src/features/{feature}/components/`

### New Store:
- Implementation: `src/stores/{store-name}.ts`
- Export from store file, use `create` from zustand

### New Gateway RPC Method:
- Types: `src/types/gateway.ts` (if new types needed)
- Client call: Use `gatewayClient.rpc('method.name', params)`
- Store integration: Add action in relevant store

### New Server Utility:
- Implementation: `server/utils/{utility-name}.ts`
- Import with `.js` extension for ES modules

### New Build Script:
- Implementation: `scripts/bundle-{target}.mjs`
- Use ZX for shell scripting
- Add to `pnpm build` command in `package.json`

## Special Directories

### `bundled/`:
- Purpose: Pre-packaged external tools (OpenClaw, Claude Code)
- Generated: Yes (by `scripts/bundle-*.mjs`)
- Committed: Yes (for self-contained deployment)
- Contains native binaries for multiple platforms

### `dist/`:
- Purpose: Production build output
- Generated: Yes (by `pnpm build`)
- Committed: No (in .gitignore)
- Contains: Frontend assets + compiled server

### `resources/skills/`:
- Purpose: Skill definitions for OpenClaw
- Generated: Yes (by `scripts/bundle-skills.mjs`)
- Bundled into deployment

### `.planning/`:
- Purpose: Planning documents and codebase analysis
- Generated: Yes (by GSD tools)
- Contains: Codebase mapping documents

---

*Structure analysis: 2026-03-14*
