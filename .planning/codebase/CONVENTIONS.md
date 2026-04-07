# Coding Conventions

**Analysis Date:** 2026-04-07

## Naming Patterns

**Files:**
- Server source files: `kebab-case.ts` (e.g., `session-store.ts`, `chat-command-parser.ts`, `cron-scheduler.ts`)
- Server subdirectories: `kebab-case/` (e.g., `web-access/`, `chatbot/`, `capabilities/`)
- React components: `PascalCase.tsx` (e.g., `ChatMessage.tsx`, `SessionTree.tsx`, `SkillPicker.tsx`)
- React pages/features: `PascalCase` directories with `index.tsx` (e.g., `features/chat/index.tsx`)
- UI primitives: `PascalCase.tsx` in `src/components/ui/` (e.g., `Button.tsx`, `Dialog.tsx`)
- Test files: `kebab-case.test.ts` in `tests/server/` mirroring server structure (e.g., `tests/server/batch-store.test.ts`, `tests/server/chatbot/chatbot-store.test.ts`)
- Utility modules: `kebab-case.ts` (e.g., `content-blocks.ts`, `batch-utils.ts`)
- Store files: `kebab-case.ts` (e.g., `stores/chat.ts`, `stores/settings.ts`)
- Config files: `kebab-case.config.*` at project root (e.g., `tailwind.config.js`, `vite.config.ts`)

**Functions:**
- Server: `camelCase` (e.g., `createSession()`, `buildMcpServers()`, `parseCrontabMd()`)
- Pure utility functions: `camelCase` (e.g., `renderTemplate()`, `detectInterpreter()`, `buildContentBlocks()`)
- React hooks: `useCamelCase` (e.g., `useChatStore()`, `useWsConnection()`, `useTheme()`)
- Helper factories: `createCamelCase` (e.g., `createLogger()`, `createWebAccessMcpServer()`)
- Private methods: `camelCase` with no prefix (e.g., `private buildSystemPromptAppend()`, `private extractTextContent()`)

**Variables:**
- `camelCase` throughout (e.g., `sessionId`, `dbPath`, `cronExpression`)
- Private class fields: no underscore prefix, use `private` keyword (e.g., `private db`, `private log`, `private sessions`)
- Constants: `UPPER_SNAKE_CASE` (e.g., `MAX_ITEMS`, `TEST_TIMEOUT_MS`, `SKILLS_CACHE_TTL`)
- Private static readonly: `UPPER_SNAKE_CASE` (e.g., `private static readonly SESSION_IDLE_TIMEOUT_MS`)
- Column name maps: `UPPER_SNAKE_CASE` (e.g., `TASK_COLUMN_MAP`, `ITEM_COLUMN_MAP`)

**Types:**
- Interfaces: `PascalCase` (e.g., `Session`, `Message`, `SmanConfig`, `BatchTask`)
- Type aliases: `PascalCase` (e.g., `BatchTaskStatus`, `BatchItemStatus`, `EngineType`)
- Enums: Not used; string literal unions instead (e.g., `'draft' | 'generating' | 'generated'`)
- Error classes: `PascalCaseError` (e.g., `SemaphoreStoppedError`, `BrowserTimeoutError`, `BrowserConnectionError`)

## Code Style

**Formatting:**
- No ESLint or Prettier configuration detected
- TypeScript strict mode enabled (`"strict": true` in both `tsconfig.json` and `server/tsconfig.json`)
- 2-space indentation (observed throughout)
- Single quotes for strings
- Trailing commas in multi-line structures

**TypeScript Configuration:**
- Target: `ES2022` (both server and frontend)
- Server module: `ES2022` with `moduleResolution: "node"`
- Frontend module: `ESNext` with `moduleResolution: "bundler"`
- JSX: `react-jsx` (no explicit React imports needed)
- ESM: `"type": "module"` in `package.json`
- Frontend path alias: `@/*` maps to `./src/*`

**Import Style:**
- Server imports use `.js` extension: `import { SessionStore } from './session-store.js'`
- Frontend imports use `@/` alias: `import { useChatStore } from '@/stores/chat'`
- Type-only imports use `type` keyword: `import type { SessionStore, Message } from './session-store.js'`
- Mixed value/type imports in one line: `import { createLogger, type Logger } from './utils/logger.js'`

## Import Organization

**Order (server files):**
1. Node.js built-ins (`import fs from 'fs'`, `import path from 'path'`, `import os from 'os'`)
2. Third-party packages (`import Database from 'better-sqlite3'`, `import { z } from 'zod'`)
3. ESM interop workarounds (e.g., `// @ts-expect-error - better-sqlite3 ESM interop`)
4. Internal modules (`import { SessionStore } from './session-store.js'`)
5. Type imports (`import type { SmanConfig } from './types.js'`)

**Order (frontend files):**
1. React hooks (`import { useState, useCallback, useEffect } from 'react'`)
2. Third-party packages (`import { create } from 'zustand'`)
3. Internal components via `@/` alias (`import { Button } from '@/components/ui/button'`)
4. Internal types via `@/` alias (`import type { RawMessage } from '@/types/chat'`)

**Path Aliases:**
- Frontend: `@/*` resolves to `./src/*` (configured in `vite.config.ts` and `tsconfig.json`)
- Server: Relative paths with `.js` extension (no aliases)

## Error Handling

**Patterns:**
- Throw `Error` with descriptive messages for validation failures:
  ```typescript
  if (!this.config?.llm?.apiKey) {
    throw new Error('缺少 API Key，请在设置中配置');
  }
  ```
- Custom error classes extending `Error` for domain-specific errors:
  ```typescript
  export class BrowserTimeoutError extends Error {
    constructor(method: string, timeoutMs: number) {
      super(`Browser operation timed out: ${method} (${timeoutMs}ms)`);
      this.name = 'BrowserTimeoutError';
    }
  }
  ```
- WebSocket error responses sent as JSON:
  ```typescript
  catch (err) {
    const errorMessage = err instanceof Error ? err.message : String(err);
    ws.send(JSON.stringify({ type: 'chat.error', sessionId: msg.sessionId, error: errorMessage }));
  }
  ```
- Silent catch for non-critical operations: `catch { /* best effort */ }` or `catch { /* ignore */ }`
- Error message extraction pattern: `err instanceof Error ? err.message : String(err)`

## Logging

**Framework:** Custom `createLogger()` from `server/utils/logger.ts`

**Patterns:**
- Create a logger per module: `const log = createLogger('ModuleName')`
- Structured JSON logging: `{ level, module, message, ...meta, ts }`
- Levels: `info`, `warn`, `error`, `debug` (debug requires `LOG_LEVEL=debug`)
- Used exclusively on server side; frontend uses no logging framework

**When to log:**
- Service lifecycle events: `'Initialized'`, `'Started'`, `'Closed'`
- Session operations: `'Session created'`, `'Query completed'`
- Error conditions: `'Stream stalled'`, `'Process dead'`
- Migration events: `'Migrated: added label column'`

## Comments

**When to Comment:**
- JSDoc for public class methods with non-obvious behavior
- Section dividers using `// ── Section Name ──` pattern (observed in `server/index.ts`)
- Inline comments for non-obvious business logic or workarounds
- Migration comments explaining why a column is being added

**JSDoc/TSDoc:**
- Used for complex functions and class-level documentation
- Not used consistently; only for modules with non-trivial behavior
- File-level doc blocks explain module purpose (e.g., `server/web-access/browser-engine.ts`, `server/web-access/mcp-server.ts`)

## Function Design

**Size:** Functions range from small (1-20 lines for pure utilities) to moderate (20-50 lines for CRUD operations). Large functions exist in `server/index.ts` message handler switch cases.

**Parameters:** Use object parameters for functions with 3+ args. Input types defined as local interfaces:
```typescript
createTask(input: {
  workspace: string;
  skillName: string;
  mdContent: string;
  execTemplate: string;
  envVars?: Record<string, string>;
  concurrency?: number;
  retryOnFailure?: number;
}): BatchTask
```

**Return Values:**
- Store methods return the created/updated entity or `undefined` for not-found
- Service methods throw on error (no Result monads)
- Async methods return `Promise<void>` or `Promise<T>`

## Module Design

**Exports:**
- Server: named exports from class-based modules (e.g., `export class SessionStore`)
- Utility functions: named exports (e.g., `export function renderTemplate()`)
- Types: co-located with implementation or in dedicated `types.ts` files
- Frontend components: named exports, not default (e.g., `export const ChatMessage = memo(...)`)
- Frontend pages: named exports from `index.tsx` (e.g., `export function Chat()`)

**Barrel Files:**
- `server/web-access/index.ts` re-exports from sub-modules
- `server/types.ts` is a shared type barrel
- `server/chatbot/types.ts` is a sub-module type barrel
- No frontend barrel files; direct imports via `@/` alias

## Server-Specific Patterns

**better-sqlite3 ESM Interop:**
Some files use a workaround for better-sqlite3 ESM compatibility:
```typescript
import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
```
Other files import directly: `import Database from 'better-sqlite3'`. Both patterns exist.

**SQLite Column Mapping:**
SQL column names use `snake_case`; TypeScript properties use `camelCase`. Mapping done via:
- SQL aliasing in SELECT: `'SELECT id, skill_name as skillName FROM ...'`
- Column map objects: `TASK_COLUMN_MAP: Record<string, string>` with camelCase-to-snakeCase mapping
- Dynamic update builder: `buildDynamicUpdate()` uses column map to translate field names

**SQLite Migration Pattern:**
Schema migrations use try/catch to detect missing columns:
```typescript
try {
  this.db.prepare('SELECT label FROM sessions LIMIT 1').get();
} catch {
  this.db.exec('ALTER TABLE sessions ADD COLUMN label TEXT');
  this.log.info('Migrated: added label column');
}
```

**WebSocket Message Protocol:**
All messages are JSON with a `type` field. Server handles messages via `switch (msg.type)` in `server/index.ts`. Error responses use `{ type: 'chat.error', error: message }`.

**Dependency Injection:**
Services receive dependencies via setter methods:
```typescript
sessionManager.setWebAccessService(webAccessService);
sessionManager.setCapabilityRegistry(capabilityRegistry);
batchEngine.setSessionManager(mockSessionManager);
```

## Frontend-Specific Patterns

**State Management (Zustand):**
```typescript
export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  loading: false,
  // ... actions
  sendMessage: async (content) => { /* ... */ },
}));
```
- Store accessed via selectors: `const messages = useChatStore((s) => s.messages)`
- Cross-store access via `getState()`: `useWsConnection.getState().client`

**Component Pattern:**
- Functional components with hooks
- `memo()` for expensive renders: `export const ChatMessage = memo(function ChatMessage(...) {})`
- TailwindCSS for styling via `cn()` utility from `src/lib/utils.ts`:
  ```typescript
  import { cn } from '@/lib/utils';
  <div className={cn('flex flex-col h-full', className)} />
  ```

**TailwindCSS:**
- Dark mode via `class` strategy
- CSS custom properties for theme colors: `hsl(var(--primary))`
- Radix UI primitives wrapped in `src/components/ui/` (shadcn/ui pattern)

---

*Convention analysis: 2026-04-07*
