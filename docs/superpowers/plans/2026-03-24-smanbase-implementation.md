# SmanBase Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从零搭建 SmanBase 智能业务底座，用 Claude Agent SDK 替代 OpenClaw Gateway，实现 Skills 驱动的业务赋能平台。

**Architecture:** 两层架构（UI → SmanBase 后端 → Claude Code），后端用 Claude Agent SDK 直连 Claude Code，Skills 通过 Registry + Profile 运行时动态注入，会话用 SQLite 持久化。

**Tech Stack:** Node.js + TypeScript, React + Vite, Claude Agent SDK (@anthropic-ai/claude-agent-sdk), SQLite (better-sqlite3), Electron

**Spec:** `docs/superpowers/specs/2026-03-24-smanbase-design.md`

---

## Chunk 1: 项目脚手架 + 后端核心

> 产出：SmanBase 后端可启动，能创建会话、发消息、流式响应、持久化到 SQLite。可以通过 WebSocket 客户端测试。

### Task 1: 项目初始化

**Files:**
- Create: `package.json`
- Create: `tsconfig.json`
- Create: `server/tsconfig.json`
- Create: `.gitignore`
- Create: `server/utils/logger.ts`

- [ ] **Step 1: 初始化 package.json**

```bash
cd ~/projects/smanbase
```

```json
// package.json
{
  "name": "smanbase",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "dev:server": "tsx watch server/index.ts",
    "build": "vite build && tsc -p server/tsconfig.json",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "@anthropic-ai/claude-agent-sdk": "^0.1.0",
    "better-sqlite3": "^11.0.0",
    "ws": "^8.18.0",
    "express": "^4.21.0",
    "uuid": "^10.0.0",
    "yaml": "^2.7.0"
  },
  "devDependencies": {
    "@types/better-sqlite3": "^7.6.0",
    "@types/express": "^5.0.0",
    "@types/ws": "^8.5.0",
    "@types/uuid": "^10.0.0",
    "@types/node": "^22.0.0",
    "typescript": "^5.7.0",
    "tsx": "^4.19.0",
    "vite": "^6.0.0",
    "@vitejs/plugin-react": "^4.3.0",
    "vitest": "^2.1.0",
    "react": "^19.0.0",
    "react-dom": "^19.0.0",
    "@types/react": "^19.0.0",
    "@types/react-dom": "^19.0.0"
  }
}
```

- [ ] **Step 2: 创建 tsconfig.json（根级）**

```json
// tsconfig.json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "esModuleInterop": true,
    "strict": true,
    "skipLibCheck": true,
    "outDir": "dist",
    "rootDir": ".",
    "jsx": "react-jsx",
    "paths": {
      "@/*": ["./src/*"]
    }
  },
  "include": ["src", "server"],
  "exclude": ["node_modules", "dist", "electron"]
}
```

- [ ] **Step 3: 创建 server/tsconfig.json**

```json
// server/tsconfig.json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "esModuleInterop": true,
    "strict": true,
    "skipLibCheck": true,
    "outDir": "../dist/server",
    "rootDir": ".",
    "declaration": true
  },
  "include": ["./**/*.ts"],
  "exclude": ["node_modules", "../dist"]
}
```

- [ ] **Step 4: 创建 .gitignore**

```
# Dependencies
node_modules/

# Build output
dist/
bundled/

# Runtime data
*.db
*.db-journal
logs/

# Environment
.env
.env.local

# IDE
.idea/
.vscode/
*.swp

# OS
.DS_Store
Thumbs.db

# Temp
tmp/
build/.tmp-*
```

- [ ] **Step 5: 创建 logger 工具**

```typescript
// server/utils/logger.ts

export interface Logger {
  info(message: string, meta?: Record<string, unknown>): void;
  warn(message: string, meta?: Record<string, unknown>): void;
  error(message: string, meta?: Record<string, unknown>): void;
  debug(message: string, meta?: Record<string, unknown>): void;
}

export function createLogger(module: string): Logger {
  const isDebug = process.env.LOG_LEVEL === 'debug';

  return {
    info(message: string, meta?: Record<string, unknown>) {
      console.log(JSON.stringify({ level: 'info', module, message, ...meta, ts: new Date().toISOString() }));
    },
    warn(message: string, meta?: Record<string, unknown>) {
      console.warn(JSON.stringify({ level: 'warn', module, message, ...meta, ts: new Date().toISOString() }));
    },
    error(message: string, meta?: Record<string, unknown>) {
      console.error(JSON.stringify({ level: 'error', module, message, ...meta, ts: new Date().toISOString() }));
    },
    debug(message: string, meta?: Record<string, unknown>) {
      if (isDebug) {
        console.debug(JSON.stringify({ level: 'debug', module, message, ...meta, ts: new Date().toISOString() }));
      }
    },
  };
}
```

- [ ] **Step 6: 安装依赖并验证**

```bash
cd ~/projects/smanbase
pnpm install
```

- [ ] **Step 7: Commit**

```bash
git add package.json tsconfig.json server/tsconfig.json .gitignore server/utils/logger.ts
git commit -m "chore: initialize project scaffold with dependencies"
```

---

### Task 2: 会话持久化（SQLite）

**Files:**
- Create: `server/session-store.ts`
- Create: `tests/server/session-store.test.ts`

- [ ] **Step 1: 写失败测试**

```typescript
// tests/server/session-store.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { SessionStore } from '../../server/session-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('SessionStore', () => {
  let store: SessionStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `smanbase-test-${Date.now()}.db`);
    store = new SessionStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  it('should create a session', () => {
    const session = store.createSession({
      id: 'sess-1',
      systemId: 'projectA',
      workspace: '/data/projectA',
    });
    expect(session.id).toBe('sess-1');
    expect(session.systemId).toBe('projectA');
  });

  it('should get a session by id', () => {
    store.createSession({
      id: 'sess-2',
      systemId: 'projectB',
      workspace: '/data/projectB',
    });
    const session = store.getSession('sess-2');
    expect(session).toBeDefined();
    expect(session!.systemId).toBe('projectB');
  });

  it('should list sessions filtered by systemId', () => {
    store.createSession({ id: 's1', systemId: 'sysA', workspace: '/a' });
    store.createSession({ id: 's2', systemId: 'sysA', workspace: '/a' });
    store.createSession({ id: 's3', systemId: 'sysB', workspace: '/b' });

    const sessions = store.listSessions('sysA');
    expect(sessions).toHaveLength(2);
  });

  it('should add and retrieve messages', () => {
    store.createSession({ id: 'sess-3', systemId: 'sysA', workspace: '/a' });
    store.addMessage('sess-3', { role: 'user', content: 'hello' });
    store.addMessage('sess-3', { role: 'assistant', content: 'hi there' });

    const messages = store.getMessages('sess-3');
    expect(messages).toHaveLength(2);
    expect(messages[0].role).toBe('user');
    expect(messages[1].content).toBe('hi there');
  });

  it('should delete a session and its messages', () => {
    store.createSession({ id: 'sess-4', systemId: 'sysA', workspace: '/a' });
    store.addMessage('sess-4', { role: 'user', content: 'hello' });

    store.deleteSession('sess-4');

    expect(store.getSession('sess-4')).toBeUndefined();
    expect(store.getMessages('sess-4')).toHaveLength(0);
  });

  it('should update lastActiveAt on message add', () => {
    store.createSession({ id: 'sess-5', systemId: 'sysA', workspace: '/a' });
    const before = store.getSession('sess-5')!.lastActiveAt;

    // small delay to ensure timestamp difference
    const start = Date.now();
    while (Date.now() === start) { /* busy wait */ }

    store.addMessage('sess-5', { role: 'user', content: 'msg' });
    const after = store.getSession('sess-5')!.lastActiveAt;

    expect(after).not.toBe(before);
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

```bash
cd ~/projects/smanbase
pnpm test tests/server/session-store.test.ts
```

Expected: FAIL — module not found

- [ ] **Step 3: 实现 SessionStore**

```typescript
// server/session-store.ts

import Database from 'better-sqlite3';
import { createLogger, type Logger } from './utils/logger.js';

export interface Session {
  id: string;
  systemId: string;
  workspace: string;
  createdAt: string;
  lastActiveAt: string;
}

export interface Message {
  id: number;
  sessionId: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: string;
}

interface CreateSessionInput {
  id: string;
  systemId: string;
  workspace: string;
}

interface AddMessageInput {
  role: 'user' | 'assistant';
  content: string;
}

export class SessionStore {
  private db: Database.Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new Database(dbPath);
    this.log = createLogger('SessionStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY,
        system_id TEXT NOT NULL,
        workspace TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        last_active_at TEXT NOT NULL DEFAULT (datetime('now'))
      );

      CREATE TABLE IF NOT EXISTS messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id TEXT NOT NULL,
        role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
        content TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
      );

      CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages(session_id);
      CREATE INDEX IF NOT EXISTS idx_sessions_system_id ON sessions(system_id);
    `);
    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
    this.log.info('Database initialized');
  }

  createSession(input: CreateSessionInput): Session {
    const { id, systemId, workspace } = input;
    this.db.prepare(
      'INSERT OR IGNORE INTO sessions (id, system_id, workspace) VALUES (?, ?, ?)'
    ).run(id, systemId, workspace);

    return this.getSession(id)!;
  }

  getSession(id: string): Session | undefined {
    const row = this.db.prepare(
      'SELECT id, system_id as systemId, workspace, created_at as createdAt, last_active_at as lastActiveAt FROM sessions WHERE id = ?'
    ).get(id) as Session | undefined;
    return row;
  }

  listSessions(systemId?: string): Session[] {
    if (systemId) {
      return this.db.prepare(
        'SELECT id, system_id as systemId, workspace, created_at as createdAt, last_active_at as lastActiveAt FROM sessions WHERE system_id = ? ORDER BY last_active_at DESC'
      ).all(systemId) as Session[];
    }
    return this.db.prepare(
      'SELECT id, system_id as systemId, workspace, created_at as createdAt, last_active_at as lastActiveAt FROM sessions ORDER BY last_active_at DESC'
    ).all() as Session[];
  }

  addMessage(sessionId: string, input: AddMessageInput): Message {
    const { role, content } = input;
    this.db.prepare(
      'UPDATE sessions SET last_active_at = datetime(\'now\') WHERE id = ?'
    ).run(sessionId);

    const result = this.db.prepare(
      'INSERT INTO messages (session_id, role, content) VALUES (?, ?, ?)'
    ).run(sessionId, role, content);

    return {
      id: result.lastInsertRowid as number,
      sessionId,
      role,
      content,
      createdAt: new Date().toISOString(),
    };
  }

  getMessages(sessionId: string, limit = 1000): Message[] {
    return this.db.prepare(
      'SELECT id, session_id as sessionId, role, content, created_at as createdAt FROM messages WHERE session_id = ? ORDER BY id ASC LIMIT ?'
    ).all(sessionId, limit) as Message[];
  }

  deleteSession(id: string): void {
    this.db.prepare('DELETE FROM sessions WHERE id = ?').run(id);
  }

  close(): void {
    this.db.close();
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
cd ~/projects/smanbase
pnpm test tests/server/session-store.test.ts
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/session-store.ts tests/server/session-store.test.ts
git commit -m "feat: implement SessionStore with SQLite persistence"
```

---

### Task 3: Skills Registry

**Files:**
- Create: `server/skills-registry.ts`
- Create: `server/types.ts`
- Create: `tests/server/skills-registry.test.ts`

- [ ] **Step 1: 定义类型**

```typescript
// server/types.ts

export interface SkillEntry {
  name: string;
  description: string;
  version: string;
  path: string;
  triggers: ('auto-on-init' | 'manual')[];
  tags: string[];
}

export interface Registry {
  version: string;
  skills: Record<string, SkillEntry>;
}

export interface Profile {
  systemId: string;
  name: string;
  workspace: string;
  description: string;
  skills: string[];
  autoTriggers: {
    onInit: string[];
    onConversationStart: string[];
  };
  claudeMdTemplate?: string;
}

export interface SmanConfig {
  port: number;
  llm: {
    apiKey: string;
    model: string;
    baseUrl?: string;
  };
  webSearch: {
    provider: 'builtin' | 'brave' | 'tavily';
    braveApiKey: string;
    tavilyApiKey: string;
    maxUsesPerSession: number;
  };
}
```

- [ ] **Step 2: 写失败测试**

```typescript
// tests/server/skills-registry.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { SkillsRegistry } from '../../server/skills-registry.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('SkillsRegistry', () => {
  let registry: SkillsRegistry;
  let homeDir: string;

  beforeEach(() => {
    homeDir = path.join(os.tmpdir(), `smanbase-reg-${Date.now()}`);
    fs.mkdirSync(homeDir, { recursive: true });
    fs.mkdirSync(path.join(homeDir, 'skills'), { recursive: true });

    // Create a sample skill directory
    fs.mkdirSync(path.join(homeDir, 'skills', 'java-scanner'), { recursive: true });
    fs.writeFileSync(
      path.join(homeDir, 'skills', 'java-scanner', 'skill.md'),
      '# Java Scanner\nScans Java projects.'
    );

    // Create registry.json
    const registryJson = {
      version: '1.0',
      skills: {
        'java-scanner': {
          name: 'Java Scanner',
          description: 'Scans Java projects',
          version: '1.0.0',
          path: 'skills/java-scanner',
          triggers: ['auto-on-init', 'manual'],
          tags: ['java'],
        },
      },
    };
    fs.writeFileSync(
      path.join(homeDir, 'registry.json'),
      JSON.stringify(registryJson, null, 2)
    );

    registry = new SkillsRegistry(homeDir);
  });

  afterEach(() => {
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  it('should list all available skills', () => {
    const skills = registry.listSkills();
    expect(skills).toHaveLength(1);
    expect(skills[0].name).toBe('Java Scanner');
  });

  it('should get a specific skill', () => {
    const skill = registry.getSkill('java-scanner');
    expect(skill).toBeDefined();
    expect(skill!.version).toBe('1.0.0');
  });

  it('should return undefined for non-existent skill', () => {
    const skill = registry.getSkill('non-existent');
    expect(skill).toBeUndefined();
  });

  it('should get skill directory path', () => {
    const dir = registry.getSkillDir('java-scanner');
    expect(fs.existsSync(dir)).toBe(true);
  });

  it('should check if skill exists', () => {
    expect(registry.hasSkill('java-scanner')).toBe(true);
    expect(registry.hasSkill('non-existent')).toBe(false);
  });
});
```

- [ ] **Step 3: 运行测试验证失败**

```bash
pnpm test tests/server/skills-registry.test.ts
```

Expected: FAIL

- [ ] **Step 4: 实现 SkillsRegistry**

```typescript
// server/skills-registry.ts

import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from './utils/logger.js';
import type { SkillEntry, Registry } from './types.js';

export class SkillsRegistry {
  private homeDir: string;
  private registryPath: string;
  private registry: Registry | null = null;
  private log: Logger;

  constructor(homeDir: string) {
    this.homeDir = homeDir;
    this.registryPath = path.join(homeDir, 'registry.json');
    this.log = createLogger('SkillsRegistry');
  }

  private load(): Registry {
    if (this.registry) return this.registry;

    if (!fs.existsSync(this.registryPath)) {
      this.log.warn('registry.json not found, creating empty');
      this.registry = { version: '1.0', skills: {} };
      return this.registry;
    }

    const raw = fs.readFileSync(this.registryPath, 'utf-8');
    this.registry = JSON.parse(raw) as Registry;
    this.log.info(`Loaded registry with ${Object.keys(this.registry.skills).length} skills`);
    return this.registry;
  }

  listSkills(): (SkillEntry & { id: string })[] {
    const reg = this.load();
    return Object.entries(reg.skills).map(([id, skill]) => ({
      id,
      ...skill,
    }));
  }

  getSkill(id: string): SkillEntry | undefined {
    return this.load().skills[id];
  }

  hasSkill(id: string): boolean {
    return id in this.load().skills;
  }

  getSkillDir(id: string): string {
    const skill = this.getSkill(id);
    if (!skill) throw new Error(`Skill not found: ${id}`);
    return path.join(this.homeDir, skill.path);
  }

  getSkillDirs(skillIds: string[]): string[] {
    return skillIds
      .filter(id => this.hasSkill(id))
      .map(id => this.getSkillDir(id));
  }
}
```

- [ ] **Step 5: 运行测试验证通过**

```bash
pnpm test tests/server/skills-registry.test.ts
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add server/skills-registry.ts server/types.ts tests/server/skills-registry.test.ts
git commit -m "feat: implement SkillsRegistry with registry.json loading"
```

---

### Task 4: Profile Manager

**Files:**
- Create: `server/profile-manager.ts`
- Create: `tests/server/profile-manager.test.ts`

- [ ] **Step 1: 写失败测试**

```typescript
// tests/server/profile-manager.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { ProfileManager } from '../../server/profile-manager.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('ProfileManager', () => {
  let pm: ProfileManager;
  let homeDir: string;

  beforeEach(() => {
    homeDir = path.join(os.tmpdir(), `smanbase-pm-${Date.now()}`);
    fs.mkdirSync(path.join(homeDir, 'profiles'), { recursive: true });
    pm = new ProfileManager(homeDir);
  });

  afterEach(() => {
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  it('should create a profile', () => {
    const profile = pm.createProfile({
      systemId: 'projectA',
      name: 'Project A',
      workspace: '/data/projectA',
      description: 'Spring Boot',
      skills: ['java-scanner'],
    });

    expect(profile.systemId).toBe('projectA');
    expect(profile.skills).toEqual(['java-scanner']);
  });

  it('should get a profile', () => {
    pm.createProfile({
      systemId: 'sys1',
      name: 'System 1',
      workspace: '/data/sys1',
      description: 'desc',
      skills: [],
    });

    const profile = pm.getProfile('sys1');
    expect(profile).toBeDefined();
    expect(profile!.name).toBe('System 1');
  });

  it('should list all profiles', () => {
    pm.createProfile({ systemId: 'a', name: 'A', workspace: '/a', description: '', skills: [] });
    pm.createProfile({ systemId: 'b', name: 'B', workspace: '/b', description: '', skills: [] });

    const profiles = pm.listProfiles();
    expect(profiles).toHaveLength(2);
  });

  it('should update a profile', () => {
    pm.createProfile({ systemId: 'sys1', name: 'Old', workspace: '/old', description: '', skills: [] });
    pm.updateProfile('sys1', { name: 'New', workspace: '/new' });

    const profile = pm.getProfile('sys1');
    expect(profile!.name).toBe('New');
    expect(profile!.workspace).toBe('/new');
  });

  it('should delete a profile', () => {
    pm.createProfile({ systemId: 'sys1', name: 'A', workspace: '/a', description: '', skills: [] });
    pm.deleteProfile('sys1');

    expect(pm.getProfile('sys1')).toBeUndefined();
  });

  it('should throw when creating profile with duplicate systemId', () => {
    pm.createProfile({ systemId: 'sys1', name: 'A', workspace: '/a', description: '', skills: [] });
    expect(() =>
      pm.createProfile({ systemId: 'sys1', name: 'B', workspace: '/b', description: '', skills: [] })
    ).toThrow();
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

```bash
pnpm test tests/server/profile-manager.test.ts
```

Expected: FAIL

- [ ] **Step 3: 实现 ProfileManager**

```typescript
// server/profile-manager.ts

import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from './utils/logger.js';
import type { Profile } from './types.js';

interface CreateProfileInput {
  systemId: string;
  name: string;
  workspace: string;
  description: string;
  skills: string[];
  autoTriggers?: {
    onInit?: string[];
    onConversationStart?: string[];
  };
  claudeMdTemplate?: string;
}

type UpdateProfileInput = Partial<Omit<CreateProfileInput, 'systemId'>>;

export class ProfileManager {
  private homeDir: string;
  private profilesDir: string;
  private log: Logger;

  constructor(homeDir: string) {
    this.homeDir = homeDir;
    this.profilesDir = path.join(homeDir, 'profiles');
    this.log = createLogger('ProfileManager');

    if (!fs.existsSync(this.profilesDir)) {
      fs.mkdirSync(this.profilesDir, { recursive: true });
    }
  }

  private profilePath(systemId: string): string {
    const dir = path.join(this.profilesDir, systemId);
    return path.join(dir, 'profile.json');
  }

  createProfile(input: CreateProfileInput): Profile {
    const filePath = this.profilePath(input.systemId);
    if (fs.existsSync(filePath)) {
      throw new Error(`Profile already exists: ${input.systemId}`);
    }

    const dir = path.join(this.profilesDir, input.systemId);
    fs.mkdirSync(dir, { recursive: true });

    const profile: Profile = {
      systemId: input.systemId,
      name: input.name,
      workspace: input.workspace,
      description: input.description,
      skills: input.skills,
      autoTriggers: {
        onInit: input.autoTriggers?.onInit ?? [],
        onConversationStart: input.autoTriggers?.onConversationStart ?? [],
      },
      claudeMdTemplate: input.claudeMdTemplate,
    };

    fs.writeFileSync(filePath, JSON.stringify(profile, null, 2), 'utf-8');
    this.log.info(`Created profile: ${input.systemId}`);
    return profile;
  }

  getProfile(systemId: string): Profile | undefined {
    const filePath = this.profilePath(systemId);
    if (!fs.existsSync(filePath)) return undefined;

    const raw = fs.readFileSync(filePath, 'utf-8');
    return JSON.parse(raw) as Profile;
  }

  listProfiles(): Profile[] {
    if (!fs.existsSync(this.profilesDir)) return [];

    const dirs = fs.readdirSync(this.profilesDir, { withFileTypes: true })
      .filter(d => d.isDirectory());

    const profiles: Profile[] = [];
    for (const dir of dirs) {
      const filePath = path.join(dir.path, dir.name, 'profile.json');
      if (fs.existsSync(filePath)) {
        const raw = fs.readFileSync(filePath, 'utf-8');
        profiles.push(JSON.parse(raw) as Profile);
      }
    }
    return profiles;
  }

  updateProfile(systemId: string, updates: UpdateProfileInput): Profile {
    const profile = this.getProfile(systemId);
    if (!profile) throw new Error(`Profile not found: ${systemId}`);

    const updated = { ...profile, ...updates };
    const filePath = this.profilePath(systemId);
    fs.writeFileSync(filePath, JSON.stringify(updated, null, 2), 'utf-8');
    this.log.info(`Updated profile: ${systemId}`);
    return updated;
  }

  deleteProfile(systemId: string): void {
    const dir = path.join(this.profilesDir, systemId);
    if (fs.existsSync(dir)) {
      fs.rmSync(dir, { recursive: true, force: true });
      this.log.info(`Deleted profile: ${systemId}`);
    }
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
pnpm test tests/server/profile-manager.test.ts
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/profile-manager.ts tests/server/profile-manager.test.ts
git commit -m "feat: implement ProfileManager for business system profiles"
```

---

### Task 5: ClaudeSessionManager（核心）

**Files:**
- Create: `server/claude-session.ts`
- Create: `tests/server/claude-session.test.ts`

- [ ] **Step 1: 写失败测试**

```typescript
// tests/server/claude-session.test.ts
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { ClaudeSessionManager, type ActiveSession } from '../../server/claude-session.js';
import { SessionStore } from '../../server/session-store.js';
import { SkillsRegistry } from '../../server/skills-registry.js';
import { ProfileManager } from '../../server/profile-manager.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

// Mock WebSocket
class MockWebSocket {
  sent: string[] = [];
  send(data: string) { this.sent.push(data); }
  close() {}
}

describe('ClaudeSessionManager', () => {
  let manager: ClaudeSessionManager;
  let store: SessionStore;
  let skillsRegistry: SkillsRegistry;
  let profileManager: ProfileManager;
  let homeDir: string;
  let dbPath: string;

  beforeEach(() => {
    homeDir = path.join(os.tmpdir(), `smanbase-csm-${Date.now()}`);
    dbPath = path.join(homeDir, 'test.db');
    fs.mkdirSync(homeDir, { recursive: true });
    fs.mkdirSync(path.join(homeDir, 'skills'), { recursive: true });
    fs.mkdirSync(path.join(homeDir, 'profiles'), { recursive: true });

    store = new SessionStore(dbPath);
    skillsRegistry = new SkillsRegistry(homeDir);
    profileManager = new ProfileManager(homeDir);

    // Create a test profile
    profileManager.createProfile({
      systemId: 'projectA',
      name: 'Project A',
      workspace: '/tmp/fake-workspace',
      description: 'Test project',
      skills: [],
    });

    manager = new ClaudeSessionManager(store, skillsRegistry, profileManager);
  });

  afterEach(() => {
    manager.close();
    store.close();
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  it('should create a new session', () => {
    const sessionId = manager.createSession('projectA');
    expect(sessionId).toBeDefined();
    expect(typeof sessionId).toBe('string');

    const session = store.getSession(sessionId);
    expect(session).toBeDefined();
    expect(session!.systemId).toBe('projectA');
  });

  it('should list active sessions', () => {
    const s1 = manager.createSession('projectA');
    const s2 = manager.createSession('projectA');

    const sessions = manager.listSessions('projectA');
    expect(sessions).toHaveLength(2);
    expect(sessions.map(s => s.id)).toContain(s1);
    expect(sessions.map(s => s.id)).toContain(s2);
  });

  it('should abort a session', () => {
    const sessionId = manager.createSession('projectA');
    expect(() => manager.abort(sessionId)).not.toThrow();
  });

  it('should get session history', () => {
    const sessionId = manager.createSession('projectA');
    store.addMessage(sessionId, { role: 'user', content: 'hello' });
    store.addMessage(sessionId, { role: 'assistant', content: 'hi' });

    const history = manager.getHistory(sessionId);
    expect(history).toHaveLength(2);
  });

  it('should throw when creating session for unknown system', () => {
    expect(() => manager.createSession('unknown-system')).toThrow();
  });
});
```

- [ ] **Step 2: 运行测试验证失败**

```bash
pnpm test tests/server/claude-session.test.ts
```

Expected: FAIL

- [ ] **Step 3: 实现 ClaudeSessionManager**

```typescript
// server/claude-session.ts

import { v4 as uuidv4 } from 'uuid';
import { createLogger, type Logger } from './utils/logger.js';
import type { SessionStore, Message } from './session-store.js';
import type { SkillsRegistry } from './skills-registry.js';
import type { ProfileManager } from './profile-manager.js';

export interface ActiveSession {
  id: string;
  systemId: string;
  workspace: string;
  createdAt: string;
  lastActiveAt: string;
}

type WsSend = (data: string) => void;

export class ClaudeSessionManager {
  private sessions = new Map<string, ActiveSession>();
  private abortControllers = new Map<string, AbortController>();
  private log: Logger;

  constructor(
    private store: SessionStore,
    private skillsRegistry: SkillsRegistry,
    private profileManager: ProfileManager,
  ) {
    this.log = createLogger('ClaudeSessionManager');
  }

  createSession(systemId: string): string {
    const profile = this.profileManager.getProfile(systemId);
    if (!profile) {
      throw new Error(`Profile not found: ${systemId}`);
    }

    const id = uuidv4();
    this.store.createSession({
      id,
      systemId,
      workspace: profile.workspace,
    });

    const session: ActiveSession = {
      id,
      systemId,
      workspace: profile.workspace,
      createdAt: new Date().toISOString(),
      lastActiveAt: new Date().toISOString(),
    };

    this.sessions.set(id, session);
    this.log.info(`Session created: ${id} for system ${systemId}`);
    return id;
  }

  async sendMessage(sessionId: string, content: string, wsSend: WsSend): Promise<void> {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error(`Session not found: ${sessionId}`);

    // Save user message
    this.store.addMessage(sessionId, { role: 'user', content });

    const profile = this.profileManager.getProfile(session.systemId);
    if (!profile) throw new Error(`Profile not found: ${session.systemId}`);

    // Build skill directories for injection
    const skillDirs = this.skillsRegistry.getSkillDirs(profile.skills);

    // TODO: Replace with actual Claude Agent SDK call in Chunk 2
    // For now, echo back to test the pipeline
    const response = `[Mock] 收到消息: ${content}. Skills: ${skillDirs.join(', ') || 'none'}`;

    // Simulate streaming delta
    wsSend(JSON.stringify({
      type: 'chat.delta',
      sessionId,
      content: response,
    }));

    // Save assistant message
    this.store.addMessage(sessionId, { role: 'assistant', content: response });

    wsSend(JSON.stringify({
      type: 'chat.done',
      sessionId,
      cost: 0,
    }));

    this.log.info(`Message processed for session ${sessionId}`);
  }

  abort(sessionId: string): void {
    const controller = this.abortControllers.get(sessionId);
    if (controller) {
      controller.abort();
      this.abortControllers.delete(sessionId);
      this.log.info(`Session aborted: ${sessionId}`);
    }
  }

  listSessions(systemId?: string): ActiveSession[] {
    const allSessions = this.store.listSessions(systemId);
    return allSessions.map(s => {
      let active = this.sessions.get(s.id);
      if (!active) {
        active = {
          id: s.id,
          systemId: s.systemId,
          workspace: s.workspace,
          createdAt: s.createdAt,
          lastActiveAt: s.lastActiveAt,
        };
        this.sessions.set(s.id, active);
      }
      return active;
    });
  }

  getHistory(sessionId: string): Message[] {
    return this.store.getMessages(sessionId);
  }

  close(): void {
    for (const controller of this.abortControllers.values()) {
      controller.abort();
    }
    this.abortControllers.clear();
    this.sessions.clear();
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
pnpm test tests/server/claude-session.test.ts
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/claude-session.ts tests/server/claude-session.test.ts
git commit -m "feat: implement ClaudeSessionManager with session lifecycle"
```

---

### Task 6: 后端入口 + WebSocket 服务器

**Files:**
- Create: `server/index.ts`

- [ ] **Step 1: 实现后端入口**

```typescript
// server/index.ts

import http from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import path from 'path';
import fs from 'fs';
import os from 'os';
import { createLogger, type Logger } from './utils/logger.js';
import { SessionStore } from './session-store.js';
import { SkillsRegistry } from './skills-registry.js';
import { ProfileManager } from './profile-manager.js';
import { ClaudeSessionManager } from './claude-session.js';

const PORT = parseInt(process.env.PORT || '5170', 10);
const log = createLogger('Server');

function getHomeDir(): string {
  const env = process.env.SMANBASE_HOME;
  if (env) return env;
  return path.join(os.homedir(), '.smanbase');
}

function ensureHomeDir(homeDir: string): void {
  const dirs = ['skills', 'profiles', 'logs'];
  for (const dir of dirs) {
    fs.mkdirSync(path.join(homeDir, dir), { recursive: true });
  }

  const configPath = path.join(homeDir, 'config.json');
  if (!fs.existsSync(configPath)) {
    const defaultConfig = {
      port: PORT,
      llm: { apiKey: '', model: 'claude-sonnet-4-6' },
      webSearch: {
        provider: 'builtin',
        braveApiKey: '',
        tavilyApiKey: '',
        maxUsesPerSession: 50,
      },
    };
    fs.writeFileSync(configPath, JSON.stringify(defaultConfig, null, 2), 'utf-8');
    log.info(`Created default config at ${configPath}`);
  }

  const registryPath = path.join(homeDir, 'registry.json');
  if (!fs.existsSync(registryPath)) {
    const defaultRegistry = { version: '1.0', skills: {} };
    fs.writeFileSync(registryPath, JSON.stringify(defaultRegistry, null, 2), 'utf-8');
    log.info(`Created empty registry at ${registryPath}`);
  }
}

// Initialize
const homeDir = getHomeDir();
ensureHomeDir(homeDir);

const dbPath = path.join(homeDir, 'smanbase.db');
const store = new SessionStore(dbPath);
const skillsRegistry = new SkillsRegistry(homeDir);
const profileManager = new ProfileManager(homeDir);
const sessionManager = new ClaudeSessionManager(store, skillsRegistry, profileManager);

// HTTP server
const server = http.createServer((req, res) => {
  if (req.url === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', timestamp: new Date().toISOString() }));
    return;
  }
  res.writeHead(404);
  res.end();
});

// WebSocket server
const wss = new WebSocketServer({ server, path: '/ws' });

interface WsMessage {
  type: string;
  sessionId?: string;
  systemId?: string;
  content?: string;
  [key: string]: unknown;
}

wss.on('connection', (ws: WebSocket) => {
  log.info('WebSocket client connected');

  ws.on('message', async (data) => {
    let msg: WsMessage;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      ws.send(JSON.stringify({ type: 'error', error: 'Invalid JSON' }));
      return;
    }

    try {
      switch (msg.type) {
        case 'session.create': {
          if (!msg.systemId) throw new Error('Missing systemId');
          const sessionId = sessionManager.createSession(msg.systemId);
          ws.send(JSON.stringify({ type: 'session.created', sessionId, systemId: msg.systemId }));
          break;
        }

        case 'session.list': {
          const sessions = sessionManager.listSessions(msg.systemId as string);
          ws.send(JSON.stringify({ type: 'session.list', sessions }));
          break;
        }

        case 'session.history': {
          if (!msg.sessionId) throw new Error('Missing sessionId');
          const messages = sessionManager.getHistory(msg.sessionId);
          ws.send(JSON.stringify({ type: 'session.history', sessionId: msg.sessionId, messages }));
          break;
        }

        case 'chat.send': {
          if (!msg.sessionId || !msg.content) throw new Error('Missing sessionId or content');
          const wsSend = (d: string) => {
            if (ws.readyState === WebSocket.OPEN) ws.send(d);
          };
          await sessionManager.sendMessage(msg.sessionId, msg.content, wsSend);
          break;
        }

        case 'chat.abort': {
          if (!msg.sessionId) throw new Error('Missing sessionId');
          sessionManager.abort(msg.sessionId);
          ws.send(JSON.stringify({ type: 'chat.aborted', sessionId: msg.sessionId }));
          break;
        }

        default:
          ws.send(JSON.stringify({ type: 'error', error: `Unknown message type: ${msg.type}` }));
      }
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      ws.send(JSON.stringify({ type: 'chat.error', sessionId: msg.sessionId, error: errorMessage }));
      log.error('Message handling error', { error: errorMessage });
    }
  });

  ws.on('close', () => {
    log.info('WebSocket client disconnected');
  });
});

// Graceful shutdown
process.on('SIGTERM', () => {
  log.info('SIGTERM received, shutting down...');
  wss.close();
  server.close(() => process.exit(0));
});

process.on('SIGINT', () => {
  log.info('SIGINT received, shutting down...');
  wss.close();
  server.close(() => process.exit(0));
});

server.listen(PORT, () => {
  log.info(`SmanBase server running on port ${PORT}`);
  log.info(`Home directory: ${homeDir}`);
  log.info(`WebSocket endpoint: ws://localhost:${PORT}/ws`);
  log.info(`Health check: http://localhost:${PORT}/api/health`);
});
```

- [ ] **Step 2: 验证服务器启动**

```bash
cd ~/projects/smanbase
SMANBASE_HOME=/tmp/smanbase-test-server pnpm dev:server
```

Expected: 看到日志 "SmanBase server running on port 5170"

在另一个终端验证 health check：
```bash
curl http://localhost:5170/api/health
```

Expected: `{"status":"ok","timestamp":"..."}`

- [ ] **Step 3: Commit**

```bash
git add server/index.ts
git commit -m "feat: implement backend entry with HTTP + WebSocket server"
```

---

### Task 7: Vitest 配置 + 全部测试通过

**Files:**
- Create: `vitest.config.ts`

- [ ] **Step 1: 创建 vitest 配置**

```typescript
// vitest.config.ts
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
  },
});
```

- [ ] **Step 2: 运行全部测试**

```bash
pnpm test
```

Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add vitest.config.ts
git commit -m "chore: add vitest configuration"
```

---

## Chunk 2: 前端迁移 + Claude SDK 集成

> 产出：React 前端能连接 SmanBase 后端，显示会话列表、对话界面、流式响应。后端用真实 Claude Agent SDK 替代 mock。

**Scope:**
- 从 smanweb 迁移前端核心组件（去掉 OpenClaw 相关代码）
- Vite + React 配置
- Settings 页面简化（只保留 LLM + Web Search）
- 后端 ClaudeSessionManager 接入真实 Claude Agent SDK
- Skills 运行时注入

**依赖 Chunk 1 完成。**

---

## Chunk 3: TaskMonitor + 业务系统接入流程

> 产出：TaskMonitor 能驱动 Claude Code 执行 Plan MD，业务系统接入时自动触发 Skills 扫描。

**Scope:**
- 从 smanweb 迁移 TaskMonitor（去掉 GatewayRpc 依赖）
- TaskMonitor 完成通知改为 WebSocket 推送
- 业务系统接入 API（POST /api/systems）
- onInit 自动触发 Skills 流程
- CLAUDE.md 模板生成

**依赖 Chunk 2 完成。**

---

## Chunk 4: Web Search + Electron + Skills 仓库

> 产出：Web Search 双模式可用，Electron 桌面应用可打包，Skills 仓库有示例内容。

**Scope:**
- Web Search 配置管理（builtin / brave / tavily）
- MCP Server 自动配置（Brave/Tavily）
- Electron 打包（复用 smanweb electron 壳）
- Claude Code 打包脚本
- Skills 仓库初始化（registry.json + 示例 skill）
- 业务系统初始化模板

**依赖 Chunk 3 完成。**
