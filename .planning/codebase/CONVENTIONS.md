# Coding Conventions

**Analysis Date:** 2026-03-14

## Naming Patterns

**Files:**
- React components: `PascalCase.tsx` (e.g., `ChatMessage.tsx`, `ConnectionSettings.tsx`)
- TypeScript modules: `kebab-case.ts` (e.g., `gateway-client.ts`, `message-utils.ts`)
- Feature directories: `kebab-case` (e.g., `src/features/chat/`, `src/features/settings/`)
- UI components (shadcn): `kebab-case.tsx` (e.g., `button.tsx`, `card.tsx`)
- Store files: `kebab-case.ts` (e.g., `chat.ts`, `gateway.ts`)

**Functions:**
- Utility functions: `camelCase` (e.g., `extractText`, `formatTimestamp`, `toWsUrl`)
- React components: `PascalCase` (e.g., `ChatMessage`, `ConnectionSettings`)
- Event handlers: `handle` prefix (e.g., `handleSave`, `handleTest`, `handleConnect`)
- Private methods: `camelCase` (e.g., `spawnProcess`, `flushPendingRequests`)

**Variables:**
- State variables: `camelCase` (e.g., `streamingText`, `activeRunId`, `isConnected`)
- Constants: `SCREAMING_SNAKE_CASE` for module-level (e.g., `DEFAULT_RECONNECT_INTERVAL`, `DEFAULT_SESSION`)
- Booleans: prefixed with `is`, `has`, `should` (e.g., `isConnected`, `hasText`, `shouldRenderStreaming`)

**Types/Interfaces:**
- Interfaces: `PascalCase` with descriptive suffix (e.g., `GatewayConfig`, `ChatSession`, `RawMessage`)
- Type aliases: `PascalCase` (e.g., `GatewayEventHandler`, `LogLevel`)
- Props interfaces: Component name + `Props` (e.g., `ChatMessageProps`, `ButtonProps`)

## Code Style

**Formatting:**
- Tool: ESLint 9.x with TypeScript plugin
- Config: `eslint.config.js` (flat config format)
- Key plugins: `eslint-plugin-react-hooks`, `eslint-plugin-react-refresh`
- TypeScript: strict mode enabled in both `tsconfig.app.json` and `server/tsconfig.json`

**Linting:**
- Linter: ESLint 9.39.4 with `typescript-eslint`
- React rules: hooks rules enforced, refresh plugin for Vite HMR
- Run command: `pnpm lint`

**TypeScript Configuration:**
- Target: ES2023 (frontend), ES2022 (server)
- Module: ESNext with bundler resolution (frontend), NodeNext (server)
- Strict mode: Enabled
- No unused locals/parameters: Enabled

## Import Organization

**Order:**
1. External packages (React, third-party libs)
2. Internal aliases (`@/` prefixed imports)
3. Relative imports (same directory or parent)

**Example from `src/features/chat/index.tsx`:**
```typescript
import { useEffect, useState, useRef } from 'react';                    // External
import { AlertCircle, Loader2 } from 'lucide-react';                    // External
import { useChatStore } from '@/stores/chat';                           // Alias
import type { RawMessage } from '@/types/chat';                         // Alias (type)
import { useGatewayStore } from '@/stores/gateway';                     // Alias
import { getGatewayClient } from '@/lib/gateway-client';                // Alias
import { ChatMessage } from './ChatMessage';                            // Relative
import { ChatInput } from './ChatInput';                                // Relative
```

**Path Aliases:**
- `@/*` maps to `./src/*` (configured in `tsconfig.app.json` and `vite.config.ts`)
- Frontend uses `@/lib/utils`, `@/types/chat`, `@/stores/chat`, etc.
- Server uses relative paths (no alias configured)

## Error Handling

**Patterns:**

1. **Custom Error Classes:**
```typescript
// src/types/gateway.ts
export class GatewayRequestError extends Error {
  readonly gatewayCode: string
  readonly details?: unknown

  constructor(error: GatewayErrorInfo) {
    super(error.message)
    this.name = 'GatewayRequestError'
    this.gatewayCode = error.code
    this.details = error.details
  }
}
```

2. **Try-Catch with State Update:**
```typescript
// src/stores/chat.ts
try {
  const result = await client.rpc<{ runId?: string }>('chat.send', { ... });
  if (result?.runId) {
    set({ activeRunId: result.runId });
  }
} catch (err) {
  set({ error: String(err), sending: false });
}
```

3. **Error Boundary for React:**
```typescript
// src/components/common/ErrorBoundary.tsx
export class ErrorBoundary extends Component<Props, State> {
  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error('Error caught by boundary:', error, errorInfo);
  }
}
```

4. **Validation with Early Returns:**
```typescript
// src/lib/gateway-client.ts
constructor(config: GatewayConfig) {
  if (!config.url) {
    throw new Error('Gateway URL is required');
  }
  // ...
}
```

## Logging

**Framework:** Custom structured logger (server), console (frontend)

**Server Logger (`server/utils/logger.ts`):**
```typescript
const log = createLogger('Server');

log.info('Starting SmanWeb server', { config });
log.error('Failed to start server', { error: err.message });
log.warn('Process exited', { code, signal });
```

**Log Format:**
```
[timestamp] [LEVEL] [component] message {data}
```

**Frontend Logging:**
- Uses `console.log`, `console.warn`, `console.error`
- Includes context prefix: `console.log('[Chat] Received chat event:', payload);`

## Comments

**When to Comment:**
- File headers with JSDoc-style descriptions
- Complex utility functions explaining transformation logic
- Non-obvious business rules

**JSDoc/TSDoc:**
```typescript
/**
 * Clean Gateway metadata from user message text for display.
 * Strips: [media attached: ... | ...], [message_id: ...],
 * and the timestamp prefix [Day Date Time Timezone].
 */
function cleanUserText(text: string): string { ... }
```

**Section Separators:**
```typescript
// ── Welcome Screen ──────────────────────────────────────────────
// ── Typing Indicator ────────────────────────────────────────────
```

## Function Design

**Size:** Functions generally under 50 lines; complex functions like `handleChatEvent` may be longer (100+ lines) but handle multiple cases

**Parameters:**
- Destructured objects for multiple parameters
- Optional parameters use TypeScript optional syntax
- Configuration objects passed as typed interfaces

**Return Values:**
- Async functions return `Promise<T>`
- Utility functions return nullable types for edge cases (`string | null`)
- State setters follow Zustand pattern: `(set, get) => ({ ... })`

## Module Design

**Exports:**
- Named exports preferred over default exports
- Component files export both component and related types
- Store files export the hook: `export const useChatStore = create<ChatState>(...)`

**Barrel Files:**
- Feature directories use `index.tsx` for main exports
- Example: `src/features/chat/index.tsx` exports `Chat` component

**React Patterns:**
- Functional components with hooks
- `memo` for performance-critical components: `export const ChatMessage = memo(function ChatMessage(...) { ... })`
- Props interfaces defined alongside components
- `forwardRef` for UI primitives (e.g., `Button`)

## State Management

**Zustand Pattern:**
```typescript
interface ChatState {
  // State
  messages: RawMessage[];
  loading: boolean;
  error: string | null;

  // Actions
  loadHistory: (quiet?: boolean) => Promise<void>;
  sendMessage: (text: string, attachments?: [...]) => Promise<void>;
  clearError: () => void;
}

export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  loading: false,
  error: null,

  loadHistory: async (quiet = false) => {
    const client = getGatewayClient();
    if (!client) return;
    // ...
  },

  clearError: () => set({ error: null }),
}));
```

**Persist Middleware:**
```typescript
export const useGatewayStore = create<GatewayState>()(
  persist(
    (set) => ({
      url: '',
      token: '',
      setConfig: (url, token) => set({ url, token }),
    }),
    {
      name: 'smanweb-gateway',
      partialize: (state) => ({ url: state.url, token: state.token }),
    }
  )
);
```

---

*Convention analysis: 2026-03-14*
