> **Note: Bazaar has been renamed to Stardom.**

# Bazaar 前端传送门 实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现前端传送门页面（看板模式），用户可查看 Agent 状态、在线 Agent 列表、任务列表、实时协作对话、每日摘要。Phase 1 MVP 只有看板模式，无像素世界 Canvas。

**Architecture:** 新增 `src/features/bazaar/` 目录，包含 BazaarPage 主页面、多个面板组件、Zustand store、hooks。通过 Bridge 层的 `bazaar.*` WebSocket 消息与后端通信。对现有代码只做 3 处最小改动：1 行路由、1 个 NavLink、1 个 Settings 区块。

**Tech Stack:** React 19 + TypeScript + TailwindCSS + Radix UI + Zustand, Vitest + @testing-library/react

**Design Doc:** `docs/superpowers/specs/2026-04-10-bazaar-agent-swarm-design.md`

**前置依赖：**
- Chunk 1 必须先完成（`shared/bazaar-types.ts` 中的 `BazaarConfig` 等共享类型）
- Chunk 2 必须先完成（`server/types.ts` 中的 `bazaar` 字段、Bridge 的 `bazaar.*` 消息路由、初始连接状态推送）
- 本 Chunk 不可独立编译，必须按 Chunk 1 → 2 → 3 顺序执行

---

## Chunk 1: 基础设施（类型 + Store + Hooks）

### Task 1: 前端 Bazaar 类型

**Files:**
- Create: `src/types/bazaar.ts`

- [ ] **Step 1: 定义前端类型**

```typescript
// src/types/bazaar.ts

export interface BazaarAgentInfo {
  agentId: string;
  name: string;
  avatar: string;
  status: 'idle' | 'busy' | 'afk' | 'offline';
  reputation: number;
  projects: string[];
}

export interface BazaarTask {
  taskId: string;
  direction: 'incoming' | 'outgoing';
  helperAgentId?: string;
  helperName?: string;
  requesterAgentId?: string;
  requesterName?: string;
  question: string;
  status: 'offered' | 'chatting' | 'completed' | 'timeout' | 'cancelled';
  rating?: number;
  createdAt: string;
  completedAt?: string;
}

export interface BazaarChatMessage {
  taskId: string;
  from: string;
  text: string;
  timestamp: string;
}

export interface BazaarDigest {
  date: string;
  helpCount: number;
  helpedByCount: number;
  reputationDelta: number;
  timeSavedMinutes: number;
  details: Array<{
    agentName: string;
    question: string;
    duration: string;
    direction: 'in' | 'out';
  }>;
}

export type BazaarMode = 'auto' | 'notify' | 'manual';

export interface BazaarConnectionStatus {
  connected: boolean;
  agentId?: string;
  agentName?: string;
  server?: string;
  agentStatus?: 'idle' | 'busy' | 'afk';
  reputation?: number;
  activeSlots: number;
  maxSlots: number;
}
```

- [ ] **Step 2: Commit**

```bash
mkdir -p src/types
git add src/types/bazaar.ts
git commit -m "feat(bazaar): add frontend bazaar type definitions"
```

---

### Task 2: Zustand Store

**Files:**
- Create: `src/stores/bazaar.ts`

- [ ] **Step 1: 实现 Bazaar Store**

```typescript
// src/stores/bazaar.ts
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type {
  BazaarTask,
  BazaarConnectionStatus,
  BazaarChatMessage,
  BazaarDigest,
  BazaarAgentInfo,
  BazaarMode,
} from '@/types/bazaar';

type MsgHandler = (msg: Record<string, unknown>) => void;

function getWsClient() {
  return useWsConnection.getState().client;
}

function wrapHandler(
  client: { on: (e: string, h: (...a: unknown[]) => void) => void; off: (e: string, h: (...a: unknown[]) => void) => void },
  event: string,
  handler: MsgHandler,
) {
  const wrapped = (...args: unknown[]) => handler(args[0] as Record<string, unknown>);
  client.on(event, wrapped);
  return () => client.off(event, wrapped);
}

interface BazaarState {
  connection: BazaarConnectionStatus;
  tasks: BazaarTask[];
  onlineAgents: BazaarAgentInfo[];
  activeChat: {
    taskId: string;
    messages: BazaarChatMessage[];
  } | null;
  digest: BazaarDigest | null;
  loading: boolean;
  error: string | null;

  // Actions
  fetchTasks: () => void;
  fetchOnlineAgents: () => void;
  cancelTask: (taskId: string) => void;
  setActiveChat: (taskId: string) => void;
  clearActiveChat: () => void;
  setMode: (mode: BazaarMode) => void;
  clearError: () => void;
}

let set: (partial: Partial<BazaarState> | ((state: BazaarState) => Partial<BazaarState>)) => void;

export const useBazaarStore = create<BazaarState>((storeSet) => {
  set = storeSet;

  return {
    connection: {
      connected: false,
      activeSlots: 0,
      maxSlots: 3,
    },
    tasks: [],
    onlineAgents: [],
    activeChat: null,
    digest: null,
    loading: false,
    error: null,

    fetchTasks: () => {
      const client = getWsClient();
      if (!client) return;
      set({ loading: true });
      client.send({ type: 'bazaar.task.list' });
    },

    fetchOnlineAgents: () => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'bazaar.agent.list' });
    },

    cancelTask: (taskId: string) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'bazaar.task.cancel', payload: { taskId } });
    },

    setActiveChat: (taskId: string) => {
      set({ activeChat: { taskId, messages: [] } });
    },

    clearActiveChat: () => {
      set({ activeChat: null });
    },

    setMode: (mode: BazaarMode) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'bazaar.config.update', payload: { mode } });
    },

    clearError: () => set({ error: null }),
  };
});

// ── WebSocket push listener ──

let pushListenerRegistered = false;

function registerPushListeners() {
  if (pushListenerRegistered) return;
  pushListenerRegistered = true;

  const handle = (msg: Record<string, unknown>) => {
    if (!msg.type?.toString().startsWith('bazaar.')) return;

    const type = msg.type as string;

    if (type === 'bazaar.status') {
      const event = msg.event as string;
      if (event === 'connected') {
        set((s) => ({
          connection: {
            ...s.connection,
            connected: true,
            agentId: msg.agentId as string,
            agentName: msg.agentName as string,
            reputation: (msg.reputation as number) ?? 0,
            activeSlots: (msg.activeSlots as number) ?? 0,
            maxSlots: (msg.maxSlots as number) ?? 3,
          },
        }));
      } else if (event === 'disconnected') {
        set((s) => ({ connection: { ...s.connection, connected: false } }));
      }
    } else if (type === 'bazaar.task.list.update') {
      set({ tasks: msg.tasks as BazaarTask[], loading: false });
    } else if (type === 'bazaar.agent.list.update') {
      set({ onlineAgents: msg.agents as BazaarAgentInfo[] });
    } else if (type === 'bazaar.task.chat.delta') {
      set((s) => {
        if (!s.activeChat || s.activeChat.taskId !== msg.taskId) return s;
        return {
          activeChat: {
            ...s.activeChat,
            messages: [...s.activeChat.messages, {
              taskId: msg.taskId as string,
              from: msg.from as string,
              text: msg.text as string,
              timestamp: new Date().toISOString(),
            }],
          },
        };
      });
    } else if (type === 'bazaar.notify') {
      // 协作请求通知 — 不在 store 中处理，由组件直接监听
    } else if (type === 'bazaar.digest') {
      set({ digest: msg as unknown as BazaarDigest });
    }
  };

  const unsub = useWsConnection.subscribe((state, prev) => {
    if (state.client && state.client !== prev.client) {
      state.client.on('message', handle as (...a: unknown[]) => void);
    }
  });

  const currentClient = useWsConnection.getState().client;
  if (currentClient) {
    currentClient.on('message', handle as (...a: unknown[]) => void);
  }
}

registerPushListeners();
```

- [ ] **Step 2: Commit**

```bash
git add src/stores/bazaar.ts
git commit -m "feat(bazaar): add Bazaar Zustand store with WS push listeners"
```

---

## Chunk 2: 核心组件

### Task 3: BazaarPage 主页面

**Files:**
- Create: `src/features/bazaar/BazaarPage.tsx`

- [ ] **Step 1: 实现主页面**

```tsx
// src/features/bazaar/BazaarPage.tsx
import { useEffect } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { AgentStatusBar } from './AgentStatusBar';
import { TaskPanel } from './TaskPanel';
import { OnlineAgents } from './OnlineAgents';
import { ControlBar } from './ControlBar';
import { OnboardingGuide } from './OnboardingGuide';
import { ArrowLeft, Loader2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';

export function BazaarPage() {
  const navigate = useNavigate();
  const { connection, fetchTasks, fetchOnlineAgents, loading } = useBazaarStore();

  useEffect(() => {
    fetchTasks();
    fetchOnlineAgents();
  }, [fetchTasks, fetchOnlineAgents]);

  // 未连接集市时显示配置提示
  if (!connection.connected) {
    return (
      <div className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
        <p>未连接到集市服务器</p>
        <p className="text-sm">请在「设置」中配置集市服务器地址</p>
        <Button variant="outline" onClick={() => navigate('/settings')}>
          前往设置
        </Button>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      {/* 顶栏 */}
      <div className="flex items-center justify-between px-4 py-2 border-b">
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => navigate('/chat')}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <h2 className="text-lg font-semibold">传送门</h2>
          {loading && <Loader2 className="h-4 w-4 animate-spin" />}
        </div>
      </div>

      {/* 主内容区 — 看板模式 */}
      <div className="flex-1 flex overflow-hidden">
        {/* 左侧：任务列表 */}
        <div className="w-1/2 border-r overflow-y-auto p-4">
          <TaskPanel />
        </div>

        {/* 右侧：在线 Agent + 控制栏 */}
        <div className="w-1/2 flex flex-col overflow-hidden">
          <div className="flex-1 overflow-y-auto p-4">
            <OnlineAgents />
          </div>
          <ControlBar />
        </div>
      </div>

      {/* 底部状态栏 */}
      <AgentStatusBar />

      {/* 首次引导 */}
      <OnboardingGuide />
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
mkdir -p src/features/bazaar
git add src/features/bazaar/BazaarPage.tsx
git commit -m "feat(bazaar): add BazaarPage main layout component"
```

---

### Task 4: AgentStatusBar 底部状态栏

**Files:**
- Create: `src/features/bazaar/AgentStatusBar.tsx`

- [ ] **Step 1: 实现状态栏**

```tsx
// src/features/bazaar/AgentStatusBar.tsx
import { useBazaarStore } from '@/stores/bazaar';
import { Circle, Zap } from 'lucide-react';

export function AgentStatusBar() {
  const { connection } = useBazaarStore();
  const statusColor = connection.agentStatus === 'idle' ? 'text-green-500' :
    connection.agentStatus === 'busy' ? 'text-yellow-500' : 'text-gray-400';

  return (
    <div className="flex items-center justify-between px-4 py-2 border-t bg-muted/30 text-sm text-muted-foreground">
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-1.5">
          <Circle className={`h-2.5 w-2.5 fill-current ${statusColor}`} />
          <span>
            {connection.agentName ?? 'Agent'}: {connection.agentStatus ?? '未知'}
          </span>
        </div>
        <span>声望 {connection.reputation ?? 0}</span>
        <span>槽位 {connection.activeSlots}/{connection.maxSlots}</span>
      </div>
      <div className="flex items-center gap-1.5">
        <Zap className="h-3.5 w-3.5" />
        <span>集市已连接</span>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add src/features/bazaar/AgentStatusBar.tsx
git commit -m "feat(bazaar): add AgentStatusBar bottom status bar"
```

---

### Task 5: OnlineAgents 在线 Agent 列表

**Files:**
- Create: `src/features/bazaar/OnlineAgents.tsx`

- [ ] **Step 1: 实现在线列表**

```tsx
// src/features/bazaar/OnlineAgents.tsx
import { useBazaarStore } from '@/stores/bazaar';
import { Users } from 'lucide-react';

export function OnlineAgents() {
  const { onlineAgents } = useBazaarStore();

  return (
    <div>
      <div className="flex items-center gap-2 mb-3">
        <Users className="h-4 w-4 text-muted-foreground" />
        <h3 className="font-medium text-sm">在线 Agent ({onlineAgents.length})</h3>
      </div>

      {onlineAgents.length === 0 ? (
        <p className="text-sm text-muted-foreground py-4 text-center">暂无其他 Agent 在线</p>
      ) : (
        <div className="space-y-2">
          {onlineAgents.map((agent) => (
            <div
              key={agent.agentId}
              className="flex items-center justify-between p-2 rounded-lg border hover:bg-muted/50 cursor-pointer transition-colors"
            >
              <div className="flex items-center gap-2">
                <span className="text-xl">{agent.avatar}</span>
                <div>
                  <div className="text-sm font-medium">{agent.name}</div>
                  <div className="text-xs text-muted-foreground">
                    {agent.projects.join(', ')}
                  </div>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <span className={`text-xs px-2 py-0.5 rounded-full ${
                  agent.status === 'idle' ? 'bg-green-100 text-green-700' :
                  agent.status === 'busy' ? 'bg-yellow-100 text-yellow-700' :
                  'bg-gray-100 text-gray-500'
                }`}>
                  {agent.status === 'idle' ? '空闲' : agent.status === 'busy' ? '忙碌' : '离开'}
                </span>
                <span className="text-xs text-muted-foreground">⭐ {agent.reputation}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add src/features/bazaar/OnlineAgents.tsx
git commit -m "feat(bazaar): add OnlineAgents list component"
```

---

### Task 6: TaskPanel 任务列表

**Files:**
- Create: `src/features/bazaar/TaskPanel.tsx`
- Create: `src/features/bazaar/TaskCard.tsx`

- [ ] **Step 1: 实现 TaskCard**

```tsx
// src/features/bazaar/TaskCard.tsx
import type { BazaarTask } from '@/types/bazaar';
import { Button } from '@/components/ui/button';
import { ArrowRight, ArrowLeft, Clock, Star } from 'lucide-react';

interface TaskCardProps {
  task: BazaarTask;
  onClick: (taskId: string) => void;
  onCancel: (taskId: string) => void;
}

const statusLabels: Record<string, { label: string; color: string }> = {
  offered: { label: '待接受', color: 'bg-blue-100 text-blue-700' },
  chatting: { label: '协作中', color: 'bg-green-100 text-green-700' },
  completed: { label: '已完成', color: 'bg-gray-100 text-gray-500' },
  timeout: { label: '超时', color: 'bg-red-100 text-red-700' },
  cancelled: { label: '已取消', color: 'bg-gray-100 text-gray-500' },
};

export function TaskCard({ task, onClick, onCancel }: TaskCardProps) {
  const status = statusLabels[task.status] ?? { label: task.status, color: 'bg-gray-100 text-gray-500' };
  const isActive = task.status === 'offered' || task.status === 'chatting';

  return (
    <div
      className={`p-3 rounded-lg border ${isActive ? 'border-primary/30 bg-primary/5' : 'border-border'} cursor-pointer hover:bg-muted/50 transition-colors`}
      onClick={() => onClick(task.taskId)}
    >
      <div className="flex items-center justify-between mb-1.5">
        <div className="flex items-center gap-1.5">
          {task.direction === 'outgoing' ? (
            <ArrowRight className="h-3.5 w-3.5 text-blue-500" />
          ) : (
            <ArrowLeft className="h-3.5 w-3.5 text-green-500" />
          )}
          <span className="text-xs text-muted-foreground">
            {task.direction === 'outgoing' ? '我求助' : '帮我'}
          </span>
        </div>
        <span className={`text-xs px-2 py-0.5 rounded-full ${status.color}`}>
          {status.label}
        </span>
      </div>

      <p className="text-sm mb-1.5 line-clamp-2">{task.question}</p>

      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <span>{task.direction === 'outgoing' ? task.helperName : task.requesterName}</span>
          <span className="flex items-center gap-0.5"><Clock className="h-3 w-3" />{new Date(task.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}</span>
          {task.rating && <span className="flex items-center gap-0.5"><Star className="h-3 w-3" />{task.rating}</span>}
        </div>

        {isActive && (
          <Button
            variant="ghost"
            size="sm"
            className="h-6 text-xs text-red-500 hover:text-red-600"
            onClick={(e) => { e.stopPropagation(); onCancel(task.taskId); }}
          >
            终止
          </Button>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 实现 TaskPanel**

```tsx
// src/features/bazaar/TaskPanel.tsx
import { useBazaarStore } from '@/stores/bazaar';
import { TaskCard } from './TaskCard';
import { ListTodo } from 'lucide-react';

export function TaskPanel() {
  const { tasks, setActiveChat, cancelTask } = useBazaarStore();

  const activeTasks = tasks.filter(t => t.status === 'offered' || t.status === 'chatting');
  const pastTasks = tasks.filter(t => t.status === 'completed' || t.status === 'timeout' || t.status === 'cancelled');

  return (
    <div>
      <div className="flex items-center gap-2 mb-3">
        <ListTodo className="h-4 w-4 text-muted-foreground" />
        <h3 className="font-medium text-sm">任务列表</h3>
      </div>

      {tasks.length === 0 ? (
        <p className="text-sm text-muted-foreground py-8 text-center">
          暂无协作任务<br />
          <span className="text-xs">Agent 会自动搜索能力并协助你</span>
        </p>
      ) : (
        <>
          {activeTasks.length > 0 && (
            <div className="mb-4">
              <div className="text-xs text-muted-foreground mb-2">进行中 ({activeTasks.length})</div>
              <div className="space-y-2">
                {activeTasks.map(task => (
                  <TaskCard key={task.taskId} task={task} onClick={setActiveChat} onCancel={cancelTask} />
                ))}
              </div>
            </div>
          )}

          {pastTasks.length > 0 && (
            <div>
              <div className="text-xs text-muted-foreground mb-2">历史 ({pastTasks.length})</div>
              <div className="space-y-2">
                {pastTasks.map(task => (
                  <TaskCard key={task.taskId} task={task} onClick={setActiveChat} onCancel={cancelTask} />
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
```

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/TaskPanel.tsx src/features/bazaar/TaskCard.tsx
git commit -m "feat(bazaar): add TaskPanel and TaskCard components"
```

---

### Task 7: ControlBar 控制栏

**Files:**
- Create: `src/features/bazaar/ControlBar.tsx`

- [ ] **Step 1: 实现控制栏**

```tsx
// src/features/bazaar/ControlBar.tsx
import { useBazaarStore } from '@/stores/bazaar';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Label } from '@/components/ui/label';
import type { BazaarMode } from '@/types/bazaar';

const modeLabels: Record<BazaarMode, string> = {
  auto: '全自动',
  notify: '半自动（推荐）',
  manual: '手动',
};

export function ControlBar() {
  const { connection, setMode } = useBazaarStore();

  return (
    <div className="flex items-center gap-4 px-4 py-2 border-t bg-muted/30">
      <div className="flex items-center gap-2">
        <Label className="text-xs text-muted-foreground">模式</Label>
        <Select defaultValue="notify" onValueChange={(v) => setMode(v as BazaarMode)}>
          <SelectTrigger className="h-7 w-32 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {Object.entries(modeLabels).map(([value, label]) => (
              <SelectItem key={value} value={value} className="text-xs">{label}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="text-xs text-muted-foreground">
        槽位 {connection.activeSlots}/{connection.maxSlots}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add src/features/bazaar/ControlBar.tsx
git commit -m "feat(bazaar): add ControlBar with mode selector"
```

---

### Task 8: OnboardingGuide 首次引导

**Files:**
- Create: `src/features/bazaar/OnboardingGuide.tsx`

- [ ] **Step 1: 实现引导弹窗**

```tsx
// src/features/bazaar/OnboardingGuide.tsx
import { useState, useEffect } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { Button } from '@/components/ui/button';
import { Sparkles, X } from 'lucide-react';

const ONBOARDED_KEY = 'bazaar-onboarded';

export function OnboardingGuide() {
  const [open, setOpen] = useState(false);
  const { connection } = useBazaarStore();

  useEffect(() => {
    if (connection.connected && !localStorage.getItem(ONBOARDED_KEY)) {
      setOpen(true);
    }
  }, [connection.connected]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-background rounded-xl p-6 max-w-md mx-4 shadow-xl">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <Sparkles className="h-5 w-5 text-primary" />
            <h3 className="font-semibold">欢迎来到集市！</h3>
          </div>
          <Button variant="ghost" size="sm" className="h-6 w-6 p-0" onClick={() => setOpen(false)}>
            <X className="h-4 w-4" />
          </Button>
        </div>

        <div className="space-y-3 text-sm text-muted-foreground">
          <p>你的 Agent 已加入集市。</p>
          <p>Agent 会在需要时自动搜索其他 Agent 的能力，帮你找到最合适的人。</p>
          <div className="bg-muted rounded-lg p-3 text-xs">
            <div className="font-medium text-foreground mb-1">你可以：</div>
            <ul className="space-y-1 list-disc list-inside">
              <li>在左侧任务列表查看进行中的协作</li>
              <li>在右侧查看在线的其他 Agent</li>
              <li>调整协作模式（全自动/半自动/手动）</li>
            </ul>
          </div>
        </div>

        <div className="mt-4">
          <Button
            className="w-full"
            onClick={() => {
              localStorage.setItem(ONBOARDED_KEY, 'true');
              setOpen(false);
            }}
          >
            开始探索
          </Button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add src/features/bazaar/OnboardingGuide.tsx
git commit -m "feat(bazaar): add OnboardingGuide first-time dialog"
```

---

## Chunk 3: 集成到现有页面（路由 + 侧边栏 + 设置）

### Task 9: 路由 + 侧边栏变更

**Files:**
- Modify: `src/app/routes.tsx`（新增 1 行）
- Modify: `src/components/layout/Sidebar.tsx`（新增 1 个 NavLink）

- [ ] **Step 1: 新增路由**

在 `src/app/routes.tsx` 的 import 区新增：

```typescript
import { BazaarPage } from '@/features/bazaar/BazaarPage';
```

在 children 数组中，`batch-tasks` 路由之后新增：

```typescript
{ path: 'bazaar', element: <BazaarPage /> },
```

- [ ] **Step 2: 新增侧边栏 NavLink**

在 `src/components/layout/Sidebar.tsx` 的 import 中新增 `Sparkles`：

```typescript
import { Settings as SettingsIcon, Sun, Moon, Clock, Layers, Sparkles } from 'lucide-react';
```

在定时任务 NavLink **之前**新增传送门入口：

```tsx
        <NavLink
          to="/bazaar"
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
              <div
                className={cn(
                  'flex shrink-0 items-center justify-center',
                  isActive ? 'text-foreground' : 'text-muted-foreground',
                )}
              >
                <Sparkles className="h-[18px] w-[18px]" strokeWidth={2} />
              </div>
              <span>传送门</span>
            </>
          )}
        </NavLink>
```

- [ ] **Step 3: 验证编译**

Run: `npx tsc --noEmit 2>&1 | head -10`
Expected: 无错误

- [ ] **Step 4: Commit**

```bash
git add src/app/routes.tsx src/components/layout/Sidebar.tsx
git commit -m "feat(bazaar): add /bazaar route and sidebar portal link"
```

---

### Task 10: Settings 页面新增集市配置区块

**Files:**
- Create: `src/features/bazaar/BazaarSettings.tsx`
- Modify: `src/features/settings/index.tsx`（新增 1 个 Tab）

- [ ] **Step 1: 实现集市设置区块**

```tsx
// src/features/bazaar/BazaarSettings.tsx
import { useState } from 'react';
import { Server, User, Shield, Save } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { useSettingsStore } from '@/stores/settings';
import { useWsConnection } from '@/stores/ws-connection';

export function BazaarSettings() {
  const settings = useSettingsStore((s) => s.settings);
  const send = useWsConnection((s) => s.client?.send.bind(s.client));
  const bazaar = settings?.bazaar;

  const [server, setServer] = useState(bazaar?.server ?? '');
  const [agentName, setAgentName] = useState(bazaar?.agentName ?? '');
  const [mode, setMode] = useState(bazaar?.mode ?? 'notify');
  const [maxSlots, setMaxSlots] = useState(bazaar?.maxConcurrentTasks ?? 3);
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    setSaving(true);
    try {
      // 通过 settings.update WS 消息保存配置到后端
      send?.({
        type: 'settings.update',
        bazaar: { server, agentName: agentName || undefined, mode, maxConcurrentTasks: maxSlots },
      });
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Server className="h-5 w-5" />
          集市配置
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label>集市服务器地址</Label>
          <Input
            placeholder="bazaar.company.com:5890"
            value={server}
            onChange={(e) => setServer(e.target.value)}
          />
          <p className="text-xs text-muted-foreground">企业内网集市服务器地址</p>
        </div>

        <div className="space-y-2">
          <Label className="flex items-center gap-1"><User className="h-3.5 w-3.5" /> Agent 显示名</Label>
          <Input
            placeholder="你的名字（可选）"
            value={agentName}
            onChange={(e) => setAgentName(e.target.value)}
          />
        </div>

        <div className="space-y-2">
          <Label className="flex items-center gap-1"><Shield className="h-3.5 w-3.5" /> 协作模式</Label>
          <Select value={mode} onValueChange={setMode}>
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="notify">半自动（推荐）</SelectItem>
              <SelectItem value="auto">全自动</SelectItem>
              <SelectItem value="manual">手动</SelectItem>
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            半自动：接任务前通知你，30秒无响应自动接
          </p>
        </div>

        <div className="space-y-2">
          <Label>最大并发槽位</Label>
          <Select value={String(maxSlots)} onValueChange={(v) => setMaxSlots(Number(v))}>
            <SelectTrigger className="w-24">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(n => (
                <SelectItem key={n} value={String(n)}>{n}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <Button onClick={handleSave} disabled={saving || !server} className="w-full">
          <Save className="h-4 w-4 mr-1" />
          {saving ? '保存中...' : '保存配置'}
        </Button>
      </CardContent>
    </Card>
  );
}
```

- [ ] **Step 2: 在 Settings 页面新增集市配置区块**

在 `src/features/settings/index.tsx` 中：
- 新增 import: `import { BazaarSettings } from '@/features/bazaar/BazaarSettings';`
- 在 `<WebSearchSettings />` 之后新增 `<BazaarSettings />`（Settings 页面是垂直卡片堆叠，不是 Tab 模式）

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/BazaarSettings.tsx src/features/settings/index.tsx
git commit -m "feat(bazaar): add BazaarSettings and integrate into Settings page"
```

---

### Task 11: 验证完整编译

- [ ] **Step 1: TypeScript 编译检查**

Run: `npx tsc --noEmit 2>&1 | head -20`
Expected: 无错误

- [ ] **Step 2: 运行已有测试确认无回归**

Run: `npx vitest run 2>&1 | tail -10`
Expected: 所有测试通过

- [ ] **Step 3: 修复编译问题（如有）**

- [ ] **Step 4: Final Commit**

```bash
git add -A
git commit -m "chore(bazaar): verify frontend compilation and existing tests"
```

---

## 总结

**本计划覆盖的文件**：

| 文件 | 说明 | 类型 |
|------|------|------|
| `src/types/bazaar.ts` | 前端类型定义 | 新增 |
| `src/stores/bazaar.ts` | Zustand store | 新增 |
| `src/features/bazaar/BazaarPage.tsx` | 传送门主页面 | 新增 |
| `src/features/bazaar/AgentStatusBar.tsx` | 底部状态栏 | 新增 |
| `src/features/bazaar/OnlineAgents.tsx` | 在线 Agent 列表 | 新增 |
| `src/features/bazaar/TaskPanel.tsx` | 任务列表面板 | 新增 |
| `src/features/bazaar/TaskCard.tsx` | 任务卡片 | 新增 |
| `src/features/bazaar/ControlBar.tsx` | 控制栏 | 新增 |
| `src/features/bazaar/OnboardingGuide.tsx` | 首次引导 | 新增 |
| `src/features/bazaar/BazaarSettings.tsx` | 集市设置 | 新增 |
| `src/app/routes.tsx` | 新增 1 行路由 | 最小改动 |
| `src/components/layout/Sidebar.tsx` | 新增 1 个 NavLink | 最小改动 |
| `src/features/settings/index.tsx` | 新增 1 个 Settings 区块 | 最小改动 |

**对现有代码的改动**：
- `routes.tsx`: 新增 1 行 `{ path: 'bazaar', element: <BazaarPage /> }`
- `Sidebar.tsx`: 新增 1 个 `<NavLink to="/bazaar">` + 1 个 import
- `settings/index.tsx`: 新增 `<BazaarSettings />` 组件 + 1 个 import

**MVP 范围裁剪（Phase 2 再实现）**：
- ChatPanel 对话面板（点击任务卡片的对话视图）
- DailyDigest 每日摘要组件
- notify 模式 30 秒超时自动接受
- manual 模式前端接受/拒绝 UI
- Bridge 层 `bazaar.agent.list` / `bazaar.task.detail` handler（当前 Chunk 2 只处理已定义的消息类型）

**判定标准**：删除 `src/features/bazaar/` 目录，还原 `routes.tsx`、`Sidebar.tsx`、`settings/index.tsx` 的 3 行改动后，项目编译运行零报错。
