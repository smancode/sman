# Development Conventions - Detailed References

This document provides examples and rationale for the top 5 conventions extracted from the incremental scan.

## 1. Zustand Store Pattern

### Description
All client-side state management uses Zustand with a consistent WebSocket synchronization pattern. Stores are defined in `src/stores/` and follow a standard structure for type safety and WebSocket integration.

### Example Locations
- `src/stores/chat.ts` - Main chat state with message history and streaming
- `src/stores/settings.ts` - Settings management with WebSocket sync
- `src/stores/git.ts` - Git operations state
- `src/stores/code-viewer.ts` - Code viewer state

### Code Example

```typescript
// src/stores/settings.ts
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';

function getWsClient() {
  return useWsConnection.getState().client;
}

function wrapHandler(client, event: string, handler: MsgHandler) {
  const wrapped = (...args: unknown[]) => handler(args[0] as Record<string, unknown>);
  client.on(event, wrapped);
  return () => client.off(event, wrapped);
}

interface SettingsState {
  settings: SmanSettings | null;
  loading: boolean;
  error: string | null;
  fetchSettings: () => Promise<void>;
  updateLlm: (updates: Partial<LlmConfig>) => Promise<void>;
  clearError: () => void;
}

export const useSettingsStore = create<SettingsState>((set, get) => ({
  settings: null,
  loading: false,
  error: null,

  fetchSettings: async () => {
    const client = getWsClient();
    if (!client) return;
    set({ loading: true, error: null });
    try {
      // ... fetch logic
    } catch (err) {
      set({ error: String(err) });
    } finally {
      set({ loading: false });
    }
  },
}));
```

### Rationale
- **Type safety**: Explicit state interfaces prevent typos and provide IDE autocomplete
- **WebSocket sync**: `wrapHandler()` ensures type-safe event handling
- **Consistency**: All stores follow the same pattern, making code predictable
- **Testing**: Pure functions make stores easy to test

---

## 2. Feature Directory Structure

### Description
Each major feature is organized as a self-contained module under `src/features/` with a consistent internal structure.

### Example Locations
- `src/features/chat/` - Chat interface components
- `src/features/code-viewer/` - Code browser and editor
- `src/features/git/` - Git operations panel
- `src/features/settings/` - Settings pages
- `src/features/stardom/` - Multi-agent collaboration UI

### Code Example

```
src/features/chat/
├── index.tsx              # Main entry, exports default
├── ChatInput.tsx          # Input component with skill picker
├── ChatMessage.tsx        # Message display with streaming
├── ChatToolbar.tsx        # Action buttons
├── InitBanner.tsx         # Session initialization banner
├── AskUserCard.tsx        # Interactive question cards
├── streamdown-components.tsx  # Custom Streamdown components
└── message-utils.ts       # Helper functions
```

### Rationale
- **Colocation**: Related code lives together, easier to find and modify
- **Clear entry points**: `index.tsx` makes imports simple (`import Chat from '@/features/chat'`)
- **Scalability**: New features follow the same pattern, reducing cognitive load
- **Routing consistency**: All features registered in `src/app/routes.tsx`

---

## 3. Server Handler Pattern

### Description
Server modules follow a consistent three-tier pattern: handlers (API endpoints), stores (persistence), and engines (business logic).

### Example Locations
- `server/git-handler.ts` + `server/session-store.ts` - Git operations
- `server/code-viewer-handler.ts` - Code browsing endpoints
- `server/batch-engine.ts` + `server/batch-store.ts` - Batch task execution
- `server/smart-path-engine.ts` + `server/smart-path-store.ts` - Multi-step workflows

### Code Example

```typescript
// server/git-handler.ts
import { createLogger, type Logger } from './utils/logger.js';

export interface GitStatusResult {
  branch: string;
  files: GitFileStatus[];
  ahead: number;
  behind: number;
}

export function handleGitStatus(workspace: string): GitStatusResult {
  // Business logic here
  const status = git(workspace, 'status --porcelain');
  return parseStatus(status);
}

// server/session-store.ts
export class SessionStore {
  private db: Database.Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new Database(dbPath);
    this.log = createLogger('SessionStore');
    this.init();
  }

  createSession(input: CreateSessionInput): Session {
    const stmt = this.db.prepare(`
      INSERT INTO sessions (id, system_id, workspace, created_at, last_active_at)
      VALUES (?, ?, ?, datetime('now'), datetime('now'))
    `);
    stmt.run(input.id, input.systemId, input.workspace);
    return { id: input.id, ...input };
  }
}
```

### Rationale
- **Separation of concerns**: Handlers (HTTP/WS) → Stores (DB) → Engines (logic)
- **Testability**: Each layer can be tested independently
- **Reusability**: Engines can be called from multiple handlers
- **Logging consistency**: `createLogger()` provides structured logging

---

## 4. TypeScript Interface Exports

### Description
All modules export explicit TypeScript interfaces for their data structures, ensuring type safety across module boundaries.

### Example Locations
- `server/git-handler.ts` - `GitStatusResult`, `GitDiffFile`, `GitLogEntry`
- `server/code-viewer-handler.ts` - `ListDirResult`, `DirEntry`
- `src/types/chat.ts` - `ChatSession`, `ContentBlock`, `Message`
- `src/types/settings.ts` - `SmanSettings`, `LlmConfig`

### Code Example

```typescript
// server/git-handler.ts
export interface GitFileStatus {
  path: string;
  status: 'added' | 'modified' | 'deleted' | 'renamed' | 'untracked';
  staged: boolean;
}

export interface GitStatusResult {
  branch: string;
  files: GitFileStatus[];
  ahead: number;
  behind: number;
  hasUpstream: boolean;
}

// src/features/chat/ChatInput.tsx
export interface StagedMedia {
  fileName: string;
  mimeType: string;
  base64Data: string;
  filePath?: string;
}

interface ChatInputProps {
  onSend: (text: string, attachments?: unknown, targetAgentId?: string | null, media?: StagedMedia[]) => void;
  disabled?: boolean;
  sending?: boolean;
}
```

### Rationale
- **Type safety**: Catches errors at compile time, not runtime
- **Documentation**: Interfaces serve as inline documentation
- **IDE support**: Enables autocomplete and refactoring tools
- **Contract clarity**: Explicit APIs between modules

---

## 5. Async Error Handling

### Description
Consistent error handling patterns for async operations, with typed errors and proper error propagation to the UI.

### Example Locations
- `server/code-viewer-handler.ts` - Path validation with error codes
- `server/git-handler.ts` - Git command error wrapping
- `src/stores/settings.ts` - Store error state management
- `server/claude-session.ts` - Session lifecycle error handling

### Code Example

```typescript
// server/code-viewer-handler.ts
export function validatePath(workspace: string, filePath: string): string {
  const resolved = path.resolve(workspace, filePath);

  if (!resolved.startsWith(normalizedWorkspace + path.sep)) {
    throw Object.assign(new Error('Path is outside workspace'), { code: 'PATH_TRAVERSAL' });
  }

  return resolved;
}

// server/git-handler.ts
function git(workspace: string, args: string): string {
  try {
    return execSync(`git --no-pager ${args}`, {
      cwd: workspace,
      timeout: 10000,
    }).trim();
  } catch (err: unknown) {
    const e = err as { stderr?: string; message?: string };
    const msg = e.stderr?.trim() || e.message || String(err);
    throw new Error(msg);
  }
}

// src/stores/settings.ts
fetchSettings: async () => {
  set({ loading: true, error: null });
  try {
    const settings = await client.request('settings.get');
    set({ settings });
  } catch (err) {
    set({ error: String(err) });
  } finally {
    set({ loading: false });
  }
}
```

### Rationale
- **Type safety**: `err: unknown` + type guards prevent runtime errors
- **Error codes**: `Object.assign()` adds metadata for error handling
- **UI feedback**: Stores expose `error: string | null` for display
- **Consistency**: Predictable error patterns across codebase
