# Project Knowledge Scanner v2 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Project Knowledge Scanner v2: serial execution, 6-hour cron, idle protection, per-scanner retry, strict SKILL.md frontmatter validation with commit hash, and per-scanner status in manifest.

**Architecture:**
- New `frontmatter-utils.ts` module for YAML frontmatter parsing and validation
- `project-scanner.ts` refactored to serial execution with per-scanner timeout/retry
- `scanner-prompts.ts` updated to include `_scanned` metadata injection
- `cron-scheduler.ts` updated to 6-hour interval
- `isScanNeeded` changes signature from `boolean` to `{ needed: boolean; reason: string }` and is now per-scanner-type

**Tech Stack:** TypeScript, Node.js, vitest, node-cron, execSync for git

---

## Chunk 1: frontmatter-utils.ts (New File)

**Files:**
- Create: `server/capabilities/frontmatter-utils.ts`
- Test: `tests/server/capabilities/frontmatter-utils.test.ts`

- [ ] **Step 1: Write the failing test**

```typescript
// tests/server/capabilities/frontmatter-utils.test.ts
import { describe, it, expect } from 'vitest';
import { parseFrontmatter, validateSkillMd } from '../../../server/capabilities/frontmatter-utils.js';
import fs from 'node:fs';
import path from 'path';
import os from 'node:os';

describe('parseFrontmatter', () => {
  it('parses valid frontmatter', () => {
    const content = `---\nname: project-structure\ndescription: "Test description"\n---\n\n# Content`;
    const result = parseFrontmatter(content);
    expect(result.name).toBe('project-structure');
    expect(result.description).toBe('Test description');
  });

  it('returns null for missing frontmatter', () => {
    const content = `# No frontmatter\nContent`;
    expect(parseFrontmatter(content)).toBeNull();
  });

  it('parses _scanned fields', () => {
    const content = `---\nname: project-apis\ndescription: "APIs"\n_scanned:\n  commitHash: "abc123"\n  scannedAt: "2026-04-09T10:00:00Z"\n  branch: main\n---\n\n# Content`;
    const result = parseFrontmatter(content);
    expect(result._scanned?.commitHash).toBe('abc123');
    expect(result._scanned?.scannedAt).toBe('2026-04-09T10:00:00Z');
    expect(result._scanned?.branch).toBe('main');
  });

  it('returns null for unclosed frontmatter', () => {
    const content = `---\nname: test\n# missing closing ---`;
    expect(parseFrontmatter(content)).toBeNull();
  });
});

describe('validateSkillMd', () => {
  it('returns true for valid SKILL.md', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    const filePath = path.join(tmpDir, 'SKILL.md');
    fs.writeFileSync(filePath, `---\nname: project-structure\ndescription: "Project structure skill with enough chars"\n_scanned:\n  commitHash: "abc123"\n  scannedAt: "2026-04-09T10:00:00Z"\n---\n\n# Content\nTable here`);
    try {
      expect(validateSkillMd(filePath)).toBe(true);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  it('returns false for missing name', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    const filePath = path.join(tmpDir, 'SKILL.md');
    fs.writeFileSync(filePath, `---\ndescription: "No name field"\n_scanned:\n  commitHash: "abc"\n---\n\n# Content`);
    try {
      expect(validateSkillMd(filePath)).toBe(false);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  it('returns false for short description', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    const filePath = path.join(tmpDir, 'SKILL.md');
    fs.writeFileSync(filePath, `---\nname: test\ndescription: "ab"\n_scanned:\n  commitHash: "abc"\n---\n\n# Content`);
    try {
      expect(validateSkillMd(filePath)).toBe(false);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  it('returns false for missing commitHash', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    const filePath = path.join(tmpDir, 'SKILL.md');
    fs.writeFileSync(filePath, `---\nname: test\ndescription: "Valid description here"\n---\n\n# Content`);
    try {
      expect(validateSkillMd(filePath)).toBe(false);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  it('returns false when file exceeds 80 lines', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    const filePath = path.join(tmpDir, 'SKILL.md');
    const lines = ['---\nname: test\ndescription: "Valid description with enough text"\n_scanned:\n  commitHash: "abc"\n---\n'];
    for (let i = 0; i < 81; i++) lines.push(`# Line ${i}`);
    fs.writeFileSync(filePath, lines.join('\n'));
    try {
      expect(validateSkillMd(filePath)).toBe(false);
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    }
  });

  it('returns false for non-existent file', () => {
    expect(validateSkillMd('/nonexistent/SKILL.md')).toBe(false);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/nasakim/projects/smanbase && pnpm test -- --run tests/server/capabilities/frontmatter-utils.test.ts`
Expected: FAIL with "module not found" (file doesn't exist yet)

- [ ] **Step 3: Write minimal implementation**

```typescript
// server/capabilities/frontmatter-utils.ts

/**
 * Parse YAML frontmatter from a markdown file.
 * Returns null if no valid frontmatter found.
 */
export function parseFrontmatter(content: string): Record<string, any> | null {
  if (!content.startsWith('---')) return null;
  const endIdx = content.indexOf('\n---', 3);
  if (endIdx === -1) return null;
  const yamlContent = content.slice(3, endIdx).trim();
  return parseYaml(yamlContent);
}

/**
 * Minimal YAML parser for frontmatter.
 * Handles only: key: value, nested objects with indent, quoted strings.
 */
function parseYaml(yaml: string): Record<string, any> {
  const result: Record<string, any> = {};
  const lines = yaml.split('\n');
  let currentKey = '';
  let currentIndent = 0;
  let inMultiline = false;
  let multilineValue = '';

  for (const rawLine of lines) {
    const line = rawLine;

    // Empty line
    if (!line.trim()) {
      if (inMultiline) {
        multilineValue += '\n';
      }
      continue;
    }

    // Nested object key
    const indentMatch = line.match(/^(\s+)(.+)$/);
    if (indentMatch) {
      const indent = indentMatch[1].length;
      const rest = indentMatch[2];
      if (rest.trim().startsWith('_scanned') || rest.includes(':')) {
        // Reset if we're at or below the current object indent
        if (indent <= currentIndent) {
          if (inMultiline && currentKey) {
            result[currentKey] = multilineValue.trim();
            multilineValue = '';
            inMultiline = false;
          }
          currentIndent = indent;
        }
        const kvMatch = rest.match(/^([^:]+):\s*(.*)$/);
        if (kvMatch) {
          const key = kvMatch[1].trim();
          const val = kvMatch[2].trim();
          if (val) {
            result[key] = parseYamlValue(val);
          } else {
            currentKey = key;
            currentIndent = indent;
          }
        }
      } else if (inMultiline && currentKey) {
        multilineValue += '\n' + line.trim();
      }
      continue;
    }

    // Top-level key: value
    if (inMultiline && currentKey) {
      result[currentKey] = multilineValue.trim();
      multilineValue = '';
      inMultiline = false;
    }

    const match = line.match(/^([^:]+):\s*(.*)$/);
    if (match) {
      const key = match[1].trim();
      const value = match[2].trim();
      result[key] = parseYamlValue(value);
    }
  }

  // Last multiline
  if (inMultiline && currentKey) {
    result[currentKey] = multilineValue.trim();
  }

  return result;
}

function parseYamlValue(value: string): any {
  if (value.startsWith('"') && value.endsWith('"')) {
    return value.slice(1, -1);
  }
  if (value.startsWith("'") && value.endsWith("'")) {
    return value.slice(1, -1);
  }
  if (value === 'true') return true;
  if (value === 'false') return false;
  if (value === 'null') return null;
  if (/^\d+$/.test(value)) return parseInt(value, 10);
  return value;
}

/**
 * Validate that SKILL.md has correct frontmatter.
 */
export function validateSkillMd(filePath: string): boolean {
  if (!fs.existsSync(filePath)) return false;
  const content = fs.readFileSync(filePath, 'utf-8');
  const frontmatter = parseFrontmatter(content);
  if (!frontmatter) return false;
  if (!frontmatter.name) return false;
  if (!frontmatter.description || frontmatter.description.length < 5) return false;
  if (!frontmatter._scanned?.commitHash) return false;
  if (content.split('\n').length > 80) return false;
  return true;
}
```

Wait - the implementation uses `fs` but doesn't import it. Let me fix:

```typescript
import fs from 'node:fs';
import path from 'node:path';
```

Actually the parseYaml and parseFrontmatter don't need fs - only validateSkillMd does. Let me structure it properly:

```typescript
import fs from 'node:fs';
import path from 'node:path';

// parseFrontmatter and parseYaml are pure functions (no fs)
// validateSkillMd uses fs
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/nasakim/projects/smanbase && pnpm test -- --run tests/server/capabilities/frontmatter-utils.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/capabilities/frontmatter-utils.ts tests/server/capabilities/frontmatter-utils.test.ts
git commit -m "feat(scanner): add frontmatter-utils for YAML parsing and SKILL.md validation"
```

---

## Chunk 2: scanner-prompts.ts — Add _scanned Fields

**Files:**
- Modify: `server/capabilities/scanner-prompts.ts`

- [ ] **Step 1: Update SKILL.md frontmatter template in STRUCTURE_PROMPT**

Old:
```yaml
---
name: project-structure
description: "{Project} directory layout..."
---
```

New (add _scanned block):
```yaml
---
name: project-structure
description: "{Project} directory layout, tech stack, module organization..."
_scanned:
  commitHash: "{COMMIT_HASH}"
  scannedAt: "{SCANNED_AT}"
  branch: "{BRANCH}"
---
```

Replace all 3 scanner templates similarly.

- [ ] **Step 2: Add injected metadata to getScannerPrompt()**

Update `getScannerPrompt()` to accept and inject actual git values:

```typescript
export function getScannerPrompt(type: ScannerType, workspace: string, gitInfo?: { commitHash: string; branch: string }): string {
  const template = PROMPTS[type];
  if (!template) throw new Error(`Unknown scanner type: ${type}`);

  const skillsDir = `${workspace}/.claude/skills`;
  const projectName = workspace.split('/').pop() || 'project';
  const commitHash = gitInfo?.commitHash ?? 'unknown';
  const branch = gitInfo?.branch ?? 'unknown';
  const scannedAt = new Date().toISOString();

  return template
    .replace(/\{SKILLS_DIR\}/g, skillsDir)
    .replace(/\{Project\}/g, projectName)
    .replace(/\{COMMIT_HASH\}/g, commitHash)
    .replace(/\{BRANCH\}/g, branch)
    .replace(/\{SCANNED_AT\}/g, scannedAt)
    + '\n\n'
    + SHARED_RULES
    + `\n\nWorkspace: ${workspace}`;
}
```

- [ ] **Step 3: Update SHARED_RULES to instruct scanner to inject metadata**

Add to SHARED_RULES:
```
10. **Set _scanned fields in SKILL.md frontmatter**: Set `_scanned.commitHash` to the workspace git commit hash, `_scanned.scannedAt` to the current ISO timestamp, and `_scanned.branch` to the current git branch. These MUST be set in the frontmatter — they enable team members to skip re-scanning when their local commit matches.
```

- [ ] **Step 4: Update tests**

```typescript
// In tests/server/capabilities/scanner-prompts.test.ts, add:
it('prompt contains _scanned fields for structure scanner', () => {
  const prompt = getScannerPrompt('structure', '/tmp/test');
  expect(prompt).toContain('_scanned:');
  expect(prompt).toContain('commitHash');
  expect(prompt).toContain('scannedAt');
  expect(prompt).toContain('branch');
});

it('prompt accepts and injects git info', () => {
  const prompt = getScannerPrompt('structure', '/tmp/test', {
    commitHash: 'abc123',
    branch: 'main'
  });
  expect(prompt).toContain('abc123');
  expect(prompt).toContain('main');
});
```

- [ ] **Step 5: Run tests**

Run: `cd /Users/nasakim/projects/smanbase && pnpm test -- --run tests/server/capabilities/scanner-prompts.test.ts`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add server/capabilities/scanner-prompts.ts tests/server/capabilities/scanner-prompts.test.ts
git commit -m "feat(scanner): add _scanned metadata fields to SKILL.md frontmatter templates"
```

---

## Chunk 3: project-scanner.ts — Serial Execution + Retry + Idle Wait + Timeout

**Files:**
- Modify: `server/capabilities/project-scanner.ts`
- Test: `tests/server/capabilities/project-scanner.test.ts` (update existing tests)

- [ ] **Step 1: Update import to include validateSkillMd**

Add import at top of project-scanner.ts:
```typescript
import { validateSkillMd } from './frontmatter-utils.js';
```

- [ ] **Step 2: Change isScanNeeded signature and implementation**

Old signature: `isScanNeeded(workspace: string): boolean`
New: `isScanNeeded(workspace: string, scannerType: ScannerType): { needed: boolean; reason: string }`

```typescript
export function isScanNeeded(workspace: string, scannerType: ScannerType): { needed: boolean; reason: string } {
  const skillMdPath = path.join(workspace, '.claude', 'skills', `project-${scannerType}`, 'SKILL.md');

  // SKILL.md doesn't exist → needs scan
  if (!fs.existsSync(skillMdPath)) {
    return { needed: true, reason: 'SKILL.md not found' };
  }

  // Parse frontmatter to get scanned commit hash
  const { parseFrontmatter } = require('./frontmatter-utils.js');
  const content = fs.readFileSync(skillMdPath, 'utf-8');
  const frontmatter = parseFrontmatter(content);
  const scannedCommit = frontmatter?._scanned?.commitHash;

  // Not a git repo → always scan
  let currentCommit: string;
  try {
    currentCommit = execSync('git rev-parse HEAD', { cwd: workspace, encoding: 'utf-8' }).trim();
  } catch {
    return { needed: true, reason: 'not a git repo' };
  }

  // Commit changed → rescan
  if (scannedCommit !== currentCommit) {
    return {
      needed: true,
      reason: scannedCommit
        ? `commit changed: ${scannedCommit} → ${currentCommit}`
        : `commit hash missing in SKILL.md`
    };
  }

  return { needed: false, reason: 'up to date' };
}
```

- [ ] **Step 3: Change scan() to scanWorkspace() with serial execution**

Rename `scan()` to `scanWorkspace()`. Change the inner loop from `Promise.all()` to a `for...of` serial loop:

```typescript
async scanWorkspace(workspace: string): Promise<void> {
  acquireLock(workspace);
  try {
    const gitInfo = getGitInfo(workspace);

    for (const type of SCANNER_TYPES) {
      const { needed, reason } = isScanNeeded(workspace, type);
      if (!needed) {
        this.log.info(`Scanner [${type}] skipped: ${reason}`);
        continue;
      }

      // Wait for idle before running this scanner
      await this.waitUntilIdle();

      let success = false;
      for (let retry = 0; retry <= 2 && !success; retry++) {
        const result = await this.runScanner(type, workspace, gitInfo);
        if (result.success) {
          const skillMdPath = path.join(workspace, '.claude', 'skills', `project-${type}`, 'SKILL.md');
          if (validateSkillMd(skillMdPath)) {
            success = true;
            this.updateManifestScanner(workspace, type, { status: 'done', filesWritten: result.filesWritten });
          } else {
            this.log.warn(`Scanner [${type}] SKILL.md validation failed, retry ${retry + 1}/2`);
            deleteScannerDir(workspace, type);
          }
        } else {
          this.log.warn(`Scanner [${type}] failed: ${result.error}, retry ${retry + 1}/2`);
          deleteScannerDir(workspace, type);
        }
      }

      if (!success) {
        this.log.error(`Scanner [${type}] exhausted retries`);
        this.updateManifestScanner(workspace, type, { status: 'error', error: 'exhausted retries' });
      }
    }
  } finally {
    releaseLock(workspace);
  }
}
```

- [ ] **Step 4: Add waitUntilIdle() method**

```typescript
private async waitUntilIdle(): Promise<void> {
  const { hasActiveStreams } = this.options.sessionManager;
  while (hasActiveStreams()) {
    this.log.info('Waiting for active sessions to finish...');
    await new Promise(r => setTimeout(r, 30_000)); // 30-second poll interval
  }
}
```

Add `hasActiveStreams()` to the session manager interface in `ProjectScannerOptions`.

- [ ] **Step 5: Add 10-minute timeout to runScanner()**

```typescript
private async runScanner(type: ScannerType, workspace: string, gitInfo: ReturnType<typeof getGitInfo>): Promise<{ filesWritten: number; success: boolean; error?: string }> {
  const sessionId = `scanner-${type}-${Date.now()}`;
  const prompt = getScannerPrompt(type, workspace, gitInfo);
  this.options.sessionManager.createSessionWithId(workspace, sessionId, true, true);

  const abortController = new AbortController();
  const timeoutId = setTimeout(() => {
    abortController.abort();
  }, 10 * 60 * 1000); // 10 minutes

  try {
    this.log.info(`Scanner [${type}] starting for session ${sessionId}`);
    await this.options.sessionManager.sendMessageForCron(sessionId, prompt, abortController, () => {});
    clearTimeout(timeoutId);

    const outputDir = path.join(workspace, '.claude', 'skills', `project-${type}`);
    const skillFile = path.join(outputDir, 'SKILL.md');

    if (!fs.existsSync(skillFile)) {
      return { filesWritten: 0, success: false, error: 'SKILL.md not written by agent' };
    }
    const content = fs.readFileSync(skillFile, 'utf-8').trim();
    if (content.length < 50) {
      return { filesWritten: 0, success: false, error: `SKILL.md too short: ${content.length} chars` };
    }

    const mdFiles = fs.readdirSync(outputDir).filter(f => f.endsWith('.md'));
    this.log.info(`Scanner [${type}] succeeded: ${mdFiles.length} files written`);
    return { filesWritten: mdFiles.length, success: true };
  } catch (e: any) {
    clearTimeout(timeoutId);
    if (e.name === 'AbortError') {
      return { filesWritten: 0, success: false, error: 'timeout after 10 minutes' };
    }
    this.log.error(`Scanner [${type}] error: ${e.message}`);
    return { filesWritten: 0, success: false, error: e.message };
  } finally {
    clearTimeout(timeoutId);
    try { this.options.sessionManager.closeV2Session(sessionId); } catch { /* ignore */ }
  }
}
```

- [ ] **Step 6: Add helper functions**

```typescript
function deleteScannerDir(workspace: string, scannerType: ScannerType): void {
  const dir = path.join(workspace, '.claude', 'skills', `project-${scannerType}`);
  if (fs.existsSync(dir)) {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

private updateManifestScanner(workspace: string, type: ScannerType, status: Record<string, any>): void {
  const manifestPath = path.join(workspace, '.claude', 'knowledge-manifest.json');
  let manifest: ScanManifest;
  if (fs.existsSync(manifestPath)) {
    try {
      manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf-8'));
    } catch {
      manifest = { version: '2.0', gitUrl: null, commitHash: null, branch: null, scannedAt: '', scanners: {} };
    }
  } else {
    manifest = { version: '2.0', gitUrl: null, commitHash: null, branch: null, scannedAt: '', scanners: {} };
  }
  manifest.scanners[type] = status;
  manifest.version = '2.0';
  const claudeDir = path.join(workspace, '.claude');
  fs.mkdirSync(claudeDir, { recursive: true });
  fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
}
```

Note: manifest is still written per-scanner but with v2 format. The manifest path stays as `knowledge-manifest.json` for backward compat.

- [ ] **Step 7: Update ProjectScannerOptions interface**

```typescript
export interface ProjectScannerOptions {
  homeDir: string;
  sessionManager: {
    createSessionWithId(workspace: string, sessionId: string, isCron?: boolean, isScanner?: boolean): string;
    sendMessageForCron(sessionId: string, content: string, abortController: AbortController, onActivity: () => void): Promise<void>;
    closeV2Session(sessionId: string): void;
    hasActiveStreams(): boolean;  // NEW: check if any sessions are streaming
  };
}
```

- [ ] **Step 8: Update tests**

The existing `isScanNeeded` tests (line 51-96 in project-scanner.test.ts) need to be updated because the function signature changed.

Old tests expect `isScanNeeded(workspace)` → boolean.
New tests should test `isScanNeeded(workspace, scannerType)` → `{ needed: boolean; reason: string }`.

Add tests for `validateSkillMd` integration (create mock SKILL.md with valid frontmatter and verify it passes).

- [ ] **Step 9: Run tests**

Run: `pnpm test -- --run tests/server/capabilities/project-scanner.test.ts`
Expected: All tests pass (after fixing the isScanNeeded test expectations)

- [ ] **Step 10: Commit**

```bash
git add server/capabilities/project-scanner.ts tests/server/capabilities/project-scanner.test.ts
git commit -m "feat(scanner): implement v2 serial execution with retry, idle wait, and timeout"
```

---

## Chunk 4: cron-scheduler.ts — Change to 6-Hour Interval

**Files:**
- Modify: `server/cron-scheduler.ts`

- [ ] **Step 1: Change nightly cron expression**

Line 68: Change `'0 3 * * *'` to `'0 */6 * * *'`

Old:
```typescript
const nightlyJob = cron.schedule('0 3 * * *', () => {
```

New:
```typescript
const sixHourJob = cron.schedule('0 */6 * * *', () => {
```

- [ ] **Step 2: Update variable name for clarity**

Rename `nightlyJob` to `sixHourRefreshJob`, `__nightly_knowledge_refresh__` to `__sixhour_knowledge_refresh__`.

Also update log messages: `'Nightly knowledge refresh failed'` → `'6-hour knowledge refresh failed'`.

- [ ] **Step 3: Verify no other references to 3AM cron**

Run: `grep -n "3 \* \* \*" server/cron-scheduler.ts` — should find 0 matches after change

- [ ] **Step 4: Run tests**

Run: `pnpm test -- --run tests/server/cron-scheduler.test.ts` (if exists) or just verify compilation
Run: `cd /Users/nasakim/projects/smanbase && pnpm build` to verify TypeScript compiles

- [ ] **Step 5: Commit**

```bash
git add server/cron-scheduler.ts
git commit -m "feat(scanner): change knowledge refresh cron from 3AM daily to every 6 hours"
```

---

## Chunk 5: Integration — Update index.ts Scanner Options

**Files:**
- Modify: `server/index.ts`

- [ ] **Step 1: Add hasActiveStreams to sessionManager in index.ts**

Find where ProjectScanner is instantiated (around line 206). Update the sessionManager object to include `hasActiveStreams`:

```typescript
const projectScanner = new ProjectScanner({
  homeDir,
  sessionManager: {
    createSessionWithId: sessionManager.createSessionWithId.bind(sessionManager),
    sendMessageForCron: sessionManager.sendMessageForCron.bind(sessionManager),
    closeV2Session: sessionManager.closeV2Session.bind(sessionManager),
    hasActiveStreams: () => sessionManager.listSessions().length > 0
      && Array.from(sessionManager['activeStreams'].values()).some(ctrl => !ctrl.signal.aborted),
    // Note: expose activeStreams map or add a dedicated method to ClaudeSessionManager
  },
});
```

Actually, `hasActiveStreams` should be a method on `ClaudeSessionManager` itself, not cobbled together in index.ts. Add to `claude-session.ts`:

```typescript
hasActiveStreams(): boolean {
  return this.activeStreams.size > 0;
}
```

Then in index.ts, simply pass `sessionManager.hasActiveStreams.bind(sessionManager)`.

- [ ] **Step 2: Verify TypeScript compiles**

Run: `pnpm build`

- [ ] **Step 3: Commit**

```bash
git add server/claude-session.ts server/index.ts
git commit -m "feat(scanner): add hasActiveStreams() to ClaudeSessionManager for idle detection"
```

---

## Chunk 6: Full Integration Test

**Files:**
- Create: `tests/server/capabilities/scanner-v2-integration.test.ts`

- [ ] **Step 1: Write integration test for full scanWorkspace flow**

Test with a real temporary git repo:
- Create temp git repo
- Call `isScanNeeded` → should return true (no SKILL.md)
- Mock `runScanner` to succeed
- Call `scanWorkspace`
- Verify manifest written with per-scanner status
- Verify SKILL.md has correct frontmatter

- [ ] **Step 2: Run all tests**

Run: `pnpm test -- --run tests/server/capabilities/`
Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add tests/server/capabilities/scanner-v2-integration.test.ts
git commit -m "test(scanner): add v2 integration tests for serial execution and frontmatter validation"
```

---

## Summary of Changes

| File | Action |
|------|--------|
| `server/capabilities/frontmatter-utils.ts` | Create — YAML parse, validateSkillMd |
| `tests/server/capabilities/frontmatter-utils.test.ts` | Create — tests for above |
| `server/capabilities/scanner-prompts.ts` | Modify — add _scanned fields to templates |
| `tests/server/capabilities/scanner-prompts.test.ts` | Modify — test _scanned field injection |
| `server/capabilities/project-scanner.ts` | Modify — serial exec, retry, idle wait, timeout |
| `tests/server/capabilities/project-scanner.test.ts` | Modify — update isScanNeeded tests |
| `server/cron-scheduler.ts` | Modify — 6-hour interval |
| `server/claude-session.ts` | Modify — add hasActiveStreams() |
| `server/index.ts` | Modify — pass hasActiveStreams to ProjectScanner |
| `tests/server/capabilities/scanner-v2-integration.test.ts` | Create — integration test |

## Verification

After implementation, verify:
1. `pnpm build` — TypeScript compiles without errors
2. `pnpm test -- --run tests/server/capabilities/` — all tests pass
3. Manual: create a temp git workspace, trigger scan, verify SKILL.md has frontmatter with commit hash
