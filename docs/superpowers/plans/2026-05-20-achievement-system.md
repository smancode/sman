# Achievement System Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a complete achievement system for Sman with ~100 achievements across 6 dimensions, real-time unlock notifications, history backfill, and a global leaderboard via sman-server.

**Architecture:** Event-driven — business modules emit achievement events via a lightweight EventEmitter bus; AchievementEngine subscribes, updates SQLite stats, checks thresholds, unlocks achievements, and broadcasts to clients via WebSocket. On first startup, `recalcStatsFromDB()` backfills all stats from existing data (including soft-deleted sessions).

**Tech Stack:** Node.js EventEmitter, better-sqlite3, Express WebSocket (ws), React + Zustand + Radix UI + Tailwind, PSK encryption (sman-server)

**Database decision:** Achievement tables live in the shared `~/.sman/sman.db` (same as sessions, messages, etc.). This simplifies `recalcStatsFromDB()` — no cross-database queries needed. The `AchievementStore` constructor takes the same `dbPath` as `SessionStore`.

**Spec:** `docs/superpowers/specs/2026-05-20-achievement-system-design.md`

**Two repos involved:**
- `/Users/nasakim/projects/sman` — client app (achievement engine, UI, events)
- `/Users/nasakim/projects/sman-server` — server (leaderboard API)

---

## Chunk 1: Foundation — Event Bus, Store, Engine Core

### Task 1: Create achievement event bus

**Files:**
- Create: `server/achievement-events.ts`

- [ ] **Step 1: Create the event bus module**

```typescript
// server/achievement-events.ts
import { EventEmitter } from 'events';

const achievementEmitter = new EventEmitter();

export type AchievementEventType =
  | 'session_created' | 'message_sent' | 'message_done'
  | 'cron_executed' | 'batch_item_completed' | 'batch_completed'
  | 'smartpath_run' | 'stardom_collab'
  | 'token_used' | 'workspace_added' | 'skill_used'
  | 'code_viewed' | 'git_operation'
  | 'error_occurred' | 'day_active'
  | 'bot_session_created' | 'bot_message_sent';

export interface AchievementEvent {
  type: AchievementEventType;
  data: Record<string, any>;
}

export function emitAchievementEvent(event: AchievementEvent): void {
  achievementEmitter.emit('achievement', event);
}

export function onAchievementEvent(handler: (event: AchievementEvent) => void): void {
  achievementEmitter.on('achievement', handler);
}

export function removeAllAchievementListeners(): void {
  achievementEmitter.removeAllListeners('achievement');
}
```

- [ ] **Step 2: Commit**

```bash
git add server/achievement-events.ts
git commit -m "feat(achievement): add achievement event bus"
```

---

### Task 2: Create achievement store (SQLite)

**Files:**
- Create: `server/achievement-store.ts`

- [ ] **Step 1: Create the store with table schemas and CRUD**

Follow the same pattern as `server/cron-task-store.ts`:
- Constructor takes `dbPath: string`
- Uses `betterSqlite3` ESM interop pattern
- `init()` creates tables with `CREATE TABLE IF NOT EXISTS`
- Post-init pragmas: `journal_mode = WAL`, `foreign_keys = ON`

Tables to create:
1. `achievement_progress` — per-achievement current value and unlock status
2. `achievement_stats` — key-value global counters
3. `achievement_streaks` — single-row table for streak tracking
4. `achievement_board` — leaderboard entries (local + synced)

Key methods:
- `getProgress(achievementId: string): AchievementProgress | undefined`
- `setProgress(achievementId: string, value: number): void`
- `unlock(achievementId: string): void` — sets `unlocked_at = now`
- `getAllProgress(): AchievementProgress[]`
- `getStat(key: string): string | undefined`
- `setStat(key: string, value: string): void`
- `getAllStats(): Record<string, string>`
- `getStreak(): { current: number; longest: number; lastActiveDate: string | null }`
- `updateStreak(today: string): { current: number; longest: number }` — handles increment/reset logic
- `getBoard(): AchievementBoardEntry[]`
- `upsertBoardEntry(entry: AchievementBoardEntry): void`
- `close(): void`

```typescript
// server/achievement-store.ts
import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
import { createLogger, type Logger } from './utils/logger.js';

// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;

export interface AchievementProgress {
  achievementId: string;
  currentValue: number;
  unlockedAt: string | null;
  notifiedAt: string | null;
}

export interface StreakData {
  currentStreak: number;
  longestStreak: number;
  lastActiveDate: string | null;
}

export interface AchievementBoardEntry {
  agentId: string;
  agentName: string;
  totalUnlocked: number;
  totalPoints: number;
  tierCounts: string;
  dimensionScores: string;
  lastSynced: string;
}

export class AchievementStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('AchievementStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS achievement_progress (
        achievement_id TEXT PRIMARY KEY,
        current_value INTEGER DEFAULT 0,
        unlocked_at TEXT,
        notified_at TEXT
      );

      CREATE TABLE IF NOT EXISTS achievement_stats (
        key TEXT PRIMARY KEY,
        value TEXT,
        updated_at TEXT
      );

      CREATE TABLE IF NOT EXISTS achievement_streaks (
        id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
        current_streak INTEGER DEFAULT 0,
        longest_streak INTEGER DEFAULT 0,
        last_active_date TEXT
      );

      CREATE TABLE IF NOT EXISTS achievement_board (
        agent_id TEXT PRIMARY KEY,
        agent_name TEXT,
        total_unlocked INTEGER DEFAULT 0,
        total_points INTEGER DEFAULT 0,
        tier_counts TEXT,
        dimension_scores TEXT,
        last_synced TEXT
      );
    `);

    // Ensure streaks row exists
    this.db.exec(`
      INSERT OR IGNORE INTO achievement_streaks (id, current_streak, longest_streak)
      VALUES (1, 0, 0);
    `);

    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
  }

  getProgress(achievementId: string): AchievementProgress | undefined {
    return this.db.prepare('SELECT achievement_id as achievementId, current_value as currentValue, unlocked_at as unlockedAt, notified_at as notifiedAt FROM achievement_progress WHERE achievement_id = ?').get(achievementId) as AchievementProgress | undefined;
  }

  setProgress(achievementId: string, value: number): void {
    const now = new Date().toISOString();
    this.db.prepare(
      'INSERT INTO achievement_progress (achievement_id, current_value, unlocked_at) VALUES (?, ?, NULL) ON CONFLICT(achievement_id) DO UPDATE SET current_value = ?, unlocked_at = unlocked_at'
    ).run(achievementId, value, value);
  }

  unlock(achievementId: string): void {
    const now = new Date().toISOString();
    this.db.prepare(
      'INSERT INTO achievement_progress (achievement_id, current_value, unlocked_at) VALUES (?, 0, ?) ON CONFLICT(achievement_id) DO UPDATE SET unlocked_at = ?'
    ).run(achievementId, now, now);
  }

  getAllProgress(): AchievementProgress[] {
    return this.db.prepare('SELECT achievement_id as achievementId, current_value as currentValue, unlocked_at as unlockedAt, notified_at as notifiedAt FROM achievement_progress').all() as AchievementProgress[];
  }

  getStat(key: string): string | undefined {
    const row = this.db.prepare('SELECT value FROM achievement_stats WHERE key = ?').get(key) as { value: string } | undefined;
    return row?.value;
  }

  setStat(key: string, value: string): void {
    const now = new Date().toISOString();
    this.db.prepare(
      'INSERT INTO achievement_stats (key, value, updated_at) VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = ?'
    ).run(key, value, now, value, now);
  }

  getAllStats(): Record<string, string> {
    const rows = this.db.prepare('SELECT key, value FROM achievement_stats').all() as { key: string; value: string }[];
    const result: Record<string, string> = {};
    for (const row of rows) {
      result[row.key] = row.value;
    }
    return result;
  }

  getStreak(): StreakData {
    const row = this.db.prepare('SELECT current_streak as currentStreak, longest_streak as longestStreak, last_active_date as lastActiveDate FROM achievement_streaks WHERE id = 1').get() as StreakData;
    return row;
  }

  updateStreak(today: string): { current: number; longest: number } {
    const streak = this.getStreak();
    const lastDate = streak.lastActiveDate;

    if (lastDate === today) {
      return { current: streak.currentStreak, longest: streak.longestStreak };
    }

    let newCurrent: number;
    if (lastDate) {
      const last = new Date(lastDate);
      const todayDate = new Date(today);
      const diffDays = Math.floor((todayDate.getTime() - last.getTime()) / (1000 * 60 * 60 * 24));
      newCurrent = diffDays === 1 ? streak.currentStreak + 1 : 1;
    } else {
      newCurrent = 1;
    }

    const newLongest = Math.max(streak.longestStreak, newCurrent);
    this.db.prepare(
      'UPDATE achievement_streaks SET current_streak = ?, longest_streak = ?, last_active_date = ? WHERE id = 1'
    ).run(newCurrent, newLongest, today);

    return { current: newCurrent, longest: newLongest };
  }

  getBoard(): AchievementBoardEntry[] {
    return this.db.prepare(
      'SELECT agent_id as agentId, agent_name as agentName, total_unlocked as totalUnlocked, total_points as totalPoints, tier_counts as tierCounts, dimension_scores as dimensionScores, last_synced as lastSynced FROM achievement_board ORDER BY total_points DESC'
    ).all() as AchievementBoardEntry[];
  }

  upsertBoardEntry(entry: AchievementBoardEntry): void {
    this.db.prepare(
      `INSERT INTO achievement_board (agent_id, agent_name, total_unlocked, total_points, tier_counts, dimension_scores, last_synced)
       VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(agent_id) DO UPDATE SET
         agent_name = excluded.agent_name,
         total_unlocked = excluded.total_unlocked,
         total_points = excluded.total_points,
         tier_counts = excluded.tier_counts,
         dimension_scores = excluded.dimension_scores,
         last_synced = excluded.last_synced`
    ).run(entry.agentId, entry.agentName, entry.totalUnlocked, entry.totalPoints, entry.tierCounts, entry.dimensionScores, entry.lastSynced);
  }

  getDatabase(): Database {
    return this.db;
  }

  close(): void {
    this.db.close();
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add server/achievement-store.ts
git commit -m "feat(achievement): add achievement store with SQLite tables"
```

---

### Task 3: Create achievement definitions

**Files:**
- Create: `server/achievement-definitions.ts`

- [ ] **Step 1: Create the full achievement definitions module**

Define ~100 achievements across 6 categories. Each definition has: id, category, tier, nameKey, descKey, icon, hidden, condition (metric + threshold).

Tier scoring map: `{ bronze: 1, silver: 3, gold: 5, platinum: 8, diamond: 12, star: 16, king: 20, legend: 25, epic: 30, eternal: 50 }`

Categories: `conversation`, `advanced`, `exploration`, `collaboration`, `bot`, `hidden`

Metrics to define:
- `total_sessions`, `total_messages`, `current_streak`, `longest_streak`, `total_active_days`
- `total_cron_runs`, `total_batch_items`, `total_smartpath_runs`, `total_skills_used`
- `total_workspaces`, `total_tokens`, `total_code_views`, `total_git_ops`
- `total_collabs`, `total_reputation`, `total_helper`
- `bot_count_wecom`, `bot_count_feishu`, `bot_count_weixin`, `bot_count_total`
- `bot_sessions_wecom`, `bot_sessions_feishu`, `bot_sessions_weixin`, `bot_sessions_total`
- `bot_messages_wecom`, `bot_messages_feishu`, `bot_messages_weixin`, `bot_messages_total`
- `bot_platforms_used`
- Hidden metrics: `hour_sent`, `session_messages`, `messages_per_minute`, `weekend_count`, `total_errors`, `days_since_last_active`, `month_day`

Export:
```typescript
export const TIER_SCORES: Record<string, number>;
export const TIER_BADGE_MAP: Record<string, string>;
export const TIER_COLORS: Record<string, string>;
export const ACHIEVEMENT_DEFINITIONS: AchievementDef[];
export const getDefinitionsByMetric(metric: string): AchievementDef[];
```

The module should build an internal `Map<string, AchievementDef[]>` indexed by `condition.metric` for O(1) lookup in the engine.

- [ ] **Step 2: Commit**

```bash
git add server/achievement-definitions.ts
git commit -m "feat(achievement): add ~100 achievement definitions across 6 dimensions"
```

---

### Task 4: Create achievement engine core

**Files:**
- Create: `server/achievement-engine.ts`

- [ ] **Step 1: Create the engine with event handling, stat updates, and unlock logic**

Follow the pattern of `server/cron-executor.ts` for constructor injection + lazy deps.

Key class structure:

```typescript
export class AchievementEngine {
  private store: AchievementStore;
  private definitions: AchievementDef[];
  private metricIndex: Map<string, AchievementDef[]>;
  private log: Logger;
  private broadcastFn: ((msg: string) => void) | null = null;
  private speedWindow: Map<string, number[]> = new Map(); // sessionId → timestamps for speed_demon

  constructor(store: AchievementStore, definitions: AchievementDef[]) { ... }

  setBroadcast(fn: (msg: string) => void): void { this.broadcastFn = fn; }

  start(): void {
    // 1. If not initialized, run recalcStatsFromDB()
    // 2. Subscribe to achievement events via onAchievementEvent()
    // 3. Emit initial day_active event
  }

  handleEvent(event: AchievementEvent): void {
    // try-catch wrapping, log errors, don't propagate
    // 1. Update relevant stat(s) based on event type
    // 2. Check special conditions (hidden achievements)
    // 3. For each updated metric, find matching definitions
    // 4. Update progress, check thresholds, unlock if met
    // 5. Broadcast unlock/progress via WebSocket
  }

  recalcStatsFromDB(): void {
    // Query sman.db tables directly (sessions, messages, cron_runs, batch_items, etc.)
    // Write all stats to achievement_stats
    // Check and unlock all eligible achievements
    // Set '_initialized' stat to 'true'
  }

  getAll(): AchievementView[] { ... }
  getStats(): Record<string, string> { ... }
  getStreak(): StreakData { ... }
  getLeaderboard(dimension: string): LeaderboardEntry[] { ... }

  close(): void { removeAllAchievementListeners(); }
}
```

Event-to-stat mapping in `handleEvent`:

| Event type | Stats updated |
|------------|--------------|
| `session_created` | `total_sessions` |
| `message_sent` | `total_messages`, check `hour_sent`, `session_messages`, `messages_per_minute` |
| `message_done` | (no stat, but can trigger `first_response`) |
| `cron_executed` | `total_cron_runs` |
| `batch_item_completed` | `total_batch_items` |
| `smartpath_run` | `total_smartpath_runs` |
| `token_used` | `total_tokens` |
| `workspace_added` | `total_workspaces` |
| `skill_used` | `total_skills_used` |
| `code_viewed` | `total_code_views` |
| `git_operation` | `total_git_ops` |
| `stardom_collab` | `total_collabs` |
| `error_occurred` | `total_errors` |
| `day_active` | `total_active_days`, update streak |
| `bot_session_created` | `bot_sessions_{platform}`, `bot_sessions_total` |
| `bot_message_sent` | `bot_messages_{platform}`, `bot_messages_total`, `bot_count_{platform}` (if new user_key) |

`recalcStatsFromDB()` runs on first startup. Since achievement tables share `sman.db`, it can directly query sessions/messages/cron_runs/batch_items/chatbot_sessions tables. **Important**: Smart Path uses file system storage (`{workspace}/.sman/paths/*/runs/*.json`), not SQLite. The backfill must scan the filesystem to count completed runs — use `glob` + `JSON.parse` on each run file.

**`day_active` handling in engine:**
- On `message_sent`, the engine checks `achievement_stats.last_active_date` against today's date
- If not yet recorded for today, update the stat + update streak in `achievement_streaks`
- On engine `start()`, also check once (covers midnight restart scenario)
- Do NOT emit a second event — handle internally within `handleEvent()`

**Backfill test:** Task 19 must include specific tests for `recalcStatsFromDB()` with a test SQLite database pre-populated with sessions/messages to verify correct stat calculation.

- [ ] **Step 2: Commit**

```bash
git add server/achievement-engine.ts
git commit -m "feat(achievement): add achievement engine with event handling and history backfill"
```

---

## Chunk 2: Server Integration — Wire Events, WS Handler, Init

### Task 5: Create WS handler module

**Files:**
- Create: `server/achievement-ws-handler.ts`

- [ ] **Step 1: Create the WS handler with message routing**

Follow the pattern of `server/cron-task-store.ts` for organized message handling.

```typescript
// server/achievement-ws-handler.ts
import type { AchievementEngine } from './achievement-engine.js';

export function handleAchievementMessage(
  msg: Record<string, any>,
  ws: any,
  engine: AchievementEngine,
): boolean {
  // Returns true if message was handled
  switch (msg.type) {
    case 'achievement.list': {
      const data = engine.getAll();
      const stats = engine.getStats();
      const streak = engine.getStreak();
      ws.send(JSON.stringify({
        type: 'achievement.data',
        achievements: data,
        stats,
        streak: { current: streak.currentStreak, longest: streak.longestStreak },
      }));
      return true;
    }
    case 'achievement.stats': {
      const stats = engine.getStats();
      ws.send(JSON.stringify({ type: 'achievement.stats', stats }));
      return true;
    }
    case 'achievement.leaderboard': {
      const dimension = msg.dimension || 'total';
      const entries = engine.getLeaderboard(dimension);
      ws.send(JSON.stringify({
        type: 'achievement.leaderboard',
        dimension,
        entries,
        isOnline: false, // Updated when Stardom is connected
      }));
      return true;
    }
    default:
      return false;
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add server/achievement-ws-handler.ts
git commit -m "feat(achievement): add WS handler for achievement messages"
```

---

### Task 6: Wire achievement system into server/index.ts

**Files:**
- Modify: `server/index.ts`

- [ ] **Step 1: Add imports and initialization**

After the chatbot initialization block (around line 356, after `chatbotManager` is created), add:

```typescript
import { AchievementStore } from './achievement-store.js';
import { AchievementEngine } from './achievement-engine.js';
import { ACHIEVEMENT_DEFINITIONS } from './achievement-definitions.js';
import { handleAchievementMessage } from './achievement-ws-handler.js';

// ... in initialization block (after chatbot setup, ~line 356):
const achievementStore = new AchievementStore(dbPath);
const achievementEngine = new AchievementEngine(achievementStore, ACHIEVEMENT_DEFINITIONS);
achievementEngine.setBroadcast((msg: string) => { broadcast(msg); });
achievementEngine.start();
```

- [ ] **Step 2: Add WS routing in the switch statement**

In the `switch (msg.type)` block (starts at line 1222), add explicit cases before `default:` (line ~3107):

```typescript
case 'achievement.list':
case 'achievement.stats':
case 'achievement.leaderboard': {
  handleAchievementMessage(msg, ws, achievementEngine);
  break;
}
```

- [ ] **Step 3: Commit**

```bash
git add server/index.ts
git commit -m "feat(achievement): wire engine and WS handler into server"
```

---

### Task 7: Add event emissions in business modules

**Files:**
- Modify: `server/session-store.ts`
- Modify: `server/claude-session.ts`
- Modify: `server/cron-executor.ts`
- Modify: `server/batch-engine.ts`
- Modify: `server/smart-path-engine.ts`
- Modify: `server/chatbot/chatbot-session-manager.ts`
- Modify: `server/stardom/stardom-session.ts`
- Modify: `server/index.ts` (code_viewed, git_operation, error_occurred events)

- [ ] **Step 1: Add emissions in session-store.ts**

In `create()` method, after successful INSERT:

```typescript
import { emitAchievementEvent } from './achievement-events.js';

// After creating session:
emitAchievementEvent({ type: 'session_created', data: { sessionId: id, workspace, isCron: false } });

// Check if workspace is new (first session for this workspace):
const workspaceCount = this.db.prepare('SELECT COUNT(*) as count FROM sessions WHERE workspace = ?').get(workspace) as { count: number };
if (workspaceCount.count <= 1) {
  emitAchievementEvent({ type: 'workspace_added', data: { workspace } });
}
```

- [ ] **Step 2: Add emissions in claude-session.ts**

In `sendMessage()`, when user message is sent:

```typescript
import { emitAchievementEvent } from './achievement-events.js';

emitAchievementEvent({ type: 'message_sent', data: { sessionId, content: userMessage } });
```

In streamDone callback:

```typescript
emitAchievementEvent({ type: 'message_done', data: { sessionId } });
emitAchievementEvent({ type: 'token_used', data: { inputTokens, outputTokens } });
```

- [ ] **Step 3: Add emissions in cron-executor.ts**

After successful cron run:

```typescript
import { emitAchievementEvent } from './achievement-events.js';

emitAchievementEvent({ type: 'cron_executed', data: { taskId: task.id } });
```

- [ ] **Step 4: Add emissions in batch-engine.ts**

After each item completes:

```typescript
import { emitAchievementEvent } from './achievement-events.js';

emitAchievementEvent({ type: 'batch_item_completed', data: { taskId, itemId: item.id } });
```

After all items complete:

```typescript
emitAchievementEvent({ type: 'batch_completed', data: { taskId } });
```

- [ ] **Step 5: Add emissions in smart-path-engine.ts**

After path run completes:

```typescript
import { emitAchievementEvent } from './achievement-events.js';

emitAchievementEvent({ type: 'smartpath_run', data: { pathId } });
```

- [ ] **Step 6: Add emissions in chatbot-session-manager.ts**

In `ensureSession()` method (line ~175), after `this.store.setSession(...)`:
- Only emit `bot_session_created` when a NEW session is created (not when reusing existing)
- Check: if `existingSession` was null/undefined before the create call

```typescript
import { emitAchievementEvent } from '../achievement-events.js';

// Only when creating a new session (not reusing):
emitAchievementEvent({
  type: 'bot_session_created',
  data: { platform: msg.platform, userKey, sessionId },
});
```

In `handleMessage()` method (line ~183), after session resolution and before sending to SDK:

```typescript
emitAchievementEvent({
  type: 'bot_message_sent',
  data: { platform: msg.platform, userKey, sessionId },
});
```

- [ ] **Step 7: Add emission in stardom-session.ts**

In `completeCollaboration()` method (line ~176), after `this.deps.store.updateTaskStatus(taskId, 'completed', ...)` (line ~189):

```typescript
import { emitAchievementEvent } from '../achievement-events.js';

emitAchievementEvent({
  type: 'stardom_collab',
  data: { taskId, rating, helperName: collab.helperName },
});
```

- [ ] **Step 8: Add emissions in server/index.ts for code_viewed, git_operation, error_occurred**

In the WS switch statement:

`code_viewed` — in `case 'code.readFile'` (~line 2470) and `case 'code.searchSymbols'` (~line 2483), after successful response:
```typescript
emitAchievementEvent({ type: 'code_viewed', data: { sessionId } });
```

`git_operation` — in `case 'git.commit'` (~line 2546) and `case 'git.push'` (~line 2621), after successful operation:
```typescript
emitAchievementEvent({ type: 'git_operation', data: { sessionId, operation: 'commit' } });
```

`error_occurred` — in `case 'chat.send'` error handling, after SDK error (filter by whitelist: sdk_error, timeout, rate_limit only):
```typescript
if (isWhitelistedError(error)) {
  emitAchievementEvent({ type: 'error_occurred', data: { sessionId, errorCode: error.code } });
}
```

`skill_used` — in the `tool_use` content block handling (~line 1500 of `claude-session.ts`), when tool name matches a pattern from `skillsRegistry`:
```typescript
// In the streaming callback where tool_use blocks are captured
if (isSkillTool(toolName)) {
  emitAchievementEvent({ type: 'skill_used', data: { sessionId, skillName: toolName } });
}
```

- [ ] **Step 9: Commit all event emissions**

```bash
git add server/session-store.ts server/claude-session.ts server/cron-executor.ts server/batch-engine.ts server/smart-path-engine.ts server/chatbot/chatbot-session-manager.ts
git commit -m "feat(achievement): wire event emissions into all business modules"
```

---

## Chunk 3: Frontend — Zustand Store, Sidebar, Routes

### Task 8: Create achievement Zustand store

**Files:**
- Create: `src/stores/achievement.ts`

- [ ] **Step 1: Create the store following stardom.ts pattern**

```typescript
// src/stores/achievement.ts
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';

function getWsClient() {
  return useWsConnection.getState().client;
}

export interface AchievementView {
  id: string;
  category: string;
  tier: string;
  nameKey: string;
  descKey: string;
  icon: { source: string; value: string };
  hidden: boolean;
  currentValue: number;
  threshold: number;
  unlockedAt: string | null;
}

export interface LeaderboardEntry {
  rank: number;
  agentId: string;
  agentName: string;
  score: number;
  unlocked: number;
  isMe: boolean;
}

interface AchievementState {
  achievements: AchievementView[];
  stats: Record<string, string>;
  streak: { current: number; longest: number };
  leaderboard: LeaderboardEntry[];
  leaderboardDimension: string;
  leaderboardOnline: boolean;
  loading: boolean;
  unlockQueue: AchievementView[];  // Pending toast notifications

  fetchAchievements: () => void;
  fetchLeaderboard: (dimension: string) => void;
  popUnlockQueue: () => AchievementView | undefined;
}

let set: (partial: Partial<AchievementState> | ((state: AchievementState) => Partial<AchievementState>)) => void;
let get: () => AchievementState;

export const useAchievementStore = create<AchievementState>((storeSet, storeGet) => {
  set = storeSet;
  get = storeGet;

  // Register WS handlers
  const client = getWsClient();
  if (client) {
    client.on('achievement.data', (data: any) => {
      set({
        achievements: data.achievements || [],
        stats: data.stats || {},
        streak: data.streak || { current: 0, longest: 0 },
        loading: false,
      });
    });

    client.on('achievement.unlocked', (data: any) => {
      // Refresh full list
      get().fetchAchievements();
      // Add to unlock toast queue
      const achievement: AchievementView = {
        id: data.achievement.id,
        category: '',
        tier: data.achievement.tier,
        nameKey: data.achievement.nameKey,
        descKey: data.achievement.descKey,
        icon: data.achievement.icon,
        hidden: false,
        currentValue: 0,
        threshold: 0,
        unlockedAt: new Date().toISOString(),
      };
      set(state => ({ unlockQueue: [...state.unlockQueue, achievement] }));
    });

    client.on('achievement.progress', (data: any) => {
      set(state => ({
        achievements: state.achievements.map(a =>
          a.id === data.achievementId
            ? { ...a, currentValue: data.currentValue, unlockedAt: data.justUnlocked ? new Date().toISOString() : a.unlockedAt }
            : a
        ),
      }));
    });

    client.on('achievement.leaderboard', (data: any) => {
      set({
        leaderboard: data.entries || [],
        leaderboardDimension: data.dimension,
        leaderboardOnline: data.isOnline,
      });
    });
  }

  return {
    achievements: [],
    stats: {},
    streak: { current: 0, longest: 0 },
    leaderboard: [],
    leaderboardDimension: 'total',
    leaderboardOnline: false,
    loading: false,
    unlockQueue: [],

    fetchAchievements: () => {
      const c = getWsClient();
      if (!c) return;
      set({ loading: true });
      c.send({ type: 'achievement.list' });
    },

    fetchLeaderboard: (dimension: string) => {
      const c = getWsClient();
      if (!c) return;
      c.send({ type: 'achievement.leaderboard', dimension });
    },

    popUnlockQueue: () => {
      const queue = get().unlockQueue;
      if (queue.length === 0) return undefined;
      const first = queue[0];
      set({ unlockQueue: queue.slice(1) });
      return first;
    },
  };
});
```

- [ ] **Step 2: Commit**

```bash
git add src/stores/achievement.ts
git commit -m "feat(achievement): add Zustand store with WS event handlers"
```

---

### Task 9: Add sidebar entry and route

**Files:**
- Modify: `src/components/layout/Sidebar.tsx`
- Modify: `src/app/routes.tsx`

- [ ] **Step 1: Add Trophy import and NavLink in Sidebar.tsx**

Add `Trophy` to the lucide-react import.

Add a new NavLink in the collapsible footer section (after Smart Paths, before Settings):

```tsx
<NavLink
  to="/achievements"
  className={({ isActive }) =>
    cn(
      'flex items-center gap-2.5 rounded-lg px-3 py-2 text-[14px] font-medium transition-all duration-200',
      'hover:bg-[hsl(var(--sidebar-border))] text-foreground/70',
      isActive && 'bg-[hsl(var(--sidebar-bg))] text-foreground',
    )
  }
>
  {({ isActive }) => (
    <>
      <div className={cn(
        'flex shrink-0 items-center justify-center',
        isActive ? 'text-foreground' : 'text-muted-foreground',
      )}>
        <Trophy className="h-[18px] w-[18px]" strokeWidth={2} />
      </div>
      <span>{t('menu.achievements')}</span>
    </>
  )}
</NavLink>
```

- [ ] **Step 2: Add route in routes.tsx**

```tsx
import { AchievementsPage } from '@/features/achievements';

// In the children array:
{ path: 'achievements', element: <AchievementsPage /> },
```

- [ ] **Step 3: Commit**

```bash
git add src/components/layout/Sidebar.tsx src/app/routes.tsx
git commit -m "feat(achievement): add sidebar entry and route"
```

---

### Task 10: Add i18n keys

**Files:**
- Modify: `src/locales/zh-CN.json`
- Modify: `src/locales/en-US.json`

- [ ] **Step 1: Add achievement-related keys to both locale files**

Keys needed (add under `achievement.*` namespace):

```json
{
  "menu.achievements": { "text": "成就殿堂" / "Achievements", "context": "..." },
  "achievement.title": { "text": "成就殿堂" / "Achievement Hall", "context": "..." },
  "achievement.tab.all": { "text": "全部" / "All", ... },
  "achievement.tab.conversation": { "text": "对话" / "Conversation", ... },
  "achievement.tab.advanced": { "text": "高级功能" / "Advanced", ... },
  "achievement.tab.exploration": { "text": "探索" / "Exploration", ... },
  "achievement.tab.collaboration": { "text": "协作" / "Collaboration", ... },
  "achievement.tab.bot": { "text": "Bot" / "Bot", ... },
  "achievement.tab.hidden": { "text": "彩蛋" / "Easter Eggs", ... },
  "achievement.tab.leaderboard": { "text": "排行榜" / "Leaderboard", ... },
  "achievement.progress": { "text": "${unlocked}/${total} 已解锁" / "${unlocked}/${total} Unlocked", ... },
  "achievement.unlocked": { "text": "成就解锁！" / "Achievement Unlocked!", ... },
  "achievement.viewDetails": { "text": "查看成就" / "View Achievements", ... },
  "achievement.close": { "text": "关闭" / "Close", ... },
  "achievement.hidden.placeholder": { "text": "???" / "???", ... },
  "achievement.hidden.locked": { "text": "未解锁" / "Locked", ... },
  "achievement.leaderboard.offline": { "text": "仅显示本地数据，连接星域网络可查看全局排名" / "Local data only. Connect to Stardom for global ranking", ... },
  "achievement.leaderboard.total": { "text": "总榜" / "Overall", ... },
  "achievement.leaderboard.weekly": { "text": "本周之星" / "Weekly Star", ... }
}
```

Plus ~100 individual achievement name/desc keys like:
```json
"achievement.session_1.name": { "text": "初次对话" / "First Chat", ... },
"achievement.session_1.desc": { "text": "创建你的第一个会话" / "Create your first session", ... },
// ... etc for all achievements
```

- [ ] **Step 2: Commit**

```bash
git add src/locales/zh-CN.json src/locales/en-US.json
git commit -m "feat(achievement): add i18n keys for all achievements"
```

---

## Chunk 4: Frontend UI — Achievement Page Components

### Task 11: Create AchievementCard component

**Files:**
- Create: `src/features/achievements/AchievementCard.tsx`

- [ ] **Step 1: Create the card component**

Props: `achievement: AchievementView`

States:
- **Unlocked** (`unlockedAt !== null`): Full color, tier badge border glow, show unlock date
- **In progress** (`currentValue > 0 && unlockedAt === null`): Show progress bar, current/threshold
- **Not started** (`currentValue === 0`): Grayscale icon, muted text
- **Hidden + locked**: Show lock icon + "???"

Tier badge border: CSS class with tier-specific color from `TIER_COLORS` map.

Progress bar: Simple `<div>` with percentage width, rounded, animated.

- [ ] **Step 2: Commit**

```bash
git add src/features/achievements/AchievementCard.tsx
git commit -m "feat(achievement): add AchievementCard component"
```

---

### Task 12: Create AchievementGrid component

**Files:**
- Create: `src/features/achievements/AchievementGrid.tsx`

- [ ] **Step 1: Create the grid with category filter tabs**

Props: none (reads from `useAchievementStore`)

- Category tabs using Radix UI Tabs
- CSS Grid layout: `grid-cols-2 sm:grid-cols-3 lg:grid-cols-4` with gap
- Map filtered achievements to `<AchievementCard />`
- Show empty state if no achievements in category

- [ ] **Step 2: Commit**

```bash
git add src/features/achievements/AchievementGrid.tsx
git commit -m "feat(achievement): add AchievementGrid with category filter"
```

---

### Task 13: Create AchievementsPage (main page)

**Files:**
- Create: `src/features/achievements/index.tsx`

- [ ] **Step 1: Create the main page with two tabs**

Layout:
- Header: title + total progress bar (`${unlocked} / ${total}`)
- Two main tabs: 成就 / 排行榜 (using Radix Tabs)
- Tab 1: `<AchievementGrid />`
- Tab 2: Leaderboard list (rank, name, score, isMe highlight, offline warning)

On mount: call `fetchAchievements()` from store.

- [ ] **Step 2: Commit**

```bash
git add src/features/achievements/index.tsx
git commit -m "feat(achievement): add AchievementsPage with tabs"
```

---

### Task 14: Create AchievementToast component

**Files:**
- Create: `src/components/layout/AchievementToast.tsx`

- [ ] **Step 1: Create the global toast notification**

Position: fixed bottom-right, animated slide-in/out.
Polls `useAchievementStore.popUnlockQueue()` on interval or via WS event.
Shows for 3 seconds then auto-dismisses.
Content: tier badge color border, icon, name (via `t(nameKey)`), description.
Button: "查看成就" navigates to `/achievements`.

**Registration:** Add `<AchievementToast />` to `src/components/layout/MainLayout.tsx` (the root layout component that wraps all pages). This ensures the toast is available on every page.

- [ ] **Step 2: Commit**

```bash
git add src/components/layout/AchievementToast.tsx
git commit -m "feat(achievement): add global unlock toast component"
```

---

## Chunk 5: Badge SVG Assets

### Task 15: Create badge SVG files

**Files:**
- Create: `src/features/achievements/assets/bronze.svg` through `eternal.svg` (10 files)

- [ ] **Step 1: Create 10 simple badge SVGs**

Each SVG is a circular badge with the tier-specific border color and a subtle glow effect. Simple geometric design, 48x48 viewBox.

Files:
- `bronze.svg` (#CD7F32)
- `silver.svg` (#C0C0C0)
- `gold.svg` (#FFD700)
- `platinum.svg` (#E5E4E2)
- `diamond.svg` (#B9F2FF)
- `star.svg` (#FF6B9D)
- `king.svg` (#FF4444)
- `legend.svg` (#9B59B6)
- `epic.svg` (#FF8C00)
- `eternal.svg` (#00FFAA)

- [ ] **Step 2: Commit**

```bash
git add src/features/achievements/assets/
git commit -m "feat(achievement): add 10 tier badge SVG assets"
```

---

## Chunk 6: sman-server — Leaderboard Service

### Task 16: Create AchievementDB in sman-server

**Working directory:** `/Users/nasakim/projects/sman-server`

**Files:**
- Create: `src/db-achievements.ts`

- [ ] **Step 1: Create the database module**

Following the pattern of `sman-server/src/db.ts` (HubDB class).

Table: `achievement_leaderboard` in `hub.db`

Key methods:
- `upsertReport(data: { clientId, totalPoints, totalUnlocked, dimensionScores, tierCounts }): void`
- `getLeaderboard(dimension: string, clientId: string, limit?: number): LeaderboardRow[]`
- `resetWeeklyIfNeeded(): void` — check if week has rolled over, reset `weekly_points`

Constructor accepts the `hub.db` Database instance (inject from existing HubDB).

- [ ] **Step 2: Commit**

```bash
cd /Users/nasakim/projects/sman-server && git add src/db-achievements.ts
git commit -m "feat(achievement): add AchievementDB with leaderboard table"
```

---

### Task 17: Create achievement API routes in sman-server

**Working directory:** `/Users/nasakim/projects/sman-server`

**Files:**
- Create: `src/routes/achievement-api.ts`
- Modify: `src/index.ts`

- [ ] **Step 1: Create the PSK-encrypted API routes**

Following the pattern of `sman-server/src/routes/hub-api.ts`.

Two endpoints:
- `POST /api/hub/achievement-report` — receive client achievement data, upsert
- `POST /api/hub/achievement-leaderboard` — return sorted leaderboard

Both use PSK decrypt → validate → process → encrypt response.

- [ ] **Step 2: Wire routes into sman-server/src/index.ts**

Mount the new routes alongside existing hub-api routes.

- [ ] **Step 3: Commit**

```bash
cd /Users/nasakim/projects/sman-server && git add src/routes/achievement-api.ts src/index.ts
git commit -m "feat(achievement): add PSK-encrypted leaderboard API endpoints"
```

---

### Task 18: Wire Sman client to sman-server leaderboard sync

**Files:**
- Modify: `server/achievement-engine.ts`

- [ ] **Step 1: Add leaderboard sync logic to the engine**

After each achievement unlock:
1. Calculate total points, dimension scores, tier counts from current progress
2. POST to `sman-server/api/hub/achievement-report` (if configured)
3. On `achievement.leaderboard` request: POST to `sman-server/api/hub/achievement-leaderboard`, merge with local data

Graceful degradation: if sman-server is unreachable, use local `achievement_board` table only.

- [ ] **Step 2: Commit**

```bash
git add server/achievement-engine.ts
git commit -m "feat(achievement): add sman-server leaderboard sync"
```

---

## Chunk 7: Testing & Polish

### Task 19: Test achievement engine unit tests

**Files:**
- Create: `tests/achievement-engine.test.ts`
- Create: `tests/achievement-store.test.ts`

- [ ] **Step 1: Write store tests**

Test:
- Table creation on init
- `setProgress` + `getProgress` round-trip
- `unlock` sets `unlocked_at`
- `setStat` + `getStat` round-trip
- `updateStreak` increment / reset / same-day dedup
- `upsertBoardEntry` + `getBoard`

- [ ] **Step 2: Write engine tests**

Test:
- Event handling updates correct stat
- Threshold check unlocks achievement
- Unlock broadcasts via mock broadcastFn
- `recalcStatsFromDB()` populates stats from test SQLite data
- Hidden achievement triggers (hour check, session message count)
- Speed_demon sliding window

- [ ] **Step 3: Commit**

```bash
git add tests/
git commit -m "test(achievement): add unit tests for store and engine"
```

---

### Task 20: Manual integration test

- [ ] **Step 1: Start dev server and verify**

1. `./dev.sh` — ensure no startup errors
2. Open browser → sidebar should show Trophy icon
3. Click Trophy → achievement page loads with empty/initial state
4. Send a chat message → achievement page should show progress update
5. Verify `recalcStatsFromDB()` populated historical stats
6. Check console for any `[achievement]` error logs

- [ ] **Step 2: Verify history backfill**

1. After first startup, all existing sessions/messages should be counted
2. Achievements eligible from historical data should be auto-unlocked
3. No duplicate unlock notifications for backfilled achievements

- [ ] **Step 3: Verify Bot stats**

1. Check `bot_sessions_wecom`, `bot_sessions_feishu`, `bot_sessions_weixin` stats
2. Check `bot_count_*` stats reflect distinct user_keys per platform
3. Verify Bot dimension achievements appear correctly

---

## Summary of Files

### New files (sman)

| File | Responsibility |
|------|---------------|
| `server/achievement-events.ts` | Event bus |
| `server/achievement-store.ts` | SQLite tables + CRUD |
| `server/achievement-engine.ts` | Core engine + history backfill |
| `server/achievement-definitions.ts` | ~100 achievement definitions |
| `server/achievement-ws-handler.ts` | WS message routing |
| `src/stores/achievement.ts` | Zustand store + WS handlers |
| `src/features/achievements/index.tsx` | Main page |
| `src/features/achievements/AchievementCard.tsx` | Card component |
| `src/features/achievements/AchievementGrid.tsx` | Grid + category filter |
| `src/components/layout/AchievementToast.tsx` | Global toast |
| `src/features/achievements/assets/*.svg` | 10 tier badge SVGs |

### New files (sman-server)

| File | Responsibility |
|------|---------------|
| `src/db-achievements.ts` | Leaderboard DB table |
| `src/routes/achievement-api.ts` | PSK-encrypted report + query API |

### Modified files (sman)

| File | Change |
|------|--------|
| `server/index.ts` | Init engine + WS routing |
| `server/session-store.ts` | emit `session_created`, `workspace_added` |
| `server/claude-session.ts` | emit `message_sent`, `message_done`, `token_used` |
| `server/cron-executor.ts` | emit `cron_executed` |
| `server/batch-engine.ts` | emit `batch_item_completed`, `batch_completed` |
| `server/smart-path-engine.ts` | emit `smartpath_run` |
| `server/chatbot/chatbot-session-manager.ts` | emit `bot_session_created`, `bot_message_sent` |
| `src/components/layout/Sidebar.tsx` | Add Trophy NavLink |
| `src/app/routes.tsx` | Add `/achievements` route |
| `src/locales/zh-CN.json` | Achievement i18n keys |
| `src/locales/en-US.json` | Achievement i18n keys |

### Modified files (sman-server)

| File | Change |
|------|--------|
| `src/index.ts` | Mount achievement API routes |
