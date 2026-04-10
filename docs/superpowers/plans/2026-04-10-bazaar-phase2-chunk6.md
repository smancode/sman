# Bazaar Phase 2 Chunk 6: 前端协作 UI 实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在前端传送门页面中实现协作对话面板（CollaborationChat）、协作请求通知组件（TaskNotify）含 notify 模式 30 秒倒计时自动接受、三栏布局改造。用户可实时观看 Agent 协作对话，手动接受/拒绝协作请求，在 notify 模式下看到倒计时通知。

**Architecture:** 在现有 `src/features/bazaar/` 基础上，将 BazaarPage 从两栏改为三栏布局（左：TaskPanel / 中：CollaborationChat / 右：OnlineAgents + ControlBar）。扩展 Zustand store 新增 `notifications` 状态和 `acceptTask` / `rejectTask` action。新增 `BazaarNotification` 类型。push listener 增强 `bazaar.notify` 和 `bazaar.task.chat.delta` 处理。

**Tech Stack:** React 19 + TypeScript + TailwindCSS + Radix UI + Zustand, Vitest + @testing-library/react

**Design Doc:** `docs/superpowers/specs/2026-04-10-bazaar-agent-swarm-design.md`

**前置依赖：**
- Phase 1 全部 Chunk（1-3）必须已完成：前端类型 + Store + 组件 + 路由 + 侧边栏
- Phase 2 Chunk 4（集市服务器任务引擎）必须已完成：Bridge 层广播 `bazaar.notify` 消息
- Phase 2 Chunk 5（Bridge 协作会话）必须已完成：Bridge 层 `bazaar.task.accept` / `bazaar.task.reject` handler、`bazaar.task.chat.delta` 推送

---

## Task 1: 扩展前端类型

**Files:**
- Modify: `src/types/bazaar.ts`

- [ ] **Step 1: 新增 BazaarNotification 类型，扩展 BazaarTask status 枚举**

在 `src/types/bazaar.ts` 中：

1. 新增 `BazaarNotification` interface（放在 `BazaarChatMessage` 之后）：

```typescript
export interface BazaarNotification {
  notificationId: string;
  taskId: string;
  from: string;           // 请求方 Agent 名
  question: string;       // 协作问题摘要
  mode: 'auto' | 'notify' | 'manual';  // 协作模式
  receivedAt: string;     // ISO timestamp
  countdownEndsAt: string | null;  // notify 模式：倒计时结束时间；其他模式为 null
}
```

2. 扩展 `BazaarTask.status` 类型，在现有联合类型中增加 `searching` 和 `matched`：

```typescript
// 旧
status: 'offered' | 'chatting' | 'completed' | 'timeout' | 'cancelled';
// 新
status: 'searching' | 'offered' | 'matched' | 'chatting' | 'completed' | 'timeout' | 'cancelled';
```

3. 新增 `BazaarTaskChat` 多任务对话存储类型（放在 `BazaarNotification` 之后）：

```typescript
export interface BazaarTaskChat {
  taskId: string;
  messages: BazaarChatMessage[];
}
```

完整的修改后文件内容见下方：

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
  status: 'searching' | 'offered' | 'matched' | 'chatting' | 'completed' | 'timeout' | 'cancelled';
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

export interface BazaarNotification {
  notificationId: string;
  taskId: string;
  from: string;
  question: string;
  mode: 'auto' | 'notify' | 'manual';
  receivedAt: string;
  countdownEndsAt: string | null;
}

export interface BazaarTaskChat {
  taskId: string;
  messages: BazaarChatMessage[];
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

- [ ] **Step 2: 验证类型编译**

```bash
npx tsc --noEmit 2>&1 | grep -i "bazaar" | head -5
```

Expected: 无错误。如果 TaskCard 的 `statusLabels` 缺少新状态，后续 Task 会补充。

- [ ] **Step 3: Commit**

```bash
git add src/types/bazaar.ts
git commit -m "feat(bazaar): add BazaarNotification type and extend task status enum"
```

---

## Task 2: 扩展 Zustand Store

**Files:**
- Modify: `src/stores/bazaar.ts`

- [ ] **Step 1: 新增 notifications 状态和协作 action**

将 `src/stores/bazaar.ts` 完整替换为以下内容：

```typescript
// src/stores/bazaar.ts
import { create } from 'zustand';
import { useWsConnection } from '@/stores/ws-connection';
import type {
  BazaarTask,
  BazaarConnectionStatus,
  BazaarChatMessage,
  BazaarAgentInfo,
  BazaarMode,
  BazaarDigest,
  BazaarNotification,
  BazaarTaskChat,
} from '@/types/bazaar';

function getWsClient() {
  return useWsConnection.getState().client;
}

// notify 模式倒计时：30 秒
const NOTIFY_COUNTDOWN_MS = 30_000;

// 多任务对话存储：key = taskId
const taskChatMap = new Map<string, BazaarChatMessage[]>();

interface BazaarState {
  connection: BazaarConnectionStatus;
  tasks: BazaarTask[];
  onlineAgents: BazaarAgentInfo[];
  activeChat: BazaarTaskChat | null;
  notifications: BazaarNotification[];
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
  acceptTask: (taskId: string) => void;
  rejectTask: (taskId: string) => void;
  dismissNotification: (notificationId: string) => void;
  getTaskChat: (taskId: string) => BazaarChatMessage[];
}

let set: (partial: Partial<BazaarState> | ((state: BazaarState) => Partial<BazaarState>)) => void;

// 存储 notify 模式的倒计时 timer，key = notificationId
const countdownTimers = new Map<string, ReturnType<typeof setTimeout>>();

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
    notifications: [],
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
      // 从 taskChatMap 恢复历史消息到 activeChat
      const existingMessages = taskChatMap.get(taskId) ?? [];
      set({ activeChat: { taskId, messages: existingMessages } });
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

    acceptTask: (taskId: string) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'bazaar.task.accept', payload: { taskId } });
      // 从通知列表中移除该任务的通知
      set((s) => ({
        notifications: s.notifications.filter((n) => n.taskId !== taskId),
      }));
    },

    rejectTask: (taskId: string) => {
      const client = getWsClient();
      if (!client) return;
      client.send({ type: 'bazaar.task.reject', payload: { taskId } });
      // 从通知列表中移除
      set((s) => ({
        notifications: s.notifications.filter((n) => n.taskId !== taskId),
      }));
    },

    dismissNotification: (notificationId: string) => {
      // 清除倒计时 timer
      const timer = countdownTimers.get(notificationId);
      if (timer) {
        clearTimeout(timer);
        countdownTimers.delete(notificationId);
      }
      set((s) => ({
        notifications: s.notifications.filter((n) => n.notificationId !== notificationId),
      }));
    },

    getTaskChat: (taskId: string) => {
      return taskChatMap.get(taskId) ?? [];
    },
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
      const taskId = msg.taskId as string;
      const from = msg.from as string;
      const text = msg.text as string;
      const chatMsg: BazaarChatMessage = {
        taskId,
        from,
        text,
        timestamp: new Date().toISOString(),
      };

      // 存入 taskChatMap（多任务对话缓存）
      const existing = taskChatMap.get(taskId) ?? [];
      taskChatMap.set(taskId, [...existing, chatMsg]);

      // 如果是当前 activeChat 的任务，同步更新 activeChat
      set((s) => {
        if (!s.activeChat || s.activeChat.taskId !== taskId) return s;
        return {
          activeChat: {
            ...s.activeChat,
            messages: [...s.activeChat.messages, chatMsg],
          },
        };
      });
    } else if (type === 'bazaar.notify') {
      // 协作请求通知
      const mode = msg.mode as 'auto' | 'notify' | 'manual';
      const notificationId = msg.notificationId as string ?? `notif-${Date.now()}`;
      const taskId = msg.taskId as string;
      const now = new Date();

      const notification: BazaarNotification = {
        notificationId,
        taskId,
        from: msg.from as string,
        question: msg.question as string,
        mode,
        receivedAt: now.toISOString(),
        countdownEndsAt: mode === 'notify'
          ? new Date(now.getTime() + NOTIFY_COUNTDOWN_MS).toISOString()
          : null,
      };

      set((s) => ({
        notifications: [...s.notifications, notification],
      }));

      // notify 模式：启动倒计时，到期自动 accept
      if (mode === 'notify') {
        const timer = setTimeout(() => {
          // 自动接受
          const client = getWsClient();
          if (client) {
            client.send({ type: 'bazaar.task.accept', payload: { taskId } });
          }
          // 移除通知
          set((s) => ({
            notifications: s.notifications.filter((n) => n.notificationId !== notificationId),
          }));
          countdownTimers.delete(notificationId);
        }, NOTIFY_COUNTDOWN_MS);
        countdownTimers.set(notificationId, timer);
      }
      // auto 模式：不显示通知，Bridge 层已自动处理
      // manual 模式：显示通知，等待用户操作
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

**关键变更说明：**

1. **新增 `notifications: BazaarNotification[]` 状态** — 存储所有待处理的协作请求通知
2. **新增 `acceptTask(taskId)`** — 发送 `bazaar.task.accept` 并移除对应通知
3. **新增 `rejectTask(taskId)`** — 发送 `bazaar.task.reject` 并移除对应通知
4. **新增 `dismissNotification(notificationId)`** — 手动关闭通知，清除倒计时 timer
5. **新增 `getTaskChat(taskId)`** — 获取多任务对话缓存
6. **`bazaar.notify` handler** — 收到通知后：auto 不处理 / notify 启动 30 秒倒计时 / manual 等待用户操作
7. **`bazaar.task.chat.delta` handler 增强** — 同时写入 `taskChatMap`（多任务对话缓存）和 `activeChat`（当前查看的任务）
8. **`setActiveChat` 增强** — 从 `taskChatMap` 恢复历史消息，不会丢失之前收到的 delta

- [ ] **Step 2: 验证类型编译**

```bash
npx tsc --noEmit 2>&1 | grep -i "bazaar\|stores/bazaar" | head -10
```

Expected: 无错误

- [ ] **Step 3: Commit**

```bash
git add src/stores/bazaar.ts
git commit -m "feat(bazaar): extend store with notifications, accept/reject actions, and multi-task chat"
```

---

## Task 3: 更新 TaskCard 状态标签

**Files:**
- Modify: `src/features/bazaar/TaskCard.tsx`

- [ ] **Step 1: 为新增的 `searching` 和 `matched` 状态补充标签**

在 `src/features/bazaar/TaskCard.tsx` 的 `statusLabels` 对象中新增两个状态：

```typescript
const statusLabels: Record<string, { label: string; color: string }> = {
  searching: { label: '搜索中', color: 'bg-yellow-100 text-yellow-700' },
  offered: { label: '待接受', color: 'bg-blue-100 text-blue-700' },
  matched: { label: '已匹配', color: 'bg-indigo-100 text-indigo-700' },
  chatting: { label: '协作中', color: 'bg-green-100 text-green-700' },
  completed: { label: '已完成', color: 'bg-gray-100 text-gray-500' },
  timeout: { label: '超时', color: 'bg-red-100 text-red-700' },
  cancelled: { label: '已取消', color: 'bg-gray-100 text-gray-500' },
};
```

同时更新 `isActive` 判断逻辑，加入 `searching` 和 `matched`：

```typescript
const isActive = task.status === 'searching' || task.status === 'offered' || task.status === 'matched' || task.status === 'chatting';
```

- [ ] **Step 2: Commit**

```bash
git add src/features/bazaar/TaskCard.tsx
git commit -m "feat(bazaar): add searching/matched status labels to TaskCard"
```

---

## Task 4: 实现 CollaborationChat 协作对话面板

**Files:**
- Create: `src/features/bazaar/CollaborationChat.tsx`

- [ ] **Step 1: 实现协作对话面板**

```tsx
// src/features/bazaar/CollaborationChat.tsx
import { useEffect, useRef } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { Button } from '@/components/ui/button';
import { MessageSquare, X, User } from 'lucide-react';

export function CollaborationChat() {
  const { activeChat, connection, clearActiveChat, cancelTask } = useBazaarStore();
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // 自动滚动到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [activeChat?.messages.length]);

  if (!activeChat) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-muted-foreground gap-3 p-4">
        <MessageSquare className="h-10 w-10 text-muted-foreground/40" />
        <p className="text-sm">点击左侧任务卡片查看协作对话</p>
        <p className="text-xs text-muted-foreground/60">选择一个进行中的任务，实时查看 Agent 协作过程</p>
      </div>
    );
  }

  const myAgentName = connection.agentName ?? '';
  const messages = activeChat.messages;

  return (
    <div className="flex flex-col h-full">
      {/* 头部：任务信息 */}
      <div className="flex items-center justify-between px-3 py-2 border-b bg-muted/20">
        <div className="flex items-center gap-2 min-w-0">
          <MessageSquare className="h-4 w-4 text-primary shrink-0" />
          <span className="text-sm font-medium truncate">
            任务 {activeChat.taskId.slice(0, 8)}
          </span>
          <span className="text-xs text-muted-foreground">
            {messages.length} 条消息
          </span>
        </div>
        <div className="flex items-center gap-1">
          <Button
            variant="ghost"
            size="sm"
            className="h-6 text-xs text-red-500 hover:text-red-600"
            onClick={() => cancelTask(activeChat.taskId)}
          >
            终止协作
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="h-6 w-6 p-0"
            onClick={clearActiveChat}
          >
            <X className="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>

      {/* 消息列表 */}
      <div className="flex-1 overflow-y-auto p-3 space-y-3">
        {messages.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-muted-foreground gap-2">
            <p className="text-sm">等待对话开始...</p>
            <p className="text-xs">协作建立后，消息将实时显示在这里</p>
          </div>
        ) : (
          messages.map((msg, idx) => {
            const isMine = msg.from === myAgentName;
            return (
              <div
                key={idx}
                className={`flex ${isMine ? 'justify-end' : 'justify-start'}`}
              >
                <div className={`max-w-[80%] ${isMine ? 'order-1' : 'order-1'}`}>
                  {/* 发送者名称 */}
                  <div className={`flex items-center gap-1 mb-0.5 ${isMine ? 'justify-end' : 'justify-start'}`}>
                    <span className="text-xs text-muted-foreground flex items-center gap-0.5">
                      {isMine && <User className="h-3 w-3" />}
                      {msg.from}
                      {!isMine && <User className="h-3 w-3" />}
                    </span>
                    <span className="text-xs text-muted-foreground/60">
                      {new Date(msg.timestamp).toLocaleTimeString('zh-CN', {
                        hour: '2-digit',
                        minute: '2-digit',
                        second: '2-digit',
                      })}
                    </span>
                  </div>
                  {/* 消息气泡 */}
                  <div
                    className={`rounded-lg px-3 py-2 text-sm ${
                      isMine
                        ? 'bg-primary text-primary-foreground'
                        : 'bg-muted text-foreground'
                    }`}
                  >
                    <p className="whitespace-pre-wrap break-words">{msg.text}</p>
                  </div>
                </div>
              </div>
            );
          })
        )}
        <div ref={messagesEndRef} />
      </div>
    </div>
  );
}
```

**设计要点：**
- 空状态（无 activeChat）：显示提示文案和图标
- 有 activeChat：三段布局（头部 + 消息列表 + 底部留白）
- 消息区分左右：通过 `msg.from === myAgentName` 判断，自己的消息在右侧（primary 色），对方在左侧（muted 色）
- 自动滚动：`useEffect` 监听 `messages.length` 变化，`scrollIntoView` 平滑滚动
- 头部提供「终止协作」和「关闭」按钮

- [ ] **Step 2: Commit**

```bash
git add src/features/bazaar/CollaborationChat.tsx
git commit -m "feat(bazaar): add CollaborationChat panel with real-time message display"
```

---

## Task 5: 实现 TaskNotify 协作请求通知组件

**Files:**
- Create: `src/features/bazaar/TaskNotify.tsx`

- [ ] **Step 1: 实现通知组件**

```tsx
// src/features/bazaar/TaskNotify.tsx
import { useEffect, useState } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { Button } from '@/components/ui/button';
import { Bell, Check, X, Clock } from 'lucide-react';

function CountdownTimer({ endsAt }: { endsAt: string }) {
  const [remaining, setRemaining] = useState(() => {
    const diff = new Date(endsAt).getTime() - Date.now();
    return Math.max(0, Math.ceil(diff / 1000));
  });

  useEffect(() => {
    const interval = setInterval(() => {
      const diff = new Date(endsAt).getTime() - Date.now();
      const seconds = Math.max(0, Math.ceil(diff / 1000));
      setRemaining(seconds);
      if (seconds <= 0) {
        clearInterval(interval);
      }
    }, 1000);
    return () => clearInterval(interval);
  }, [endsAt]);

  return (
    <span className="flex items-center gap-1 text-xs text-amber-600">
      <Clock className="h-3 w-3" />
      {remaining}s 后自动接受
    </span>
  );
}

export function TaskNotify() {
  const { notifications, acceptTask, rejectTask } = useBazaarStore();

  // 只显示 notify 和 manual 模式的通知
  const visibleNotifications = notifications.filter(
    (n) => n.mode === 'notify' || n.mode === 'manual'
  );

  if (visibleNotifications.length === 0) return null;

  return (
    <div className="absolute top-12 left-0 right-0 z-40 flex flex-col items-center gap-2 px-4 pointer-events-none">
      {visibleNotifications.map((notification) => (
        <div
          key={notification.notificationId}
          className="pointer-events-auto w-full max-w-lg bg-background border border-primary/30 rounded-lg shadow-lg p-3 animate-in slide-in-from-top-2"
        >
          <div className="flex items-start gap-3">
            <div className="shrink-0 mt-0.5">
              <Bell className="h-5 w-5 text-primary" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-1">
                <span className="text-sm font-medium">协作请求</span>
                {notification.mode === 'notify' && (
                  <span className="text-xs px-1.5 py-0.5 rounded bg-amber-100 text-amber-700">
                    半自动
                  </span>
                )}
                {notification.mode === 'manual' && (
                  <span className="text-xs px-1.5 py-0.5 rounded bg-blue-100 text-blue-700">
                    需确认
                  </span>
                )}
              </div>
              <p className="text-sm text-muted-foreground mb-1">
                <span className="font-medium text-foreground">{notification.from}</span>
                {' 向你请求帮助'}
              </p>
              <p className="text-sm line-clamp-2">{notification.question}</p>

              {/* 倒计时 / 按钮 */}
              <div className="flex items-center gap-2 mt-2">
                {notification.mode === 'notify' && notification.countdownEndsAt && (
                  <CountdownTimer endsAt={notification.countdownEndsAt} />
                )}
                <div className="flex-1" />
                <Button
                  size="sm"
                  className="h-7 text-xs"
                  onClick={() => acceptTask(notification.taskId)}
                >
                  <Check className="h-3 w-3 mr-1" />
                  接受
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  className="h-7 text-xs"
                  onClick={() => rejectTask(notification.taskId)}
                >
                  <X className="h-3 w-3 mr-1" />
                  拒绝
                </Button>
              </div>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
```

**设计要点：**
- 通知浮层固定在 BazaarPage 顶部（通过 `absolute` 定位），不阻塞主内容交互（`pointer-events-none` 容器 + `pointer-events-auto` 卡片）
- auto 模式：不显示通知（Bridge 层自动处理）
- notify 模式：显示倒计时 + 接受/拒绝按钮。倒计时 `CountdownTimer` 组件每秒更新，显示剩余秒数
- manual 模式：只显示接受/拒绝按钮，无倒计时
- 通知卡片带滑入动画（`animate-in slide-in-from-top-2`）
- 最多同时显示多个通知（纵向堆叠）

- [ ] **Step 2: Commit**

```bash
git add src/features/bazaar/TaskNotify.tsx
git commit -m "feat(bazaar): add TaskNotify component with countdown and accept/reject UI"
```

---

## Task 6: 改造 BazaarPage 三栏布局

**Files:**
- Modify: `src/features/bazaar/BazaarPage.tsx`

- [ ] **Step 1: 改造为三栏布局 + 集成 TaskNotify**

将 `src/features/bazaar/BazaarPage.tsx` 替换为：

```tsx
// src/features/bazaar/BazaarPage.tsx
import { useEffect } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { AgentStatusBar } from './AgentStatusBar';
import { TaskPanel } from './TaskPanel';
import { OnlineAgents } from './OnlineAgents';
import { ControlBar } from './ControlBar';
import { CollaborationChat } from './CollaborationChat';
import { TaskNotify } from './TaskNotify';
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
    <div className="flex flex-col h-full relative">
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

      {/* 协作请求通知浮层 */}
      <TaskNotify />

      {/* 主内容区 — 三栏看板模式 */}
      <div className="flex-1 flex overflow-hidden">
        {/* 左栏：任务列表 */}
        <div className="w-1/3 border-r overflow-y-auto p-4">
          <TaskPanel />
        </div>

        {/* 中栏：协作对话面板 */}
        <div className="w-1/3 border-r overflow-hidden">
          <CollaborationChat />
        </div>

        {/* 右栏：在线 Agent + 控制栏 */}
        <div className="w-1/3 flex flex-col overflow-hidden">
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

**布局变更说明：**
- 从两栏（`w-1/2` + `w-1/2`）改为三栏（`w-1/3` + `w-1/3` + `w-1/3`）
- 左栏：TaskPanel（任务列表，点击任务切换中间栏的对话）
- 中栏：CollaborationChat（实时协作对话，无 activeChat 时显示空状态）
- 右栏：OnlineAgents + ControlBar（在线 Agent 列表 + 模式控制栏）
- 顶部浮动 TaskNotify（协作请求通知）
- 外层容器增加 `relative` 定位以支持 TaskNotify 的 absolute 浮层

- [ ] **Step 2: 验证编译**

```bash
npx tsc --noEmit 2>&1 | grep -i "BazaarPage\|CollaborationChat\|TaskNotify" | head -10
```

Expected: 无错误

- [ ] **Step 3: Commit**

```bash
git add src/features/bazaar/BazaarPage.tsx
git commit -m "feat(bazaar): refactor BazaarPage to 3-column layout with chat panel and notifications"
```

---

## Task 7: 更新 TaskPanel 的 activeTasks 过滤

**Files:**
- Modify: `src/features/bazaar/TaskPanel.tsx`

- [ ] **Step 1: 更新 activeTasks 过滤条件，包含新的状态**

将 `TaskPanel.tsx` 中 `activeTasks` 过滤从：

```typescript
const activeTasks = tasks.filter(t => t.status === 'offered' || t.status === 'chatting');
```

改为：

```typescript
const activeTasks = tasks.filter(t =>
  t.status === 'searching' || t.status === 'offered' || t.status === 'matched' || t.status === 'chatting'
);
```

完整的更新后文件：

```tsx
// src/features/bazaar/TaskPanel.tsx
import { useBazaarStore } from '@/stores/bazaar';
import { TaskCard } from './TaskCard';
import { ListTodo } from 'lucide-react';

export function TaskPanel() {
  const { tasks, setActiveChat, cancelTask } = useBazaarStore();

  const activeTasks = tasks.filter(t =>
    t.status === 'searching' || t.status === 'offered' || t.status === 'matched' || t.status === 'chatting'
  );
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

- [ ] **Step 2: Commit**

```bash
git add src/features/bazaar/TaskPanel.tsx
git commit -m "feat(bazaar): update TaskPanel active filter to include searching/matched statuses"
```

---

## Task 8: 验证完整编译和测试

- [ ] **Step 1: TypeScript 编译检查**

```bash
npx tsc --noEmit 2>&1 | head -20
```

Expected: 无错误

- [ ] **Step 2: 运行已有测试确认无回归**

```bash
npx vitest run 2>&1 | tail -20
```

Expected: 所有测试通过

- [ ] **Step 3: 修复编译问题（如有）**

- [ ] **Step 4: Final Commit**

```bash
git add -A
git commit -m "chore(bazaar): verify compilation and tests pass after Phase 2 Chunk 6"
```

---

## 总结

**本计划覆盖的文件：**

| 文件 | 说明 | 类型 |
|------|------|------|
| `src/types/bazaar.ts` | 新增 `BazaarNotification`、`BazaarTaskChat`，扩展 `BazaarTask.status` | 修改 |
| `src/stores/bazaar.ts` | 新增 `notifications` 状态、`acceptTask`/`rejectTask`/`dismissNotification` action、`bazaar.notify` handler（含 30 秒倒计时）、多任务对话缓存 | 修改 |
| `src/features/bazaar/CollaborationChat.tsx` | 协作对话面板（左右消息气泡、自动滚动、空状态） | 新增 |
| `src/features/bazaar/TaskNotify.tsx` | 协作请求通知浮层（倒计时、接受/拒绝按钮） | 新增 |
| `src/features/bazaar/BazaarPage.tsx` | 三栏布局改造（左 TaskPanel / 中 CollaborationChat / 右 OnlineAgents + ControlBar） | 修改 |
| `src/features/bazaar/TaskCard.tsx` | 补充 `searching`/`matched` 状态标签 | 修改 |
| `src/features/bazaar/TaskPanel.tsx` | 更新 activeTasks 过滤条件 | 修改 |

**对现有代码的改动范围：**
- `types/bazaar.ts`：新增 2 个 interface，扩展 1 个 status 联合类型
- `stores/bazaar.ts`：新增 4 个 action、增强 2 个 push handler、新增多任务对话缓存
- `TaskCard.tsx`：补充 2 个状态标签 + 更新 isActive 判断
- `TaskPanel.tsx`：更新 1 行过滤条件
- `BazaarPage.tsx`：布局从两栏改为三栏 + 集成 TaskNotify

**未改动的一期文件：**
- `AgentStatusBar.tsx`：不变
- `OnlineAgents.tsx`：不变
- `ControlBar.tsx`：不变
- `OnboardingGuide.tsx`：不变
- `BazaarSettings.tsx`：不变
- `routes.tsx`：不变
- `Sidebar.tsx`：不变

**判定标准**：还原 `types/bazaar.ts`、`stores/bazaar.ts`、`BazaarPage.tsx`、`TaskCard.tsx`、`TaskPanel.tsx` 到 Phase 1 版本，删除 `CollaborationChat.tsx` 和 `TaskNotify.tsx`，项目编译运行零报错。

**MVP 范围裁剪（Phase 2 后续 Chunk 再实现）：**
- 人类接管协作对话（在中间栏直接发消息）
- 每日摘要 DailyDigest 组件
- 任务完成后评分 UI（1-5 星）
- 搜索中状态的进度展示（正在搜索哪些 Agent）
- 管理者视图/团队看板
