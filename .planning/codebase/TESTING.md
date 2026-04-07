# Testing Patterns

**Analysis Date:** 2026-04-07

## Test Framework

**Runner:**
- Vitest 2.1+
- Config: No dedicated `vitest.config.*`; uses defaults via `vite.config.ts` resolution
- TypeScript path resolution works via `vite.config.ts` alias configuration

**Assertion Library:**
- Vitest built-in `expect` (compatible with Jest assertions)

**Run Commands:**
```bash
pnpm test          # Run all tests (vitest run)
pnpm test:watch    # Watch mode (vitest)
```

## Test File Organization

**Location:**
- Tests in separate `tests/` directory, mirroring server structure
- No co-located test files (no `.test.ts` files in `src/` or `server/`)

**Naming:**
- `<module-name>.test.ts` for all test files
- One test file per source module

**Structure:**
```
tests/
└── server/
    ├── batch-engine.test.ts         # tests/server/batch-engine.ts -> server/batch-engine.ts
    ├── batch-store.test.ts          # tests/server/batch-store.ts -> server/batch-store.ts
    ├── batch-utils.test.ts
    ├── claude-session.test.ts
    ├── content-blocks.test.ts
    ├── cron-scheduler.test.ts
    ├── cron-task-store.test.ts
    ├── mcp-config.test.ts
    ├── model-capabilities.test.ts
    ├── semaphore.test.ts
    ├── session-store.test.ts
    ├── settings-manager.test.ts
    ├── skills-registry.test.ts
    ├── user-profile.test.ts
    ├── chatbot/
    │   ├── chatbot-session-manager.test.ts
    │   ├── chatbot-store.test.ts
    │   ├── chat-command-parser.test.ts
    │   ├── wecom-bot-connection.test.ts
    │   ├── feishu-bot-connection.test.ts
    │   └── weixin-bot-connection.test.ts
    ├── web-access/
    │   ├── cdp-engine.test.ts
    │   ├── mcp-server.test.ts
    │   └── web-access-service.test.ts
    └── capabilities/
        ├── registry.test.ts
        ├── registry-search.test.ts
        ├── usage-tracking.test.ts
        └── experience-learning.test.ts
```

## Test Structure

**Suite Organization:**
```typescript
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { BatchStore } from '../../server/batch-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('BatchStore', () => {
  let store: BatchStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `batch-test-${Date.now()}.db`);
    store = new BatchStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  describe('createTask', () => {
    it('should create a task with default values', () => {
      // ...
    });
  });
});
```

**Patterns:**
- **Setup (beforeEach):** Create fresh temp database in `os.tmpdir()` with unique name using `Date.now()` or `Math.random()`
- **Teardown (afterEach):** Close store, delete temp database file (including `-wal` and `-shm` files), remove temp directories
- **Grouping:** Nested `describe()` blocks group tests by method or feature
- **Async tests:** Use `async/await` with `it('should ...', async () => { ... })`

## Mocking

**Framework:** Vitest `vi` from `'vitest'`

**Patterns:**

1. **Mock modules at file level:**
```typescript
const mockSendMessageForCron = vi.fn();
const mockCreateSessionWithId = vi.fn();

const mockSessionManager = {
  sendMessageForCron: mockSendMessageForCron,
  createSessionWithId: mockCreateSessionWithId,
  abort: vi.fn(),
  updateConfig: vi.fn(),
  close: vi.fn(),
};

vi.mock('../../server/claude-session.js', () => ({
  ClaudeSessionManager: vi.fn().mockImplementation(() => mockSessionManager),
}));
```

2. **Mock SDK modules:**
```typescript
const mockQuery = vi.fn();
vi.mock('@anthropic-ai/claude-agent-sdk', () => ({
  query: (...args: unknown[]) => mockQuery(...args),
}));
```

3. **Spy on internal methods:**
```typescript
vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);
vi.spyOn(engine as any, 'sendCDP').mockImplementation(async (method: string) => { ... });
```

4. **Mock global fetch for LLM tests:**
```typescript
const mockFetch = vi.fn().mockResolvedValue({
  ok: true,
  json: () => Promise.resolve({
    content: [{ type: 'text', text: '["office-skills"]' }],
  }),
});
vi.stubGlobal('fetch', mockFetch);
// ... test ...
vi.restoreAllMocks();
```

5. **Mock object creation for service dependencies:**
```typescript
function createMockEngine() {
  return {
    isAvailable: vi.fn().mockResolvedValue(true),
    newTab: vi.fn().mockResolvedValue({ tabId: 'tab-1', ... }),
    navigate: vi.fn().mockResolvedValue({ title: 'Page', ... }),
    // ... all interface methods
  };
}
```

6. **Helper factories for test data:**
```typescript
function createSender(): { sender: any; responses: string[]; errors: string[] } {
  const responses: string[] = [];
  const errors: string[] = [];
  const sender = {
    start: () => {},
    sendChunk: () => {},
    finish: (c: string) => responses.push(c),
    error: (m: string) => errors.push(m),
  };
  return { sender, responses, errors };
}
```

**What to Mock:**
- External SDK calls (`@anthropic-ai/claude-agent-sdk`)
- Database dependencies only when testing higher-level services
- Global APIs like `fetch` for LLM-based features
- File system only when testing engine detection (via `vi.spyOn(fs, 'existsSync')`)

**What NOT to Mock:**
- SQLite database operations (use real temp databases)
- File system for config management (use real temp directories)
- Pure utility functions (test directly)
- Store CRUD operations (use real database instances)

## Fixtures and Factories

**Test Data:**
- Inline data creation within tests (no external fixture files)
- Common pattern: create data via store methods then assert state
- Capability test manifests created as inline JSON objects:
```typescript
const manifest = {
  version: '1.0',
  capabilities: {
    'test-cap': {
      id: 'test-cap',
      name: 'Test Capability',
      description: 'A test capability',
      executionMode: 'instruction-inject',
      triggers: ['test', 'testing'],
      runnerModule: './test-runner.js',
      pluginPath: 'test-plugin',
      enabled: true,
      version: '1.0.0',
    },
  },
};
fs.writeFileSync(path.join(tmpDir, 'capabilities.json'), JSON.stringify(manifest));
```

- Helper factory functions for complex types:
```typescript
function makeEntry(overrides: Partial<CapabilityEntry> & { id: string }): CapabilityEntry {
  return {
    name: overrides.id,
    description: '',
    executionMode: 'instruction-inject',
    triggers: [],
    runnerModule: `./${overrides.id}.js`,
    pluginPath: overrides.id,
    enabled: true,
    version: '1.0.0',
    ...overrides,
  };
}
```

**Location:**
- All test data defined inline in test files
- No shared fixture directory or factory modules

## Coverage

**Requirements:** None enforced (no coverage threshold configuration)

**View Coverage:**
```bash
pnpm vitest run --coverage
```
(Not configured in CI; coverage is ad-hoc)

## Test Types

**Unit Tests:**
- Primary test type in this codebase
- Pure functions tested directly: `tests/server/batch-utils.test.ts`, `tests/server/content-blocks.test.ts`
- Store classes tested with real SQLite databases in temp directories
- Parser functions tested with string inputs: `tests/server/chatbot/chat-command-parser.test.ts`, `tests/server/cron-scheduler.test.ts`
- Settings tested with real file system in temp directories: `tests/server/settings-manager.test.ts`

**Integration Tests:**
- Store + engine integration (e.g., `BatchEngine` tests with real `BatchStore` but mocked `ClaudeSessionManager`)
- `ChatbotSessionManager` tests with real `ChatbotStore` but mocked session manager
- `CdpEngine` tests with mocked WebSocket but real engine logic
- `WebAccessService` tests with mocked engine but real service logic
- Migration tests that verify old schema can be opened and migrated: `tests/server/cron-task-store.test.ts` ("should drop old table with interval_minutes and recreate")

**E2E Tests:**
- Not used in this codebase

## Common Patterns

**Database Test Pattern:**
Every store test follows this pattern:
```typescript
describe('StoreName', () => {
  let store: StoreName;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `prefix-test-${Date.now()}.db`);
    store = new StoreName(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
    // Also clean WAL/SHM files:
    for (const ext of ['-wal', '-shm']) {
      const f = dbPath + ext;
      if (fs.existsSync(f)) fs.unlinkSync(f);
    }
  });
});
```

**File System Test Pattern:**
```typescript
describe('ManagerName', () => {
  let homeDir: string;
  let manager: ManagerName;

  beforeEach(() => {
    homeDir = path.join(os.tmpdir(), `sman-test-${Date.now()}`);
    fs.mkdirSync(homeDir, { recursive: true });
    manager = new ManagerName(homeDir);
  });

  afterEach(() => {
    fs.rmSync(homeDir, { recursive: true, force: true });
  });
});
```

**Async Testing:**
```typescript
it('should process items concurrently', async () => {
  // Arrange
  mockSendMessageForCron.mockResolvedValue(undefined);

  // Act
  await engine.execute(task.id);

  // Assert
  expect(mockSendMessageForCron).toHaveBeenCalledTimes(3);
  expect(store.getTask(task.id)!.status).toBe('completed');
});
```

**Error Testing:**
```typescript
it('should throw if task not found', async () => {
  await expect(engine.generateCode('non-existent')).rejects.toThrow('Task not found');
});

it('should revert to draft status on failure', async () => {
  mockQuery.mockReturnValue({
    async *[Symbol.asyncIterator]() {
      throw new Error('API error');
    },
  });

  await expect(engine.generateCode(task.id)).rejects.toThrow('API error');
  expect(store.getTask(task.id)!.status).toBe('draft');
});
```

**Async Iterator Mocking (SDK):**
```typescript
mockQuery.mockReturnValue({
  async *[Symbol.asyncIterator]() {
    yield {
      type: 'assistant',
      message: { content: [{ type: 'text', text: 'result code' }] },
      session_id: 'sess-1',
      is_error: false,
    };
  },
});
```

**Migration Testing:**
```typescript
it('should drop old table and recreate', () => {
  store.close();
  if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);

  // Create old schema
  const rawDb = new DatabaseConstructor(dbPath);
  rawDb.exec(`
    CREATE TABLE cron_tasks (
      id TEXT PRIMARY KEY,
      -- old columns...
    );
    INSERT INTO cron_tasks VALUES (...);
  `);
  rawDb.close();

  // Reopen with new code — triggers migration
  const migratedStore = new CronTaskStore(dbPath);

  // Verify old data handled correctly
  expect(migratedStore.getTask('old-task-1')).toBeUndefined();
  expect(migratedStore.createTask({ ... })).toBeDefined();

  migratedStore.close();
  store = migratedStore;
});
```

**Concurrency Testing:**
```typescript
it('should limit concurrency to max', async () => {
  const sem = new Semaphore(2);
  let active = 0;
  let peak = 0;

  const tasks = Array.from({ length: 10 }, () =>
    sem.withLock(async () => {
      active++;
      peak = Math.max(peak, active);
      await new Promise(r => setTimeout(r, 10));
      active--;
    }),
  );

  await Promise.all(tasks);
  expect(peak).toBeLessThanOrEqual(2);
});
```

**Testing with Private Methods (Engine tests):**
```typescript
// Access private methods via casting
vi.spyOn(engine as any, 'ensureConnected').mockResolvedValue(mockWs);
vi.spyOn(engine as any, 'sendCDP').mockImplementation(async (method: string, params: any) => {
  if (method === 'Target.createTarget') return { result: { targetId: 'tab-123' } };
  return { result: {} };
});
```

## Test Coverage Map

**Well-tested modules (comprehensive tests):**
- `server/semaphore.ts` - Concurrency control
- `server/session-store.ts` - Session and message storage
- `server/batch-store.ts` - Batch task and item CRUD
- `server/batch-engine.ts` - Batch execution lifecycle
- `server/batch-utils.ts` - Template rendering and interpreter detection
- `server/settings-manager.ts` - Config management
- `server/cron-task-store.ts` - Cron task CRUD and migration
- `server/cron-scheduler.ts` - Crontab parsing (`parseCrontabMd`)
- `server/chatbot/chatbot-store.ts` - Chatbot state storage
- `server/chatbot/chatbot-session-manager.ts` - Message routing and commands
- `server/chatbot/chat-command-parser.ts` - Command parsing
- `server/mcp-config.ts` - MCP server configuration
- `server/web-access/cdp-engine.ts` - CDP engine operations
- `server/web-access/web-access-service.ts` - Service layer
- `server/web-access/mcp-server.ts` - MCP tool registration
- `server/utils/content-blocks.ts` - Multimodal content building
- `server/capabilities/registry.ts` - Capability lookup, search, usage tracking

**Minimally tested modules (basic import/structure only):**
- `server/chatbot/wecom-bot-connection.ts` - Only verifies class exists with start/stop
- `server/chatbot/feishu-bot-connection.ts` - Only verifies class exists with start/stop
- `server/chatbot/weixin-bot-connection.ts` - Similar minimal test

**Untested modules (no test files):**
- `server/index.ts` - Main entry point (WebSocket handler, HTTP server)
- `server/claude-session.ts` - Complex V2 session management (has test file but relies heavily on SDK mocking)
- `server/web-access/chrome-sites.ts` - Chrome history/bookmark discovery
- `server/web-access/url-experience-store.ts` - URL experience persistence
- `server/user-profile.ts` - User profile management
- `server/skills-registry.ts` - Skills loading (has test file)
- All frontend code (`src/`) - No frontend tests exist

---

*Testing analysis: 2026-04-07*
