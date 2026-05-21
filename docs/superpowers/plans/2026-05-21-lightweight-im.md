# Sman 轻量 IM 实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Sman 现有 Hub 架构上实现轻量 IM，支持群聊讨论需求 + @Agent 分身执行任务的自然协作流程。

**Architecture:** Hub 纯路由（WS 广播），客户端本地 SQLite 存储消息。Agent 是人的「分身」，只在被 @ 时激活。前端左侧栏新增「会话」+「群聊」两个入口，群聊中 Agent 输出以新野兽派粗彩色边框折叠卡片呈现。

**Tech Stack:** TypeScript, better-sqlite3, Express WS, React 19, Zustand, Zod, React Query, TailwindCSS, Radix UI

**设计文档:** `docs/superpowers/specs/2026-05-21-lightweight-im-design.md`
**原型参考:** `tmp/im-prototype-v3.html`

---

## 文件结构

### 后端新建

| 文件 | 职责 |
|------|------|
| `server/im/im-store.ts` | 本地 SQLite 消息存储 (CRUD + 分页) |
| `server/im/im-ws-handler.ts` | WS 消息分发 (im.send/history/sync/typing) |
| `server/im/im-agent-bridge.ts` | @Agent 激活 → 创建/复用 Claude Session → 流式广播 |
| `server/im/index.ts` | IM 模块初始化入口，注册 WS handler |

### 后端修改

| 文件 | 改动 |
|------|------|
| `server/session-store.ts` | `init()` 中新增 `im_messages` 表 + `im_rooms` 表 |
| `server/index.ts` | WS switch 中新增 `im.*` case，委托给 im-ws-handler |

### 前端新建

| 文件 | 职责 |
|------|------|
| `src/schemas/im.ts` | Zod schema (IMMessage, IMRoom, IMAgent) |
| `src/queries/use-im.ts` | React Query hooks (消息查询、房间列表、发送消息) |
| `src/stores/im.ts` | Zustand store (WS 实时状态、选中房间、Agent 输出流) |
| `src/features/im/IMEntry.tsx` | IM 入口页面，左侧列表 + 右侧聊天窗口布局 |
| `src/features/im/GroupChatList.tsx` | 群聊列表 |
| `src/features/im/SessionList.tsx` | 1v1 会话列表 |
| `src/features/im/ChatWindow.tsx` | 聊天窗口 (消息列表 + 输入框) |
| `src/features/im/MessageList.tsx` | 消息列表 (交错排列，支持分页加载) |
| `src/features/im/MessageBubble.tsx` | 普通消息气泡 |
| `src/features/im/AgentCard.tsx` | Agent 折叠卡片 (新野兽派粗边框) |
| `src/features/im/ChatInput.tsx` | 输入框 + @提及弹窗 |
| `src/features/im/MemberPanel.tsx` | 成员 & 分身面板 |

### 前端修改

| 文件 | 改动 |
|------|------|
| `src/app/routes.tsx` | 新增 `/im` 路由 |
| `src/components/layout/Sidebar.tsx` | 新增会话/群聊入口 |
| `src/locales/zh-CN.json` | 新增 `im.*` i18n 键 |
| `src/locales/en-US.json` | 新增 `im.*` i18n 键 |

---

## Chunk 1: 后端数据层 + WS 消息路由

### Task 1: im_messages 数据库表 + IMStore

**Files:**
- Modify: `server/session-store.ts` (init 方法内新增表)
- Create: `server/im/im-store.ts`
- Test: `tests/server/im/im-store.test.ts`

- [ ] **Step 1: 新增数据库表**

在 `server/session-store.ts` 的 `init()` 方法末尾追加 `im_messages` 和 `im_rooms` 表创建：

```typescript
// IM 消息表
this.db.exec(`
  CREATE TABLE IF NOT EXISTS im_messages (
    id TEXT PRIMARY KEY,
    room_id TEXT NOT NULL,
    sender TEXT NOT NULL,
    content TEXT NOT NULL,
    mentioned_agents TEXT,
    quote_id TEXT,
    type TEXT NOT NULL DEFAULT 'text',
    status TEXT DEFAULT NULL,
    attachments TEXT,
    session_id TEXT,
    timestamp INTEGER NOT NULL,
    created_at DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_at DATETIME DEFAULT (datetime('now', 'localtime'))
  );
  CREATE INDEX IF NOT EXISTS idx_im_messages_room_ts ON im_messages(room_id, timestamp);
  CREATE INDEX IF NOT EXISTS idx_im_messages_sender ON im_messages(sender);
  CREATE INDEX IF NOT EXISTS idx_im_messages_session ON im_messages(session_id);
`);

// IM 房间表
this.db.exec(`
  CREATE TABLE IF NOT EXISTS im_rooms (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    type TEXT NOT NULL DEFAULT 'group',
    members TEXT NOT NULL,
    last_message TEXT,
    last_message_time INTEGER,
    created_at DATETIME DEFAULT (datetime('now', 'localtime'))
  );
`);
```

- [ ] **Step 2: 创建 IMStore 类**

创建 `server/im/im-store.ts`，注入 `Database` 实例，提供以下方法：

```typescript
export class IMStore {
  constructor(private db: Database) {}

  // 消息 CRUD
  insertMessage(msg: IMMessage): void           // INSERT OR IGNORE (去重)
  getMessage(id: string): IMMessage | undefined
  getMessagesByRoom(roomId: string, options: { before?: number; limit: number }): IMMessage[]
  updateMessageStatus(id: string, status: string): void  // Agent 卡片状态更新

  // 房间 CRUD
  createRoom(room: IMRoom): void
  getRoom(id: string): IMRoom | undefined
  listRooms(): IMRoom[]
  updateRoomLastMessage(roomId: string, preview: string, time: number): void

  // 分页 (timestamp 游标)
  getMessagesBefore(roomId: string, beforeTimestamp: number, limit: number): IMMessage[]
}
```

- [ ] **Step 3: 写测试**

创建 `tests/server/im/im-store.test.ts`，测试：
- insertMessage + getMessage 基本读写
- INSERT OR IGNORE 去重（相同 id 插入两次不报错）
- getMessagesByRoom 按 timestamp 排序
- getMessagesBefore 游标分页正确
- updateMessageStatus 更新 Agent 卡片状态
- createRoom + listRooms

- [ ] **Step 4: 运行测试**

Run: `pnpm test tests/server/im/im-store.test.ts`
Expected: 全部通过

- [ ] **Step 5: 提交**

```bash
git add server/session-store.ts server/im/im-store.ts tests/server/im/im-store.test.ts
git commit -m "feat(im): add im_messages/im_rooms tables and IMStore"
```

---

### Task 2: WS 消息处理 — im-ws-handler

**Files:**
- Create: `server/im/im-ws-handler.ts`
- Create: `server/im/index.ts`
- Modify: `server/index.ts`

- [ ] **Step 1: 创建 IM WS Handler**

创建 `server/im/im-ws-handler.ts`：

```typescript
export class IMWsHandler {
  constructor(
    private imStore: IMStore,
    private agentBridge: IMAgentBridge,
    private broadcastToRoom: (roomId: string, msg: any) => void,
  ) {}

  // 来自本地客户端的请求 (通过 server WS switch-case 路由过来)
  handleLocalMessage(msg: any, ws: WebSocket, clientInfo: { clientId: string }): void {
    switch (msg.type) {
      case 'im.send':      return this.handleSend(msg, ws, clientInfo);
      case 'im.history':   return this.handleHistory(msg, ws);
      case 'im.sync':      return this.handleSync(msg, ws);
      case 'im.typing':    return this.handleTyping(msg, ws, clientInfo);
      default: break;
    }
  }

  // 来自 Hub 远程广播的消息 (其他客户端发的)
  handleHubMessage(msg: any): void {
    switch (msg.type) {
      case 'im.message':    // 存本地，推前端
      case 'im.agent_delta': // 更新 AgentCard 流式内容
      case 'im.presence':
      case 'im.typing':
        this.processInboundMessage(msg);
        break;
    }
  }

  // im.send (仅发送方执行):
  //   1. 存本地 im_messages
  //   2. 生成 im.message 广播到 Hub (广播给 Room 内其他客户端)
  //   3. 如果有 mentionedAgents → 触发 agentBridge (只有发送方执行)
  // 其他客户端收到 im.message 后只做存本地 + 推前端，不触发 Agent

  // im.history: 返回 roomId 的消息列表 (游标分页)
  // im.sync: 返回 afterTimestamp 之后的消息
  // im.typing: 广播正在输入提示
}
```

- [ ] **Step 2: 注册到 WS 分发**

在 `server/index.ts` 的 WS switch-case 中新增：

```typescript
case 'im.send':
case 'im.history':
case 'im.sync':
case 'im.typing':
  imHandler?.handleLocalMessage(parsed, ws, clientInfo);
  break;
```

房间列表通过 REST API 提供（在 `server/index.ts` 的 HTTP 路由中新增 `GET /api/im/rooms`），不走 WS。

- [ ] **Step 3: 创建 IM 模块入口**

创建 `server/im/index.ts`，导出 `initIM(db, sessionManager, clientId)` 返回 `{ imStore, imHandler, agentBridge }`，供 `index.ts` 调用。

- [ ] **Step 4: 新增 REST API**

在 `server/index.ts` 的 HTTP 路由中新增（需要 auth）：
- `GET /api/im/rooms` → 返回用户的房间列表（从 imStore 读取）
- `POST /api/im/rooms` → 创建新群聊

- [ ] **Step 6: 提交**

```bash
git add server/im/im-ws-handler.ts server/im/index.ts server/index.ts
git commit -m "feat(im): add WS handler for im.send/history/rooms/sync/typing"
```

---

### Task 3: Hub WS 路由层 — im 消息转发

**Files:**
- Modify: `server/hub/hub-ws-client.ts` (新增 im 消息转发)
- Modify: `server/hub/index.ts` (onMessage 中新增 im 消息处理)

- [ ] **Step 1: Hub WS 客户端支持 im 消息**

在 `HubWsClient` 中，im 消息直接透传：客户端发送 `im.send` → HubWsClient 转发到 Hub 服务器 → Hub 服务器广播到 Room 内所有客户端。

- [ ] **Step 2: Hub onMessage 中处理 im 消息**

在 `server/hub/index.ts` 的 `onMessage` 回调中新增：

```typescript
if (msg.type === 'im.message' || msg.type === 'im.agent_delta' || msg.type === 'im.presence' || msg.type === 'im.typing') {
  // 转发给本地 IM handler 处理（存本地 + 推送前端）
  imHandler?.handleHubMessage(msg);
}
```

- [ ] **Step 3: 提交**

```bash
git add server/hub/hub-ws-client.ts server/hub/index.ts
git commit -m "feat(im): add Hub WS routing for im.* messages"
```

---

### Task 4: Agent Bridge — @激活 + Session 创建 + 流式广播

**Files:**
- Create: `server/im/im-agent-bridge.ts`

- [ ] **Step 1: 创建 Agent Bridge**

```typescript
export class IMAgentBridge {
  constructor(
    private imStore: IMStore,
    private sessionManager: ClaudeSessionManager,
    private broadcastToRoom: (roomId: string, msg: any) => void,
    private clientId: string,
  ) {}

  // 检测消息中的 mentionedAgents，筛选出属于自己的分身
  async handleMention(message: IMMessage): Promise<void> {
    const myAgents = this.getMyAgents(); // 从本地 workspace 列表获取
    const mentioned = message.mentionedAgents.filter(a => myAgents.includes(a));
    if (mentioned.length === 0) return;

    for (const agentId of mentioned) {
      this.activateAgent(agentId, message);
    }
  }

  // 为 Agent 创建/复用 Claude Session，发送消息，流式输出广播
  private async activateAgent(agentId: string, message: IMMessage): Promise<void> {
    // 1. 生成 agent_output 消息，status=running，广播
    // 2. 复用或创建该 workspace 的 Claude Session
    // 3. 将 message.content + quote 上下文作为 prompt 发给 Session
    // 4. 流式 delta 通过 im.agent_delta 广播
    // 5. 完成后更新 status=completed，广播 im.message (完整 agent_output)
  }
}
```

- [ ] **Step 2: 在 im-ws-handler 的 handleSend 中触发 Agent Bridge（仅发送方）**

用户发送消息后，如果 `mentionedAgents` 不为空，调用 `agentBridge.handleMention(msg)`。
**关键**：Agent Bridge 只在 `handleSend`（即发送方客户端）中触发。其他客户端收到 `im.message` 广播后走 `handleHubMessage`，只存本地 + 推前端，不触发 Agent。

- [ ] **Step 3: 提交**

```bash
git add server/im/im-agent-bridge.ts server/im/im-ws-handler.ts
git commit -m "feat(im): add Agent Bridge for @activation and streaming output"
```

---

## Chunk 2: 前端基础 — Schema + Query + Store + 路由

### Task 5: Zod Schema + i18n

**Files:**
- Create: `src/schemas/im.ts`
- Modify: `src/locales/zh-CN.json`
- Modify: `src/locales/en-US.json`

- [ ] **Step 1: 创建 IM Schema**

```typescript
// src/schemas/im.ts
import { z } from 'zod';

export const IMMessageSchema = z.object({
  id: z.string(),
  roomId: z.string(),
  sender: z.string(),
  content: z.string(),
  mentionedAgents: z.array(z.string()).default([]),
  quoteId: z.string().optional(),
  type: z.enum(['text', 'agent_output', 'system']).default('text'),
  status: z.enum(['running', 'completed', 'failed']).optional(),
  attachments: z.array(z.any()).optional(),
  sessionId: z.string().optional(),
  timestamp: z.number(),
}).passthrough();

export const IMRoomSchema = z.object({
  id: z.string(),
  name: z.string(),
  type: z.enum(['group', 'dm', 'workspace']).default('group'),
  members: z.array(z.string()),
  lastMessage: z.string().optional(),
  lastMessageTime: z.number().optional(),
}).passthrough();

export type IMMessage = z.infer<typeof IMMessageSchema>;
export type IMRoom = z.infer<typeof IMRoomSchema>;
```

- [ ] **Step 2: 新增 i18n 键**

在 `zh-CN.json` 中新增 `im.*` 命名空间的翻译键（至少覆盖：菜单名称、群聊/会话 tab、发送/取消按钮、成员面板标题、Agent 卡片状态等）。

在 `en-US.json` 中新增对应英文翻译。

- [ ] **Step 3: 提交**

```bash
git add src/schemas/im.ts src/locales/zh-CN.json src/locales/en-US.json
git commit -m "feat(im): add Zod schemas and i18n keys"
```

---

### Task 6: React Query + Zustand Store

**Files:**
- Create: `src/queries/use-im.ts`
- Create: `src/stores/im.ts`

- [ ] **Step 1: 创建 React Query hooks**

```typescript
// src/queries/use-im.ts
// useRoomList()          — 查询房间列表
// useRoomMessages(roomId, options) — 查询房间消息 (游标分页)
// useSendMessage()       — mutation: 发送消息
// useSyncMessages()      — mutation: 离线同步
```

- [ ] **Step 2: 创建 Zustand Store**

```typescript
// src/stores/im.ts
interface IMStore {
  // 状态
  selectedRoomId: string | null;
  activeTab: 'sessions' | 'groups';
  agentStreams: Map<string, string>;  // agentId → 流式内容

  // 操作
  selectRoom: (roomId: string) => void;
  setActiveTab: (tab: 'sessions' | 'groups') => void;
  appendAgentStream: (agentId: string, delta: string) => void;
  clearAgentStream: (agentId: string) => void;
}
```

- [ ] **Step 3: WS 事件监听**

在 Store 中注册 WS 事件处理：
- `im.message` → 更新消息列表
- `im.agent_delta` → 更新 agentStreams
- `im.presence` → 更新在线状态

- [ ] **Step 4: 提交**

```bash
git add src/queries/use-im.ts src/stores/im.ts
git commit -m "feat(im): add React Query hooks and Zustand store"
```

---

### Task 7: 路由 + 侧边栏入口

**Files:**
- Modify: `src/app/routes.tsx`
- Modify: `src/components/layout/Sidebar.tsx`

- [ ] **Step 1: 注册路由**

在 `routes.tsx` 的 `MainLayout children` 中新增：

```tsx
{ path: 'im', element: <IMEntry /> }
```

- [ ] **Step 2: 侧边栏新增入口**

在 `Sidebar.tsx` 中新增两个图标入口：
- 💬 会话 → `/im?tab=sessions`
- 👥 群聊 → `/im?tab=groups`

放在现有协作星图和设置之间。

- [ ] **Step 3: 提交**

```bash
git add src/app/routes.tsx src/components/layout/Sidebar.tsx
git commit -m "feat(im): add /im route and sidebar entries"
```

---

## Chunk 3: 前端核心 UI — 群聊列表 + 聊天窗口 + Agent 卡片

### Task 8: IMEntry + 群聊列表 + 会话列表

**Files:**
- Create: `src/features/im/IMEntry.tsx`
- Create: `src/features/im/GroupChatList.tsx`
- Create: `src/features/im/SessionList.tsx`

- [ ] **Step 1: IMEntry 布局**

```tsx
// src/features/im/IMEntry.tsx
// 三栏布局: 侧边列表 | 聊天窗口 | 成员面板
// 左侧 tab 切换: 会话 | 群聊
// 参考原型 v3 的布局
```

- [ ] **Step 2: GroupChatList 组件**

群聊列表项：头像、房间名、最后消息预览、时间、未读数。
参考原型 v3 的 `chat-item` 样式。
列表顶部有「+ 新建群聊」按钮，点击弹出成员选择器（从 Hub Room 成员列表中选择），创建后调用 `POST /api/im/rooms`。

- [ ] **Step 3: SessionList 组件**

Agent 会话列表项：Agent 头像（彩色边框）、分身名称（如 `nasakim/sman-server`）、最后消息预览。
来自群聊 @Agent 自动创建的会话标记来源。

- [ ] **Step 4: 提交**

```bash
git add src/features/im/IMEntry.tsx src/features/im/GroupChatList.tsx src/features/im/SessionList.tsx
git commit -m "feat(im): add IMEntry layout with group and session lists"
```

---

### Task 9: ChatWindow + MessageList + MessageBubble

**Files:**
- Create: `src/features/im/ChatWindow.tsx`
- Create: `src/features/im/MessageList.tsx`
- Create: `src/features/im/MessageBubble.tsx`

- [ ] **Step 1: ChatWindow**

顶部：房间名 + 成员摘要 + 操作按钮（成员面板切换）
中间：MessageList（flex 列表，自动滚动到底部）
底部：ChatInput

- [ ] **Step 2: MessageList**

- 消息按 timestamp 升序排列
- 区分自己/他人消息（self 气泡靠右，紫色背景）
- 支持 Agent 输出卡片交错排列
- 游标分页：滚动到顶部时加载更多
- 系统消息居中显示（灰色斜体）

- [ ] **Step 3: MessageBubble**

- sender 显示规则：无重名显示 username，有重名显示 username@host
- @提及高亮（彩色背景 + 对应 Agent 颜色）
- 引用消息（Quote）灰色左边框缩略展示
- 时间戳显示

- [ ] **Step 4: 提交**

```bash
git add src/features/im/ChatWindow.tsx src/features/im/MessageList.tsx src/features/im/MessageBubble.tsx
git commit -m "feat(im): add ChatWindow with message list and bubbles"
```

---

### Task 10: AgentCard — 折叠卡片 + 群聊/私聊按钮

**Files:**
- Create: `src/features/im/AgentCard.tsx`

- [ ] **Step 1: AgentCard 折叠/展开**

```
折叠态: [🤖 nasakim/sman-server] [执行中/✅完成] [一行摘要...] [展开▼]
展开态: 完整输出 + 底部输入框 + [私聊] [群聊回复]
```

- **新野兽派风格**: 2px 粗彩色边框，颜色按 agentId hash 映射
- 折叠态显示摘要（截取第一行）
- 点击 header 切换展开/折叠
- 流式输出时折叠态自动更新摘要，header 显示 `●执行中` 动画

- [ ] **Step 2: 展开态交互**

展开后底部：
- textarea 输入框（placeholder: "继续对话..."）
- [私聊] 按钮 → 跳转到会话 tab，选中该 Agent 的 1v1 会话
- [群聊回复] 按钮 → 引用该 Agent 输出 + @Agent → 插入群聊 ChatInput

- [ ] **Step 3: 提交**

```bash
git add src/features/im/AgentCard.tsx
git commit -m "feat(im): add AgentCard with collapse/expand and reply actions"
```

---

### Task 11: ChatInput — 输入框 + @提及弹窗

**Files:**
- Create: `src/features/im/ChatInput.tsx`

- [ ] **Step 1: ChatInput 组件**

- textarea 自适应高度（最小 1 行，最大 6 行）
- Shift+Enter 换行，Enter 发送
- 工具栏：📎 附件、🖼 图片、@ 提及、↩ 引用

- [ ] **Step 2: @提及弹窗**

- 输入 `@` 触发弹窗
- 只显示**自己的分身**（别人的灰色不可选）
- 支持多选（依次点击追加 @mention）
- 选中后插入 `{agentDisplayId} ` 到光标位置
- 显示格式：`nasakim/sman-server`（彩色文字）

- [ ] **Step 3: 提交**

```bash
git add src/features/im/ChatInput.tsx
git commit -m "feat(im): add ChatInput with @mention popup"
```

---

### Task 12: MemberPanel + 身份显示

**Files:**
- Create: `src/features/im/MemberPanel.tsx`

- [ ] **Step 1: MemberPanel**

右侧面板，分两组：
- **人员**：在线/离线分组，显示 username 或 username@host
- **分身**：按人员分组，每个分身显示 `username/workspaceName`，虚线边框，彩色标签
- 点击成员跳转到 1v1 会话（如果是自己的分身）或查看资料（如果是其他人）

- [ ] **Step 2: 提交**

```bash
git add src/features/im/MemberPanel.tsx
git commit -m "feat(im): add MemberPanel with identity display"
```

---

## Chunk 4: 集成 + 端到端验证

### Task 13: WS 事件前后端联调

**Files:**
- Modify: `src/stores/im.ts` (WS 事件注册)
- Modify: `src/features/im/ChatWindow.tsx` (接入实时消息)

- [ ] **Step 1: 前端 WS 监听注册**

在 `im.ts` store 中，注册 WS 事件监听：
- `im.message` → 追加到当前房间的消息列表
- `im.agent_delta` → 更新 AgentCard 流式内容
- `im.presence` → 更新成员在线状态
- `im.typing` → 显示"正在输入"提示

- [ ] **Step 2: ChatWindow 接入实时更新**

消息列表响应 store 变化自动更新，新消息时滚动到底部。

- [ ] **Step 3: 端到端测试**

启动 `pnpm dev`，验证完整流程：
1. 打开 IM 页面
2. 创建群聊
3. 发送消息 → 验证消息出现在列表
4. @Agent → 验证 Agent 卡片出现（折叠态，流式更新）
5. 点击展开 → 验证完整输出 + 群聊/私聊按钮
6. 点击群聊回复 → 验证输入框被填充引用 + @mention
7. 点击私聊 → 验证跳转到会话 tab

- [ ] **Step 4: 提交**

```bash
git add src/stores/im.ts src/features/im/ChatWindow.tsx src/features/im/AgentCard.tsx
git commit -m "feat(im): wire up WS events for real-time messaging and agent streaming"
```

---

### Task 14: 编译检查 + 清理

**Files:**
- 可能修改: 所有 IM 相关文件

- [ ] **Step 1: TypeScript 编译检查**

Run: `pnpm build`
Expected: 无错误

- [ ] **Step 2: 运行全量测试**

Run: `pnpm test`
Expected: 全部通过（不破坏现有功能）

- [ ] **Step 3: 清理临时文件**

删除原型文件 `tmp/im-prototype*.html`。

- [ ] **Step 4: 最终提交**

```bash
git add -A
git commit -m "feat(im): lightweight IM with group chat and @agent activation"
```
