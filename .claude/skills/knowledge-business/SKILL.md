---
name: knowledge-business
description: "业务知识：产品需求、用户流程、业务规则、领域术语。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "70d53baa472e0b2f87d9b0080e3239118c1f1ec7"
  scannedAt: "2026-05-22T00:00:00.000Z"
  branch: "master"
---

# Business Knowledge

> Contributors: nasakim | Verified: 2026-05-22

## Core Product Positioning
> by nasakim | Verified: 2026-05
✅ [Verified] CLAUDE.md:L1-10
- **Sman**: Intelligent business system assistant, multi-terminal conversational interface
- Four platforms: Desktop (Electron), WeCom Bot, Feishu Bot, WeChat Bot
- Zero pre-configuration: select project directory to start chatting

## Top Business Flows

### 1. Multi-User IM with Agent @Mentions ⚠️ NEW
> by nasakim | Verified: 2026-05-22
✅ [Verified] server/im/, src/features/im/
- **User Flow**: Create group chat → Invite members → @mention agent (e.g., "@bot-xxx") → IMAgentBridge activates Claude session → Stream response → Broadcast to all room members → Update message status (running/completed/failed)
- **Business Rules**:
  - **Agent activation only triggers for owned agents**: Each client filters `mentionedAgents` to only those with workspace opened locally
  - **Decoupled architecture**: IMAgentBridge depends on injected callbacks (`createOrGetSession`, `streamSessionMessage`), not direct ClaudeSessionManager coupling
  - **Message types**: text/agent_output/agent_error (status: running/completed/failed)
  - **Hub sync**: Messages forwarded to Hub WebSocket for cross-device sync
  - **Typing indicators**: Real-time typing status broadcast to room
- **Pain Points Solved**: No real-time collaboration, agent responses isolated to single user, no multi-user brainstorming

### 2. Smart Path User-Editable Steps (Persistent) ⚠️ UPDATED
> by nasakim | Verified: 2026-05-22
✅ [Verified] server/smart-path-engine.ts, src/stores/smart-path.ts
- **User Flow**: Start stepping → Execute step 0 → User edits step input/delivery check in UI → Edits stored in `stepEdits` state → Next step uses edited input → Finalize → Edits written back to path.md (persistent)
- **Business Rules**:
  - **Editable fields**: `userInput`, `name`, `deliveryCheck` (NOT `description`, prevents breaking historical context)
  - **Persistence on finalize**: `stepEdits` merged into path.md steps JSON, survives re-runs
  - **State tracking**: `stepEdits: Record<number, Partial<SmartPathStep>>`, applied via `applyStepEdits()` before each execution
  - **Execution history**: `smartpath_run_log` table tracks mode (full/stepping), step count, status, error message
  - **Cancellation support**: Abort updates run status to "cancelled", clears active runs
- **Pain Points Solved**: Cannot correct step inputs mid-execution, iterative refinement lost after run, no execution history visibility

### 3. Achievement Scoring Update (Status-Weighted Smart Paths) ⚠️ UPDATED
> by nasakim | Verified: 2026-05-22
✅ [Verified] server/achievement-engine.ts, src/types/achievement.ts
- **User Flow**: Execute Smart Path → Record status (completed/failed/cancelled) → AchievementEngine calculates points using status weights → Update `smartpath_score` metric → Contribute to total score → Tier progression
- **Business Rules**:
  - **Status-weighted scoring**: Completed = 2 points, Failed = 0.5 points, Cancelled/ignored = 0 points
  - **Tier thresholds increased**: Star(2500), King(4000), Legend(6000), Epic(9000), Eternal(15000) — higher requirements for top tiers
  - **Eternal icon changed**: "droplet" → "infinity" (better semantic fit)
  - **Tier colors refined**: Bronze/Silver/Platinum adjusted for better visual hierarchy
  - **DB-backed scoring**: `smartpath_run_log` table source of truth, no filesystem scanning
- **Pain Points Solved**: Failed runs rewarded equally to completed, tier progression too fast, inconsistent achievement tracking

## Core Features
> by nasakim | Verified: 2026-05
✅ [Verified] CLAUDE.md:L45-50
- **Stardom**: Multi-agent collaboration network, reputation system, task routing, remote cooperation
- **Smart Path**: Multi-step automation, stepping execution, previous step result as next step context
- **Cron Tasks**: Cron expression-driven automation, manual trigger support, queue management
- **Knowledge Management**: Auto-extract business/conventions/technical knowledge, team share via git push
- **IM (NEW)**: Multi-user group chat, agent @mention activation, Hub sync, typing indicators

## Sidebar Entry Points
> by nasakim | Verified: 2026-05
✅ [Verified] CLAUDE.md:L30-36, src/components/layout/Sidebar.tsx
1. New Session 2. New Group 3. Stardom 4. Hub 5. Cron Tasks 6. Smart Path 7. Settings 8. Code Viewer 9. Git Panel 10. IM (NEW)

## Chatbot Commands
> by nasakim | Verified: 2026-05
✅ [Verified] CLAUDE.md:L180-195
`//cd` (switch directory), `//pwd` (show current directory), `//workspaces` (list opened projects), `//status` (connection status), `//help`

## Key UX Principles
> by nasakim | Verified: 2026-05
