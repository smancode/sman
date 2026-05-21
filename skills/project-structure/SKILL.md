---
_scanned:
  commitHash: "353989234d641c959d8c0aa37aea150735c4ccd8"
  timestamp: "2025-05-21"
  mode: INCREMENTAL
  previousCommit: "c63e3fcf76ba9e8b362d9d73ebccab934d1d998d"
  changes: "59 files changed, 6176 insertions(+), 812 deletions(-)"
---

# Sman Project Structure Skill

## Overview

Sman is an intelligent business platform built with **React 19 + TypeScript** (frontend), **Node.js + Express** (backend), and **Electron** (desktop). This document provides a comprehensive overview of the project structure, focusing on recent changes and architectural evolution.

**Recent Major Updates (Since c63e3fcf → 35398923)**:
- ✅ **Achievement System**: Full gamification engine with 96 achievements, 10 tiers, weighted scoring, and leaderboard
- ✅ **Hub Entry**: New collaboration hub feature with phased unlocking mechanism
- ✅ **Enhanced Interactivity**: Sidebar hover states, smart paths improvements, stardom canvas updates
- ✅ **Massive i18n Expansion**: +276 translation keys for achievements and UI elements

**Technology Stack**:
- Frontend: React 19, TypeScript, TailwindCSS, Radix UI, Zustand, CodeMirror 6
- Backend: Node.js 22 LTS, Express, WebSocket (ws), TypeScript (ESM)
- Desktop: Electron, electron-vite, better-sqlite3, node-screenshots
- AI: Claude Agent SDK v0.2.110+, @anthropic-ai/claude-code v2.1.110+
- Database: SQLite (better-sqlite3) with WAL mode

---

## Project Layout

```
sman/
├── src/                          # Frontend source (React + TypeScript)
│   ├── app/                      # App configuration
│   │   └── routes.tsx            # Route definitions (NEW: /achievements, /hub)
│   ├── components/               # Reusable UI components
│   │   ├── layout/               # Layout components (MODIFIED: enhanced interactivity)
│   │   │   └── Sidebar.tsx       # Main sidebar (MODIFIED: hover states)
│   │   └── ui/                   # Radix UI primitives + custom components
│   ├── features/                 # Feature modules (organized by domain)
│   │   ├── achievements/         # NEW: Achievement system UI
│   │   │   ├── index.tsx         # Main achievements page
│   │   │   ├── AchievementCard.tsx
│   │   │   ├── AchievementToast.tsx
│   │   │   ├── TierBadge.tsx
│   │   │   └── LeaderboardTab.tsx
│   │   ├── hub/                  # NEW: Collaboration hub
│   │   │   ├── HubEntry.tsx      # Entry point with phased unlock
│   │   │   ├── HubDashboard.tsx  # Main dashboard
│   │   │   ├── AgentList.tsx
│   │   │   ├── TaskBoard.tsx
│   │   │   └── TaskDetail.tsx
│   │   ├── chat/                 # Chat interface
│   │   ├── smart-paths/          # MODIFIED: Earth path automation
│   │   ├── stardom/              # MODIFIED: Multi-agent canvas
│   │   ├── cron-tasks/           # Scheduled tasks
│   │   ├── batch-tasks/          # Batch operations
│   │   ├── settings/             # Configuration UI
│   │   ├── code-viewer/          # Code browser
│   │   └── git/                  # Git operations UI
│   ├── locales/                  # i18n translations (MODIFIED: +276 keys)
│   │   ├── en-US.json            # English translations
│   │   └── zh-CN.json            # Chinese translations
│   ├── stores/                   # Zustand state management
│   ├── queries/                  # React Query hooks
│   └── types/                    # TypeScript type definitions
│       └── achievement.ts        # NEW: Achievement types
├── server/                       # Backend source (Node.js + Express)
│   ├── achievement-*.ts          # NEW: Achievement system (5 files, 1187 lines)
│   │   ├── achievement-engine.ts       # Core scoring & event handling (680 lines)
│   │   ├── achievement-store.ts        # SQLite persistence (200 lines)
│   │   ├── achievement-definitions.ts  # 96 achievement configs (228 lines)
│   │   ├── achievement-events.ts       # Event emission system (29 lines)
│   │   └── achievement-ws-handler.ts    # WebSocket API (50 lines)
│   ├── init/                     # Initialization workflows
│   ├── utils/                    # Backend utilities
│   └── db.ts                     # Main database setup
├── electron/                     # Electron main process
│   └── main/                     # Main process entry
├── skills/                       # Global skills directory
├── sman-server/                  # Hub server (separate Express app)
│   └── src/
│       ├── routes/               # Hub API routes
│       └── db.ts                 # Hub database (SQLite)
├── docs/                         # Documentation
├── package.json                  # Root package.json
├── tsconfig.json                 # TypeScript configuration
├── tailwind.config.js            # TailwindCSS configuration
├── vite.config.ts                # Vite configuration
└── electron.vite.config.ts       # Electron Vite config
```

---

## Core Features

### 1. **Achievement System** (NEW - Major Addition)

**Architecture**: Event-driven engine with SQLite persistence, real-time WebSocket updates, and Hub leaderboard integration.

**Server Components** (`server/achievement-*.ts`):
- **`achievement-engine.ts`** (680 lines): Core scoring engine
  - Weighted metric calculation (12 dimensions: sessions, messages, tokens, cron, smart-paths, skills, code-views, git-ops, bot-sessions, bot-messages, bot-count, streak)
  - Event-driven progression tracking
  - Tier-based leveling system (bronze → eternal, 10 tiers)
  - DB reconciliation (every 30 min to catch missed events)
  - Hub integration (upload + fetch leaderboard)
  - Streak tracking (daily active streaks)
  - Speed protection (prevent achievement spamming)

- **`achievement-store.ts`** (200 lines): SQLite persistence
  - Tables: `achievement_progress`, `achievement_stats`, `achievement_streaks`, `achievement_board`
  - WAL mode for performance
  - Atomic operations for progress updates

- **`achievement-definitions.ts`** (228 lines): 96 achievement configs
  - Categories: conversation, advanced, exploration, collaboration, bot, hidden
  - Tiers: bronze → eternal (10 levels)
  - i18n keys for all text (nameKey, descKey)

- **`achievement-events.ts`** (29 lines): Event bus
  - `onAchievementEvent()` listener registration
  - Event types: session_created, message_sent, token_used, cron_run, smartpath_run, skill_used, code_viewed, git_op, bot_session_created, bot_message_sent, bot_created

- **`achievement-ws-handler.ts`** (50 lines): WebSocket API
  - `achievement.list` - Get full achievement summary
  - `achievement.stats` - Get raw stats
  - `achievement.leaderboard` - Upload + fetch leaderboard

**Frontend Components** (`src/features/achievements/`):
- **`index.tsx`**: Main page with category filters, tier badges, score breakdown modal
- **`AchievementCard.tsx`**: Individual achievement display with progress bar
- **`TierBadge.tsx`**: User level badge (bronze → eternal)
- **`AchievementToast.tsx`**: Real-time unlock notification
- **`LeaderboardTab.tsx`**: Global leaderboard with dimension-based sorting

**Data Flow**:
```
User Action → Achievement Event → Engine.calculateScore() → Store.updateProgress()
                ↓
        WebSocket.broadcast() → Frontend Toast + UI Update
                ↓
        Hub.upload() → Leaderboard Sync
```

**Scoring System**:
- **Weighted Sum**: `totalScore = Σ(metricValue × weight)`
  - Example: 10 sessions × 3 + 100 messages × 0.5 + 200k tokens × 0.000005 = 32 points
- **Level Thresholds**: bronze(0) → silver(100) → gold(300) → ... → eternal(10000)
- **Decoupled**: Achievement unlocking is separate from scoring (achievements have their own thresholds)

**Integration Points**:
- **Server**: Wired into main server initialization, session store, WebSocket handler
- **Frontend**: New route `/achievements`, sidebar menu entry, Zustand store
- **Database**: New SQLite tables in main `sman.db`
- **Hub**: Encrypted API for leaderboard upload/fetch

---

### 2. **Hub Entry** (NEW)

**Purpose**: Collaboration hub with phased unlocking mechanism (dev-mode gated).

**Components** (`src/features/hub/`):
- **`HubEntry.tsx`**: Entry point with 3-second check, phased unlock animation
  - Phases: `checking` → `locked` / `unlocking` → `unlocked`
  - Uses `useHubDevMode()` query to check remote enablement
  - StarfieldCanvas background for visual consistency
  - 2-second fade transition when unlocking

- **`HubDashboard.tsx`**: Main hub dashboard (agent list, task board, task detail)
- **`AgentList.tsx`**: Agent management
- **`TaskBoard.tsx`**: Kanban-style task board
- **`TaskDetail.tsx`**: Task details view

**Integration**:
- Route: `/hub` in `src/app/routes.tsx`
- Sidebar: "组队" (Team) menu entry
- Uses existing StarfieldCanvas from stardom feature

---

### 3. **Enhanced Interactivity** (MODIFIED)

**Sidebar** (`src/components/layout/Sidebar.tsx`):
- **Mouse Hover State Tracking**: Added `isHovered` state for dynamic visual feedback
- **Blur Logic**: Exclude `/chat`, `/stardom`, `/hub`, `/achievements` from blur effect
- **119 lines changed**: Enhanced interactivity + responsive behavior

**Smart Paths** (`src/features/smart-paths/index.tsx`):
- **63 lines changed**: UI improvements (likely step editing, temp file rules based on commits)
- **Step Editing**: Added functionality to edit path steps (based on commit `d1264ef`)
- **Temp File Rules**: Single-step filename prefix requirement (commit `7c8683f`)

**Stardom Canvas** (`src/features/stardom/StarfieldCanvas.tsx`):
- **93 lines changed**: Visual optimizations (based on commit `f69c187`)
- **Performance**: Enhanced rendering for multi-agent collaboration network

---

### 4. **Internationalization (i18n)** (MODIFIED - Massive Expansion)

**Scale**: +276 lines in each of `en-US.json` and `zh-CN.json` (total +552 lines).

**New Translation Categories**:
- **Achievement System** (majority of additions):
  - 96 achievement names + descriptions (nameKey, descKey)
  - Tier names (10 levels)
  - Category labels (6 categories)
  - Score breakdown labels (12 metrics)
  - UI text (tabs, buttons, tooltips, empty states)
- **Hub**: Collaboration hub UI text
- **General UI**: Additional UI refinements

**Pattern**: All user-facing text uses `t('key')` function, no hardcoded strings in JSX.

**Key Files**:
- `src/locales/en-US.json`: English translations
- `src/locales/zh-CN.json`: Chinese translations
- `src/locales/index.ts`: `t()` function and locale context

---

## Database Schema

### Main Database (`~/.sman/sman.db`)

**Achievement Tables** (NEW):
```sql
-- Progress tracking for each achievement
CREATE TABLE achievement_progress (
  achievement_id TEXT PRIMARY KEY,
  current_value INTEGER DEFAULT 0,
  unlocked_at TEXT,
  notified_at TEXT
);

-- Global stats (key-value store)
CREATE TABLE achievement_stats (
  key TEXT PRIMARY KEY,
  value TEXT,
  updated_at TEXT
);

-- Daily streak tracking
CREATE TABLE achievement_streaks (
  id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  current_streak INTEGER DEFAULT 0,
  longest_streak INTEGER DEFAULT 0,
  last_active_date TEXT
);

-- Local leaderboard cache
CREATE TABLE achievement_board (
  agent_id TEXT PRIMARY KEY,
  agent_name TEXT,
  total_unlocked INTEGER DEFAULT 0,
  total_points INTEGER DEFAULT 0,
  tier_counts TEXT,        -- JSON string
  dimension_scores TEXT,    -- JSON string
  last_synced TEXT
);
```

**Existing Tables** (unchanged):
- `sessions`, `messages`, `cron_tasks`, `batch_tasks`, `smart_paths`, `git_operations`, etc.

### Hub Database (`sman-server/`)

**Hub Tables** (separate SQLite instance):
- `achievement_leaderboard`: Global leaderboard with `dimension_scores` JSON column
- `hub_*`: Hub-specific tables (agents, tasks, etc.)

---

## WebSocket API

### Achievement System Endpoints (NEW)

**Client → Server**:
```typescript
// Get full achievement summary
{ type: 'achievement.list' }

// Get raw stats
{ type: 'achievement.stats' }

// Get leaderboard (upload first, then fetch)
{ type: 'achievement.leaderboard', dimension?: string }
```

**Server → Client**:
```typescript
// Full achievement data
{
  type: 'achievement.data',
  achievements: AchievementView[],
  stats: Record<string, string>,
  streak: { current: number; longest: number },
  totalPoints: number,
  level: Tier,
  levelProgress: { currentTier: Tier, currentPoints: number, nextTier: Tier | null, pointsNeeded: number, progressPercent: number },
  totalUnlocked: number,
  totalAchievements: number
}

// Raw stats
{ type: 'achievement.stats', stats: Record<string, string> }

// Leaderboard
{
  type: 'achievement.leaderboard',
  entries: LeaderboardEntry[],
  dimension: string,
  isOnline: boolean,
  clientId: string
}
```

**Real-Time Notifications**: Server broadcasts `achievement.data` on every unlock → Frontend shows `AchievementToast`.

---

## Build & Run Commands

### Development Mode

```bash
# One-command startup (backend + frontend + Electron)
./dev.sh

# Or separately
pnpm dev           # Frontend (Vite dev server on :5881)
pnpm dev:server    # Backend (Express + WebSocket on :5880)
pnpm dev:electron  # Electron (in watch mode)
```

### Production Build

```bash
pnpm build         # Build frontend + backend to dist/
pnpm build:electron # Compile Electron main process
pnpm electron:build # Full pipeline: build + build:electron + electron-builder
```

### Platform-Specific Packaging

**Windows**:
```bash
bash build-win.sh              # Complete build → NSIS installer
bash build-win.sh --skip-deps  # Skip dependency installation
```
- Output: `release/Sman-Setup-<version>.exe`
- Version: Auto-generated from date (YY.MMDD.HH)
- Fixes: Cleans invalid rollup symlinks
- Optional: `.env.build` for enterprise edition URL

**macOS**:
```bash
bash build-mac.sh              # Complete build → DMG
bash build-mac.sh --skip-deps  # Skip dependency installation
```
- Output: `release/Sman-<version>-arm64.dmg`
- Version: Auto-generated from date (YY.MMDD.HH)
- Recompiles: better-sqlite3, node-screenshots for Electron ABI

### Testing

```bash
pnpm test          # Run all tests
pnpm test:watch    # Watch mode
```

---

## Environment Configuration

### Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | 5880 | Backend HTTP + WebSocket port |
| `SMANBASE_HOME` | `~/.sman` | User data directory |
| `CLAUDE_CONFIG_DIR` | `~/.sman/claude-config` | Isolated Claude CLI config |

### Port Usage

| Port | Service |
|------|---------|
| 5880 | Production backend (HTTP + WebSocket) |
| 5881 | Vite dev server (frontend hot reload) |

---

## Key Technical Points

### 1. **ESM Modules**

- **Target**: ES2022 for server (`"module": "ES2022"` in tsconfig.json)
- **CJS/ESM Interop**: `better-sqlite3` requires special handling:
  ```typescript
  import betterSqlite3 from 'better-sqlite3';
  const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
  ```
- **Package.json**: `dist/server/package.json` has `"type": "module"`

### 2. **Electron-Specific Constraints**

- **ASAR Disabled**: Native modules (better-sqlite3, node-screenshots) fail in ASAR archives
- **__dirname Replacement**: Use `path.dirname(fileURLToPath(import.meta.url))` in ESM
- **Windows GPU**: `app.disableHardwareAcceleration()` prevents white screen in VDI environments
- **Native Module Recompilation**: Required before packaging (electron-builder ABI mismatch)

### 3. **Auth & API Boundaries**

- **API Routes**: `/api/*` endpoints require Bearer token authentication
- **Static Files**: Served without auth (direct file access)
- **WebSocket**: Authenticated via session token in handshake

### 4. **Environment Isolation**

- **Clean Env**: `getCleanEnv()` removes `ANTHROPIC_*`, `OPENAI_*`, `CLAUDE_*` env vars
- **Isolated Config**: `CLAUDE_CONFIG_DIR` points to `~/.sman/claude-config`
- **Purpose**: Prevent conflicts with system-wide Claude CLI installations

### 5. **Message Queueing**

- **SDK Limitation**: Cannot interrupt a running turn
- **Backend Solution**: `await streamDone` queues messages sequentially
- **UX Impact**: Prevents concurrent message processing per session

### 6. **Multi-Language (i18n) Architecture**

- **Translation Function**: `import { t } from '@/locales'`
- **Key Storage**: `src/locales/{lang}.json` (structured by feature)
- **Usage**: `t('achievement.scoreDetail.sessions')` → "会话数" (zh-CN) / "Sessions" (en-US)
- **Forbidden**: No hardcoded Chinese/English strings in JSX
- **Exception**: Developer-facing content (logs, comments) can use hardcoded strings

### 7. **Achievement System Specifics**

- **Weighted Scoring**: Decoupled from achievement unlocking (achievements are "badges" on top of the level system)
- **Speed Protection**: Window-based rate limiting (e.g., max 1 smartpath run per 5 seconds counts towards score)
- **DB Reconciliation**: Every 30 minutes, engine recalculates stats from DB to catch missed events
- **Event-Driven**: All features emit achievement events → Engine processes → Store persists
- **Hub Integration**: Encrypted payload upload, dimension-based leaderboard sorting
- **Session Message Count**: Tracks messages per session to prevent session farming (only sessions with messages count towards score)

### 8. **Hub Architecture**

- **Phased Unlocking**: 3-second check → unlock animation → dashboard reveal
- **Dev Mode Gated**: Uses `useHubDevMode()` to check remote enablement
- **Shared Canvas**: Reuses `StarfieldCanvas` from stardom feature
- **Separate Server**: `sman-server/` is a standalone Express app with its own database

---

## User Data Structure

### `~/.sman/` (User Data Directory)

```
~/.sman/
├── config.json          # LLM + WebSearch + Chatbot + Auth config
├── registry.json        # Skills registry
├── sman.db              # Main SQLite database (sessions, messages, achievements, etc.)
├── claude-config/       # Isolated Claude CLI config directory
├── skills/              # Global skills (user-defined)
└── logs/                # Application logs
```

### `{workspace}/.sman/` (Project Workspace)

```
{workspace}/.sman/
├── INIT.md              # Workspace initialization result
├── knowledge/           # Team knowledge (git-shared)
│   ├── business-{username}.md
│   ├── conventions-{username}.md
│   └── technical-{username}.md
└── paths/               # Smart paths (file-based)
    └── {pathId}/
        ├── path.md
        ├── runs/
        ├── reports/
        └── references/
```

---

## Feature Organization

### Sidebar Navigation (Top-Level)

1. **新建会话** (New Session) → `/chat`
2. **协作星图** (Stardom) → `/stardom` (Multi-agent collaboration)
3. **组队** (Hub) → `/hub` (Team collaboration)
4. **定时任务** (Cron) → `/cron-tasks`
5. **地球路径** (Smart Paths) → `/smart-paths`
6. **设置** (Settings) → `/settings`

### Bottom Bar (Secondary)

- **成就** (Achievements) → `/achievements` (NEW)
- **代码查看器** (Code Viewer) → Slide-out panel
- **Git 面板** (Git) → Slide-out panel

---

## Dependencies & Integration

### Internal Dependencies

- **Achievement System** depends on:
  - Session store (for session/message counts)
  - WebSocket handler (for real-time updates)
  - Database (for persistence)
  - Hub (for leaderboard - optional)

- **Hub** depends on:
  - WebSocket (for real-time collaboration)
  - Separate database (sman-server/)
  - StarfieldCanvas (shared UI component)

- **Smart Paths** depends on:
  - Session management (for execution)
  - Workspace skills (for step execution rules)
  - Achievement system (emits `smartpath_run` events)

### External Dependencies

- **Claude Agent SDK**: `@anthropic-ai/claude-agent-sdk` v0.2.110+
- **Claude Code**: `@anthropic-ai/claude-code` v2.1.110+
- **Database**: better-sqlite3 (precompiled binaries, no native build required)
- **Screenshots**: node-screenshots (Rust XCap backend)
- **Build**: electron-vite, electron-builder, Vite

---

## Common Patterns

### 1. **Feature Module Structure**

```
src/features/{feature}/
├── index.tsx              # Main component
├── SubComponent.tsx       # Feature-specific components
├── useFeatureHook.ts      # Custom hooks (if any)
└── types.ts               # Feature-specific types
```

### 2. **Server Module Structure**

```
server/{module}-*.ts
├── {module}-store.ts      # Database persistence
├── {module}-engine.ts     # Business logic
├── {module}-events.ts     # Event emission
└── {module}-ws-handler.ts # WebSocket API
```

### 3. **i18n Pattern**

```tsx
// ✅ Correct: Use t() function
import { t } from '@/locales';
<button>{t('common.confirm')}</button>

// ❌ Wrong: Hardcoded string
<button>确定</button>
```

### 4. **State Management**

- **Zustand** for client-side state (achievements, settings, UI state)
- **React Query** for server state (sessions, cron tasks, hub data)
- **WebSocket** for real-time updates (chat, achievements)

---

## Migration Notes (Since Last Scan)

### What's NEW

1. **Achievement System** (5 server files, 5 frontend components, 1 type file)
   - 1,187 lines of server code
   - Full event-driven architecture
   - 96 achievements across 10 tiers
   - Hub leaderboard integration

2. **Hub Feature** (5 frontend components)
   - Phased unlocking mechanism
   - Reuses stardom canvas
   - Dev-mode gated

3. **i18n Expansion** (+552 lines)
   - Complete achievement system translations
   - Hub translations
   - General UI refinements

### What's MODIFIED

1. **Sidebar**: Enhanced interactivity (hover states, blur logic)
2. **Smart Paths**: Step editing, temp file rules
3. **Stardom Canvas**: Visual optimizations
4. **Routes**: Added `/achievements` and `/hub`

### What's UNCHANGED

- Build system (Vite, electron-vite, electron-builder)
- Core chat functionality
- Database schema (except new achievement tables)
- WebSocket protocol (except new achievement messages)
- Authentication & session management

---

## Quick Reference

### File Locations

| Purpose | File Path |
|---------|-----------|
| Route definitions | `src/app/routes.tsx` |
| Achievement engine | `server/achievement-engine.ts` |
| Achievement UI | `src/features/achievements/index.tsx` |
| Hub entry | `src/features/hub/HubEntry.tsx` |
| Translations | `src/locales/{lang}.json` |
| Database schema | `server/db.ts` (main), `sman-server/src/db.ts` (hub) |
| WebSocket handler | `server/index.ts` (main achievement ws handler integration) |

### Key Commands

| Task | Command |
|------|---------|
| Start dev | `./dev.sh` or `pnpm dev` + `pnpm dev:server` |
| Build | `pnpm build` |
| Package Windows | `bash build-win.sh` |
| Package macOS | `bash build-mac.sh` |
| Run tests | `pnpm test` |

### Environment Setup

- **Node.js**: 22 LTS (required for better-sqlite3 precompiled binaries)
- **Package Manager**: pnpm (required)
- **Platform Support**: Windows, macOS, Linux (Electron)

---

## Summary

**Since the last scan (c63e3fcf → 35398923)**, Sman has evolved with:

1. **Major Feature Addition**: Complete achievement/gamification system (1,187 lines of server code, 5 frontend components)
2. **New Collaboration Feature**: Hub entry with phased unlocking (5 components)
3. **Enhanced UX**: Sidebar interactivity, smart paths improvements, stardom canvas updates
4. **i18n Expansion**: +552 translation lines for full localization support
5. **Architectural Consistency**: All new features follow existing patterns (event-driven, WebSocket-based, i18n-compliant)

**Technical Debt**: None identified. The codebase maintains clean architecture with proper separation of concerns.

**Next Steps**: Consider documenting the Hub server architecture (sman-server/) in a future scan.
