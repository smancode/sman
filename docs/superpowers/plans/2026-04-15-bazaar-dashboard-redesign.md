# Bazaar Dashboard Redesign Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the pixel canvas world with a three-column audit dashboard that shows what agents are doing, what they can do, and lets users adjust agent capabilities.

**Architecture:** Three-column layout — left fixed panel (My Agent status), center elastic area (Activity Feed with expandable collaboration details), right collapsible panel (task queue + leaderboard + online agents). All built with Radix UI + TailwindCSS, consistent with existing Sman chat page style. Store and backend unchanged.

**Tech Stack:** React 19 + TypeScript + TailwindCSS + Radix UI + Zustand + lucide-react

**Spec:** `docs/superpowers/specs/2026-04-15-bazaar-dashboard-redesign.md`

---

## Chunk 1: Store Extension + Type Definitions

### Task 1: Add ActivityEntry type to bazaar types

**Files:**
- Modify: `src/types/bazaar.ts`

- [ ] **Step 1: Add ActivityEntry interface**

Add to the end of `src/types/bazaar.ts`, before the last export (or at end of file):

```typescript
export type ActivityType =
  | 'status_change'
  | 'task_event'
  | 'capability_search'
  | 'collab_start'
  | 'collab_complete'
  | 'reputation_change'
  | 'system';

export interface ActivityEntry {
  id: string;
  timestamp: number;
  type: ActivityType;
  agentId?: string;
  agentName?: string;
  agentAvatar?: string;
  description: string;
  metadata?: Record<string, unknown>;
}
```

- [ ] **Step 2: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS (no errors — new types only)

- [ ] **Step 3: Commit**

```bash
git add src/types/bazaar.ts
git commit -m "feat(bazaar): add ActivityEntry type for dashboard activity feed"
```

### Task 2: Extend bazaar store with activity log

**Files:**
- Modify: `src/stores/bazaar.ts`

- [ ] **Step 1: Add activityLog state and actions to the store interface**

In `src/stores/bazaar.ts`, extend the `BazaarState` interface (after `worldPositions`):

```typescript
import type { ActivityEntry } from '@/types/bazaar';

// Add to BazaarState interface:
activityLog: ActivityEntry[];
addActivity: (entry: Omit<ActivityEntry, 'id' | 'timestamp'>) => void;
```

- [ ] **Step 2: Add initial state and implementation**

In the store initial state:

```typescript
activityLog: [],
```

Add the `addActivity` action:

```typescript
addActivity: (entry) => {
  const fullEntry: ActivityEntry = {
    ...entry,
    id: `act-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    timestamp: Date.now(),
  };
  set((s) => ({
    activityLog: [fullEntry, ...s.activityLog].slice(0, 200),
  }));
},
```

- [ ] **Step 3: Wire activity logging into existing WS push handlers**

In the `registerPushListeners` function's `handle` callback, add `addActivity` calls for key events:

After `bazaar.status` connected handler:
```typescript
get().addActivity({
  type: 'status_change',
  agentId: msg.agentId as string,
  agentName: msg.agentName as string,
  description: `${msg.agentName ?? 'Agent'} 已连接到集市`,
});
```

After `bazaar.task.list.update`:
```typescript
// Log new tasks by comparing with previous
const prevTasks = get().tasks;
const newTasks = (msg.tasks as BazaarTask[]).filter(
  (t) => !prevTasks.some((p) => p.taskId === t.taskId)
);
for (const t of newTasks) {
  get().addActivity({
    type: 'task_event',
    agentId: t.direction === 'outgoing' ? get().connection.agentId : t.helperAgentId,
    agentName: t.direction === 'outgoing' ? get().connection.agentName : t.helperName,
    description: t.direction === 'outgoing'
      ? `发出协作请求: ${t.question}`
      : `收到协作请求: ${t.question}`,
    metadata: { taskId: t.taskId, direction: t.direction, status: t.status },
  });
}
```

After `bazaar.task.chat.delta`:
```typescript
get().addActivity({
  type: 'task_event',
  agentId: from,
  description: `${from}: ${text.slice(0, 50)}${text.length > 50 ? '...' : ''}`,
  metadata: { taskId },
});
```

After `bazaar.notify`:
```typescript
get().addActivity({
  type: 'task_event',
  agentId: msg.from as string,
  agentName: msg.from as string,
  description: `${msg.from} 请求协作: ${msg.question}`,
  metadata: { taskId: msg.taskId, mode: msg.mode },
});
```

After `bazaar.leaderboard.update`:
```typescript
const myId = get().connection.agentId;
const myEntry = (msg.leaderboard as BazaarLeaderboardEntry[]).find(
  (e: BazaarLeaderboardEntry) => e.agentId === myId
);
if (myEntry) {
  const prevReputation = get().connection.reputation ?? 0;
  if (myEntry.reputation !== prevReputation) {
    get().addActivity({
      type: 'reputation_change',
      agentId: myId,
      agentName: get().connection.agentName,
      description: `声望 ${myEntry.reputation > prevReputation ? '+' : ''}${myEntry.reputation - prevReputation}（当前 ${myEntry.reputation}）`,
      metadata: { reputation: myEntry.reputation, delta: myEntry.reputation - prevReputation },
    });
  }
}
```

- [ ] **Step 4: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/stores/bazaar.ts
git commit -m "feat(bazaar): add activity log to store with WS event logging"
```

---

## Chunk 2: Install Collapsible + Core Components

### Task 3: Install @radix-ui/react-collapsible and create collapsible wrapper

**Files:**
- Create: `src/components/ui/collapsible.tsx`

- [ ] **Step 1: Install the package**

Run: `pnpm add @radix-ui/react-collapsible`

- [ ] **Step 2: Create collapsible UI component wrapper**

Create `src/components/ui/collapsible.tsx`:

```typescript
import * as CollapsiblePrimitive from '@radix-ui/react-collapsible';

const Collapsible = CollapsiblePrimitive.Root;
const CollapsibleTrigger = CollapsiblePrimitive.Trigger;
const CollapsibleContent = CollapsiblePrimitive.Content;

export { Collapsible, CollapsibleTrigger, CollapsibleContent };
```

- [ ] **Step 3: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/components/ui/collapsible.tsx package.json pnpm-lock.yaml
git commit -m "feat(ui): add Radix Collapsible component"
```

### Task 4: Create MyAgentPanel component

**Files:**
- Create: `src/features/bazaar/components/MyAgentPanel.tsx`

This is the left fixed panel showing user's own agent info.

- [ ] **Step 1: Create MyAgentPanel**

Create `src/features/bazaar/components/MyAgentPanel.tsx`:

```typescript
// src/features/bazaar/components/MyAgentPanel.tsx
import { useBazaarStore } from '@/stores/bazaar';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Wifi, WifiOff, Clock, Trophy, Users, ArrowUp, ArrowDown, Minus } from 'lucide-react';
import { Label } from '@/components/ui/label';

function formatDuration(ms: number): string {
  const minutes = Math.floor(ms / 60000);
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  if (hours > 0) return `${hours}h ${mins}m`;
  return `${mins}m`;
}

function StatusDot({ status }: { status: string }) {
  const colorMap: Record<string, string> = {
    idle: 'bg-green-500',
    busy: 'bg-yellow-500',
    collaborating: 'bg-blue-500',
    afk: 'bg-gray-500',
    offline: 'bg-gray-400',
  };
  return (
    <span className={`inline-block w-2 h-2 rounded-full ${colorMap[status] ?? 'bg-gray-400'}`} />
  );
}

function ReputationTrend({ current, previous }: { current: number; previous?: number }) {
  if (previous === undefined) return <Minus className="h-3 w-3 text-muted-foreground" />;
  if (current > previous) return <ArrowUp className="h-3 w-3 text-green-500" />;
  if (current < previous) return <ArrowDown className="h-3 w-3 text-red-500" />;
  return <Minus className="h-3 w-3 text-muted-foreground" />;
}

const STATUS_LABELS: Record<string, string> = {
  idle: '空闲',
  busy: '忙碌',
  collaborating: '协作中',
  afk: '离开',
  offline: '离线',
};

export function MyAgentPanel() {
  const { connection, setMode, leaderboard } = useBazaarStore();
  const { connected, agentName, reputation, agentStatus } = connection;
  const agentAvatar = connection.agentId ? '🧙' : '👤';

  const myRank = leaderboard.findIndex((e) => e.agentId === connection.agentId) + 1;

  return (
    <div className="w-[200px] flex-shrink-0 border-r bg-background/80 backdrop-blur-sm flex flex-col">
      {/* Header: avatar + name */}
      <div className="p-3 space-y-2">
        <div className="flex items-center gap-2">
          <span className="text-2xl">{agentAvatar}</span>
          <div className="min-w-0 flex-1">
            <div className="font-medium text-sm truncate">{agentName ?? '未连接'}</div>
            <div className="flex items-center gap-1 text-xs text-muted-foreground">
              {connected ? (
                <><Wifi className="h-3 w-3 text-green-500" /> 已连接</>
              ) : (
                <><WifiOff className="h-3 w-3" /> 未连接</>
              )}
            </div>
          </div>
        </div>
      </div>

      <Separator />

      <ScrollArea className="flex-1">
        <div className="p-3 space-y-4">
          {/* Reputation */}
          <div className="space-y-1">
            <div className="flex items-center gap-1 text-xs text-muted-foreground">
              <Trophy className="h-3 w-3" style={{ color: '#E8C460' }} />
              声望
            </div>
            <div className="flex items-baseline gap-1">
              <span className="text-lg font-mono font-bold" style={{ color: '#E8C460' }}>
                {reputation ?? 0}
              </span>
              {myRank > 0 && (
                <span className="text-xs text-muted-foreground">
                  #{myRank}
                </span>
              )}
            </div>
          </div>

          {/* Status */}
          <div className="space-y-1">
            <div className="text-xs text-muted-foreground">状态</div>
            <div className="flex items-center gap-1.5">
              <StatusDot status={agentStatus ?? 'offline'} />
              <span className="text-sm">{STATUS_LABELS[agentStatus ?? 'offline']}</span>
            </div>
          </div>

          {/* Collaboration Mode */}
          <div className="space-y-2">
            <div className="text-xs text-muted-foreground">协作模式</div>
            <RadioGroup
              value={connection.collabMode ?? 'notify'}
              onValueChange={(v) => setMode(v as 'auto' | 'notify' | 'manual')}
              className="space-y-1"
            >
              <div className="flex items-center gap-2">
                <RadioGroupItem value="auto" id="mode-auto" className="h-3 w-3" />
                <Label htmlFor="mode-auto" className="text-xs cursor-pointer">全自动</Label>
              </div>
              <div className="flex items-center gap-2">
                <RadioGroupItem value="notify" id="mode-notify" className="h-3 w-3" />
                <Label htmlFor="mode-notify" className="text-xs cursor-pointer">半自动 30s</Label>
              </div>
              <div className="flex items-center gap-2">
                <RadioGroupItem value="manual" id="mode-manual" className="h-3 w-3" />
                <Label htmlFor="mode-manual" className="text-xs cursor-pointer">手动</Label>
              </div>
            </RadioGroup>
          </div>

          <Separator />

          {/* Stats */}
          <div className="space-y-1.5">
            <div className="flex items-center justify-between text-xs">
              <span className="text-muted-foreground flex items-center gap-1">
                <Users className="h-3 w-3" />
                协作次数
              </span>
              <span className="font-mono">{leaderboard.find((e) => e.agentId === connection.agentId)?.helpCount ?? 0}</span>
            </div>
          </div>
        </div>
      </ScrollArea>
    </div>
  );
}
```

- [ ] **Step 2: Add collabMode to BazaarConnectionStatus type**

In `src/types/bazaar.ts`, add `collabMode` to `BazaarConnectionStatus`:

```typescript
export interface BazaarConnectionStatus {
  connected: boolean;
  agentId?: string;
  agentName?: string;
  server?: string;
  agentStatus?: 'idle' | 'busy' | 'afk';
  reputation?: number;
  activeSlots: number;
  maxSlots: number;
  collabMode?: BazaarMode;
}
```

And update the `bazaar.status` connected handler in `src/stores/bazaar.ts` to include `collabMode`:

```typescript
collabMode: (msg.collabMode as BazaarMode) ?? 'notify',
```

- [ ] **Step 3: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/features/bazaar/components/MyAgentPanel.tsx src/types/bazaar.ts src/stores/bazaar.ts
git commit -m "feat(bazaar): add MyAgentPanel component with status, reputation, mode switch"
```

### Task 5: Create ActivityFeed component

**Files:**
- Create: `src/features/bazaar/components/ActivityFeed.tsx`
- Create: `src/features/bazaar/components/ActivityCard.tsx`
- Create: `src/features/bazaar/components/CollaborationDetail.tsx`

- [ ] **Step 1: Create ActivityCard**

Create `src/features/bazaar/components/ActivityCard.tsx`:

```typescript
// src/features/bazaar/components/ActivityCard.tsx
import type { ActivityEntry } from '@/types/bazaar';
import { cn } from '@/lib/utils';

const TYPE_ICONS: Record<string, string> = {
  status_change: '●',
  task_event: '📋',
  capability_search: '🔍',
  collab_start: '🤝',
  collab_complete: '✅',
  reputation_change: '⭐',
  system: '🔔',
};

const TYPE_COLORS: Record<string, string> = {
  status_change: 'text-muted-foreground',
  task_event: 'text-blue-400',
  capability_search: 'text-purple-400',
  collab_start: 'text-green-400',
  collab_complete: 'text-emerald-400',
  reputation_change: 'text-yellow-400',
  system: 'text-orange-400',
};

function timeAgo(timestamp: number): string {
  const diff = Date.now() - timestamp;
  const seconds = Math.floor(diff / 1000);
  if (seconds < 60) return '刚刚';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m 前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h 前`;
  return `${Math.floor(hours / 24)}d 前`;
}

interface ActivityCardProps {
  entry: ActivityEntry;
  isCollab?: boolean;
  isExpanded?: boolean;
  onClick?: () => void;
}

export function ActivityCard({ entry, isCollab, isExpanded, onClick }: ActivityCardProps) {
  const clickable = isCollab || entry.type === 'collab_start' || entry.type === 'task_event';

  return (
    <div
      className={cn(
        'flex gap-3 px-4 py-2.5 border-b border-border/50 transition-colors',
        clickable && 'cursor-pointer hover:bg-muted/50',
        isExpanded && 'bg-muted/30',
      )}
      onClick={clickable ? onClick : undefined}
    >
      {/* Icon */}
      <span className={cn('text-sm mt-0.5 flex-shrink-0', TYPE_COLORS[entry.type])}>
        {TYPE_ICONS[entry.type]}
      </span>

      {/* Content */}
      <div className="flex-1 min-w-0">
        <p className="text-sm leading-relaxed">{entry.description}</p>
        {entry.agentName && (
          <span className="text-xs text-muted-foreground">{entry.agentAvatar} {entry.agentName}</span>
        )}
      </div>

      {/* Timestamp */}
      <span className="text-xs text-muted-foreground flex-shrink-0 mt-0.5">
        {timeAgo(entry.timestamp)}
      </span>
    </div>
  );
}
```

- [ ] **Step 2: Create CollaborationDetail**

Create `src/features/bazaar/components/CollaborationDetail.tsx`:

```typescript
// src/features/bazaar/components/CollaborationDetail.tsx
import { useBazaarStore } from '@/stores/bazaar';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Button } from '@/components/ui/button';
import { Progress } from '@/components/ui/progress';
import { X, AlertTriangle } from 'lucide-react';
import { useState, useEffect } from 'react';

interface CollaborationDetailProps {
  taskId: string;
  onClose: () => void;
}

export function CollaborationDetail({ taskId, onClose }: CollaborationDetailProps) {
  const { activeChat, getTaskChat, acceptTask, rejectTask, notifications, clearActiveChat } = useBazaarStore();
  const messages = activeChat?.taskId === taskId ? activeChat.messages : getTaskChat(taskId);
  const notification = notifications.find((n) => n.taskId === taskId);

  // Countdown for notify mode
  const [remaining, setRemaining] = useState<number | null>(null);
  useEffect(() => {
    if (!notification?.countdownEndsAt) return;
    const end = new Date(notification.countdownEndsAt).getTime();
    const tick = () => {
      const left = Math.max(0, end - Date.now());
      setRemaining(left);
      if (left <= 0) return;
      requestAnimationFrame(tick);
    };
    tick();
  }, [notification?.countdownEndsAt]);

  return (
    <div className="border-t border-border bg-muted/20">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2 border-b border-border/50">
        <span className="text-sm font-medium">协作详情</span>
        <Button variant="ghost" size="sm" className="h-6 w-6 p-0" onClick={onClose}>
          <X className="h-3 w-3" />
        </Button>
      </div>

      {/* Messages */}
      <ScrollArea className="max-h-48 px-4 py-2">
        {messages.length === 0 ? (
          <p className="text-xs text-muted-foreground py-2">暂无对话消息</p>
        ) : (
          <div className="space-y-1.5">
            {messages.map((msg, i) => (
              <div key={i} className="text-sm">
                <span className="font-medium text-primary">{msg.from}:</span>{' '}
                <span className="text-muted-foreground">{msg.text}</span>
              </div>
            ))}
          </div>
        )}
      </ScrollArea>

      {/* Actions */}
      {notification && (
        <div className="flex items-center gap-2 px-4 py-2 border-t border-border/50">
          {remaining !== null && remaining > 0 && (
            <span className="text-xs text-yellow-500 flex items-center gap-1">
              <AlertTriangle className="h-3 w-3" />
              {Math.ceil(remaining / 1000)}s 后自动接受
            </span>
          )}
          <div className="flex-1" />
          <Button
            variant="outline"
            size="sm"
            className="h-7 text-xs"
            onClick={() => { rejectTask(taskId); onClose(); }}
          >
            拒绝
          </Button>
          <Button
            size="sm"
            className="h-7 text-xs"
            onClick={() => { acceptTask(taskId); onClose(); }}
          >
            接受
          </Button>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 3: Create ActivityFeed**

Create `src/features/bazaar/components/ActivityFeed.tsx`:

```typescript
// src/features/bazaar/components/ActivityFeed.tsx
import { useState } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { ScrollArea } from '@/components/ui/scroll-area';
import { ActivityCard } from './ActivityCard';
import { CollaborationDetail } from './CollaborationDetail';

export function ActivityFeed() {
  const { activityLog } = useBazaarStore();
  const [expandedTaskId, setExpandedTaskId] = useState<string | null>(null);

  const handleCardClick = (entry: typeof activityLog[number]) => {
    const taskId = entry.metadata?.taskId as string | undefined;
    if (!taskId) return;
    if (expandedTaskId === taskId) {
      setExpandedTaskId(null);
    } else {
      setExpandedTaskId(taskId);
    }
  };

  if (activityLog.length === 0) {
    return (
      <div className="flex-1 flex items-center justify-center text-muted-foreground text-sm">
        等待 Agent 活动...
      </div>
    );
  }

  return (
    <ScrollArea className="flex-1">
      <div className="divide-y divide-border/50">
        {activityLog.map((entry) => (
          <div key={entry.id}>
            <ActivityCard
              entry={entry}
              isCollab={
                entry.type === 'collab_start' ||
                entry.type === 'collab_complete' ||
                (entry.type === 'task_event' && !!entry.metadata?.taskId)
              }
              isExpanded={expandedTaskId != null && entry.metadata?.taskId === expandedTaskId}
              onClick={() => handleCardClick(entry)}
            />
            {expandedTaskId && entry.metadata?.taskId === expandedTaskId && (
              <CollaborationDetail
                taskId={expandedTaskId}
                onClose={() => setExpandedTaskId(null)}
              />
            )}
          </div>
        ))}
      </div>
    </ScrollArea>
  );
}
```

- [ ] **Step 4: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/features/bazaar/components/ActivityCard.tsx src/features/bazaar/components/ActivityFeed.tsx src/features/bazaar/components/CollaborationDetail.tsx
git commit -m "feat(bazaar): add ActivityFeed, ActivityCard, CollaborationDetail components"
```

---

## Chunk 3: Right Panel + Main Page Assembly

### Task 6: Create ControlPanel (right side)

**Files:**
- Create: `src/features/bazaar/components/ControlPanel.tsx`

- [ ] **Step 1: Create ControlPanel with three collapsible sections**

Create `src/features/bazaar/components/ControlPanel.tsx`:

```typescript
// src/features/bazaar/components/ControlPanel.tsx
import { useState } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { ChevronDown, ChevronRight, Trophy, Users, ClipboardList, Check, X } from 'lucide-react';
import { cn } from '@/lib/utils';

function SectionHeader({ label, count, open, onToggle }: {
  label: string; count?: number; open: boolean; onToggle: () => void;
}) {
  return (
    <button
      onClick={onToggle}
      className="flex items-center justify-between w-full px-3 py-2 text-sm font-medium hover:bg-muted/50 rounded"
    >
      <div className="flex items-center gap-2">
        {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
        {label}
        {count !== undefined && (
          <Badge variant="secondary" className="h-4 px-1.5 text-xs">{count}</Badge>
        )}
      </div>
    </button>
  );
}

function TaskQueue() {
  const { tasks, acceptTask, rejectTask } = useBazaarStore();
  const pending = tasks.filter((t) => t.status === 'offered' || t.status === 'searching');
  const [open, setOpen] = useState(true);

  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <SectionHeader label="任务队列" count={pending.length} open={open} onToggle={() => setOpen(!open)} />
      <CollapsibleContent>
        <div className="space-y-2 px-3 pb-2">
          {pending.length === 0 ? (
            <p className="text-xs text-muted-foreground py-1">暂无待处理任务</p>
          ) : (
            pending.map((task) => (
              <div key={task.taskId} className="rounded border border-border/50 p-2 space-y-1">
                <div className="flex items-center gap-1">
                  <span className="text-xs font-medium">
                    {task.direction === 'outgoing' ? '→ ' : '← '}
                    {task.helperName ?? task.requesterName ?? '未知'}
                  </span>
                </div>
                <p className="text-xs text-muted-foreground line-clamp-2">{task.question}</p>
                <div className="flex gap-1">
                  <Button
                    size="sm"
                    className="h-6 text-xs px-2"
                    onClick={() => acceptTask(task.taskId)}
                  >
                    <Check className="h-3 w-3 mr-0.5" />接受
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    className="h-6 text-xs px-2"
                    onClick={() => rejectTask(task.taskId)}
                  >
                    <X className="h-3 w-3 mr-0.5" />拒绝
                  </Button>
                </div>
              </div>
            ))
          )}
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

function Leaderboard() {
  const { leaderboard, connection } = useBazaarStore();
  const [open, setOpen] = useState(true);

  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <SectionHeader label="声望排行" open={open} onToggle={() => setOpen(!open)} />
      <CollapsibleContent>
        <div className="px-3 pb-2 space-y-1">
          {leaderboard.slice(0, 10).map((entry, i) => {
            const isMe = entry.agentId === connection.agentId;
            return (
              <div
                key={entry.agentId}
                className={cn(
                  'flex items-center justify-between py-1 px-1.5 rounded text-xs',
                  isMe && 'bg-primary/10 border border-primary/20',
                )}
              >
                <div className="flex items-center gap-2 min-w-0">
                  <span className="w-4 text-muted-foreground font-mono">{i + 1}.</span>
                  <span>{entry.avatar}</span>
                  <span className={cn('truncate', isMe && 'font-medium')}>{entry.name}</span>
                </div>
                <span className="font-mono flex items-center gap-0.5" style={{ color: '#E8C460' }}>
                  <Trophy className="h-3 w-3" />
                  {entry.reputation}
                </span>
              </div>
            );
          })}
          {leaderboard.length === 0 && (
            <p className="text-xs text-muted-foreground py-1">暂无排行数据</p>
          )}
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

function OnlineAgentList() {
  const { onlineAgents, connection } = useBazaarStore();
  const [open, setOpen] = useState(true);

  const STATUS_COLORS: Record<string, string> = {
    idle: 'bg-green-500',
    busy: 'bg-yellow-500',
    afk: 'bg-gray-500',
    offline: 'bg-gray-400',
  };

  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <SectionHeader label="在线 Agent" count={onlineAgents.length} open={open} onToggle={() => setOpen(!open)} />
      <CollapsibleContent>
        <div className="px-3 pb-2 space-y-1">
          {onlineAgents.map((agent) => {
            const isMe = agent.agentId === connection.agentId;
            return (
              <div
                key={agent.agentId}
                className={cn(
                  'flex items-center justify-between py-1 px-1.5 rounded text-xs',
                  isMe && 'bg-primary/10',
                )}
              >
                <div className="flex items-center gap-2 min-w-0">
                  <span className={`w-2 h-2 rounded-full flex-shrink-0 ${STATUS_COLORS[agent.status] ?? 'bg-gray-400'}`} />
                  <span>{agent.avatar}</span>
                  <span className="truncate">{agent.name}</span>
                </div>
                <span className="text-muted-foreground font-mono">{agent.reputation}</span>
              </div>
            );
          })}
          {onlineAgents.length === 0 && (
            <p className="text-xs text-muted-foreground py-1">暂无在线 Agent</p>
          )}
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

export function ControlPanel() {
  const [collapsed, setCollapsed] = useState(false);

  if (collapsed) {
    return (
      <button
        onClick={() => setCollapsed(false)}
        className="w-8 border-l bg-background/80 flex items-center justify-center hover:bg-muted/50"
      >
        <ChevronLeft className="h-4 w-4" />
      </button>
    );
  }

  return (
    <div className="w-[280px] flex-shrink-0 border-l bg-background/80 backdrop-blur-sm flex flex-col">
      {/* Toggle */}
      <div className="flex justify-end px-2 pt-2">
        <button onClick={() => setCollapsed(true)} className="text-muted-foreground hover:text-foreground">
          <ChevronRight className="h-4 w-4" />
        </button>
      </div>

      <ScrollArea className="flex-1">
        <div className="space-y-1 pb-4">
          <TaskQueue />
          <Leaderboard />
          <OnlineAgentList />
        </div>
      </ScrollArea>
    </div>
  );
}

// Missing import — add at top with others
function ChevronLeft(props: React.SVGProps<SVGSVGElement>) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...props}>
      <path d="m15 18-6-6 6-6" />
    </svg>
  );
}
```

- [ ] **Step 2: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/components/ControlPanel.tsx
git commit -m "feat(bazaar): add ControlPanel with task queue, leaderboard, online agents"
```

### Task 7: Create BazaarDashboard main page

**Files:**
- Create: `src/features/bazaar/BazaarDashboard.tsx`

- [ ] **Step 1: Create BazaarDashboard page component**

Create `src/features/bazaar/BazaarDashboard.tsx`:

```typescript
// src/features/bazaar/BazaarDashboard.tsx
import { useEffect } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { MyAgentPanel } from './components/MyAgentPanel';
import { ActivityFeed } from './components/ActivityFeed';
import { ControlPanel } from './components/ControlPanel';
import { TaskNotify } from './TaskNotify';
import { OnboardingGuide } from './OnboardingGuide';
import { Loader2 } from 'lucide-react';

export function BazaarDashboard() {
  const { fetchTasks, fetchOnlineAgents, fetchLeaderboard, loading } = useBazaarStore();

  useEffect(() => {
    fetchTasks();
    fetchOnlineAgents();
    fetchLeaderboard();
  }, [fetchTasks, fetchOnlineAgents, fetchLeaderboard]);

  return (
    <div className="flex h-full relative">
      {/* Collaboration request notifications */}
      <TaskNotify />

      {/* Left: My Agent fixed panel */}
      <MyAgentPanel />

      {/* Center: Activity feed (elastic width) */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {/* Loading indicator */}
        {loading && (
          <div className="flex items-center justify-center py-2 border-b border-border/50">
            <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
          </div>
        )}
        <ActivityFeed />
      </div>

      {/* Right: Control panel (collapsible) */}
      <ControlPanel />

      {/* First-time onboarding guide */}
      <OnboardingGuide />
    </div>
  );
}
```

- [ ] **Step 2: Update route to use BazaarDashboard**

In `src/app/routes.tsx`, replace the `BazaarPage` import with `BazaarDashboard`:

Change:
```typescript
import { BazaarPage } from '@/features/bazaar/BazaarPage';
```
To:
```typescript
import { BazaarDashboard } from '@/features/bazaar/BazaarDashboard';
```

And update the route:
```typescript
{ path: '/bazaar', element: <BazaarDashboard /> },
```

- [ ] **Step 3: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS (may have warnings about unused BazaarPage — that's fine for now)

- [ ] **Step 4: Commit**

```bash
git add src/features/bazaar/BazaarDashboard.tsx src/app/routes.tsx
git commit -m "feat(bazaar): create BazaarDashboard with three-column layout, update route"
```

---

## Chunk 4: Cleanup — Remove Pixel World

### Task 8: Delete world/ directory and remove BazaarPage

**Files:**
- Delete: `src/features/bazaar/world/` (entire directory)
- Delete: `src/features/bazaar/BazaarPage.tsx` (replaced by BazaarDashboard)

- [ ] **Step 1: Delete the world/ directory**

Run: `rm -rf src/features/bazaar/world/`

- [ ] **Step 2: Delete old BazaarPage**

Run: `rm src/features/bazaar/BazaarPage.tsx`

- [ ] **Step 3: Remove any imports referencing deleted files**

Check for any remaining imports of deleted modules. Likely files to check:
- `src/features/bazaar/ControlBar.tsx` — may import from `world/`
- `src/features/bazaar/AgentStatusBar.tsx` — may reference world
- `src/features/bazaar/TaskPanel.tsx` — should be clean
- `src/features/bazaar/OnlineAgents.tsx` — should be clean

Search and fix any broken imports. If any of these old components import from `world/`, remove or update those imports.

- [ ] **Step 4: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS (no broken imports)

- [ ] **Step 5: Verify the app builds**

Run: `pnpm build`
Expected: Build succeeds

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore(bazaar): remove pixel world canvas, delete BazaarPage and world/ directory"
```

---

## Chunk 5: Update Existing Components + Final Integration

### Task 9: Update TaskNotify to work with new dashboard layout

**Files:**
- Modify: `src/features/bazaar/TaskNotify.tsx`

- [ ] **Step 1: Read current TaskNotify.tsx and update**

Read `src/features/bazaar/TaskNotify.tsx`. It should work as-is since it's an overlay component that reads from `useBazaarStore.notifications`. Verify no `world/` imports exist. If the component references any world types or components, remove those references.

If no changes needed, skip to commit.

- [ ] **Step 2: Commit (if changed)**

```bash
git add src/features/bazaar/TaskNotify.tsx
git commit -m "fix(bazaar): update TaskNotify to remove world references"
```

### Task 10: Update OnboardingGuide and remaining components

**Files:**
- Modify: `src/features/bazaar/OnboardingGuide.tsx`
- Modify: `src/features/bazaar/AgentStatusBar.tsx` (if needed)
- Modify: `src/features/bazaar/ControlBar.tsx` (if needed)

- [ ] **Step 1: Audit remaining bazaar components for world/ imports**

Run: `grep -r "from.*world" src/features/bazaar/ --include="*.tsx" --include="*.ts" | grep -v components/ | grep -v BazaarDashboard`

Any matches need to be fixed.

- [ ] **Step 2: Fix broken imports**

For each file with broken imports, either:
- Remove the import if the component is no longer needed
- Replace with dashboard-equivalent logic

- [ ] **Step 3: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS

- [ ] **Step 4: Run build**

Run: `pnpm build`
Expected: Build succeeds

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(bazaar): update remaining components to remove world references"
```

### Task 11: Remove worldPositions from store (cleanup)

**Files:**
- Modify: `src/stores/bazaar.ts`
- Modify: `src/types/bazaar.ts`

- [ ] **Step 1: Remove worldPositions from store state**

In `src/stores/bazaar.ts`:
- Remove `worldPositions` from `BazaarState` interface
- Remove `worldPositions: new Map()` from initial state
- Remove `sendWorldMove` action
- Remove handlers for `bazaar.world.agent_update`, `bazaar.world.zone_snapshot`, `bazaar.world.agent_leave` in the push listener

- [ ] **Step 2: Remove WorldAgentPosition from types**

In `src/types/bazaar.ts`, remove the `WorldAgentPosition` interface.

- [ ] **Step 3: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/stores/bazaar.ts src/types/bazaar.ts
git commit -m "chore(bazaar): remove world position state and types"
```

---

## Verification

After all tasks are complete:

1. `npx tsc --noEmit` — no type errors
2. `pnpm build` — build succeeds
3. `npx vitest run` — existing tests pass (note: world/ tests are deleted, no other tests should be affected)
4. Manual verification: open `/bazaar` in browser, confirm three-column layout renders correctly

## File Summary

### Created
| File | Purpose |
|------|---------|
| `src/features/bazaar/BazaarDashboard.tsx` | New main page — three-column layout |
| `src/features/bazaar/components/MyAgentPanel.tsx` | Left panel — user's agent info |
| `src/features/bazaar/components/ActivityFeed.tsx` | Center — scrollable activity timeline |
| `src/features/bazaar/components/ActivityCard.tsx` | Single activity entry card |
| `src/features/bazaar/components/CollaborationDetail.tsx` | Expandable collaboration conversation |
| `src/features/bazaar/components/ControlPanel.tsx` | Right panel — task queue, leaderboard, agents |
| `src/components/ui/collapsible.tsx` | Radix Collapsible wrapper |

### Modified
| File | Change |
|------|--------|
| `src/types/bazaar.ts` | Add `ActivityEntry`, `ActivityType`, `collabMode` to `BazaarConnectionStatus`; remove `WorldAgentPosition` |
| `src/stores/bazaar.ts` | Add `activityLog` + `addActivity`; wire WS events to activity log; remove `worldPositions` + `sendWorldMove` + world handlers |
| `src/app/routes.tsx` | Route `/bazaar` → `BazaarDashboard` |
| `src/features/bazaar/TaskNotify.tsx` | Remove any world/ imports |
| `src/features/bazaar/OnboardingGuide.tsx` | Remove any world/ imports |

### Deleted
| Path | Reason |
|------|--------|
| `src/features/bazaar/world/` | Entire directory — canvas rendering engine |
| `src/features/bazaar/BazaarPage.tsx` | Replaced by BazaarDashboard |
