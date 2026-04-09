# Project Knowledge Scanner v2 Design

**Date:** 2026-04-09
**Status:** Draft
**Supersedes:** 2026-04-07-project-knowledge-scanner-design.md

## Problem

When developers open a business system project in Sman, Claude has no prior knowledge of the project structure, APIs, or external dependencies. Every session starts from scratch. The previous v1 design had these issues:

1. SKILL.md missing YAML frontmatter — Claude Code couldn't recognize them as skills
2. `project-structure` scanner completed but wrote 0 files (silent failure)
3. Parallel execution caused resource contention
4. No idle protection — scanner ran even when active user sessions existed
5. No per-scanner retry mechanism
6. No commit hash in SKILL.md — team members each had to re-scan independently

## Goals

1. **Self-contained skills** — Each `SKILL.md` includes scan metadata (commit hash, timestamp, branch) so any team member can verify freshness without scanning
2. **Reliable execution** — idle protection, per-scanner timeout (10 min), retry up to 2 times, strict frontmatter validation
3. **Incremental refresh** — git commit changes trigger rescan; unchanged scanners skip
4. **Serial execution** — one scanner at a time to avoid resource contention
5. **Graceful degradation** — partial results (some scanners done, some failed) are preserved and reflected in manifest

## Output Structure

```
{workspace}/.claude/skills/
├── project-structure/
│   ├── SKILL.md              # YAML frontmatter + tech stack / directory tree / modules / build
│   └── references/
│       └── {module}.md       # Per module: purpose, key files, dependencies
├── project-apis/
│   ├── SKILL.md              # YAML frontmatter + API endpoint table
│   └── references/
│       └── {METHOD}-{slug}.md
└── project-external-calls/
    ├── SKILL.md              # YAML frontmatter + external dependency table
    └── references/
        └── {service}.md
```

### SKILL.md Format (Mandatory)

Every SKILL.md MUST start with YAML frontmatter. This is the **primary source of truth** for scan status.

```yaml
---
name: project-structure
description: "aipro directory layout, tech stack, module organization, build and run instructions. Consult this when you need to understand project structure, find where code lives, or determine how to build/run."
_scanned:
  commitHash: "abc123def"
  scannedAt: "2026-04-09T10:00:00Z"
  branch: "main"
---

# Project Structure

{Content — tables, lists, structured data}
```

**Frontmatter fields:**

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Lowercase, hyphen-separated. Same as parent dir name |
| `description` | Yes | 1-2 sentences for skill discovery and triggering. Min 5 chars |
| `_scanned.commitHash` | Yes | Git commit hash at scan time |
| `_scanned.scannedAt` | Yes | ISO 8601 timestamp |
| `_scanned.branch` | No | Git branch name |

**Total SKILL.md must be < 80 lines including frontmatter.**

### references/ Files

- Max 100 lines each
- Per-module (structure), per-endpoint (apis), per-service (external-calls)
- Plain markdown, no frontmatter required

### File Naming Rules

- API path `/api/payment/create` with POST → `POST-api-payment-create.md`
- Replace `/` with `-`, remove leading `-`, max 80 chars
- Module/service names → sanitized to lowercase alphanumeric + hyphens

## Scanner Agents

### Scanner Types

```typescript
export const SCANNER_TYPES = ['structure', 'apis', 'external-calls'] as const;
export type ScannerType = (typeof SCANNER_TYPES)[number];
```

### Scanner Session Configuration

Minimal SDK session — **no plugins, no MCP servers, no web-access**. Same as v1.

Scanner session uses `buildScannerSessionOptions()` which skips plugin/MCP injection.

### Scanner Prompts (unchanged from v1)

Each scanner receives a prompt from `scanner-prompts.ts` with:
- Exploration strategy (bash commands to run)
- Output file structure
- Shared rules (safety, file path format, no guessing, mandatory SKILL.md)

**Output language:** English. File paths stay in original form.

## Execution Flow

### Trigger Points

1. **First session** — `session.create` WebSocket handler → `projectScanner.scheduleScanIfNeeded(workspace)` (fire-and-forget, does not block)
2. **Every 6 hours** — Cron scheduler ticks, checks all registered workspaces

### Per-Workspace Scan Logic

```typescript
async scanWorkspace(workspace: string): Promise<void> {
  acquireLock(workspace);

  try {
    for (const scannerType of SCANNER_TYPES) {  // Serial
      const { needed, reason } = isScanNeeded(workspace, scannerType);
      if (!needed) {
        this.log.info(`Scanner [${scannerType}] skipped: ${reason}`);
        continue;
      }

      // Wait for idle (all active sessions done streaming)
      await waitUntilIdle(this.sessionManager);
      
      let success = false;
      for (let retry = 0; retry <= 2 && !success; retry++) {
        const result = await runScanner(scannerType, workspace);
        
        if (result.success && validateSkillMd(result.skillMdPath)) {
          success = true;
          writeManifest(workspace, scannerType, { status: 'done', filesWritten: result.filesWritten });
        } else {
          // Failure: delete directory, retry
          deleteScannerDir(workspace, scannerType);
          if (retry < 2) {
            this.log.warn(`Scanner [${scannerType}] failed, retry ${retry + 1}/2`);
          }
        }
      }
      
      if (!success) {
        writeManifest(workspace, scannerType, { status: 'error', retryCount: 3 });
      }
    }
  } finally {
    releaseLock(workspace);
  }
}
```

### isScanNeeded Logic

```typescript
function isScanNeeded(workspace: string, scannerType: ScannerType): { needed: boolean; reason: string } {
  const skillMdPath = path.join(workspace, '.claude', 'skills', `project-${scannerType}`, 'SKILL.md');

  // SKILL.md doesn't exist → needs scan
  if (!fs.existsSync(skillMdPath)) {
    return { needed: true, reason: 'SKILL.md not found' };
  }

  // Parse frontmatter to get scanned commit hash
  const frontmatter = parseFrontmatter(fs.readFileSync(skillMdPath, 'utf-8'));
  const scannedCommit = frontmatter._scanned?.commitHash;

  // Get current workspace commit hash
  let currentCommit: string;
  try {
    currentCommit = execSync('git rev-parse HEAD', { cwd: workspace, encoding: 'utf-8' }).trim();
  } catch {
    // Not a git repo → always scan
    return { needed: true, reason: 'not a git repo' };
  }

  // Commit changed → rescan
  if (scannedCommit !== currentCommit) {
    return { needed: true, reason: `commit changed: ${scannedCommit} → ${currentCommit}` };
  }

  return { needed: false, reason: 'up to date' };
}
```

### Frontmatter Validation

```typescript
function validateSkillMd(filePath: string): boolean {
  const content = fs.readFileSync(filePath, 'utf-8');
  
  // Must start with YAML frontmatter
  if (!content.startsWith('---')) return false;
  
  // Must have closing ---
  const endIdx = content.indexOf('---', 3);
  if (endIdx === -1) return false;
  
  // Parse YAML between the --- markers
  const yamlContent = content.slice(3, endIdx).trim();
  const frontmatter = parseYaml(yamlContent);
  
  // name field required
  if (!frontmatter.name) return false;
  
  // description field required, min 5 chars
  if (!frontmatter.description || frontmatter.description.length < 5) return false;
  
  // _scanned.commitHash required
  if (!frontmatter._scanned?.commitHash) return false;
  
  // Total file < 80 lines
  if (content.split('\n').length > 80) return false;
  
  return true;
}
```

### Scanner Timeout

Each scanner has a **10-minute timeout**. If it doesn't complete in 10 minutes:
1. Abort the session
2. Delete the scanner's output directory
3. Mark as error with retry

### Idle Protection

Before running any scanner, wait until all active user sessions finish streaming:

```typescript
async function waitUntilIdle(sessionManager: ClaudeSessionManager): Promise<void> {
  while (sessionManager.hasActiveStreams()) {
    await sleep(30_000); // Check every 30 seconds
  }
}
```

- `hasActiveStreams()` returns true if any session has an active stream
- Prevents scanner from competing with user sessions for Claude API tokens

## Manifest

Manifest is **secondary** — used for debugging and history. SKILL.md frontmatter is the source of truth.

```json
{
  "version": "2.0",
  "gitUrl": "https://github.com/smancode/aipro.git",
  "scanners": {
    "structure":     { "status": "done",    "filesWritten": 12 },
    "apis":          { "status": "error",   "filesWritten": 0,  "error": "...", "retryCount": 3 },
    "external-calls": { "status": "done",   "filesWritten": 8  }
  }
}
```

**Status values:** `done` | `error` | `pending`

## scanned-workspaces.json

```json
{
  "version": "1.0",
  "workspaces": [
    { "path": "/Users/x/projects/aipro", "lastScannedAt": "2026-04-09T10:00:00Z" }
  ]
}
```

- **Auto-register:** New session.create → workspace auto-added
- **No auto-delete:** If workspace dir no longer exists, skip during cron (don't remove entry)
- **Git-based detection:** Each SKILL.md frontmatter contains commit hash → compare against current HEAD

## Lock File

Location: `{workspace}/.claude/.scanning`

```json
{
  "pid": 12345,
  "startedAt": "2026-04-09T10:00:00Z",
  "scanners": ["structure", "apis", "external-calls"]
}
```

- Acquired before starting any scanner in a workspace
- Released after all scanners complete (success or failure)
- Stale lock (> 30 min) → deleted and rescan proceeds

## Files to Create/Modify

### New Files

- `server/capabilities/frontmatter-utils.ts` — `parseFrontmatter()`, `validateSkillMd()`, `parseYaml()`

### Modified Files

| File | Changes |
|------|---------|
| `server/capabilities/project-scanner.ts` | Serial execution, idle wait, per-scanner timeout (10min), retry (≤2), manifest per-scanner status, lock per workspace |
| `server/capabilities/scanner-prompts.ts` | Add `_scanned` fields to frontmatter templates, require commit hash injection |
| `server/capabilities/registry.ts` | (no changes needed — already reads skills dirs) |
| `server/index.ts` | Cron registration already done; verify 6-hour interval |
| `server/cron-scheduler.ts` | Verify scanner cron registered, confirm 6-hour tick |

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Scanner timeout (10 min) | Delete output dir, retry up to 2 times |
| SKILL.md invalid frontmatter | Delete output dir, retry up to 2 times |
| Scanner writes nothing (filesWritten=0) | Treat as failure, retry up to 2 times |
| All retries exhausted | Mark scanner as `error` in manifest, continue to next scanner |
| Workspace not git repo | Always scan (no commit hash to compare) |
| Lock already held | Skip this workspace, try next |
| Idle wait > 30 min | Proceed anyway (don't wait forever) |

## Out of Scope

- **Knowledge version migration** — v2 format; if format changes, delete and re-scan
- **Per-scanner git diff** — Only full rescan on any file change in workspace
- **Concurrent workspace scanning** — One workspace at a time (serial across workspaces too)

## Workflow: Team Member Opens Project

1. User opens workspace in Sman → session.create triggers `scheduleScanIfNeeded()`
2. `isScanNeeded()` reads each SKILL.md frontmatter `_scanned.commitHash`
3. If commit hash matches current HEAD → skip, skills already up-to-date
4. If different → scan only the scanners whose commit changed (or all 3 if unknown)
5. Scanned results are in `.claude/skills/` → user can git add + commit + push
6. Teammates pull → their SKILL.md already reflects the latest scan → no re-scan needed

## Relationship to User Profile

The scanner uses the **same idle-waiting pattern** as UserProfileManager:

- Profile update: waits for all sessions idle, then fires after 10-min accumulation
- Scanner: waits for all sessions idle, then fires on 6-hour cron tick

Both share the `sessionManager.hasActiveStreams()` check mechanism.
