# Project Knowledge Scanner Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现项目知识自动扫描 — 首次打开项目时后台扫描生成轻量知识文件（目录索引式），凌晨3点定时增量更新。

**Architecture:** 新建 `server/capabilities/knowledge-loader.ts`（加载 overview 到 system prompt）、`server/capabilities/scanner-prompts.ts`（3 个 scanner 的 system prompt）、`server/capabilities/project-scanner.ts`（编排器：检测 manifest、创建 scanner session、写 manifest）。修改 `claude-session.ts` 注入知识到 system prompt，修改 `index.ts` 触发扫描，修改 `cron-scheduler.ts` 注册夜间刷新。

**Tech Stack:** TypeScript, fs/path (文件操作), child_process (git 命令), Claude Agent SDK (scanner sessions), Vitest

**Spec:** `docs/superpowers/specs/2026-04-07-project-knowledge-scanner-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `server/capabilities/knowledge-loader.ts` | 读取 overview.md 文件，拼接成 system prompt 片段 |
| Create | `server/capabilities/scanner-prompts.ts` | 3 个 scanner 的 system prompt 定义 |
| Create | `server/capabilities/project-scanner.ts` | 编排器：manifest 检测、锁文件、创建 scanner session、写 manifest、更新 scanned-workspaces.json |
| Create | `tests/server/capabilities/knowledge-loader.test.ts` | knowledge-loader 单元测试 |
| Create | `tests/server/capabilities/project-scanner.test.ts` | project-scanner 单元测试 |
| Modify | `server/claude-session.ts:142-192` | buildSystemPromptAppend 末尾注入知识 |
| Modify | `server/claude-session.ts:194-264` | buildSessionOptions 支持 scanner session（跳过 plugin/MCP） |
| Modify | `server/index.ts:431-435` | session.create 后触发扫描 |
| Modify | `server/index.ts:198-202` | 初始化 ProjectScanner |
| Modify | `server/cron-scheduler.ts` | 注册内置夜间知识刷新任务 |

---

## Chunk 1: Knowledge Loader + Tests

基础模块：读取 overview.md 文件并拼接成 system prompt 片段。

### Task 1: knowledge-loader 测试与实现

**Files:**
- Create: `server/capabilities/knowledge-loader.ts`
- Create: `tests/server/capabilities/knowledge-loader.test.ts`

- [ ] **Step 1: 写测试 — loadKnowledgeOverviews 基础场景**

```typescript
// tests/server/capabilities/knowledge-loader.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { loadKnowledgeOverviews } from '../../../server/capabilities/knowledge-loader.js';

describe('loadKnowledgeOverviews', () => {
  let workspace: string;

  beforeEach(() => {
    workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-knowledge-'));
  });

  afterEach(() => {
    fs.rmSync(workspace, { recursive: true, force: true });
  });

  it('returns null when no manifest.json exists', () => {
    expect(loadKnowledgeOverviews(workspace)).toBeNull();
  });

  it('returns null when manifest exists but no overview files', () => {
    const knowledgeDir = path.join(workspace, '.claude', 'knowledge');
    fs.mkdirSync(knowledgeDir, { recursive: true });
    fs.writeFileSync(
      path.join(knowledgeDir, 'manifest.json'),
      JSON.stringify({ version: '1.0', scannedAt: new Date().toISOString(), scanners: {} }),
    );
    expect(loadKnowledgeOverviews(workspace)).toBeNull();
  });

  it('concatenates available overview files with separator', () => {
    const knowledgeDir = path.join(workspace, '.claude', 'knowledge');
    fs.mkdirSync(path.join(knowledgeDir, 'structure'), { recursive: true });
    fs.mkdirSync(path.join(knowledgeDir, 'apis'), { recursive: true });
    fs.mkdirSync(path.join(knowledgeDir, 'external-calls'), { recursive: true });

    fs.writeFileSync(
      path.join(knowledgeDir, 'manifest.json'),
      JSON.stringify({ version: '1.0', scannedAt: new Date().toISOString(), scanners: {} }),
    );
    fs.writeFileSync(
      path.join(knowledgeDir, 'structure', 'overview.md'),
      '# Structure\n- Java 17 + Spring Boot',
    );
    fs.writeFileSync(
      path.join(knowledgeDir, 'apis', 'overview.md'),
      '# APIs\n| POST | /api/payment |',
    );

    const result = loadKnowledgeOverviews(workspace);
    expect(result).not.toBeNull();
    expect(result!).toContain('# Structure');
    expect(result!).toContain('# APIs');
    expect(result!).toContain('---');
    expect(result!).toContain('.claude/knowledge/{category}/');
    // external-calls has no overview.md, should not appear
    expect(result!).not.toContain('external-calls');
  });

  it('handles partial overviews (only structure)', () => {
    const knowledgeDir = path.join(workspace, '.claude', 'knowledge');
    fs.mkdirSync(path.join(knowledgeDir, 'structure'), { recursive: true });

    fs.writeFileSync(
      path.join(knowledgeDir, 'manifest.json'),
      JSON.stringify({ version: '1.0', scannedAt: new Date().toISOString(), scanners: {} }),
    );
    fs.writeFileSync(
      path.join(knowledgeDir, 'structure', 'overview.md'),
      '# Structure\n- Node.js project',
    );

    const result = loadKnowledgeOverviews(workspace);
    expect(result).toContain('# Structure');
    expect(result).toContain('Node.js project');
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npx vitest run tests/server/capabilities/knowledge-loader.test.ts`
Expected: FAIL — module not found

- [ ] **Step 3: 实现 knowledge-loader.ts**

```typescript
// server/capabilities/knowledge-loader.ts
/**
 * Knowledge Loader — reads overview.md files from .claude/knowledge/
 * and returns a string for injection into session system prompt.
 */
import fs from 'node:fs';
import path from 'node:path';

export function loadKnowledgeOverviews(workspace: string): string | null {
  const knowledgeDir = path.join(workspace, '.claude', 'knowledge');
  const manifestPath = path.join(knowledgeDir, 'manifest.json');

  if (!fs.existsSync(manifestPath)) return null;

  const sections: string[] = [];
  for (const subdir of ['structure', 'apis', 'external-calls']) {
    const overviewPath = path.join(knowledgeDir, subdir, 'overview.md');
    if (fs.existsSync(overviewPath)) {
      sections.push(fs.readFileSync(overviewPath, 'utf-8'));
    }
  }

  return sections.length > 0
    ? sections.join('\n\n---\n\n')
      + '\n\nFor details, read files from `.claude/knowledge/{category}/`.'
    : null;
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `npx vitest run tests/server/capabilities/knowledge-loader.test.ts`
Expected: PASS (4 tests)

- [ ] **Step 5: 提交**

```bash
git add server/capabilities/knowledge-loader.ts tests/server/capabilities/knowledge-loader.test.ts
git commit -m "feat(scanner): add knowledge-loader for reading overview.md files"
```

---

## Chunk 2: Scanner Prompts

3 个 scanner 的 system prompt 定义。

### Task 2: scanner-prompts 测试与实现

**Files:**
- Create: `server/capabilities/scanner-prompts.ts`
- Create: `tests/server/capabilities/scanner-prompts.test.ts`

- [ ] **Step 1: 写测试 — prompt 导出和基本结构验证**

```typescript
// tests/server/capabilities/scanner-prompts.test.ts
import { describe, it, expect } from 'vitest';
import {
  getScannerPrompt,
  SCANNER_TYPES,
  type ScannerType,
} from '../../../server/capabilities/scanner-prompts.js';

describe('scanner-prompts', () => {
  it('exports 3 scanner types', () => {
    expect(SCANNER_TYPES).toHaveLength(3);
    expect(SCANNER_TYPES).toContain('structure');
    expect(SCANNER_TYPES).toContain('apis');
    expect(SCANNER_TYPES).toContain('external-calls');
  });

  it('each prompt contains output directory instruction', () => {
    for (const type of SCANNER_TYPES) {
      const prompt = getScannerPrompt(type as ScannerType, '/tmp/test-workspace');
      expect(prompt).toContain('.claude/knowledge/');
      expect(prompt).toContain(type);
    }
  });

  it('each prompt contains safety rules', () => {
    for (const type of SCANNER_TYPES) {
      const prompt = getScannerPrompt(type as ScannerType, '/tmp/test-workspace');
      expect(prompt).toContain('.env');
      expect(prompt).toContain('⚠️');
      expect(prompt).toContain('preprocessing aid');
      expect(prompt).toContain('Output language: English');
    }
  });

  it('each prompt contains workspace path', () => {
    const prompt = getScannerPrompt('structure', '/Users/x/projects/payment');
    expect(prompt).toContain('/Users/x/projects/payment');
  });

  it('throws for unknown scanner type', () => {
    expect(() => getScannerPrompt('unknown' as ScannerType, '/tmp')).toThrow();
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npx vitest run tests/server/capabilities/scanner-prompts.test.ts`
Expected: FAIL

- [ ] **Step 3: 实现 scanner-prompts.ts**

```typescript
// server/capabilities/scanner-prompts.ts
/**
 * Scanner Prompts — system prompts for the 3 parallel scanner agents.
 *
 * Each prompt instructs Claude to explore a specific aspect of the codebase
 * and write structured markdown files directly to .claude/knowledge/.
 */

export const SCANNER_TYPES = ['structure', 'apis', 'external-calls'] as const;
export type ScannerType = (typeof SCANNER_TYPES)[number];

const SHARED_RULES = `
## Safety Rules

1. **Never read sensitive files**: .env, .env.*, credentials.*, *.key, *.pem, *.p12, *.jks. Note their EXISTENCE only.
2. **File paths must be exact**: Always use backtick format: \`src/services/PaymentService.java\`
3. **Don't guess**: If uncertain, mark with ⚠️
4. **Write files directly**: Use the Write tool. Do NOT return file contents in your response.
5. **Your output is a preprocessing aid**: Claude will verify against actual code when working. Prioritize accuracy of file paths and signatures over completeness of descriptions.
6. **overview.md must be < 50 lines**: Table format, scannable at a glance.
7. **Detail files must be < 100 lines**: Key facts only.
8. **Output language: English**: Save tokens. File paths and code references stay in original form.
`;

const STRUCTURE_PROMPT = `
You are a project structure scanner. Analyze the codebase and write knowledge files.

## Output Directory
Write all files to: {KNOWLEDGE_DIR}/structure/

## Files to Write

1. **overview.md** (< 50 lines) — Must include:
   - Tech stack (languages, frameworks, build tools — from package.json/pom.xml/build.gradle/go.mod)
   - Directory tree (top 2-3 levels, exclude node_modules/.git/target)
   - Module list (table: name | path | purpose)
   - How to build and run (from Makefile/Dockerfile/README/scripts)

2. **modules/{name}.md** (< 100 lines each) — Per module/package:
   - Purpose (1-2 sentences)
   - Key files (list with paths)
   - Dependencies (imports from other modules)

## Exploration Strategy

\`\`\`bash
# Tech stack
cat package.json pom.xml build.gradle go.mod Cargo.toml pyproject.toml 2>/dev/null | head -100

# Directory structure
find . -type d -not -path '*/node_modules/*' -not -path '*/.git/*' -not -path '*/target/*' -not -path '*/dist/*' | head -50

# Entry points
ls src/index.* src/main.* app.py main.go cmd/ 2>/dev/null

# Build/run
cat Makefile Dockerfile README.md 2>/dev/null | head -50
\`\`\`
`;

const APIS_PROMPT = `
You are an API endpoint scanner. Analyze the codebase and write knowledge files.

## Output Directory
Write all files to: {KNOWLEDGE_DIR}/apis/

## File Naming
API path /api/payment/create with method POST → file: endpoints/POST-api-payment-create.md
Rule: Replace / with -, remove leading -, max 80 chars.

## Files to Write

1. **overview.md** (< 50 lines) — Must include:
   - Endpoint table: | Method | Path | Description | Detail File |
   - One row per endpoint
   - Group by controller/module if possible

2. **endpoints/{METHOD}-{slug}.md** (< 100 lines each) — Per endpoint:
   - Signature (method + path + parameters)
   - Request parameters (from annotations/types)
   - Business flow summary (1-3 sentences: what it does)
   - Called services (internal service calls within this endpoint)
   - Source file path

## Exploration Strategy

\`\`\`bash
# REST controllers/routes
grep -rn "@RestController\\|@Controller\\|@RequestMapping\\|@GetMapping\\|@PostMapping\\|@PutMapping\\|@DeleteMapping\\|router\\.\\(get\\|post\\|put\\|delete\\)\\|app\\.\\(get\\|post\\|put\\|delete\\)" --include="*.java" --include="*.ts" --include="*.js" --include="*.py" --include="*.go" | head -100

# GraphQL
grep -rn "type Query\\|type Mutation\\|@Query\\|@Mutation" --include="*.graphql" --include="*.java" --include="*.ts" | head -50

# gRPC
find . -name "*.proto" | head -20
\`\`\`
`;

const EXTERNAL_CALLS_PROMPT = `
You are an external call scanner. Analyze the codebase and write knowledge files.

## Output Directory
Write all files to: {KNOWLEDGE_DIR}/external-calls/

## Files to Write

1. **overview.md** (< 50 lines) — Must include:
   - External service table: | Service | Type (HTTP/DB/MQ) | Purpose | Detail File |
   - One row per external dependency

2. **services/{name}.md** (< 100 lines each) — Per external service:
   - Call method (HTTP client, ORM, SDK, message queue client)
   - Config source (env var name, config file path — NOT actual values)
   - Call locations in code (file paths that call this service)
   - Purpose (1-2 sentences)

## Exploration Strategy

\`\`\`bash
# HTTP client calls
grep -rn "RestTemplate\\|WebClient\\|FeignClient\\|fetch(\\|axios\\|http\\.\\(get\\|post\\)\\|requests\\.\\(get\\|post\\)" --include="*.java" --include="*.ts" --include="*.js" --include="*.py" --include="*.go" | head -100

# Database connections
grep -rn "DataSource\\|@Repository\\|@Entity\\|SqlConnection\\|mongoose\\|prisma\\|sqlalchemy" --include="*.java" --include="*.ts" --include="*.py" | head -50

# Message queues
grep -rn "@RabbitListener\\|@KafkaListener\\|RabbitTemplate\\|KafkaTemplate\\|amqp\\|kafka" --include="*.java" --include="*.ts" --include="*.py" | head -50
\`\`\`
`;

const PROMPTS: Record<ScannerType, string> = {
  structure: STRUCTURE_PROMPT,
  apis: APIS_PROMPT,
  'external-calls': EXTERNAL_CALLS_PROMPT,
};

export function getScannerPrompt(type: ScannerType, workspace: string): string {
  const template = PROMPTS[type];
  if (!template) throw new Error(`Unknown scanner type: ${type}`);

  const knowledgeDir = `${workspace}/.claude/knowledge`;

  return template
    .replace(/\{KNOWLEDGE_DIR\}/g, knowledgeDir)
    + '\n\n'
    + SHARED_RULES
    + `\n\nWorkspace: ${workspace}`;
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `npx vitest run tests/server/capabilities/scanner-prompts.test.ts`
Expected: PASS (5 tests)

- [ ] **Step 5: 提交**

```bash
git add server/capabilities/scanner-prompts.ts tests/server/capabilities/scanner-prompts.test.ts
git commit -m "feat(scanner): add scanner prompts for structure, APIs, external calls"
```

---

## Chunk 3: Project Scanner Orchestrator

核心编排器：manifest 检测、锁文件、创建 scanner session、写 manifest。

### Task 3: project-scanner 工具函数测试与实现

**Files:**
- Create: `server/capabilities/project-scanner.ts`
- Create: `tests/server/capabilities/project-scanner.test.ts`

- [ ] **Step 1: 写测试 — 工具函数（sanitizeEndpointSlug、getGitInfo、lock file 操作）**

```typescript
// tests/server/capabilities/project-scanner.test.ts
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {
  sanitizeEndpointSlug,
  getGitInfo,
  isScanNeeded,
  acquireLock,
  releaseLock,
  isLockStale,
  type ScanManifest,
} from '../../../server/capabilities/project-scanner.js';

describe('sanitizeEndpointSlug', () => {
  it('converts API path to filename', () => {
    expect(sanitizeEndpointSlug('/api/payment/create')).toBe('api-payment-create');
  });

  it('removes leading dashes', () => {
    expect(sanitizeEndpointSlug('/api/users')).toBe('api-users');
  });

  it('truncates to 80 chars', () => {
    const long = '/api/' + 'a'.repeat(100);
    expect(sanitizeEndpointSlug(long).length).toBeLessThanOrEqual(80);
  });
});

describe('getGitInfo', () => {
  it('returns nulls for non-git directory', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-git-'));
    try {
      const info = getGitInfo(tmpDir);
      expect(info.commitHash).toBeNull();
      expect(info.gitUrl).toBeNull();
      expect(info.branch).toBeNull();
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });
});

describe('isScanNeeded', () => {
  let workspace: string;

  beforeEach(() => {
    workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-scan-'));
  });

  afterEach(() => {
    fs.rmSync(workspace, { recursive: true, force: true });
  });

  it('returns true when no manifest exists', () => {
    expect(isScanNeeded(workspace)).toBe(true);
  });

  it('returns false when manifest exists', () => {
    const knowledgeDir = path.join(workspace, '.claude', 'knowledge');
    fs.mkdirSync(knowledgeDir, { recursive: true });
    fs.writeFileSync(
      path.join(knowledgeDir, 'manifest.json'),
      JSON.stringify({ version: '1.0', scannedAt: new Date().toISOString(), scanners: {} }),
    );
    expect(isScanNeeded(workspace)).toBe(false);
  });

  it('returns false when lock file exists (scan in progress)', () => {
    const knowledgeDir = path.join(workspace, '.claude', 'knowledge');
    fs.mkdirSync(knowledgeDir, { recursive: true });
    fs.writeFileSync(
      path.join(knowledgeDir, '.scanning'),
      JSON.stringify({ pid: 99999, startedAt: new Date().toISOString() }),
    );
    expect(isScanNeeded(workspace)).toBe(false);
  });
});

describe('lock file operations', () => {
  let workspace: string;
  let knowledgeDir: string;

  beforeEach(() => {
    workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-lock-'));
    knowledgeDir = path.join(workspace, '.claude', 'knowledge');
    fs.mkdirSync(knowledgeDir, { recursive: true });
  });

  afterEach(() => {
    fs.rmSync(workspace, { recursive: true, force: true });
  });

  it('acquireLock writes .scanning file', () => {
    acquireLock(workspace);
    const lockPath = path.join(knowledgeDir, '.scanning');
    expect(fs.existsSync(lockPath)).toBe(true);
    const lock = JSON.parse(fs.readFileSync(lockPath, 'utf-8'));
    expect(lock.pid).toBe(process.pid);
    expect(lock.startedAt).toBeDefined();
  });

  it('releaseLock deletes .scanning file', () => {
    acquireLock(workspace);
    releaseLock(workspace);
    expect(fs.existsSync(path.join(knowledgeDir, '.scanning'))).toBe(false);
  });

  it('isLockStale returns false for recent lock', () => {
    acquireLock(workspace);
    expect(isLockStale(workspace)).toBe(false);
  });

  it('isLockStale returns true for lock older than 30 min', () => {
    const lockPath = path.join(knowledgeDir, '.scanning');
    const oldDate = new Date(Date.now() - 31 * 60 * 1000).toISOString();
    fs.writeFileSync(lockPath, JSON.stringify({ pid: 99999, startedAt: oldDate }));
    expect(isLockStale(workspace)).toBe(true);
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npx vitest run tests/server/capabilities/project-scanner.test.ts`
Expected: FAIL

- [ ] **Step 3: 实现 project-scanner.ts（工具函数部分）**

```typescript
// server/capabilities/project-scanner.ts
/**
 * Project Scanner — orchestrates parallel scanner agents to generate
 * lightweight knowledge files in {workspace}/.claude/knowledge/.
 */
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { getScannerPrompt, SCANNER_TYPES, type ScannerType } from './scanner-prompts.js';

// ── Types ──

export interface ScanManifest {
  version: string;
  gitUrl: string | null;
  commitHash: string | null;
  branch: string | null;
  scannedAt: string;
  scanners: Record<string, ScannerStatus>;
}

export interface ScannerStatus {
  status: 'done' | 'error' | 'pending';
  filesWritten?: number;
  itemsCount?: number;
  error?: string;
}

interface ScannedWorkspaces {
  version: string;
  workspaces: Array<{ path: string; lastScannedAt: string }>;
}

// ── Utility Functions ──

export function sanitizeEndpointSlug(apiPath: string): string {
  return apiPath
    .replace(/^\//, '')
    .replace(/\//g, '-')
    .substring(0, 80);
}

export function getGitInfo(workspace: string): {
  commitHash: string | null;
  gitUrl: string | null;
  branch: string | null;
} {
  try {
    const commitHash = execSync('git rev-parse HEAD', { cwd: workspace, encoding: 'utf-8' }).trim();
    let gitUrl: string | null = null;
    try {
      gitUrl = execSync('git remote get-url origin', { cwd: workspace, encoding: 'utf-8' }).trim();
      // Strip embedded credentials
      gitUrl = gitUrl.replace(/:\/\/[^@]+@/, '://');
    } catch { /* no remote */ }
    let branch: string | null = null;
    try {
      branch = execSync('git rev-parse --abbrev-ref HEAD', { cwd: workspace, encoding: 'utf-8' }).trim();
    } catch { /* detached head or no git */ }
    return { commitHash, gitUrl, branch };
  } catch {
    return { commitHash: null, gitUrl: null, branch: null };
  }
}

export function isScanNeeded(workspace: string): boolean {
  const knowledgeDir = path.join(workspace, '.claude', 'knowledge');
  const manifestPath = path.join(knowledgeDir, 'manifest.json');
  const lockPath = path.join(knowledgeDir, '.scanning');

  // Already scanned
  if (fs.existsSync(manifestPath)) return false;

  // Scan in progress
  if (fs.existsSync(lockPath)) {
    if (isLockStale(workspace)) {
      // Clean up stale lock
      fs.unlinkSync(lockPath);
      return true;
    }
    return false;
  }

  return true;
}

const LOCK_STALE_MS = 30 * 60 * 1000; // 30 minutes

export function isLockStale(workspace: string): boolean {
  const lockPath = path.join(workspace, '.claude', 'knowledge', '.scanning');
  if (!fs.existsSync(lockPath)) return false;

  try {
    const lock = JSON.parse(fs.readFileSync(lockPath, 'utf-8'));
    const startedAt = new Date(lock.startedAt).getTime();
    return Date.now() - startedAt > LOCK_STALE_MS;
  } catch {
    return true; // Corrupted lock = stale
  }
}

export function acquireLock(workspace: string): void {
  const knowledgeDir = path.join(workspace, '.claude', 'knowledge');
  fs.mkdirSync(knowledgeDir, { recursive: true });
  const lockPath = path.join(knowledgeDir, '.scanning');
  fs.writeFileSync(lockPath, JSON.stringify({
    pid: process.pid,
    startedAt: new Date().toISOString(),
    scanners: [...SCANNER_TYPES],
  }, null, 2));
}

export function releaseLock(workspace: string): void {
  const lockPath = path.join(workspace, '.claude', 'knowledge', '.scanning');
  if (fs.existsSync(lockPath)) {
    fs.unlinkSync(lockPath);
  }
}

export function writeManifest(workspace: string, manifest: ScanManifest): void {
  const knowledgeDir = path.join(workspace, '.claude', 'knowledge');
  fs.mkdirSync(knowledgeDir, { recursive: true });
  fs.writeFileSync(
    path.join(knowledgeDir, 'manifest.json'),
    JSON.stringify(manifest, null, 2),
  );
}

// ── Scanned Workspaces Registry ──

export function getScannedWorkspacesPath(homeDir: string): string {
  return path.join(homeDir, 'scanned-workspaces.json');
}

export function registerScannedWorkspace(homeDir: string, workspace: string): void {
  const registryPath = getScannedWorkspacesPath(homeDir);
  let registry: ScannedWorkspaces = { version: '1.0', workspaces: [] };

  if (fs.existsSync(registryPath)) {
    try {
      registry = JSON.parse(fs.readFileSync(registryPath, 'utf-8'));
    } catch { /* corrupted, start fresh */ }
  }

  const existing = registry.workspaces.findIndex(w => w.path === workspace);
  const entry = { path: workspace, lastScannedAt: new Date().toISOString() };

  if (existing >= 0) {
    registry.workspaces[existing] = entry;
  } else {
    registry.workspaces.push(entry);
  }

  fs.writeFileSync(registryPath, JSON.stringify(registry, null, 2));
}

export function listScannedWorkspaces(homeDir: string): ScannedWorkspaces {
  const registryPath = getScannedWorkspacesPath(homeDir);
  if (!fs.existsSync(registryPath)) return { version: '1.0', workspaces: [] };

  try {
    return JSON.parse(fs.readFileSync(registryPath, 'utf-8'));
  } catch {
    return { version: '1.0', workspaces: [] };
  }
}

// ── Orchestrator Class ──

export interface ProjectScannerOptions {
  homeDir: string;
  sessionManager: {
    createSessionWithId(workspace: string, sessionId: string, isCron?: boolean, isScanner?: boolean): string;
    sendMessageForCron(
      sessionId: string,
      content: string,
      abortController: AbortController,
      onActivity: () => void,
    ): Promise<void>;
    closeV2Session(sessionId: string): void;
  };
}

export class ProjectScanner {
  private log = { info: (...args: any[]) => console.log('[ProjectScanner]', ...args) };

  constructor(private options: ProjectScannerOptions) {}

  async scheduleScanIfNeeded(workspace: string): Promise<void> {
    if (!isScanNeeded(workspace)) return;

    this.log.info(`Scheduling scan for ${workspace}`);
    try {
      await this.scan(workspace);
    } catch (e: any) {
      this.log.info(`Scan failed for ${workspace}: ${e.message}`);
    }
  }

  async scan(workspace: string): Promise<void> {
    acquireLock(workspace);

    const gitInfo = getGitInfo(workspace);
    const scannerStatuses: Record<string, ScannerStatus> = {};
    const scannerPromises: Promise<void>[] = [];

    for (const type of SCANNER_TYPES) {
      const status: ScannerStatus = { status: 'pending' };
      scannerStatuses[type] = status;

      scannerPromises.push(
        this.runScanner(type, workspace)
          .then((count) => {
            scannerStatuses[type] = { status: 'done', filesWritten: count };
          })
          .catch((e: any) => {
            scannerStatuses[type] = { status: 'error', error: e.message };
          }),
      );
    }

    await Promise.all(scannerPromises);

    const manifest: ScanManifest = {
      version: '1.0',
      ...gitInfo,
      scannedAt: new Date().toISOString(),
      scanners: scannerStatuses,
    };

    writeManifest(workspace, manifest);
    releaseLock(workspace);
    registerScannedWorkspace(this.options.homeDir, workspace);

    const doneCount = Object.values(scannerStatuses).filter(s => s.status === 'done').length;
    this.log.info(`Scan complete for ${workspace}: ${doneCount}/3 scanners succeeded`);
  }

  private async runScanner(type: ScannerType, workspace: string): Promise<number> {
    const sessionId = `scanner-${type}-${Date.now()}`;
    const prompt = getScannerPrompt(type, workspace);

    this.options.sessionManager.createSessionWithId(workspace, sessionId, true, true);

    const abortController = new AbortController();
    let filesWritten = 0;

    try {
      await this.options.sessionManager.sendMessageForCron(
        sessionId,
        prompt,
        abortController,
        () => { /* activity callback */ },
      );

      // Count files written
      const outputDir = path.join(workspace, '.claude', 'knowledge', type);
      if (fs.existsSync(outputDir)) {
        filesWritten = fs.readdirSync(outputDir).filter(f => f.endsWith('.md')).length;
      }
    } finally {
      try {
        this.options.sessionManager.closeV2Session(sessionId);
      } catch { /* ignore cleanup errors */ }
    }

    return filesWritten;
  }

  async nightlyRefresh(): Promise<void> {
    const registry = listScannedWorkspaces(this.options.homeDir);
    this.log.info(`Nightly refresh: checking ${registry.workspaces.length} workspaces`);

    for (const entry of registry.workspaces) {
      if (!fs.existsSync(entry.path)) continue;

      const manifestPath = path.join(entry.path, '.claude', 'knowledge', 'manifest.json');
      if (!fs.existsSync(manifestPath)) continue;

      let needsRescan = false;

      try {
        const manifest: ScanManifest = JSON.parse(fs.readFileSync(manifestPath, 'utf-8'));

        if (manifest.commitHash) {
          // Git repo: check commit hash
          const currentHash = execSync('git rev-parse HEAD', {
            cwd: entry.path,
            encoding: 'utf-8',
          }).trim();
          needsRescan = currentHash !== manifest.commitHash;
        } else {
          // Non-git: check age
          const scannedAt = new Date(manifest.scannedAt).getTime();
          needsRescan = Date.now() - scannedAt > 7 * 24 * 60 * 60 * 1000; // 7 days
        }
      } catch {
        needsRescan = true;
      }

      if (needsRescan) {
        // Delete old knowledge to force full rescan
        const knowledgeDir = path.join(entry.path, '.claude', 'knowledge');
        if (fs.existsSync(knowledgeDir)) {
          fs.rmSync(knowledgeDir, { recursive: true, force: true });
        }
        await this.scan(entry.path);
      }
    }
  }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `npx vitest run tests/server/capabilities/project-scanner.test.ts`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/capabilities/project-scanner.ts tests/server/capabilities/project-scanner.test.ts
git commit -m "feat(scanner): add project scanner orchestrator with lock and manifest"
```

---

## Chunk 4: Integration — System Prompt Injection + Session Trigger

### Task 4: 注入知识到 system prompt

**Files:**
- Modify: `server/claude-session.ts:142-192`

- [ ] **Step 1: 在 buildSystemPromptAppend 末尾添加知识加载**

在 `server/claude-session.ts` 文件顶部添加 import:

```typescript
import { loadKnowledgeOverviews } from './capabilities/knowledge-loader.js';
```

在 `buildSystemPromptAppend` 方法末尾（`return` 之前）添加:

```typescript
// Append project knowledge if available
const knowledgeContent = loadKnowledgeOverviews(workspace);
if (knowledgeContent) {
  prompt += `\n\n## Project Knowledge\n\n${knowledgeContent}`;
}

return prompt;
```

注意: `buildSystemPromptAppend` 当前直接 return 模板字符串，需要改成先赋值给 `let prompt` 再追加知识内容后返回。

- [ ] **Step 2: 运行现有测试确认无回归**

Run: `npx vitest run tests/server/claude-session.test.ts`
Expected: PASS

- [ ] **Step 3: 提交**

```bash
git add server/claude-session.ts
git commit -m "feat(scanner): inject project knowledge into session system prompt"
```

### Task 5: session.create 触发扫描

**Files:**
- Modify: `server/index.ts`

- [ ] **Step 1: 添加 import 和初始化**

在 `server/index.ts` 文件顶部添加:

```typescript
import { ProjectScanner } from './capabilities/project-scanner.js';
```

在 capability registry 初始化后（~line 202）添加:

```typescript
// Initialize project scanner
const projectScanner = new ProjectScanner({
  homeDir,
  sessionManager,
});
```

- [ ] **Step 2: 在 session.create handler 中触发扫描**

修改 session.create case（~line 431-435）:

```typescript
case 'session.create': {
  if (!msg.workspace) throw new Error('Missing workspace');
  const sessionId = sessionManager.createSession(msg.workspace);
  ws.send(JSON.stringify({ type: 'session.created', sessionId, workspace: msg.workspace }));
  // Trigger knowledge scan if needed (fire-and-forget)
  projectScanner.scheduleScanIfNeeded(msg.workspace).catch(() => {});
  break;
}
```

- [ ] **Step 3: 编译检查**

Run: `npx tsc -p server/tsconfig.json --noEmit`
Expected: 无错误

- [ ] **Step 4: 提交**

```bash
git add server/index.ts
git commit -m "feat(scanner): trigger knowledge scan on first session create"
```

### Task 6: buildSessionOptions 支持 scanner session

**Files:**
- Modify: `server/claude-session.ts:194-264`

- [ ] **Step 1: 修改 ActiveSession 接口添加 isScanner 字段**

在 `server/claude-session.ts:32-38` 的 `ActiveSession` 接口中添加:

```typescript
export interface ActiveSession {
  id: string;
  workspace: string;
  label?: string;
  createdAt: string;
  lastActiveAt: string;
  isScanner?: boolean;  // Scanner sessions skip plugins/MCP
}
```

- [ ] **Step 2: 修改 createSessionWithId 签名，传入 isScanner**

```typescript
createSessionWithId(workspace: string, sessionId: string, isCron = true, isScanner = false): string
```

在 session entry 中存储 `isScanner`:

```typescript
const session: ActiveSession = {
  id: sessionId,
  workspace,
  createdAt: new Date().toISOString(),
  lastActiveAt: new Date().toISOString(),
  ...(isScanner ? { isScanner: true } : {}),
};
```

- [ ] **Step 3: 在 getOrCreateV2Session 中跳过 plugin/MCP for scanner sessions**

在 `getOrCreateV2Session` 方法中，`buildSessionOptions` 调用前检查:

```typescript
// Check if this is a scanner session
const sessionInfo = this.sessions.get(sessionId);
const isScanner = sessionInfo?.isScanner === true;

const opts = isScanner
  ? this.buildScannerSessionOptions(session.workspace)
  : this.buildSessionOptions(session.workspace);
```

添加 `buildScannerSessionOptions` 方法 — **必须匹配 `buildSessionOptions` 的实际结构**:

```typescript
private buildScannerSessionOptions(workspace: string): Record<string, any> {
  const env: Record<string, string | undefined> = { ...process.env as Record<string, string | undefined> };
  env['ANTHROPIC_API_KEY'] = this.config!.llm!.apiKey;
  if (this.config!.llm!.baseUrl) {
    env['ANTHROPIC_BASE_URL'] = this.config!.llm!.baseUrl;
  }

  const claudeCodePath = this.getClaudeCodePath();

  return {
    model: this.config!.llm!.model,
    env,
    pathToClaudeCodeExecutable: claudeCodePath,
    cwd: workspace,
    permissionMode: 'bypassPermissions',
    allowDangerouslySkipPermissions: true,
    includePartialMessages: true,
    systemPrompt: {
      type: 'preset' as const,
      preset: 'claude_code',
    },
    settingSources: ['project'],
    extraArgs: {
      'dangerously-skip-permissions': null,
    },
    // No plugins, no MCP servers, no web-access
  };
}
```

> **Why this structure:** SDK session options require `pathToClaudeCodeExecutable` (not `apiKey` as top-level), `permissionMode: 'bypassPermissions'` (scanner needs to write files without interactive permission prompts), and `extraArgs` for skip-permissions flag. These match the existing `buildSessionOptions` structure at line 220-238.

- [ ] **Step 4: 编译检查**

Run: `npx tsc -p server/tsconfig.json --noEmit`
Expected: 无错误

- [ ] **Step 5: 运行现有测试确认无回归**

Run: `npx vitest run tests/server/`
Expected: 所有现有测试通过

- [ ] **Step 6: 提交**

```bash
git add server/claude-session.ts
git commit -m "feat(scanner): add minimal scanner session mode (no plugins/MCP)"
```

---

## Chunk 5: Integration — Nightly Cron Refresh

### Task 7: 注册夜间知识刷新 cron 任务

**Files:**
- Modify: `server/cron-scheduler.ts`
- Modify: `server/index.ts`

- [ ] **Step 1: 在 CronScheduler 中添加 projectScanner 引用**

在 `server/cron-scheduler.ts` 中:

```typescript
import type { ProjectScanner } from './capabilities/project-scanner.js';
```

添加 setter:

```typescript
private projectScanner: ProjectScanner | null = null;

setProjectScanner(scanner: ProjectScanner): void {
  this.projectScanner = scanner;
}
```

- [ ] **Step 2: 在 start() 方法中注册内置夜间刷新任务**

在 `start()` 方法末尾添加:

```typescript
// Register built-in nightly knowledge refresh (3 AM daily)
if (this.projectScanner) {
  const nightlyJob = cron.schedule('0 3 * * *', () => {
    this.projectScanner!.nightlyRefresh().catch((e: any) => {
      this.log.error(`Nightly knowledge refresh failed: ${e.message}`);
    });
  });
  this.jobs.set('__nightly_knowledge_refresh__', nightlyJob);
}
```

- [ ] **Step 3: 在 server/index.ts 中注入 projectScanner 到 cronScheduler**

在 `cronScheduler.start()` 之前添加:

```typescript
cronScheduler.setProjectScanner(projectScanner);
```

- [ ] **Step 4: 编译检查**

Run: `npx tsc -p server/tsconfig.json --noEmit`
Expected: 无错误

- [ ] **Step 5: 提交**

```bash
git add server/cron-scheduler.ts server/index.ts
git commit -m "feat(scanner): register nightly knowledge refresh cron job at 3 AM"
```

---

## Chunk 6: Final Verification

### Task 8: 完整测试 + 编译验证

- [ ] **Step 1: 运行所有 capability 相关测试**

Run: `npx vitest run tests/server/capabilities/`
Expected: ALL PASS

- [ ] **Step 2: 运行完整编译**

Run: `npx tsc -p server/tsconfig.json --noEmit`
Expected: 无错误

- [ ] **Step 3: 运行全部后端测试**

Run: `npx vitest run tests/server/`
Expected: 所有测试通过（排除已知的无关失败）

- [ ] **Step 4: 最终提交**

```bash
git add -A
git commit -m "feat(scanner): complete project knowledge auto-scanner system"
```
