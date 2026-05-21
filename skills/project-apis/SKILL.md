---
_scanned:
  commitHash: "353989234d641c959d8c0aa37aea150735c4ccd8"
  timestamp: "2025-05-21"
  mode: INCREMENTAL
  previousCommit: "c63e3fcf76ba9e8b362d9d73ebccab934d1d998d"
  changes: "59 files changed, 6176 insertions(+), 812 deletions(-)"
---

# Sman Project APIs Skill

## Overview

This skill provides a comprehensive catalog of all API endpoints in Sman, including WebSocket messages and REST routes. It focuses on endpoint signatures, parameters, and purposes without tracing deep call chains (degraded strategy).

**Architecture**:
- **Primary Protocol**: WebSocket (bi-directional real-time communication)
- **Secondary Protocol**: REST HTTP (for file serving and external integrations)
- **Authentication**: Bearer token for `/api/*` routes, session-based for WebSocket

---

## WebSocket API Catalog

### Achievement System APIs (NEW)

**Handler**: `server/achievement-ws-handler.ts` (50 lines)

#### `achievement.list`
Get full achievement summary including unlocked achievements, stats, streak, and level progression.

**Request**:
```typescript
{ type: 'achievement.list' }
```

**Response**:
```typescript
{
  type: 'achievement.data',
  achievements: AchievementView[],    // All achievements with progress
  stats: Record<string, string>,      // Raw stats (sessions, messages, tokens, etc.)
  streak: { current: number; longest: number },
  totalPoints: number,                // Weighted score
  level: Tier,                        // Current tier (bronze → eternal)
  levelProgress: {
    currentTier: Tier,
    currentPoints: number,
    nextTier: Tier | null,
    pointsNeeded: number,
    progressPercent: number
  },
  totalUnlocked: number,
  totalAchievements: number
}
```

---

#### `achievement.stats`
Get raw statistics without achievement details.

**Request**:
```typescript
{ type: 'achievement.stats' }
```

**Response**:
```typescript
{
  type: 'achievement.stats',
  stats: Record<string, string>       // Key-value pairs of metrics
}
```

**Stats Keys**:
- `total_sessions`, `total_messages`, `total_tokens`
- `total_cron_runs`, `total_smartpath_runs`, `total_skills_used`
- `total_code_views`, `total_git_ops`
- `bot_sessions_total`, `bot_messages_total`, `bot_count_total`
- `current_streak`, `longest_streak`, `last_active_date`

---

#### `achievement.leaderboard`
Upload current stats to Hub and fetch global leaderboard.

**Request**:
```typescript
{
  type: 'achievement.leaderboard',
  dimension?: string                  // Optional: sort by dimension (total, sessions, messages, etc.)
}
```

**Response**:
```typescript
{
  type: 'achievement.leaderboard',
  entries: LeaderboardEntry[],        // Sorted leaderboard entries
  dimension: string,                  // The dimension used for sorting
  isOnline: boolean,                  // Whether Hub is accessible
  clientId: string                    // Current agent's ID
}
```

**Behavior**:
1. Uploads local `dimensionScores` to Hub (`POST /api/achievement-report`)
2. Fetches leaderboard from Hub (`GET /api/achievement-leaderboard?dimension=xxx`)
3. Falls back to empty list if Hub is offline

**Integration**: Handler calls `AchievementEngine.uploadToLeaderboard()` and `AchievementEngine.fetchLeaderboard()`

---

### Smart Path APIs (MODIFIED - Enhanced Step Editing)

**Handler**: `server/index.ts` (lines 1961-2361), **Engine**: `server/smart-path-engine.ts` (917 lines)

#### Core CRUD Operations

##### `smartpath.list`
List all smart paths with next run time.

**Request**:
```typescript
{ type: 'smartpath.list' }
```

**Response**:
```typescript
{
  type: 'smartpath.list',
  paths: Array<{
    id: string
    name: string
    description?: string
    steps: SmartPathStep[]
    cronExpression?: string
    nextRunAt: string | null         // ISO timestamp or null
  }>
}
```

---

##### `smartpath.create`
Create a new smart path.

**Request**:
```typescript
{
  type: 'smartpath.create',
  path: {
    name: string
    description?: string
    steps: SmartPathStep[]
    cronExpression?: string
  }
}
```

**Response**:
```typescript
{
  type: 'smartpath.created',
  path: SmartPath                    // Created path with generated ID
}
```

---

##### `smartpath.update`
Update an existing smart path.

**Request**:
```typescript
{
  type: 'smartpath.update',
  pathId: string,
  updates: Partial<SmartPath>        // Fields to update
}
```

**Response**:
```typescript
{
  type: 'smartpath.updated',
  path: SmartPath,                   // Updated path with nextRunAt
  nextRunAt: string | null
}
```

---

##### `smartpath.delete`
Delete a smart path.

**Request**:
```typescript
{
  type: 'smartpath.delete',
  pathId: string
}
```

**Response**:
```typescript
{
  type: 'smartpath.deleted',
  pathId: string
}
```

---

#### Execution APIs

##### `smartpath.run`
Execute a smart path (legacy mode: all steps sequentially).

**Request**:
```typescript
{
  type: 'smartpath.run',
  pathId: string,
  workspace: string,
  args?: string                      // Optional user arguments
  useRefs?: boolean                  // Whether to use references
}
```

**Broadcasts During Execution**:
```typescript
// Step progress (streaming)
{
  type: 'smartpath.stepExecutionProgress',
  pathId: string,
  stepIndex: number,
  delta: string                      // Streaming content delta
}

// Step result
{
  type: 'smartpath.stepExecutionResult',
  pathId: string,
  stepIndex: number,
  result: string                     // Full step result
}

// Overall progress
{
  type: 'smartpath.progress',
  pathId: string,
  stepIndex: number,
  totalSteps: number,
  status: string
}
```

**Final Response**:
```typescript
{
  type: 'smartpath.running',
  pathId: string
}
```

**Completion Broadcasts**:
```typescript
// Success
{
  type: 'smartpath.completed',
  pathId: string,
  path: SmartPath,
  references: Reference[]
}

// Failure
{
  type: 'smartpath.failed',
  pathId: string,
  error: string
}
```

---

##### `smartpath.abort`
Abort a running smart path.

**Request**:
```typescript
{
  type: 'smartpath.abort',
  pathId: string
}
```

**Response**:
```typescript
{
  type: 'smartpath.aborted',
  pathId: string
}
```

---

#### Step-by-Step Execution APIs (NEW - Enhanced)

##### `smartpath.orchestrate`
Orchestration phase: analyze path and generate execution blueprint.

**Request**:
```typescript
{
  type: 'smartpath.orchestrate',
  pathId: string,
  workspace: string,
  args?: string,
  useRefs?: boolean
}
```

**Broadcasts**:
```typescript
{
  type: 'smartpath.stepExecutionProgress',
  pathId: string,
  stepIndex: -1,                     // -1 indicates orchestration phase
  delta: string                      // Orchestrator analysis output
}
```

**Response**:
```typescript
{
  type: 'smartpath.orchestrated',
  pathId: string,
  blueprint: PathBlueprint,          // Execution plan with revised inputs
  runId: string                      // New run ID
}
```

**PathBlueprint Structure**:
```typescript
{
  goal: string,                      // Global objective
  stepPlans: Array<{
    revisedInput: string,            // Clarified instruction
    roleDescription: string,         // Step's purpose in workflow
    expectedOutputs: string,         // What this step should produce
    dependenciesOnPrior: string      // What this step needs from previous steps
  }>,
  modifications: Array<{             // Only includes actual changes
    step: number,
    original: string,
    revised: string,
    reason: string
  }>
}
```

---

##### `smartpath.runStep`
Execute a single step with blueprint context.

**Request**:
```typescript
{
  type: 'smartpath.runStep',
  pathId: string,
  workspace: string,
  runId: string,
  blueprint: PathBlueprint,
  stepIndex: number,
  totalSteps: number,
  priorResults: string[],            // Results from previous steps
  args?: string,
  useRefs?: boolean,
  skills?: string[],                 // Workspace skills to use
  deliveryCheck?: string             // Optional delivery check criteria
}
```

**Broadcasts**:
```typescript
// Step progress (streaming)
{
  type: 'smartpath.stepExecutionProgress',
  pathId: string,
  stepIndex: number,
  delta: string
}
```

**Response**:
```typescript
{
  type: 'smartpath.stepExecutionResult',
  pathId: string,
  stepIndex: number,
  result: string,
  deliveryCheckPassed?: boolean,     // If delivery check was performed
  deliveryCheckReason?: string,      // Why it failed (if applicable)
  retried?: boolean                  // Whether auto-retry was triggered
}
```

**Behavior**:
1. Creates ephemeral session for step execution
2. Executes with blueprint context + prior results
3. If `deliveryCheck` provided, runs LLM-based validation
4. Auto-retries once if delivery check fails
5. Extracts `[REFERENCE:filename]` blocks and saves to `{workspace}/.sman/paths/{pathId}/references/`

---

##### `smartpath.finalize`
Complete a path execution: generate report + update run.md + mark as completed.

**Request**:
```typescript
{
  type: 'smartpath.finalize',
  pathId: string,
  workspace: string,
  runId: string,
  blueprint: PathBlueprint,
  stepResults: string[]
}
```

**Broadcasts**:
```typescript
{
  type: 'smartpath.completed',
  pathId: string,
  path: SmartPath,
  references: Reference[]            // Updated references list
}
```

**Behavior**:
1. Marks path status as `completed`
2. Creates report in `{workspace}/.sman/paths/{pathId}/reports/{runId}.md`
3. Updates run record in SQLite
4. Emits `smartpath_run` achievement event
5. Updates `references/run.md` with experience沉淀
6. Cleans up active run tracking

---

#### Guide & Reference APIs (NEW)

##### `smartpath.guideChat`
Interactive guide generation (initial confirmation or multi-turn refinement).

**Request**:
```typescript
{
  type: 'smartpath.guideChat',
  pathId: string,
  workspace: string,
  stepIndex: number,
  stepResult: string,
  userMessage?: string,              // Omit for initial, provide for follow-up
  sessionId?: string,                // Reuse existing session if provided
  pathName: string,
  stepInput: string,
  existingGuide?: string             // Previous guide content for optimization
}
```

**Broadcasts**:
```typescript
{
  type: 'smartpath.guideChat.delta',
  pathId: string,
  stepIndex: number,
  delta: string                      // Streaming guide content
}
```

**Response**:
```typescript
{
  type: 'smartpath.guideChat.completed',
  pathId: string,
  stepIndex: number,
  guideContent: string,              // Full generated guide
  sessionId: string                  // Session ID for potential follow-up
}
```

**Behavior**:
- **Initial**: Confirms step result + generates detailed操作指南
- **Follow-up**: Refines guide based on user feedback
- Uses Claude to generate actionable guide (max 10-line code blocks, focus on text explanation)

---

##### `smartpath.guideSave`
Save generated guide to `references/guide{stepIndex}.md`.

**Request**:
```typescript
{
  type: 'smartpath.guideSave',
  pathId: string,
  workspace: string,
  stepIndex: number,
  guideContent: string
}
```

**Response**:
```typescript
{
  type: 'smartpath.guideSaved',
  pathId: string,
  stepIndex: number,
  fileName: string                   // "guide{n}.md"
}
```

---

#### Run & Report APIs

##### `smartpath.runs`
List all runs for a path with associated reports.

**Request**:
```typescript
{
  type: 'smartpath.runs',
  pathId: string,
  workspace: string
}
```

**Response**:
```typescript
{
  type: 'smartpath.runs',
  pathId: string,
  runs: Array<{
    id: string
    status: 'running' | 'completed' | 'failed'
    startedAt: string
    finishedAt?: string
    reportFileName?: string
    errorMessage?: string
  }>,
  reports: Array<{                  // Available report files
    fileName: string
    createdAt: string
  }>
}
```

---

##### `smartpath.report`
Get report content.

**Request**:
```typescript
{
  type: 'smartpath.report',
  pathId: string,
  workspace: string,
  fileName: string                   // Report filename
}
```

**Response**:
```typescript
{
  type: 'smartpath.report',
  pathId: string,
  fileName: string,
  content: string                    // Full report markdown
}
```

---

##### `smartpath.references`
List all reference files for a path.

**Request**:
```typescript
{
  type: 'smartpath.references',
  pathId: string,
  workspace: string
}
```

**Response**:
```typescript
{
  type: 'smartpath.references',
  pathId: string,
  references: Array<{
    fileName: string
    createdAt: string
    size: number
  }>
}
```

---

##### `smartpath.reference.read`
Read a specific reference file content.

**Request**:
```typescript
{
  type: 'smartpath.reference.read',
  pathId: string,
  workspace: string,
  fileName: string
}
```

**Response**:
```typescript
{
  type: 'smartpath.reference.content',
  pathId: string,
  fileName: string,
  content: string
}
```

---

#### Legacy Step Generation API

##### `smartpath.generateStep`
Generate step content (legacy, likely for single-step generation).

**Request**:
```typescript
{
  type: 'smartpath.generateStep',
  pathId: string,
  workspace: string,
  stepIndex: number,
  userInput: string
}
```

**Response**:
```typescript
{
  type: 'smartpath.stepGenerated',
  payload: {
    generatedContent: string
  }
}
```

---

### Session Management APIs

**Handler**: `server/index.ts` (lines 1619-1758)

#### `session.create`
Create a new session for a workspace.

**Request**:
```typescript
{
  type: 'session.create',
  workspace: string,
  label?: string
}
```

**Response**:
```typescript
{
  type: 'session.created',
  session: Session
}
```

---

#### `session.list`
List all sessions (optionally filtered by workspace).

**Request**:
```typescript
{
  type: 'session.list',
  workspace?: string                 // Optional: filter by workspace
}
```

**Response**:
```typescript
{
  type: 'session.list',
  sessions: Session[]
}
```

---

#### `session.delete`
Delete a session.

**Request**:
```typescript
{
  type: 'session.delete',
  sessionId: string
}
```

**Response**:
```typescript
{
  type: 'session.deleted',
  sessionId: string
}
```

---

#### `session.history`
Get message history for a session.

**Request**:
```typescript
{
  type: 'session.history',
  sessionId: string
}
```

**Response**:
```typescript
{
  type: 'session.history',
  sessionId: string,
  messages: Message[]
}
```

---

#### `session.updateLabel`
Update session label.

**Request**:
```typescript
{
  type: 'session.updateLabel',
  sessionId: string,
  label: string
}
```

**Response**:
```typescript
{
  type: 'session.labelUpdated',
  sessionId: string,
  label: string
}
```

---

#### `session.preheat`
Preload a session (warm up caches, prepare context).

**Request**:
```typescript
{
  type: 'session.preheat',
  sessionId: string
}
```

**Response**:
```typescript
{
  type: 'session.preheated',
  sessionId: string
}
```

---

### Chat APIs

**Handler**: `server/index.ts` (lines 1761-1958)

#### `chat.send`
Send a message to a session.

**Request**:
```typescript
{
  type: 'chat.send',
  sessionId: string,
  message: string,
  files?: FileAttachment[]
}
```

**Broadcasts**:
```typescript
// Streaming response
{
  type: 'chat.delta',
  sessionId: string,
  delta: string
}

// Tool start
{
  type: 'chat.tool_start',
  sessionId: string,
  toolName: string,
  toolInput: any
}

// Tool done
{
  type: 'chat.tool_done',
  sessionId: string,
  toolName: string,
  toolOutput: any
}
```

**Response**:
```typescript
{
  type: 'chat.done',
  sessionId: string,
  message: Message
}
```

---

#### `chat.abort`
Abort a running chat request.

**Request**:
```typescript
{
  type: 'chat.abort',
  sessionId: string
}
```

**Response**:
```typescript
{
  type: 'chat.aborted',
  sessionId: string
}
```

---

#### `chat.answer_question`
Direct Q&A without session context.

**Request**:
```typescript
{
  type: 'chat.answer_question',
  question: string,
  workspace?: string
}
```

**Response**:
```typescript
{
  type: 'chat.answer',
  answer: string
}
```

---

### Settings APIs

**Handler**: `server/index.ts` (lines 2364-2589)

#### `settings.get`
Get current settings.

**Request**:
```typescript
{
  type: 'settings.get'
}
```

**Response**:
```typescript
{
  type: 'settings.data',
  settings: {
    llm: LlmConfig,
    webSearch: WebSearchConfig,
    chatbot: ChatbotConfig,
    auth: AuthConfig
  }
}
```

---

#### `settings.update`
Update settings.

**Request**:
```typescript
{
  type: 'settings.update',
  updates: Partial<Settings>
}
```

**Response**:
```typescript
{
  type: 'settings.updated',
  settings: Settings
}
```

---

#### `settings.testAndSave`
Test LLM configuration and save if valid.

**Request**:
```typescript
{
  type: 'settings.testAndSave',
  profile: LlmProfile
}
```

**Response**:
```typescript
{
  type: 'settings.testResult',
  success: boolean,
  error?: string
}
```

---

#### `settings.fetchModels`
Fetch available models from LLM provider.

**Request**:
```typescript
{
  type: 'settings.fetchModels',
  baseUrl: string,
  apiKey: string
}
```

**Response**:
```typescript
{
  type: 'settings.models',
  models: string[]
}
```

---

### Cron Task APIs

**Handler**: `server/index.ts` (lines 1759-1960)

#### `cron.create`
Create a new cron task.

**Request**:
```typescript
{
  type: 'cron.create',
  task: {
    name: string
    cronExpression: string
    workspace: string
    enabled: boolean
    userInput: string
  }
}
```

**Response**:
```typescript
{
  type: 'cron.created',
  task: CronTask
}
```

---

#### `cron.list`
List all cron tasks.

**Request**:
```typescript
{
  type: 'cron.list'
}
```

**Response**:
```typescript
{
  type: 'cron.list',
  tasks: CronTask[]
}
```

---

#### `cron.execute`
Manually trigger a cron task execution.

**Request**:
```typescript
{
  type: 'cron.execute',
  taskId: string
}
```

**Response**:
```typescript
{
  type: 'cron.executed',
  taskId: string
}
```

---

#### `cron.runs`
Get execution history for a task.

**Request**:
```typescript
{
  type: 'cron.runs',
  taskId: string
}
```

**Response**:
```typescript
{
  type: 'cron.runs',
  taskId: string,
  runs: Array<{
    id: string
    executedAt: string
    status: 'success' | 'failure'
    output: string
  }>
}
```

---

### Batch Task APIs

**Handler**: `server/index.ts` (lines 1961-2260)

#### `batch.create`
Create a new batch task.

**Request**:
```typescript
{
  type: 'batch.create',
  batch: {
    name: string
    workspace: string
    items: BatchItem[]
    template: string
  }
}
```

**Response**:
```typescript
{
  type: 'batch.created',
  batch: BatchTask
}
```

---

#### `batch.execute`
Execute a batch task.

**Request**:
```typescript
{
  type: 'batch.execute',
  batchId: string
}
```

**Response**:
```typescript
{
  type: 'batch.executed',
  batchId: string
}
```

---

#### `batch.list`
List all batch tasks.

**Request**:
```typescript
{
  type: 'batch.list'
}
```

**Response**:
```typescript
{
  type: 'batch.list',
  batches: BatchTask[]
}
```

---

### Git Operation APIs

**Handler**: `server/index.ts` (lines 2592-2950)

#### `git.status`
Get git status for workspace.

**Request**:
```typescript
{
  type: 'git.status',
  workspace: string
}
```

**Response**:
```typescript
{
  type: 'git.status',
  status: GitStatus
}
```

---

#### `git.diff`
Get git diff.

**Request**:
```typescript
{
  type: 'git.diff',
  workspace: string,
  file?: string                     // Optional: specific file
}
```

**Response**:
```typescript
{
  type: 'git.diff',
  diff: string
}
```

---

#### `git.commit`
Create a commit.

**Request**:
```typescript
{
  type: 'git.commit',
  workspace: string,
  message: string,
  files: string[]
}
```

**Response**:
```typescript
{
  type: 'git.committed',
  commit: GitCommit
}
```

---

#### `git.log`
Get commit history.

**Request**:
```typescript
{
  type: 'git.log',
  workspace: string,
  limit?: number,
  offset?: number
}
```

**Response**:
```typescript
{
  type: 'git.log',
  commits: GitCommit[]
}
```

---

#### `git.push`
Push commits to remote.

**Request**:
```typescript
{
  type: 'git.push',
  workspace: string,
  remote?: string,
  branch?: string
}
```

**Response**:
```typescript
{
  type: 'git.pushed',
  result: string
}
```

---

### Code Viewer APIs

**Handler**: `server/index.ts` (lines 2952-3127)

#### `code.listDir`
List directory contents.

**Request**:
```typescript
{
  type: 'code.listDir',
  workspace: string,
  path: string                      // Relative path from workspace root
}
```

**Response**:
```typescript
{
  type: 'code.dirList',
  entries: Array<{
    name: string
    path: string
    type: 'file' | 'directory'
    size?: number
  }>
}
```

---

#### `code.readFile`
Read file content.

**Request**:
```typescript
{
  type: 'code.readFile',
  workspace: string,
  path: string
}
```

**Response**:
```typescript
{
  type: 'code.fileContent',
  content: string
}
```

---

#### `code.searchFiles`
Search files by pattern.

**Request**:
```typescript
{
  type: 'code.searchFiles',
  workspace: string,
  pattern: string,
  path?: string                     // Optional: search within subdirectory
}
```

**Response**:
```typescript
{
  type: 'code.searchResults',
  results: Array<{
    path: string
    matches: Array<{
      line: number
      content: string
    }>
  }>
}
```

---

#### `code.searchSymbols`
Search code symbols (functions, classes, etc.).

**Request**:
```typescript
{
  type: 'code.searchSymbols',
  workspace: string,
  query: string
}
```

**Response**:
```typescript
{
  type: 'code.symbols',
  symbols: Array<{
    name: string
    kind: string
    path: string
    line: number
  }>
}
```

---

#### `code.saveFile`
Save file content.

**Request**:
```typescript
{
  type: 'code.saveFile',
  workspace: string,
  path: string,
  content: string
}
```

**Response**:
```typescript
{
  type: 'code.fileSaved',
  path: string
}
```

---

### Skills APIs

**Handler**: `server/index.ts` (lines 3129-3180)

#### `skills.list`
List all available global skills.

**Request**:
```typescript
{
  type: 'skills.list'
}
```

**Response**:
```typescript
{
  type: 'skills.list',
  skills: Skill[]
}
```

---

#### `skills.listProject`
List workspace-specific skills.

**Request**:
```typescript
{
  type: 'skills.listProject',
  workspace: string
}
```

**Response**:
```typescript
{
  type: 'skills.projectList',
  skills: Skill[]
}
```

---

### Chatbot APIs

**Handler**: `server/chatbot/` directory

#### `chatbot.listWorkspaceSkills`
List skills available in a workspace for chatbot.

**Request**:
```typescript
{
  type: 'chatbot.listWorkspaceSkills',
  workspace: string
}
```

**Response**:
```typescript
{
  type: 'chatbot.workspaceSkills',
  skills: string[]
}
```

---

#### `chatbot.getCollectFiles`
Get files to collect for chatbot context.

**Request**:
```typescript
{
  type: 'chatbot.getCollectFiles',
  workspace: string
}
```

**Response**:
```typescript
{
  type: 'chatbot.collectFiles',
  files: string[]
}
```

---

#### `chatbot.weixin.qr.request`
Request WeChat login QR code.

**Request**:
```typescript
{
  type: 'chatbot.weixin.qr.request'
}
```

**Response**:
```typescript
{
  type: 'chatbot.weixin.qr.data',
  qrCodeUrl: string,
  expiresIn: number
}
```

---

#### `chatbot.weixin.qr.poll`
Poll WeChat login status.

**Request**:
```typescript
{
  type: 'chatbot.weixin.qr.poll',
  qrId: string
}
```

**Response**:
```typescript
{
  type: 'chatbot.weixin.qr.status',
  status: 'pending' | 'scanned' | 'confirmed' | 'expired',
  userInfo?: WeChatUserInfo
}
```

---

### Hub APIs

**Handler**: `server/index.ts` (lines 3182-3250)

#### `hub:status`
Get Hub connection status.

**Request**:
```typescript
{
  type: 'hub:status'
}
```

**Response**:
```typescript
{
  type: 'hub:status',
  connected: boolean,
  hubUrl: string,
  lastPing?: string
}
```

---

#### `hub:query`
Query Hub for data.

**Request**:
```typescript
{
  type: 'hub:query',
  query: string,
  params?: Record<string, any>
}
```

**Response**:
```typescript
{
  type: 'hub:response',
  data: any
}
```

---

### Group/Team APIs

**Handler**: `server/index.ts` (lines 3252-3400)

#### `group.list`
List all groups.

**Request**:
```typescript
{
  type: 'group.list'
}
```

**Response**:
```typescript
{
  type: 'group.list',
  groups: Group[]
}
```

---

#### `group.create`
Create a new group.

**Request**:
```typescript
{
  type: 'group.create',
  group: {
    name: string
    description?: string
    members: string[]
  }
}
```

**Response**:
```typescript
{
  type: 'group.created',
  group: Group
}
```

---

#### `group.update`
Update a group.

**Request**:
```typescript
{
  type: 'group.update',
  groupId: string,
  updates: Partial<Group>
}
```

**Response**:
```typescript
{
  type: 'group.updated',
  group: Group
}
```

---

#### `group.delete`
Delete a group.

**Request**:
```typescript
{
  type: 'group.delete',
  groupId: string
}
```

**Response**:
```typescript
{
  type: 'group.deleted',
  groupId: string
}
```

---

#### `group-task.list`
List tasks in a group.

**Request**:
```typescript
{
  type: 'group-task.list',
  groupId: string
}
```

**Response**:
```typescript
{
  type: 'group-task.list',
  tasks: GroupTask[]
}
```

---

#### `group-task.create`
Create a new task in a group.

**Request**:
```typescript
{
  type: 'group-task.create',
  groupId: string,
  task: {
    title: string
    description?: string
    assignee?: string
    status: 'todo' | 'in_progress' | 'done'
  }
}
```

**Response**:
```typescript
{
  type: 'group-task.created',
  task: GroupTask
}
```

---

#### `group-task.dispatch`
Dispatch a task to an agent.

**Request**:
```typescript
{
  type: 'group-task.dispatch',
  taskId: string,
  agentId: string
}
```

**Response**:
```typescript
{
  type: 'group-task.dispatched',
  taskId: string,
  agentId: string
}
```

---

#### `group-task.delete`
Delete a task.

**Request**:
```typescript
{
  type: 'group-task.delete',
  taskId: string
}
```

**Response**:
```typescript
{
  type: 'group-task.deleted',
  taskId: string
}
```

---

#### `group-subtask.list`
List subtasks for a task.

**Request**:
```typescript
{
  type: 'group-subtask.list',
  taskId: string
}
```

**Response**:
```typescript
{
  type: 'group-subtask.list',
  subtasks: GroupSubTask[]
}
```

---

### Utility APIs

#### `feedback.submit`
Submit user feedback.

**Request**:
```typescript
{
  type: 'feedback.submit',
  feedback: {
    type: 'bug' | 'feature' | 'improvement'
    title: string
    description: string
    email?: string
  }
}
```

**Response**:
```typescript
{
  type: 'feedback.submitted',
  id: string
}
```

---

#### `error.report`
Report an error to the server.

**Request**:
```typescript
{
  type: 'error.report',
  error: {
    message: string
    stack?: string
    context?: Record<string, any>
  }
}
```

**Response**:
```typescript
{
  type: 'error.reported',
  id: string
}
```

---

## REST API Endpoints

### Authentication

#### `POST /api/auth/login`
Login and receive bearer token.

**Request**:
```typescript
{
  username: string,
  password: string
}
```

**Response**:
```typescript
{
  token: string,
  user: User
}
```

---

#### `POST /api/auth/logout`
Logout and invalidate token.

**Request**:
```typescript
{
  token: string
}
```

**Response**:
```typescript
{
  success: true
}
```

---

### File Serving

#### `GET /api/files/*`
Serve files from user data directory (requires auth).

**Headers**:
```
Authorization: Bearer <token>
```

**Response**: File content with appropriate MIME type

---

### Hub Integration APIs

#### `POST /api/achievement-report`
Upload achievement stats to Hub leaderboard.

**Request**:
```typescript
{
  clientId: string,
  totalPoints: number,
  level: string,
  totalUnlocked: number,
  dimensionScores: Record<string, number>  // 12 metrics
}
```

**Response**:
```typescript
{
  success: true,
  rank?: number
}
```

---

#### `GET /api/achievement-leaderboard?dimension=xxx`
Fetch leaderboard sorted by dimension.

**Query Params**:
- `dimension`: Sort dimension (total, sessions, messages, tokens, etc.)

**Response**:
```typescript
{
  entries: Array<{
    rank: number,
    agentId: string,
    agentName: string,
    totalPoints: number,
    level: string,
    dimensionScores: Record<string, number>
  }>,
  dimension: string
}
```

---

## WebSocket Connection

### Connection URL

**Development**:
```
ws://localhost:5880
```

**Production**:
```
ws://localhost:5880
```

### Connection Flow

1. **Handshake**: Client connects with `?token=<session_token>`
2. **Authentication**: Server validates token
3. **Message Loop**: Client sends `{ type: 'xxx', ... }`, Server responds
4. **Broadcasts**: Server broadcasts to all connected clients for events

### Message Format

**Request**:
```typescript
{
  type: string,           // Message type (determines handler)
  [key: string]: any      // Type-specific parameters
}
```

**Response**:
```typescript
{
  type: string,           // Response type (usually same as request + suffix)
  [key: string]: any      // Response data
}
```

**Error Response**:
```typescript
{
  type: 'error',
  message: string,
  code?: string
}
```

---

## Type Definitions

### SmartPathStep

```typescript
interface SmartPathStep {
  name?: string
  userInput: string
  deliveryCheck?: string    // LLM-based validation criteria
  skills?: string[]         // Workspace skills to use
}
```

### Session

```typescript
interface Session {
  id: string
  workspace: string
  label?: string
  createdAt: string
  updatedAt: string
  messageCount: number
}
```

### Message

```typescript
interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: string
  toolUse?: ToolUse[]
}
```

### CronTask

```typescript
interface CronTask {
  id: string
  name: string
  cronExpression: string
  workspace: string
  enabled: boolean
  userInput: string
  lastRunAt?: string
  nextRunAt?: string
}
```

### BatchTask

```typescript
interface BatchTask {
  id: string
  name: string
  workspace: string
  items: BatchItem[]
  template: string
  status: 'pending' | 'running' | 'completed' | 'failed'
  createdAt: string
}
```

### Reference

```typescript
interface Reference {
  fileName: string
  createdAt: string
  size: number
}
```

---

## Key Integration Points

### Achievement System Integration

**Event Emitters** (all features emit these):
- `session_created` → When a session with messages is created
- `message_sent` → When a user message is sent
- `token_used` → When tokens are consumed
- `cron_run` → When a cron task executes successfully
- `smartpath_run` → When a smart path completes
- `skill_used` → When a skill is invoked
- `code_viewed` → When code is viewed in the viewer
- `git_op` → When a Git operation is performed
- `bot_session_created` → When a bot session is created
- `bot_message_sent` → When a bot message is sent
- `bot_created` → When a bot is created

**Engine Processing**:
1. Event → `AchievementEngine.handleEvent()`
2. Update stats in SQLite
3. Recalculate weighted score
4. Check achievement unlock conditions
5. Broadcast `achievement.data` if new unlocks
6. Upload to Hub (if online)

### Smart Path Integration

**Execution Flow** (new step-by-step mode):
1. `smartpath.orchestrate` → Generate blueprint
2. `smartpath.runStep` (loop for each step) → Execute with context
3. `smartpath.finalize` → Generate report + update run.md

**Guide Generation Flow**:
1. `smartpath.runStep` completes
2. User requests guide via `smartpath.guideChat`
3. LLM generates actionable guide
4. User saves via `smartpath.guideSave`
5. Next execution uses saved guide (from `references/guide{n}.md`)

### Session Management Integration

**Message Queueing**:
- SDK doesn't support interrupting running turns
- Backend uses `await streamDone` to serialize messages per session
- Prevents concurrent message processing

### Hub Integration

**Leaderboard Sync**:
- Desktop uploads stats to Hub on `achievement.leaderboard`
- Hub aggregates across all users
- Desktop fetches sorted leaderboard by dimension
- Falls back gracefully if Hub is offline

---

## Common Patterns

### Error Handling

**Client-Side**:
```typescript
ws.send(JSON.stringify({
  type: 'chat.send',
  sessionId: 'xxx',
  message: 'hello'
}));

ws.addEventListener('message', (event) => {
  const msg = JSON.parse(event.data);
  if (msg.type === 'error') {
    console.error('Server error:', msg.message);
  }
});
```

**Server-Side**:
```typescript
try {
  // ... handler logic
} catch (err) {
  ws.send(JSON.stringify({
    type: 'error',
    message: err instanceof Error ? err.message : String(err)
  }));
}
```

### Streaming Responses

**Pattern** (used in chat, smart paths, guide chat):
```typescript
// Server sends multiple deltas
ws.send(JSON.stringify({
  type: 'chat.delta',
  delta: 'Hello'
}));

ws.send(JSON.stringify({
  type: 'chat.delta',
  delta: ' world'
}));

// Final message signals completion
ws.send(JSON.stringify({
  type: 'chat.done',
  message: fullMessage
}));
```

**Client Handling**:
```typescript
let buffer = '';
ws.addEventListener('message', (event) => {
  const msg = JSON.parse(event.data);
  if (msg.type === 'chat.delta') {
    buffer += msg.delta;
    updateUI(buffer);  // Progressive update
  } else if (msg.type === 'chat.done') {
    finalizeUI(msg.message);
  }
});
```

### Broadcast Messages

**Pattern** (used for real-time updates):
```typescript
// Server broadcasts to ALL connected clients
function broadcast(payload: string) {
  wss.clients.forEach(client => {
    if (client.readyState === WebSocket.OPEN) {
      client.send(payload);
    }
  });
}

// Usage
broadcast(JSON.stringify({
  type: 'achievement.unlocked',
  achievementId: 'first_session',
  tier: 'bronze'
}));
```

---

## Migration Notes (Since Last Scan)

### What's NEW

1. **Achievement System APIs** (3 WebSocket endpoints)
   - `achievement.list` - Full achievement summary
   - `achievement.stats` - Raw statistics
   - `achievement.leaderboard` - Hub leaderboard integration

2. **Smart Path APIs** (4 new step-by-step execution endpoints)
   - `smartpath.orchestrate` - Blueprint generation
   - `smartpath.runStep` - Single step execution with delivery check
   - `smartpath.finalize` - Report generation + run.md update
   - `smartpath.guideChat` - Interactive guide generation
   - `smartpath.guideSave` - Save guide to references

3. **Hub REST APIs** (2 endpoints)
   - `POST /api/achievement-report` - Upload stats
   - `GET /api/achievement-leaderboard` - Fetch leaderboard

### What's MODIFIED

1. **Smart Path Execution**: Enhanced with step-by-step mode (orchestrate → runStep → finalize)
2. **Smart Path Guides**: Added interactive guide chat + save functionality
3. **Smart Path References**: Enhanced to support guide files + temp file rules

### What's UNCHANGED

- All other WebSocket APIs (chat, session, cron, batch, git, code, skills, chatbot, hub, groups)
- Authentication flow
- File serving endpoints
- WebSocket protocol and message format

---

## Quick Reference

### Finding an API

| I want to... | API |
|--------------|-----|
| Get achievements | `achievement.list` |
| Get leaderboard | `achievement.leaderboard` |
| Execute path step-by-step | `orchestrate` → `runStep` → `finalize` |
| Generate step guide | `smartpath.guideChat` → `smartpath.guideSave` |
| Send chat message | `chat.send` |
| Manage sessions | `session.*` |
| Schedule cron task | `cron.create` |
| Execute batch task | `batch.execute` |
| Git operations | `git.*` |
| Browse code | `code.*` |
| List skills | `skills.list` |
| Manage groups | `group.*` |

### API Handler Locations

| Feature | Handler File | Lines |
|---------|--------------|-------|
| Achievement | `server/achievement-ws-handler.ts` | 1-50 |
| Smart Path | `server/index.ts` | 1961-2361 |
| Session | `server/index.ts` | 1619-1758 |
| Chat | `server/index.ts` | 1761-1958 |
| Settings | `server/index.ts` | 2364-2589 |
| Cron | `server/index.ts` | 1759-1960 |
| Batch | `server/index.ts` | 1961-2260 |
| Git | `server/index.ts` | 2592-2950 |
| Code Viewer | `server/index.ts` | 2952-3127 |
| Skills | `server/index.ts` | 3129-3180 |
| Chatbot | `server/chatbot/*.ts` | - |
| Hub | `server/index.ts` | 3182-3250 |
| Groups | `server/index.ts` | 3252-3400 |

---

## Summary

**Since the last scan (c63e3fcf → 35398923)**, Sman's API surface has expanded with:

1. **Achievement System**: 3 new WebSocket APIs + 2 Hub REST endpoints for gamification features
2. **Enhanced Smart Paths**: 5 new APIs for step-by-step execution, guide generation, and delivery checks
3. **Hub Integration**: REST APIs for leaderboard sync (upload + fetch)

**Total API Count**: ~100 WebSocket message types + ~5 REST endpoints

**Architecture**: All new APIs follow existing patterns (WebSocket-first, bidirectional streaming, broadcast notifications).

**Degraded Strategy**: This scan focused on endpoint signatures and purposes. Deep call chain tracing was skipped for efficiency.
