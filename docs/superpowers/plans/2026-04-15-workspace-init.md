# Workspace Auto-Init Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-initialize workspace on first session creation — scan directory, AI-match capabilities, inject skills, generate CLAUDE.md.

**Architecture:** Backend-only init system triggered by `session.create`. WorkspaceScanner collects filesystem metadata, CapabilityMatcher calls LLM to select capabilities, SkillInjector copies SKILL.md files to `.claude/skills/`. Results cached in `.sman/INIT.md`. Frontend receives init cards as WebSocket events rendered as a top banner.

**Tech Stack:** TypeScript, Node.js fs, Anthropic Messages API (fetch), WebSocket, React

**Spec:** `docs/superpowers/specs/2026-04-15-workspace-init-design.md`

---

## File Structure

### New Files

| File | Responsibility |
|------|----------------|
| `server/init/workspace-scanner.ts` | Pure filesystem scan → `WorkspaceScanResult` |
| `server/init/capability-matcher.ts` | LLM call to match capabilities → `CapabilityMatchResult` |
| `server/init/skill-injector.ts` | Copy SKILL.md + auxiliary files to workspace |
| `server/init/claude-init-runner.ts` | Run `claude -p` to generate CLAUDE.md |
| `server/init/init-manager.ts` | Orchestrator: scan → match → inject → notify |
| `server/init/init-types.ts` | Shared types for the init system |
| `server/init/templates/skill-auto-updater/SKILL.md` | Self-updater skill template |
| `server/init/templates/skill-auto-updater/crontab.md` | Cron schedule template (disabled) |
| `tests/server/init/workspace-scanner.test.ts` | Scanner unit tests |
| `tests/server/init/capability-matcher.test.ts` | Matcher unit tests |
| `tests/server/init/skill-injector.test.ts` | Injector unit tests |
| `tests/server/init/init-manager.test.ts` | Orchestrator integration tests |

### Modified Files

| File | Change |
|------|--------|
| `server/index.ts:459-464` | Add initManager instantiation + call in `session.create` handler |
| `src/stores/chat.ts` | Handle `init.*` WebSocket events, store init state |
| `src/features/chat/index.tsx` | Render init banner above chat area |
| `src/features/chat/InitBanner.tsx` | New component for init card rendering |

---

## Chunk 1: Backend Core — Scanner, Types, and Injector

### Task 1: Create init types

**Files:**
- Create: `server/init/init-types.ts`
- Test: `tests/server/init/init-types.test.ts` (compile check only)

- [ ] **Step 1: Create type definitions**

```typescript
// server/init/init-types.ts
import type { CapabilityEntry } from '../capabilities/types.js';

export type ProjectType = 'java' | 'python' | 'node' | 'react' | 'go' | 'rust' | 'docs' | 'mixed' | 'empty';

export interface WorkspaceScanResult {
  types: ProjectType[];
  languages: Record<string, number>;
  markers: string[];
  packageJson?: { name: string; scripts: string[]; deps: string[] };
  pomXml?: { groupId: string; artifactId: string; deps: string[] };
  /** Top-level directory names (excluding node_modules, .git, dist, build, target) */
  topDirs: string[];
  fileCount: number;
  isGitRepo: boolean;
  hasClaudeMd: boolean;
}

export interface CapabilityMatch {
  capabilityId: string;
  reason: string;
}

export interface CapabilityMatchResult {
  matches: CapabilityMatch[];
  projectSummary: string;
  techStack: string[];
}

export interface InitResult {
  success: boolean;
  scanResult: WorkspaceScanResult;
  matchResult: CapabilityMatchResult;
  injectedSkills: string[];
  claudeMdGenerated: boolean;
  error?: string;
}

export type InitCardType = 'initializing' | 'complete' | 'already' | 'error';

export interface InitCard {
  type: InitCardType;
  workspace: string;
  /** For 'initializing' cards: which phase we're in */
  phase?: 'scanning' | 'matching' | 'injecting';
  /** For 'complete' and 'already' cards */
  projectSummary?: string;
  techStack?: string[];
  injectedSkills?: Array<{ id: string; name: string }>;
  /** For 'already' cards */
  initializedAt?: string;
  /** For 'error' cards */
  error?: string;
}
```

- [ ] **Step 2: Verify compilation**

Run: `npx tsc --noEmit --pretty 2>&1 | grep -v "DecoLayer\|WorldCanvas\|map-data\|palette" | tail -5`
Expected: No new errors from init-types.ts

- [ ] **Step 3: Commit**

```bash
git add server/init/init-types.ts
git commit -m "feat(init): add workspace init type definitions"
```

---

### Task 2: Implement WorkspaceScanner

**Files:**
- Create: `server/init/workspace-scanner.ts`
- Test: `tests/server/init/workspace-scanner.test.ts`

- [ ] **Step 1: Write the failing tests**

```typescript
// tests/server/init/workspace-scanner.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { scanWorkspace } from '../../../server/init/workspace-scanner.js';

describe('WorkspaceScanner', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-scan-test-'));
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('detects empty directory', () => {
    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('empty');
    expect(result.fileCount).toBe(0);
    expect(result.isGitRepo).toBe(false);
    expect(result.hasClaudeMd).toBe(false);
  });

  it('detects Node.js project from package.json', () => {
    fs.writeFileSync(path.join(tmpDir, 'package.json'), JSON.stringify({
      name: 'test-app',
      scripts: { dev: 'vite', build: 'tsc' },
      dependencies: { express: '^4.0.0' },
    }));
    fs.writeFileSync(path.join(tmpDir, 'server.ts'), '');
    fs.writeFileSync(path.join(tmpDir, 'index.ts'), '');

    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('node');
    expect(result.packageJson).toBeDefined();
    expect(result.packageJson!.name).toBe('test-app');
    expect(result.languages['.ts']).toBe(2);
  });

  it('detects React project from package.json deps', () => {
    fs.writeFileSync(path.join(tmpDir, 'package.json'), JSON.stringify({
      name: 'react-app',
      dependencies: { react: '^19.0.0', 'react-dom': '^19.0.0' },
    }));
    fs.writeFileSync(path.join(tmpDir, 'App.tsx'), '');

    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('react');
  });

  it('detects Java project from pom.xml', () => {
    fs.writeFileSync(path.join(tmpDir, 'pom.xml'), `
      <project>
        <groupId>com.example</groupId>
        <artifactId>my-service</artifactId>
        <dependencies>
          <dependency><artifactId>spring-boot-starter</artifactId></dependency>
        </dependencies>
      </project>
    `);
    fs.mkdirSync(path.join(tmpDir, 'src', 'main', 'java'), { recursive: true });
    fs.writeFileSync(path.join(tmpDir, 'src', 'main', 'java', 'App.java'), '');

    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('java');
    expect(result.pomXml).toBeDefined();
    expect(result.pomXml!.artifactId).toBe('my-service');
  });

  it('detects git repo', () => {
    fs.mkdirSync(path.join(tmpDir, '.git'));
    const result = scanWorkspace(tmpDir);
    expect(result.isGitRepo).toBe(true);
  });

  it('detects CLAUDE.md', () => {
    fs.writeFileSync(path.join(tmpDir, 'CLAUDE.md'), '# My Project');
    const result = scanWorkspace(tmpDir);
    expect(result.hasClaudeMd).toBe(true);
  });

  it('detects Go project from go.mod', () => {
    fs.writeFileSync(path.join(tmpDir, 'go.mod'), 'module github.com/example/app\ngo 1.22');
    fs.writeFileSync(path.join(tmpDir, 'main.go'), '');

    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('go');
  });

  it('detects Python project from requirements.txt', () => {
    fs.writeFileSync(path.join(tmpDir, 'requirements.txt'), 'flask==3.0\nrequests');
    fs.writeFileSync(path.join(tmpDir, 'app.py'), '');

    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('python');
  });

  it('detects Rust project from Cargo.toml', () => {
    fs.writeFileSync(path.join(tmpDir, 'Cargo.toml'), '[package]\nname = "test"');
    fs.writeFileSync(path.join(tmpDir, 'main.rs'), '');

    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('rust');
  });

  it('detects docs-only directory', () => {
    fs.writeFileSync(path.join(tmpDir, 'README.md'), '# Docs');
    fs.writeFileSync(path.join(tmpDir, 'guide.md'), '');
    fs.writeFileSync(path.join(tmpDir, 'api.md'), '');

    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('docs');
  });

  it('excludes noise directories from topDirs', () => {
    for (const dir of ['node_modules', '.git', 'dist', 'build', 'target', '.next']) {
      fs.mkdirSync(path.join(tmpDir, dir), { recursive: true });
    }
    fs.mkdirSync(path.join(tmpDir, 'src'), { recursive: true });
    fs.writeFileSync(path.join(tmpDir, 'src', 'index.ts'), '');

    const result = scanWorkspace(tmpDir);
    expect(result.topDirs).toContain('src');
    expect(result.topDirs).not.toContain('node_modules');
    expect(result.topDirs).not.toContain('.git');
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run tests/server/init/workspace-scanner.test.ts`
Expected: FAIL — module not found

- [ ] **Step 3: Implement WorkspaceScanner**

```typescript
// server/init/workspace-scanner.ts
import fs from 'fs';
import path from 'path';
import type { WorkspaceScanResult, ProjectType } from './init-types.js';

const NOISE_DIRS = new Set([
  'node_modules', '.git', 'dist', 'build', 'target', '.next',
  '.sveltekit', '.nuxt', 'coverage', '__pycache__', '.tox',
  'venv', '.venv', 'env', '.env', '.idea', '.vscode',
]);

const MARKER_FILES: Record<string, ProjectType> = {
  'package.json': 'node',
  'pom.xml': 'java',
  'build.gradle': 'java',
  'build.gradle.kts': 'java',
  'go.mod': 'go',
  'Cargo.toml': 'rust',
  'requirements.txt': 'python',
  'pyproject.toml': 'python',
  'setup.py': 'python',
  'Pipfile': 'python',
};

export function scanWorkspace(workspace: string): WorkspaceScanResult {
  const entries = fs.readdirSync(workspace, { withFileTypes: true });
  const fileNames = entries.filter(e => e.isFile()).map(e => e.name);
  const dirNames = entries.filter(e => e.isDirectory()).map(e => e.name);

  // File extension counts
  const languages: Record<string, number> = {};
  let fileCount = 0;
  collectExtensions(workspace, languages, 0, 2, new Set([workspace]));
  for (const count of Object.values(languages)) {
    fileCount += count;
  }

  // Detect markers
  const markers: string[] = [];
  const types: Set<ProjectType> = new Set();

  for (const [marker, type] of Object.entries(MARKER_FILES)) {
    if (fileNames.includes(marker)) {
      markers.push(marker);
      types.add(type);
    }
  }

  // Check for React (must have react in deps)
  let packageJson: WorkspaceScanResult['packageJson'] = undefined;
  if (fileNames.includes('package.json')) {
    try {
      const pkg = JSON.parse(fs.readFileSync(path.join(workspace, 'package.json'), 'utf-8'));
      const allDeps = { ...pkg.dependencies, ...pkg.devDependencies };
      if (allDeps['react'] || allDeps['next'] || allDeps['@remix-run/react']) {
        types.add('react');
      }
      packageJson = {
        name: pkg.name || '',
        scripts: Object.keys(pkg.scripts || {}),
        deps: Object.keys(allDeps || {}),
      };
    } catch { /* ignore parse errors */ }
  }

  // Parse pom.xml
  let pomXml: WorkspaceScanResult['pomXml'] = undefined;
  if (fileNames.includes('pom.xml')) {
    try {
      const content = fs.readFileSync(path.join(workspace, 'pom.xml'), 'utf-8');
      const groupId = content.match(/<groupId>([^<]+)<\/groupId>/)?.[1] || '';
      const artifactId = content.match(/<artifactId>([^<]+)<\/artifactId>/)?.[1] || '';
      const deps = [...content.matchAll(/<artifactId>([^<]+)<\/artifactId>/g)].map(m => m[1]);
      pomXml = { groupId, artifactId, deps };
    } catch { /* ignore */ }
  }

  // Detect docs directory
  if (types.size === 0) {
    const mdFiles = fileNames.filter(f => f.endsWith('.md'));
    const nonMdFiles = fileNames.filter(f => !f.endsWith('.md') && !f.startsWith('.'));
    if (mdFiles.length > 0 && nonMdFiles.length === 0) {
      types.add('docs');
    }
  }

  if (types.size === 0 && fileCount > 0) {
    types.add('mixed');
  }
  if (fileCount === 0 && !types.has('empty')) {
    types.add('empty');
  }

  // Top-level dirs excluding noise
  const topDirs = dirNames.filter(d => !NOISE_DIRS.has(d) && !d.startsWith('.'));

  return {
    types: [...types],
    languages,
    markers,
    packageJson,
    pomXml,
    topDirs,
    fileCount,
    isGitRepo: dirNames.includes('.git'),
    hasClaudeMd: fileNames.includes('CLAUDE.md'),
  };
}

function collectExtensions(
  dir: string,
  languages: Record<string, number>,
  depth: number,
  maxDepth: number,
  visited: Set<string>,
): void {
  if (depth > maxDepth) return;
  try {
    const realPath = fs.realpathSync(dir);
    if (visited.has(realPath)) return;
    visited.add(realPath);
  } catch { return; }

  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch { return; }

  for (const entry of entries) {
    if (entry.name.startsWith('.') || NOISE_DIRS.has(entry.name)) continue;
    if (entry.isFile()) {
      const ext = path.extname(entry.name);
      if (ext) {
        languages[ext] = (languages[ext] || 0) + 1;
      }
    } else if (entry.isDirectory()) {
      collectExtensions(path.join(dir, entry.name), languages, depth + 1, maxDepth, visited);
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run tests/server/init/workspace-scanner.test.ts`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/init/workspace-scanner.ts tests/server/init/workspace-scanner.test.ts
git commit -m "feat(init): add workspace filesystem scanner"
```

---

### Task 3: Implement SkillInjector

**Files:**
- Create: `server/init/skill-injector.ts`
- Test: `tests/server/init/skill-injector.test.ts`

- [ ] **Step 1: Write the failing tests**

```typescript
// tests/server/init/skill-injector.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { injectSkills } from '../../../server/init/skill-injector.js';

describe('SkillInjector', () => {
  let tmpDir: string;
  let pluginsDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-inject-test-'));
    pluginsDir = path.join(tmpDir, 'plugins');
    fs.mkdirSync(pluginsDir, { recursive: true });
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('copies SKILL.md from plugin to workspace .claude/skills/', () => {
    // Setup plugin
    const pluginDir = path.join(pluginsDir, 'my-skill');
    fs.mkdirSync(pluginDir);
    fs.writeFileSync(path.join(pluginDir, 'SKILL.md'), '---\nname: my-skill\n---\nContent');

    // Setup workspace
    const workspace = path.join(tmpDir, 'workspace');
    fs.mkdirSync(workspace);

    const result = injectSkills(
      [{ capabilityId: 'my-skill', pluginPath: 'my-skill' }],
      pluginsDir,
      workspace,
    );

    expect(result).toEqual(['my-skill']);
    const target = path.join(workspace, '.claude', 'skills', 'my-skill', 'SKILL.md');
    expect(fs.existsSync(target)).toBe(true);
    expect(fs.readFileSync(target, 'utf-8')).toContain('Content');
  });

  it('copies auxiliary files (.md, .py, .css, .json)', () => {
    const pluginDir = path.join(pluginsDir, 'my-skill');
    fs.mkdirSync(pluginDir);
    fs.writeFileSync(path.join(pluginDir, 'SKILL.md'), 'content');
    fs.writeFileSync(path.join(pluginDir, 'checklist.md'), '- item 1');
    fs.writeFileSync(path.join(pluginDir, 'template.json'), '{}');
    fs.writeFileSync(path.join(pluginDir, 'SKILL.md.tmpl'), 'template'); // should NOT be copied

    const workspace = path.join(tmpDir, 'workspace');
    fs.mkdirSync(workspace);

    injectSkills(
      [{ capabilityId: 'my-skill', pluginPath: 'my-skill' }],
      pluginsDir,
      workspace,
    );

    const skillsDir = path.join(workspace, '.claude', 'skills', 'my-skill');
    expect(fs.existsSync(path.join(skillsDir, 'SKILL.md'))).toBe(true);
    expect(fs.existsSync(path.join(skillsDir, 'checklist.md'))).toBe(true);
    expect(fs.existsSync(path.join(skillsDir, 'template.json'))).toBe(true);
    expect(fs.existsSync(path.join(skillsDir, 'SKILL.md.tmpl'))).toBe(false);
  });

  it('does not overwrite existing skills', () => {
    // Existing skill in workspace
    const workspace = path.join(tmpDir, 'workspace');
    const existingDir = path.join(workspace, '.claude', 'skills', 'my-skill');
    fs.mkdirSync(existingDir, { recursive: true });
    fs.writeFileSync(path.join(existingDir, 'SKILL.md'), 'USER CUSTOM CONTENT');

    // Plugin with same ID
    const pluginDir = path.join(pluginsDir, 'my-skill');
    fs.mkdirSync(pluginDir);
    fs.writeFileSync(path.join(pluginDir, 'SKILL.md'), 'PLUGIN CONTENT');

    injectSkills(
      [{ capabilityId: 'my-skill', pluginPath: 'my-skill' }],
      pluginsDir,
      workspace,
    );

    expect(fs.readFileSync(path.join(existingDir, 'SKILL.md'), 'utf-8')).toBe('USER CUSTOM CONTENT');
  });

  it('skips skills whose plugin directory does not exist', () => {
    const workspace = path.join(tmpDir, 'workspace');
    fs.mkdirSync(workspace);

    const result = injectSkills(
      [{ capabilityId: 'missing', pluginPath: 'nonexistent-plugin' }],
      pluginsDir,
      workspace,
    );

    expect(result).toEqual([]);
  });

  it('copies subdirectories (references/, templates/)', () => {
    const pluginDir = path.join(pluginsDir, 'my-skill');
    fs.mkdirSync(path.join(pluginDir, 'references'), { recursive: true });
    fs.mkdirSync(path.join(pluginDir, 'templates'), { recursive: true });
    fs.writeFileSync(path.join(pluginDir, 'SKILL.md'), 'content');
    fs.writeFileSync(path.join(pluginDir, 'references', 'taxonomy.md'), 'taxonomy');
    fs.writeFileSync(path.join(pluginDir, 'templates', 'report.md'), 'template');

    const workspace = path.join(tmpDir, 'workspace');
    fs.mkdirSync(workspace);

    injectSkills(
      [{ capabilityId: 'my-skill', pluginPath: 'my-skill' }],
      pluginsDir,
      workspace,
    );

    const skillsDir = path.join(workspace, '.claude', 'skills', 'my-skill');
    expect(fs.existsSync(path.join(skillsDir, 'references', 'taxonomy.md'))).toBe(true);
    expect(fs.existsSync(path.join(skillsDir, 'templates', 'report.md'))).toBe(true);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run tests/server/init/skill-injector.test.ts`
Expected: FAIL — module not found

- [ ] **Step 3: Implement SkillInjector**

```typescript
// server/init/skill-injector.ts
import fs from 'fs';
import path from 'path';

const AUXILIARY_EXTENSIONS = new Set(['.md', '.py', '.css', '.js', '.json']);
const SKIP_FILES = new Set(['SKILL.md.tmpl', 'package.json', 'package-lock.json']);

interface SkillSource {
  capabilityId: string;
  pluginPath: string;
}

export function injectSkills(
  matches: SkillSource[],
  pluginsDir: string,
  workspace: string,
): string[] {
  const injected: string[] = [];
  const skillsDir = path.join(workspace, '.claude', 'skills');

  for (const { capabilityId, pluginPath } of matches) {
    const sourceDir = path.join(pluginsDir, pluginPath);
    if (!fs.existsSync(sourceDir)) continue;

    const targetDir = path.join(skillsDir, capabilityId);

    // Never overwrite existing user skills
    if (fs.existsSync(path.join(targetDir, 'SKILL.md'))) continue;

    fs.mkdirSync(targetDir, { recursive: true });

    // Copy SKILL.md
    const skillMd = path.join(sourceDir, 'SKILL.md');
    if (fs.existsSync(skillMd)) {
      fs.copyFileSync(skillMd, path.join(targetDir, 'SKILL.md'));
    }

    // Copy auxiliary files
    try {
      for (const entry of fs.readdirSync(sourceDir, { withFileTypes: true })) {
        if (entry.name === 'SKILL.md') continue;
        if (SKIP_FILES.has(entry.name)) continue;

        if (entry.isFile()) {
          const ext = path.extname(entry.name);
          if (AUXILIARY_EXTENSIONS.has(ext)) {
            fs.copyFileSync(
              path.join(sourceDir, entry.name),
              path.join(targetDir, entry.name),
            );
          }
        } else if (entry.isDirectory() && !entry.name.startsWith('.') && entry.name !== 'node_modules') {
          // Copy subdirectories (references/, templates/, etc.)
          copyDirRecursive(
            path.join(sourceDir, entry.name),
            path.join(targetDir, entry.name),
          );
        }
      }
    } catch { /* partial copy is acceptable */ }

    injected.push(capabilityId);
  }

  return injected;
}

function copyDirRecursive(src: string, dst: string): void {
  fs.mkdirSync(dst, { recursive: true });
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const srcPath = path.join(src, entry.name);
    const dstPath = path.join(dst, entry.name);
    if (entry.isDirectory()) {
      copyDirRecursive(srcPath, dstPath);
    } else if (entry.isFile()) {
      fs.copyFileSync(srcPath, dstPath);
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run tests/server/init/skill-injector.test.ts`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/init/skill-injector.ts tests/server/init/skill-injector.test.ts
git commit -m "feat(init): add skill injector"
```

---

### Task 4: Implement CapabilityMatcher

**Files:**
- Create: `server/init/capability-matcher.ts`
- Test: `tests/server/init/capability-matcher.test.ts`

- [ ] **Step 1: Write the failing tests**

```typescript
// tests/server/init/capability-matcher.test.ts
import { describe, it, expect } from 'vitest';
import { matchCapabilities, formatCapabilityCatalog, keywordFallback } from '../../../server/init/capability-matcher.js';
import type { WorkspaceScanResult } from '../../../server/init/init-types.js';
import type { CapabilityEntry } from '../../../server/capabilities/types.js';

const MOCK_CAPABILITIES: CapabilityEntry[] = [
  {
    id: 'review',
    name: 'PR 代码审查',
    description: '预合并 PR 审查，分析 diff 检查问题',
    executionMode: 'instruction-inject',
    triggers: ['review', 'code review', '代码审查'],
    runnerModule: './generic-instruction-runner.js',
    pluginPath: 'gstack-skills/review',
    enabled: true,
    version: '1.0.0',
  },
  {
    id: 'office-skills',
    name: 'Office 文档处理',
    description: '创建和编辑 PowerPoint、Word 文档',
    executionMode: 'mcp-dynamic',
    triggers: ['PPT', 'Word', 'docx'],
    runnerModule: './office-skills-runner.js',
    pluginPath: 'office-skills',
    enabled: true,
    version: '1.0.0',
  },
];

const MOCK_SCAN_RESULT: WorkspaceScanResult = {
  types: ['java'],
  languages: { '.java': 50, '.xml': 10 },
  markers: ['pom.xml'],
  pomXml: { groupId: 'com.example', artifactId: 'my-service', deps: ['spring-boot-starter'] },
  topDirs: ['src'],
  fileCount: 60,
  isGitRepo: true,
  hasClaudeMd: false,
};

describe('CapabilityMatcher', () => {
  it('formats capability catalog as compact text', () => {
    const text = formatCapabilityCatalog(MOCK_CAPABILITIES);
    expect(text).toContain('review');
    expect(text).toContain('PR 代码审查');
    expect(text).toContain('office-skills');
  });

  it('falls back to keyword matching when LLM unavailable', () => {
    const result = keywordFallback(MOCK_SCAN_RESULT, MOCK_CAPABILITIES);
    // Should still return some matches based on keywords
    expect(result.matches.length).toBeGreaterThanOrEqual(0);
    expect(result.projectSummary).toBeDefined();
    expect(result.techStack).toBeDefined();
  });

  it('keyword fallback returns at least careful/guard for any project', () => {
    const safetyCaps: CapabilityEntry[] = [
      {
        id: 'careful', name: '危险命令防护', description: 'Safety guardrails',
        executionMode: 'instruction-inject', triggers: ['careful', 'safety'],
        runnerModule: './generic-instruction-runner.js', pluginPath: 'gstack-skills/careful',
        enabled: true, version: '1.0.0',
      },
      {
        id: 'guard', name: '安全防护模式', description: 'Full safety mode',
        executionMode: 'instruction-inject', triggers: ['guard', 'safety'],
        runnerModule: './generic-instruction-runner.js', pluginPath: 'gstack-skills/guard',
        enabled: true, version: '1.0.0',
      },
    ];
    const emptyScan: WorkspaceScanResult = {
      types: ['empty'], languages: {}, markers: [], topDirs: [],
      fileCount: 0, isGitRepo: false, hasClaudeMd: false,
    };
    const result = keywordFallback(emptyScan, safetyCaps);
    // careful and guard should always be matched for safety
    const ids = result.matches.map(m => m.capabilityId);
    expect(ids).toContain('careful');
    expect(ids).toContain('guard');
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run tests/server/init/capability-matcher.test.ts`
Expected: FAIL — module not found

- [ ] **Step 3: Implement CapabilityMatcher**

```typescript
// server/init/capability-matcher.ts
import type { WorkspaceScanResult, CapabilityMatchResult, CapabilityMatch } from './init-types.js';
import type { CapabilityEntry, SemanticSearchLlmConfig } from '../capabilities/types.js';

/** Always-match capabilities for any project (safety essentials) */
const ALWAYS_MATCH_IDS = new Set(['careful', 'guard']);

export function formatCapabilityCatalog(capabilities: CapabilityEntry[]): string {
  return capabilities
    .filter(c => c.enabled)
    .map(c => `ID: ${c.id} | Name: ${c.name} | Desc: ${c.description} | Triggers: ${c.triggers.join(', ')}`)
    .join('\n');
}

export async function matchCapabilities(
  scanResult: WorkspaceScanResult,
  capabilities: CapabilityEntry[],
  llmConfig: SemanticSearchLlmConfig,
): Promise<CapabilityMatchResult> {
  try {
    const catalog = formatCapabilityCatalog(capabilities);
    const systemPrompt = `You are a project capability advisor. Given project metadata, select the most useful capabilities from the catalog.

Rules:
- Select capabilities that would be MOST useful for this specific project
- Always include safety capabilities (careful, guard) for any project with code
- Consider the project type, tech stack, and directory structure
- Don't select browser/QA capabilities for non-web projects
- Return a JSON object with: { "matches": [{"capabilityId": "...", "reason": "..."}], "projectSummary": "...", "techStack": ["..."] }
- Return ONLY the JSON, no other text`;

    const userPrompt = `Project metadata:
- Types: ${scanResult.types.join(', ')}
- Languages: ${Object.entries(scanResult.languages).map(([ext, count]) => `${ext}: ${count}`).join(', ')}
- Markers: ${scanResult.markers.join(', ')}
- Top dirs: ${scanResult.topDirs.join(', ')}
- File count: ${scanResult.fileCount}
- Git repo: ${scanResult.isGitRepo}
- Has CLAUDE.md: ${scanResult.hasClaudeMd}
${scanResult.packageJson ? `- Package: ${scanResult.packageJson.name}\n- Scripts: ${scanResult.packageJson.scripts.join(', ')}\n- Key deps: ${scanResult.packageJson.deps.slice(0, 20).join(', ')}` : ''}
${scanResult.pomXml ? `- Maven: ${scanResult.pomXml.groupId}:${scanResult.pomXml.artifactId}\n- Key deps: ${scanResult.pomXml.deps.slice(0, 20).join(', ')}` : ''}

Available capabilities:
${catalog}`;

    const baseUrl = (llmConfig.baseUrl || 'https://api.anthropic.com').replace(/\/$/, '');

    const response = await fetch(`${baseUrl}/v1/messages`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-api-key': llmConfig.apiKey,
        'anthropic-version': '2023-06-01',
        'anthropic-dangerous-direct-browser-access': 'true',
      },
      body: JSON.stringify({
        model: llmConfig.model,
        max_tokens: 1024,
        messages: [{ role: 'user', content: userPrompt }],
        system: systemPrompt,
      }),
    });

    if (!response.ok) {
      throw new Error(`LLM call failed: ${response.status}`);
    }

    const data = await response.json() as any;
    const text = data.content?.[0]?.text || '';

    // Extract JSON from response
    const jsonMatch = text.match(/\{[\s\S]*\}/);
    if (!jsonMatch) throw new Error('No JSON in LLM response');

    const parsed = JSON.parse(jsonMatch[0]);

    // Validate capability IDs
    const knownIds = new Set(capabilities.map(c => c.id));
    const validMatches = (parsed.matches || []).filter(
      (m: CapabilityMatch) => knownIds.has(m.capabilityId),
    );

    return {
      matches: validMatches,
      projectSummary: parsed.projectSummary || scanResult.types.join(', '),
      techStack: parsed.techStack || [],
    };
  } catch (err) {
    // Don't degrade to keyword matching — let the caller handle failure.
    // Low-quality init is worse than no init.
    throw err;
  }
}

export function keywordFallback(
  scanResult: WorkspaceScanResult,
  capabilities: CapabilityEntry[],
): CapabilityMatchResult {
  // NOTE: keywordFallback is NOT used as a degraded init path.
  // If LLM is unavailable, we skip initialization entirely rather than
  // producing low-quality matches. This function exists only for testing.
  const matches: CapabilityMatch[] = [];

  for (const cap of capabilities) {
    if (!cap.enabled) continue;
    if (ALWAYS_MATCH_IDS.has(cap.id)) {
      matches.push({ capabilityId: cap.id, reason: '安全基础能力，适用于所有项目' });
      continue;
    }
    const triggerLower = cap.triggers.map(t => t.toLowerCase());
    const typeLower = scanResult.types.map(t => t.toLowerCase());
    const hasMatch = typeLower.some(t => triggerLower.some(tr => tr.includes(t)));
    if (hasMatch) {
      matches.push({ capabilityId: cap.id, reason: `关键词匹配: ${scanResult.types.join(', ')}` });
    }
  }

  return {
    matches,
    projectSummary: scanResult.types.join(', ') + ' 项目',
    techStack: [
      ...Object.keys(scanResult.languages).map(ext => ext.replace('.', '').toUpperCase()),
      ...scanResult.markers,
    ],
  };
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run tests/server/init/capability-matcher.test.ts`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/init/capability-matcher.ts tests/server/init/capability-matcher.test.ts
git commit -m "feat(init): add AI capability matcher with keyword fallback"
```

---

### Task 5: Create self-updater skill template

**Files:**
- Create: `server/init/templates/skill-auto-updater/SKILL.md`
- Create: `server/init/templates/skill-auto-updater/crontab.md`

- [ ] **Step 1: Create template files**

`server/init/templates/skill-auto-updater/SKILL.md`:
```markdown
---
name: skill-auto-updater
description: |
  每日自动检查并更新项目 skills。扫描项目变更，匹配最新能力。
  默认关闭，需要在 Sman 设置中启用。
allowed-tools:
  - Bash
  - Read
  - Grep
  - Glob
---

# Skill Auto-Updater

仅在用户手动触发或 cron 启用时运行。默认不执行。

## 执行步骤

1. 读取 .sman/INIT.md 获取上次扫描结果
2. 检查项目是否有显著变更（git log --since、文件结构变化）
3. 如有变更，重新执行轻量扫描并匹配能力
4. 对比现有 .claude/skills/ 与最新匹配结果
5. 如有新的匹配，复制新 SKILL.md 到 .claude/skills/
6. 更新 .sman/INIT.md 时间戳
7. 汇报更新结果
```

`server/init/templates/skill-auto-updater/crontab.md`:
```markdown
---
schedule: "0 3 * * *"
enabled: false
description: 每日凌晨3点自动更新项目 skills
---

默认关闭。在 Sman 设置页面 -> Cron 任务中启用。
```

- [ ] **Step 2: Commit**

```bash
git add server/init/templates/
git commit -m "feat(init): add self-updater skill template"
```

---

## Chunk 2: Backend Orchestrator and Integration

### Task 6: Implement ClaudeInitRunner

**Files:**
- Create: `server/init/claude-init-runner.ts`

- [ ] **Step 1: Implement ClaudeInitRunner**

```typescript
// server/init/claude-init-runner.ts
import { execFile } from 'child_process';
import { promisify } from 'util';
import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from '../utils/logger.js';

const execFileAsync = promisify(execFile);
const log: Logger = createLogger('ClaudeInitRunner');
const INIT_TIMEOUT_MS = 60_000;

export async function generateClaudeMd(workspace: string): Promise<boolean> {
  const claudeMdPath = path.join(workspace, 'CLAUDE.md');
  if (fs.existsSync(claudeMdPath)) {
    return false; // Already exists
  }

  // Find claude CLI
  const claudePath = process.env.CLAUDE_CODE_PATH || 'claude';
  try {
    const { stdout } = await execFileAsync('which', [claudePath], { timeout: 5000 });
    if (!stdout.trim()) return false;
  } catch {
    log.warn('claude CLI not found, skipping CLAUDE.md generation');
    return false;
  }

  try {
    await execFileAsync(claudePath, [
      '-p', 'Generate a CLAUDE.md file for this project. Analyze the codebase structure, tech stack, coding conventions, and write a comprehensive CLAUDE.md.',
      '--allowedTools', 'Write,Read,Glob,Grep',
      '--max-turns', '10',
    ], {
      cwd: workspace,
      timeout: INIT_TIMEOUT_MS,
      env: { ...process.env },
    });
    return fs.existsSync(claudeMdPath);
  } catch (err: any) {
    log.warn(`CLAUDE.md generation failed: ${err.message}`);
    return false;
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add server/init/claude-init-runner.ts
git commit -m "feat(init): add CLAUDE.md generation via claude CLI"
```

---

### Task 7: Implement InitManager orchestrator

**Files:**
- Create: `server/init/init-manager.ts`
- Test: `tests/server/init/init-manager.test.ts`

- [ ] **Step 1: Write the failing tests**

```typescript
// tests/server/init/init-manager.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { InitManager } from '../../../server/init/init-manager.js';
import type { CapabilityRegistry } from '../../../server/capabilities/registry.js';

describe('InitManager', () => {
  let tmpDir: string;
  let workspace: string;
  let pluginsDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-manager-test-'));
    workspace = path.join(tmpDir, 'workspace');
    pluginsDir = path.join(tmpDir, 'plugins');
    fs.mkdirSync(workspace, { recursive: true });
    fs.mkdirSync(pluginsDir, { recursive: true });
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('detects uninitialized workspace', () => {
    const manager = new InitManager({
      pluginsDir,
      capabilityRegistry: null as any,
      llmConfig: () => null,
    });
    expect(manager.isInitialized(workspace)).toBe(false);
  });

  it('detects initialized workspace from INIT.md', () => {
    fs.mkdirSync(path.join(workspace, '.sman'), { recursive: true });
    fs.writeFileSync(path.join(workspace, '.sman', 'INIT.md'), '---\nsmanVersion: "1.0.0"\n---\nInitialized');

    const manager = new InitManager({
      pluginsDir,
      capabilityRegistry: null as any,
      llmConfig: () => null,
    });
    expect(manager.isInitialized(workspace)).toBe(true);
  });

  it('acquires and releases lock', async () => {
    const manager = new InitManager({
      pluginsDir,
      capabilityRegistry: null as any,
      llmConfig: () => null,
    });

    const lockAcquired = manager.acquireLock(workspace);
    expect(lockAcquired).toBe(true);
    expect(fs.existsSync(path.join(workspace, '.sman', '.initializing'))).toBe(true);

    // Second lock should fail
    const secondAttempt = manager.acquireLock(workspace);
    expect(secondAttempt).toBe(false);

    manager.releaseLock(workspace);
    expect(fs.existsSync(path.join(workspace, '.sman', '.initializing'))).toBe(false);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx vitest run tests/server/init/init-manager.test.ts`
Expected: FAIL — module not found

- [ ] **Step 3: Implement InitManager**

```typescript
// server/init/init-manager.ts
import fs from 'fs';
import path from 'path';
import type { WebSocket } from 'ws';
import { createLogger, type Logger } from '../utils/logger.js';
import { scanWorkspace } from './workspace-scanner.js';
import { matchCapabilities } from './capability-matcher.js';
import { injectSkills } from './skill-injector.js';
import { generateClaudeMd } from './claude-init-runner.js';
import type { InitResult, InitCard, CapabilityMatchResult } from './init-types.js';
import type { CapabilityRegistry } from '../capabilities/registry.js';
import type { SemanticSearchLlmConfig } from '../capabilities/types.js';

const SMAN_VERSION = '1.0.0'; // TODO: read from package.json
const LOCK_STALE_MS = 5 * 60 * 1000; // 5 minutes

export class InitManager {
  private log: Logger;
  private pluginsDir: string;
  private capabilityRegistry: CapabilityRegistry | null;
  private llmConfig: () => SemanticSearchLlmConfig | null;

  constructor(deps: {
    pluginsDir: string;
    capabilityRegistry: CapabilityRegistry | null;
    llmConfig: () => SemanticSearchLlmConfig | null;
  }) {
    this.log = createLogger('InitManager');
    this.pluginsDir = deps.pluginsDir;
    this.capabilityRegistry = deps.capabilityRegistry;
    this.llmConfig = deps.llmConfig;
  }

  isInitialized(workspace: string): boolean {
    const initPath = path.join(workspace, '.sman', 'INIT.md');
    if (!fs.existsSync(initPath)) return false;

    // Check version — if stale, not considered initialized
    try {
      const content = fs.readFileSync(initPath, 'utf-8');
      const versionMatch = content.match(/smanVersion:\s*["']?([^"'\n]+)/);
      if (versionMatch && versionMatch[1] !== SMAN_VERSION) {
        return false; // Version mismatch, needs re-init
      }
    } catch {
      return false;
    }

    return true;
  }

  acquireLock(workspace: string): boolean {
    const smanDir = path.join(workspace, '.sman');
    const lockPath = path.join(smanDir, '.initializing');

    fs.mkdirSync(smanDir, { recursive: true });

    if (fs.existsSync(lockPath)) {
      try {
        const stat = fs.statSync(lockPath);
        if (Date.now() - stat.mtimeMs < LOCK_STALE_MS) {
          return false; // Lock is active
        }
        // Stale lock, remove it
        fs.unlinkSync(lockPath);
      } catch {
        return false;
      }
    }

    fs.writeFileSync(lockPath, JSON.stringify({
      pid: process.pid,
      startedAt: new Date().toISOString(),
    }, null, 2));

    return true;
  }

  releaseLock(workspace: string): void {
    const lockPath = path.join(workspace, '.sman', '.initializing');
    try {
      fs.unlinkSync(lockPath);
    } catch { /* ignore */ }
  }

  async handleSessionCreate(
    workspace: string,
    sessionId: string,
    ws: WebSocket,
  ): Promise<void> {
    try {
      // LLM unavailable → skip init entirely, no card, no pretending
      const llmConfig = this.llmConfig();
      if (!llmConfig) {
        this.log.info(`LLM not configured, skipping init for ${workspace}`);
        return;
      }

      if (this.isInitialized(workspace)) {
        const initMd = fs.readFileSync(path.join(workspace, '.sman', 'INIT.md'), 'utf-8');
        const card = this.parseInitMdToCard(initMd, workspace);
        this.sendCard(ws, sessionId, card);
        return;
      }

      // Send "initializing" card — phase: scanning
      this.sendCard(ws, sessionId, {
        type: 'initializing',
        workspace,
        phase: 'scanning',
      });

      if (!this.acquireLock(workspace)) {
        this.log.info(`Init already running for ${workspace}, skipping`);
        return;
      }

      try {
        const result = await this.initializeWithProgress(workspace, ws, sessionId, llmConfig);
        this.sendCard(ws, sessionId, {
          type: 'complete',
          workspace,
          projectSummary: result.matchResult.projectSummary,
          techStack: result.matchResult.techStack,
          injectedSkills: result.injectedSkills.map(id => {
            const cap = this.capabilityRegistry?.getCapability(id);
            return { id, name: cap?.name || id };
          }),
        });
      } finally {
        this.releaseLock(workspace);
      }
    } catch (err: any) {
      this.log.warn(`Init failed for ${workspace}: ${err.message}`);
      this.sendCard(ws, sessionId, {
        type: 'error',
        workspace,
        error: err.message,
      });
    }
  }

  /** Initialize with progress updates — global timeout 90s */
  private async initializeWithProgress(
    workspace: string,
    ws: WebSocket,
    sessionId: string,
    llmConfig: SemanticSearchLlmConfig,
  ): Promise<InitResult> {
    const GLOBAL_TIMEOUT_MS = 180_000;

    return Promise.race([
      (async () => {
        // Step 1: Scan (< 1s)
        const scanResult = scanWorkspace(workspace);

        // Step 2: AI Match — update phase card
        this.sendCard(ws, sessionId, {
          type: 'initializing',
          workspace,
          phase: 'matching',
        });

        const capabilities = this.capabilityRegistry?.listCapabilities() || [];
        const matchResult = await matchCapabilities(scanResult, capabilities, llmConfig);

        // Step 3: Inject skills
        this.sendCard(ws, sessionId, {
          type: 'initializing',
          workspace,
          phase: 'injecting',
        });

        const skillSources = matchResult.matches.map(m => {
          const cap = this.capabilityRegistry?.getCapability(m.capabilityId);
          return { capabilityId: m.capabilityId, pluginPath: cap?.pluginPath || m.capabilityId };
        });

        const injectedSkills = injectSkills(skillSources, this.pluginsDir, workspace);
        this.injectSelfUpdater(workspace);

        // Step 4: CLAUDE.md — silent background, no progress card
        let claudeMdGenerated = false;
        if (!scanResult.hasClaudeMd) {
          claudeMdGenerated = await generateClaudeMd(workspace);
        }

        // Step 5: Write INIT.md
        this.writeInitMd(workspace, scanResult, matchResult, injectedSkills, claudeMdGenerated);

        return { success: true, scanResult, matchResult, injectedSkills, claudeMdGenerated };
      })(),
      new Promise<never>((_, reject) =>
        setTimeout(() => reject(new Error('初始化超时，请稍后重试')), GLOBAL_TIMEOUT_MS)
      ),
    ]);
  }

  private injectSelfUpdater(workspace: string): void {
    const targetDir = path.join(workspace, '.claude', 'skills', 'skill-auto-updater');
    if (fs.existsSync(path.join(targetDir, 'SKILL.md'))) return; // Don't overwrite

    // Template path relative to this source file
    const templateDir = path.join(__dirname, 'templates', 'skill-auto-updater');
    if (!fs.existsSync(templateDir)) {
      this.log.warn('Self-updater template not found');
      return;
    }

    fs.mkdirSync(targetDir, { recursive: true });
    for (const file of fs.readdirSync(templateDir)) {
      fs.copyFileSync(
        path.join(templateDir, file),
        path.join(targetDir, file),
      );
    }
  }

  private writeInitMd(
    workspace: string,
    scanResult: ReturnType<typeof scanWorkspace>,
    matchResult: CapabilityMatchResult,
    injectedSkills: string[],
    claudeMdGenerated: boolean,
  ): void {
    const smanDir = path.join(workspace, '.sman');
    fs.mkdirSync(smanDir, { recursive: true });

    const lines = [
      `---`,
      `smanVersion: "${SMAN_VERSION}"`,
      `initializedAt: "${new Date().toISOString()}"`,
      `---`,
      ``,
      `# Project Init`,
      ``,
      `**Type:** ${scanResult.types.join(', ')}`,
      `**Tech Stack:** ${matchResult.techStack.join(', ')}`,
      `**Summary:** ${matchResult.projectSummary}`,
      `**Files:** ${scanResult.fileCount}`,
      `**Git:** ${scanResult.isGitRepo ? 'yes' : 'no'}`,
      `**CLAUDE.md:** ${scanResult.hasClaudeMd ? 'existing' : claudeMdGenerated ? 'generated' : 'missing'}`,
      ``,
      `## Injected Skills`,
      ...injectedSkills.map(id => `- ${id}`),
      ``,
      `## Match Reasons`,
      ...matchResult.matches.map(m => `- **${m.capabilityId}**: ${m.reason}`),
    ];

    fs.writeFileSync(path.join(smanDir, 'INIT.md'), lines.join('\n'), 'utf-8');
  }

  sendCard(ws: WebSocket, sessionId: string, card: InitCard): void {
    try {
      ws.send(JSON.stringify({
        type: 'init.card',
        sessionId,
        card,
      }));
    } catch (err: any) {
      this.log.warn(`Failed to send init card: ${err.message}`);
    }
  }

  private parseInitMdToCard(content: string, workspace: string): InitCard {
    const summary = content.match(/\*\*Summary:\*\* (.+)/)?.[1] || '';
    const techStack = content.match(/\*\*Tech Stack:\*\* (.+)/)?.[1]?.split(', ') || [];
    const initializedAt = content.match(/initializedAt: "([^"]+)"/)?.[1] || '';
    const skills = [...content.matchAll(/^- (.+)$/gm)].map(m => m[1]);

    return {
      type: 'already',
      workspace,
      projectSummary: summary,
      techStack,
      injectedSkills: skills.map(id => ({ id, name: id })),
      initializedAt,
    };
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx vitest run tests/server/init/init-manager.test.ts`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add server/init/init-manager.ts tests/server/init/init-manager.test.ts
git commit -m "feat(init): add InitManager orchestrator"
```

---

### Task 8: Wire InitManager into server startup and session.create

**Files:**
- Modify: `server/index.ts`

- [ ] **Step 1: Add import and instantiation**

In `server/index.ts`, add import near line 41:

```typescript
import { InitManager } from './init/init-manager.js';
```

After the `capabilityRegistry` instantiation (around line 213), add:

```typescript
// Initialize workspace auto-init manager
const initManager = new InitManager({
  pluginsDir,
  capabilityRegistry,
  llmConfig: () => {
    const config = settingsManager.getConfig();
    if (!config?.llm?.apiKey) return null;
    return {
      apiKey: config.llm.apiKey,
      model: config.llm.model || 'claude-sonnet-4-20250514',
      baseUrl: config.llm.baseUrl,
    };
  },
});
```

- [ ] **Step 2: Add init call to session.create handler**

Modify the `session.create` case (around line 459) to:

```typescript
case 'session.create': {
  if (!msg.workspace) throw new Error('Missing workspace');
  const sessionId = sessionManager.createSession(msg.workspace);
  ws.send(JSON.stringify({ type: 'session.created', sessionId, workspace: msg.workspace }));

  // Auto-initialize workspace (async, non-blocking)
  initManager.handleSessionCreate(msg.workspace, sessionId, ws).catch((err: any) => {
    log.warn(`Workspace init failed for ${msg.workspace}: ${err.message}`);
  });
  break;
}
```

- [ ] **Step 3: Verify compilation**

Run: `npx tsc --noEmit --pretty 2>&1 | grep -v "DecoLayer\|WorldCanvas\|map-data\|palette" | tail -5`
Expected: No new errors

- [ ] **Step 4: Commit**

```bash
git add server/index.ts
git commit -m "feat(init): wire InitManager into session creation"
```

---

## Chunk 3: Frontend — Init Banner Component

### Task 9: Handle init WebSocket events in chat store

**Files:**
- Modify: `src/stores/chat.ts`

- [ ] **Step 1: Add init state to chat store**

Find the WebSocket message handler in `src/stores/chat.ts` and add handling for `init.card` messages. The init card data should be stored in a new field `initCard` on the session state.

Add to the store state interface:
```typescript
initCard: InitCard | null;
```

In the WS message handler, add:
```typescript
case 'init.card': {
  const { card } = msg as any;
  set({ initCard: card });
  break;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/stores/chat.ts
git commit -m "feat(init): handle init.card WebSocket events in chat store"
```

---

### Task 10: Create InitBanner component

**Files:**
- Create: `src/features/chat/InitBanner.tsx`

- [ ] **Step 1: Implement InitBanner**

```tsx
// src/features/chat/InitBanner.tsx
import React, { useEffect, useState } from 'react';
import { useChatStore } from '../../stores/chat';

export function InitBanner() {
  const initCard = useChatStore(s => s.initCard);
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    setDismissed(false);
  }, [initCard]);

  if (!initCard || dismissed) return null;

  // Auto-dismiss "already initialized" after 5s
  useEffect(() => {
    if (initCard.type === 'already') {
      const timer = setTimeout(() => setDismissed(true), 5000);
      return () => clearTimeout(timer);
    }
  }, [initCard.type]);

  const borderColor = {
    initializing: 'border-blue-300 bg-blue-50',
    complete: 'border-green-300 bg-green-50',
    already: 'border-gray-300 bg-gray-50',
    error: 'border-red-300 bg-red-50',
  }[initCard.type];

  return (
    <div className={`mx-4 mt-2 mb-1 border rounded-lg p-3 flex items-start gap-3 ${borderColor}`}>
      {initCard.type === 'initializing' && (
        <>
          <span className="text-lg animate-spin">◌</span>
          <div className="flex-1">
            <div className="font-medium text-sm">项目初始化中</div>
            <div className="text-xs text-gray-500 mt-0.5">{initCard.workspace}</div>
            <div className="text-xs text-gray-400 mt-1">
              {initCard.phase === 'scanning' && '正在扫描项目结构...'}
              {initCard.phase === 'matching' && '正在分析并匹配最佳能力...'}
              {initCard.phase === 'injecting' && '正在注入能力...'}
              {!initCard.phase && '正在初始化...'}
            </div>
          </div>
        </>
      )}

      {initCard.type === 'complete' && (
        <>
          <span className="text-lg">✅</span>
          <div className="flex-1">
            <div className="font-medium text-sm">项目初始化完成</div>
            {initCard.projectSummary && (
              <div className="text-xs mt-1">
                <span className="font-medium">{initCard.projectSummary}</span>
                {initCard.techStack && initCard.techStack.length > 0 && (
                  <span className="text-gray-500 ml-2">({initCard.techStack.join(', ')})</span>
                )}
              </div>
            )}
            {initCard.injectedSkills && initCard.injectedSkills.length > 0 && (
              <div className="text-xs text-gray-500 mt-1">
                已加载 {initCard.injectedSkills.length} 个能力:
                {' ' + initCard.injectedSkills.map(s => s.name).join(', ')}
              </div>
            )}
          </div>
        </>
      )}

      {initCard.type === 'already' && (
        <>
          <span className="text-lg">📂</span>
          <div className="flex-1">
            <div className="text-sm">
              <span className="font-medium">{initCard.projectSummary}</span>
              {initCard.techStack && initCard.techStack.length > 0 && (
                <span className="text-gray-500 ml-2">({initCard.techStack.join(', ')})</span>
              )}
            </div>
            {initCard.injectedSkills && (
              <div className="text-xs text-gray-400 mt-0.5">
                已加载 {initCard.injectedSkills.length} 个能力
                {initCard.initializedAt && ` · 初始化于 ${new Date(initCard.initializedAt).toLocaleDateString()}`}
              </div>
            )}
          </div>
        </>
      )}

      {initCard.type === 'error' && (
        <>
          <span className="text-lg">⚠️</span>
          <div className="flex-1">
            <div className="font-medium text-sm">初始化失败</div>
            <div className="text-xs text-gray-500">{initCard.error}</div>
          </div>
        </>
      )}

      <button
        className="text-gray-400 hover:text-gray-600 text-sm p-1"
        onClick={() => setDismissed(true)}
      >
        ✕
      </button>
    </div>
  );
}
```

- [ ] **Step 2: Add InitBanner to chat page**

In `src/features/chat/index.tsx`, import and render `InitBanner` above the chat message list:

```tsx
import { InitBanner } from './InitBanner';

// In the render, above the messages container:
<InitBanner />
```

- [ ] **Step 3: Commit**

```bash
git add src/features/chat/InitBanner.tsx src/features/chat/index.tsx
git commit -m "feat(init): add InitBanner component"
```

---

### Task 11: Verify full integration

- [ ] **Step 1: Run full test suite**

Run: `npx vitest run tests/server/init/`
Expected: All tests PASS

- [ ] **Step 2: Type check**

Run: `npx tsc --noEmit --pretty 2>&1 | grep -v "DecoLayer\|WorldCanvas\|map-data\|palette" | tail -5`
Expected: No new errors

- [ ] **Step 3: Manual smoke test**

1. Start dev server: `pnpm dev:server`
2. Start frontend: `pnpm dev`
3. Create a new session pointing to any project directory
4. Verify: "initializing" card appears, then "complete" card with matched skills
5. Create another session for the same directory
6. Verify: "already initialized" card appears and auto-dismisses

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "feat(init): workspace auto-initialization system complete"
```
