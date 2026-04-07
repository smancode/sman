# Project Knowledge Scanner Design

**Date:** 2026-04-07
**Status:** Draft

## Problem

When developers open a business system project in Sman, Claude has no prior knowledge of the project structure, APIs, or external dependencies. Every session starts from scratch — Claude must re-explore the codebase to understand directory layout, find entry points, discover API endpoints, and identify external service calls. This wastes tokens and time, especially for large projects (100K+ lines).

## Solution

Automatic project knowledge scanning that runs:
1. **First time** a project directory is opened → full scan in background
2. **Nightly** (3 AM) via cron → re-scan if git commit hash changed

Scan results are stored as lightweight markdown files in `{workspace}/.claude/knowledge/`, organized as directory-index style: overview files are always loaded (<1KB each), detail files are read on demand by Claude.

## Design Principles

1. **Preprocessing, not precision analysis** — Extract facts directly from code, don't guess
2. **Framework + global view** — Structure-level facts (paths, signatures, deps) must be accurate; semantic descriptions (business flow, module purpose) are directional
3. **Not exhaustive** — Implementation details, return types, business logic are left for Claude to discover at coding time
4. **Incremental disclosure** — Overview files always loaded, detail files read on demand
5. **Knowledge follows the repo** — Stored in `{workspace}/.claude/knowledge/`, shared via git

## Output Structure

```
{workspace}/.claude/knowledge/
├── manifest.json                     ← Scan metadata
├── structure/
│   ├── overview.md                   ← Always loaded (<1KB)
│   └── modules/                      ← Read on demand
│       ├── {module-name}.md
│       └── ...
├── apis/
│   ├── overview.md                   ← Always loaded (<1KB)
│   └── endpoints/                    ← Read on demand
│       ├── {METHOD}-{path-slug}.md
│       └── ...
└── external-calls/
    ├── overview.md                   ← Always loaded (<1KB)
    └── services/                     ← Read on demand
        ├── {service-name}.md
        └── ...
```

**File name sanitization rule:** API path `/api/payment/create` → `POST-api-payment-create.md`. Replace `/` with `-`, remove leading `-`, truncate to max 80 chars.

### manifest.json

```json
{
  "version": "1.0",
  "gitUrl": "git@corp:payment-service.git",
  "commitHash": "abc123def",
  "branch": "master",
  "scannedAt": "2026-04-07T10:00:00Z",
  "scanners": {
    "structure": { "status": "done", "modulesCount": 12, "filesWritten": 13 },
    "apis": { "status": "done", "endpointsCount": 23, "filesWritten": 24 },
    "external-calls": { "status": "done", "servicesCount": 5, "filesWritten": 6 }
  }
}
```

**gitUrl** obtained via `git -C {workspace} remote get-url origin` in the orchestrator (not in scanner agents). If not a git repo, set to `null`. If multiple remotes, use `origin`. Strip embedded credentials from HTTPS URLs.

## Scanner Agents

3 parallel Scanner Agents, each an independent Claude SDK session created via `createSessionWithId()`.

### Scanner Session Configuration

Each scanner session is a minimal SDK session — **no plugins, no MCP servers, no web-access**. Only has Read, Bash, Glob, Grep, Write tools (standard Claude Code tools).

> **Implementation note:** `sendMessageForCron()` internally calls `buildSessionOptions()` which loads plugins and MCP servers. Scanner sessions should use a separate creation path or a flag to skip plugin/MCP injection (e.g., `isScanner` flag checked in `buildSessionOptions`).

```typescript
// In project-scanner.ts
const scannerSessionId = `scanner-${scannerType}-${Date.now()}`;
this.sessionManager.createSessionWithId(workspace, scannerSessionId, /* isCron */ true);

// Send scanner prompt as the first message
await this.sessionManager.sendMessageForCron(
  scannerSessionId,
  scannerPrompt,
  abortController,
  activityCallback,
);
```

**Session lifecycle:** Scanner sessions use the same idle cleanup mechanism as cron sessions (30 min timeout). After scanner completes, the `done` event triggers normal cleanup via `closeV2Session()`.

### Scanner 1: Structure

**Focus:** Technology stack, directory layout, modules, startup method

**Output:**
- `structure/overview.md` — Tech stack + directory tree + module list + how to build/run
- `structure/modules/{name}.md` — Per module: purpose, key files, dependencies

**Exploration commands:**
```bash
# Tech stack
cat package.json pom.xml build.gradle go.mod Cargo.toml pyproject.toml 2>/dev/null | head -100

# Directory structure
find . -type d -not -path '*/node_modules/*' -not -path '*/.git/*' -not -path '*/target/*' | head -50

# Entry points
ls src/index.* src/main.* app.py main.go cmd/ 2>/dev/null

# Build/run scripts
cat Makefile Dockerfile docker-compose.yml README.md 2>/dev/null | head -50
```

### Scanner 2: API

**Focus:** External-facing API endpoints, request/response signatures, business flow

**Output:**
- `apis/overview.md` — Endpoint table (method, path, description, detail file)
- `apis/endpoints/{METHOD}-{path-slug}.md` — Per endpoint: signature, parameters, business flow summary, called services

**Exploration commands:**
```bash
# REST controllers/routes
grep -rn "@RestController\|@Controller\|@RequestMapping\|@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping\|router\.\(get\|post\|put\|delete\)\|app\.\(get\|post\|put\|delete\)" --include="*.java" --include="*.ts" --include="*.js" --include="*.py" --include="*.go" | head -100

# GraphQL
grep -rn "type Query\|type Mutation\|@Query\|@Mutation" --include="*.graphql" --include="*.java" --include="*.ts" | head -50

# gRPC
find . -name "*.proto" | head -20
```

### Scanner 3: External Calls

**Focus:** Calls to external services, databases, message queues

**Output:**
- `external-calls/overview.md` — External service table (service name, type, purpose, detail file)
- `external-calls/services/{name}.md` — Per service: call method, config source, call location in code

**Exploration commands:**
```bash
# HTTP client calls
grep -rn "RestTemplate\|WebClient\|FeignClient\|fetch(\|axios\|http\.\(get\|post\)\|requests\.\(get\|post\)" --include="*.java" --include="*.ts" --include="*.js" --include="*.py" --include="*.go" | head -100

# Database connections
grep -rn "DataSource\|@Repository\|@Entity\|SqlConnection\|mongoose\|prisma\|sqlalchemy" --include="*.java" --include="*.ts" --include="*.py" | head -50

# Message queues
grep -rn "@RabbitListener\|@KafkaListener\|RabbitTemplate\|KafkaTemplate\|amqp\|kafka\|redis.*pub" --include="*.java" --include="*.ts" --include="*.py" | head -50
```

## Scanner Agent Prompt Principles

Each scanner agent receives a system prompt with these rules:

1. **Extract facts from code** — Don't invent, don't guess. If uncertain, mark with `⚠️ 待验证`
2. **File paths must be exact** — Always use backtick format: `src/services/PaymentService.java`
3. **Never read sensitive files** — `.env`, `credentials.*`, `*.key`, `*.pem`, etc. Note existence only
4. **overview.md < 50 lines** — Table format, scannable at a glance
5. **Detail files < 100 lines** — Key facts only, not implementation
6. **Write files directly** — Don't return content to orchestrator
7. **Code is the source of truth** — "Your output is a preprocessing aid. Claude will verify against actual code when working. Prioritize accuracy of file paths and signatures over completeness of descriptions."
8. **Output language: English** — Save tokens in system prompt. File paths and code references stay in original form.

## Trigger Logic

### New Session (First Time Only)

**Trigger point:** `server/index.ts` → `session.create` WebSocket handler, **after** `sessionManager.createSession(workspace)` succeeds and **before** sending `session.created` response to client.

```
// In server/index.ts, session.create handler (after line ~437)
const sessionId = sessionManager.createSession(workspace);

// Check if knowledge scan needed (fire-and-forget, does not block)
projectScanner.scheduleScanIfNeeded(workspace).catch(() => {});

// Continue to send session.created response
```

**Detection logic** (in `project-scanner.ts`):

```typescript
async scheduleScanIfNeeded(workspace: string): Promise<void> {
  const manifestPath = path.join(workspace, '.claude', 'knowledge', 'manifest.json');

  // Already scanned → skip
  if (fs.existsSync(manifestPath)) return;

  // Lock file exists → another scan in progress → skip
  if (fs.existsSync(path.join(workspace, '.claude', 'knowledge', '.scanning'))) return;

  // Fire-and-forget scan
  this.scan(workspace);
}
```

**Does NOT block session creation.** User can start chatting immediately. Knowledge becomes available in the **next session** for this workspace (or in the current session if the scan finishes before the user asks a relevant question — `buildSystemPromptAppend` reads knowledge files at session creation time).

### Nightly Cron (3 AM)

**Workspace discovery:** Scan `~/.sman/scanned-workspaces.json` — a simple registry file updated each time a workspace is scanned.

```json
{
  "version": "1.0",
  "workspaces": [
    { "path": "/Users/x/projects/payment-service", "lastScannedAt": "2026-04-07T10:00:00Z" },
    { "path": "/Users/x/projects/order-service", "lastScannedAt": "2026-04-06T03:00:00Z" }
  ]
}
```

**Nightly logic** (registered as a built-in cron task in `cron-scheduler.ts`):

```
For each workspace in scanned-workspaces.json:
  1. Check workspace path still exists
  2. git -C {workspace} rev-parse HEAD → local hash
  3. Compare with manifest.json commitHash
  4. CHANGED → Re-scan (same 3 parallel agents)
  5. UNCHANGED → Skip
  6. NOT a git repo → Re-scan if manifest older than 7 days
```

## Integration Points

### New Files

```
server/capabilities/
├── project-scanner.ts          ← Orchestrator: check manifest, spawn agents, write manifest
├── scanner-prompts.ts          ← 3 scanner system prompts + shared prompt principles
└── knowledge-loader.ts         ← Read overview.md files, return string for system prompt
```

### Modified Files

```
server/index.ts                 ← Trigger scan after session.create
server/claude-session.ts        ← Call knowledgeLoader in buildSystemPromptAppend()
server/cron-scheduler.ts        ← Register built-in nightly knowledge refresh
```

### Session System Prompt Injection

**Where:** `buildSystemPromptAppend()` in `claude-session.ts` (line 142). Add a call to `knowledgeLoader.loadOverviews(workspace)` at the end of the method.

```typescript
// At the end of buildSystemPromptAppend()
private buildSystemPromptAppend(workspace: string): string {
  let prompt = `...existing sections...`;

  // Append project knowledge if available
  const knowledgeContent = loadKnowledgeOverviews(workspace);
  if (knowledgeContent) {
    prompt += `\n\n## Project Knowledge\n\n${knowledgeContent}`;
  }

  return prompt;
}
```

**`knowledge-loader.ts` implementation:**

```typescript
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

**Timing:** This reads from disk at session creation time. If the scan hasn't finished yet (first session), `manifest.json` doesn't exist → returns null → no knowledge section. Next session created for this workspace will have knowledge.

## Concurrency Control

**Lock file:** `{workspace}/.claude/knowledge/.scanning`

```json
{
  "pid": 12345,
  "startedAt": "2026-04-07T10:00:00Z",
  "scanners": ["structure", "apis", "external-calls"]
}
```

**Protocol:**
1. Before scan: check `.scanning` exists. If yes, check PID alive (`process.kill(pid, 0)`) and not stale (>30 min). If stale, delete and proceed.
2. Start scan: write `.scanning` with current PID + timestamp.
3. Each scanner completes: update `.scanning` progress.
4. All done: write `manifest.json`, delete `.scanning`.
5. On crash: `.scanning` remains. Next scan attempt detects stale lock and cleans up.

## Error Handling

- **Scan fails:** Write manifest.json with `status: "error"` for failed scanners. Don't block session.
- **Partial scan:** If 2/3 scanners succeed, use what's available. Mark failed scanner in manifest.
- **Concurrent scans:** Lock file (`.claude/knowledge/.scanning`) with PID + stale detection.
- **Large projects:** Scanner agents have their own context limits. For 1M+ line projects, scanners may miss some files — acceptable.
- **API key not configured:** `sendMessageForCron` will fail. Catch and write manifest with `status: "error"`, log warning.
- **Workspace not readable:** `fs.existsSync` check before scan. Skip with warning.
- **Git not available:** Nightly cron `git rev-parse` failure → catch, skip with warning. Non-git projects re-scan based on age (7 days).
- **Scanner timeout:** Scanner sessions are cron sessions, subject to existing 30-min idle timeout. This is sufficient for most projects.

## Cost Considerations

- Each scanner is a Claude SDK session consuming API tokens.
- Estimated: ~5K-20K input tokens + ~2K-5K output tokens per scanner (project size dependent).
- Total per scan: ~15K-60K input tokens, ~6K-15K output tokens (3 scanners combined).
- For small projects (<1000 lines): scanners will finish quickly with minimal tokens.
- No special handling needed — the cost is amortized over many sessions that benefit from the knowledge.

## Out of Scope

- **Implementation details** — Method bodies, return types, field values
- **Code quality analysis** — Tech debt, conventions, testing patterns
- **Runtime behavior** — Performance, error patterns
- **Cross-service call chains** — Only direct external calls within the project
- **Database schema details** — Only note which databases are used, not table structures
- **Knowledge version migration** — v1.0 format; if format changes, delete and re-scan

These are left for Claude to discover when actually coding, using its existing tools (Read, Grep, LSP).
