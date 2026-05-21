# Sman 轻量 IM 设计

> 日期: 2026-05-21
> 状态: Approved
> 原型: `tmp/im-prototype-v3.html`

## 1. 目标

在 Sman 现有 Hub 架构上增加轻量 IM 能力，实现「群聊讨论需求 → @Agent 执行任务」的自然协作流程。

**核心设计原则**：
- 越简单越好，不引入重依赖
- Hub 纯路由，消息存本地
- Agent 是人的分身，不是独立个体
- 群聊和会话（1v1）复用同一套消息基础设施

## 2. 身份模型

### 2.1 人员 ID

- **格式**: `{username}@{host}`，例: `nasakim@1.1.1.1`
- **显示规则**: 群内无重名时只显示 `nasakim`，有重名时显示完整 `nasakim@1.1.1.1`
- **来源**: Hub 现有用户系统

### 2.2 分身 ID

- **显示格式**: `{username}/{workspaceName}`，例: `nasakim/sman-server`（无重名时省略 host）
- **底层映射**: Hub 注册时，在 Agent 元信息中携带 `workspaceName`，客户端维护 `workspaceName → agentId` 映射表。现有 `buildAgentId()` 格式（`nasakim:abc123def456`）不变，`@nasakim/sman-server` 在发送时解析为底层 agentId
- **来源**: 用户本地打开的项目目录名称
- **可见性**: 所有人可见（只读），只有主人能 @自己的分身
- **显示**: 始终显示友好分身 ID，用颜色区分（新野兽派风格粗边框）
- **权限校验**: 客户端 UI 灰掉别人的分身（前端校验） + Hub WS 发送时校验 sender 与 mentionedAgents 的归属关系（服务端兜底）

### 2.3 关键约束

- 每个人只能 @ 自己的分身，不能 @ 别人的分身
- Agent 只在被 @ 时才「看到」那条消息 + 引用上下文
- Agent 不监听群聊其他消息

## 3. 消息路由与存储

### 3.1 Hub 角色

Hub 是**纯路由**，不持久化消息。

```
客户端 A 发消息 → Hub WS → 广播到 Room 内所有在线客户端
                                    ↓
                     各客户端本地 SQLite 存储消息
```

### 3.2 本地存储

客户端 SQLite 新增 `im_messages` 表：

```sql
CREATE TABLE im_messages (
  id TEXT PRIMARY KEY,           -- UUIDv7 (时间有序)
  room_id TEXT NOT NULL,         -- 群聊 ID
  sender TEXT NOT NULL,          -- "nasakim@1.1.1.1" 或 "nasakim@1.1.1.1/sman-server"
  content TEXT NOT NULL,         -- 消息正文
  mentioned_agents TEXT,         -- JSON array, 被@的分身列表
  quote_id TEXT,                 -- 引用的消息 ID
  type TEXT NOT NULL DEFAULT 'text',  -- text | agent_output | system
  status TEXT DEFAULT NULL,      -- Agent 卡片状态: running | completed | failed (仅 agent_output)
  attachments TEXT,              -- JSON array, 文件/图片附件
  session_id TEXT,               -- 关联的 Claude Session ID (群聊@Agent自动创建的会话)
  timestamp INTEGER NOT NULL,   -- 毫秒时间戳
  created_at DATETIME DEFAULT (datetime('now', 'localtime')),
  updated_at DATETIME DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX idx_im_messages_room_ts ON im_messages(room_id, timestamp);
CREATE INDEX idx_im_messages_sender ON im_messages(sender);
CREATE INDEX idx_im_messages_session ON im_messages(session_id);
```

**去重机制**: `id` 是 PRIMARY KEY，INSERT OR IGNORE 保证广播和离线同步不重复。消息 ID 用 UUIDv7 生成，天然按时间有序且全局唯一。

### 3.3 离线同步

- Hub 纯内存缓存消息（最多 7 天，LRU 淘汰，不引入 Redis）
- 客户端上线时发送 `last_message_timestamp`（按 room_id 分别记录）
- Hub 返回该时间戳之后的所有缓存消息
- 客户端收到后 INSERT OR IGNORE 写入本地 SQLite，按 timestamp 排序

### 3.4 消息结构

```typescript
interface IMMessage {
  id: string;
  roomId: string;
  sender: string;           // personId 或 agentId
  content: string;
  mentionedAgents: string[]; // 被提到的分身 ID 列表
  quoteId?: string;         // 引用消息 ID
  type: 'text' | 'agent_output' | 'system';
  attachments?: Attachment[];
  timestamp: number;        // 毫秒时间戳
}
```

### 3.5 分页

采用 **timestamp + sequence** 双游标分页，不使用 offset，不受消息删除影响。

## 4. 导航结构

### 4.1 侧边栏

现有侧边栏新增 IM 入口，与协作星图、设置等并列：

```
💬  会话  (1v1，跟 Agent 单聊)
👥  群聊  (多人聊天，@Agent 派活)
🌟  协作星图
⚙️  设置
```

### 4.2 会话 Tab（1v1）

- 列表展示所有 Agent 会话（按最近消息时间排序）
- 会话在群聊中首次 @Agent 时自动创建
- 也可直接在会话 tab 里 @Agent 开始新对话
- 会话复用现有 Claude Session 机制（私聊本质就是一个 workspace session）

### 4.3 群聊 Tab

- 群聊列表（最近消息预览、未读数）
- 「nk 的工作台」：自己一个人的群聊，自言自语给分身派活
- 新建群聊：选择参与人员（从 Hub Room 成员中选择）

## 5. Agent 交互规则

### 5.1 @激活

- 群聊消息中出现 `@{agentId}` 时，该 Agent 被激活
- **Agent 只收到被 @ 的那条消息** + quote 引用上下文
- Agent 不监听、不看到群聊其他任何消息
- 一条消息可同时 @ 多个分身

### 5.2 Agent 输出

- Agent 输出以**新野兽派粗彩色边框卡片**呈现
- 每个分身有固定颜色（按分身 ID hash 映射）
- **默认折叠**：显示 Agent 名称 + 状态（执行中/完成）+ 一行摘要
- **点击展开**：完整输出内容 + 底部输入框
- 多个 Agent 同时输出时**交错排列**（按时间顺序），不并排

### 5.3 折叠卡片内交互

展开后的 Agent 卡片包含：

```
┌──────────────────────────────────┐
│ 🤖 nasakim@1.1.1.1/sman-server  │
│ ─────────────────────────────── │
│ [Agent 完整输出内容]              │
│                                  │
│ ┌──────────────────────────────┐ │
│ │ 继续对话...                   │ │
│ └──────────────────────────────┘ │
│              [私聊]  [群聊回复]   │
└──────────────────────────────────┘
```

- **群聊回复**：引用该 Agent 输出 + @Agent → 插入群聊输入框，用户确认后发送
- **私聊**：跳转到「会话」tab，进入该 Agent 的 1v1 会话

### 5.4 多轮对话

两种方式：
1. **引用 + @**：在群聊中引用 Agent 之前的输出 + @它 → Agent 看到引用上下文继续对话
2. **私聊**：从折叠卡片跳转到会话 tab，自由多轮对话

### 5.5 模糊派活

`@nasakim/sman-server @nasakim/web-app 处理跟自己相关的需求`

- Agent 收到消息后，自主分析**该 Room 内所有可见消息**（只看到 @它的消息）
- 筛选与自己 workspace 职责相关的部分
- 各自独立输出任务清单和执行结果

## 6. WebSocket 协议

### 6.1 Hub → 客户端

```typescript
// 消息广播
{ type: 'im.message', data: IMMessage }

// 在线状态变更
{ type: 'im.presence', data: { userId: string, status: 'online' | 'offline' } }

// 正在输入
{ type: 'im.typing', data: { roomId: string, sender: string } }

// Agent 输出流式
{ type: 'im.agent_delta', data: { messageId: string, agentId: string, content: string } }
```

### 6.2 客户端 → Hub

```typescript
// 发送消息
{ type: 'im.send', data: { roomId: string, content: string, mentionedAgents: string[], quoteId?: string } }

// 请求离线同步
{ type: 'im.sync', data: { roomId: string, afterTimestamp: number } }

// 通知正在输入
{ type: 'im.typing', data: { roomId: string } }
```

## 7. 前端组件

### 7.1 新增组件

| 组件 | 路径 | 职责 |
|------|------|------|
| `IMEntry` | `src/features/im/` | IM 入口，切换会话/群聊 |
| `SessionList` | `src/features/im/` | Agent 会话列表 |
| `GroupChatList` | `src/features/im/` | 群聊列表 |
| `ChatWindow` | `src/features/im/` | 通用聊天窗口 |
| `MessageList` | `src/features/im/` | 消息列表（支持交错排列） |
| `MessageBubble` | `src/features/im/` | 普通消息气泡 |
| `AgentCard` | `src/features/im/` | Agent 输出折叠卡片 |
| `ChatInput` | `src/features/im/` | 输入框 + @提及弹窗 |
| `MemberPanel` | `src/features/im/` | 成员 & 分身面板 |

### 7.2 复用现有组件

- `Avatar` — 用户/Agent 头像
- `CodeBlock` — Agent 输出中的代码高亮
- `FilePreview` — 文件/图片附件预览

## 8. 后端模块

### 8.1 新增模块

| 模块 | 路径 | 职责 |
|------|------|------|
| `im-store` | `server/im/` | 本地 SQLite 消息存储 |
| `im-ws-handler` | `server/im/` | WS 消息分发 |
| `im-agent-bridge` | `server/im/` | Agent @激活 → 创建/复用 Session |

### 8.2 Hub 扩展

- Hub WS 新增 `im.*` 消息类型
- Hub 新增消息缓存层（内存 + 可选 Redis，最多 7 天）
- Hub 新增 Room 成员在线状态管理

### 8.3 与现有系统的关系

- **Hub 系统**：复用 Room、Agent 注册、WS 连接，新增 IM 消息层
- **会话系统**：群聊 @Agent 自动创建 1v1 会话，复用现有 Claude Session
- **Group 系统**：旧版 Group 可废弃，Hub 完全替代

## 9. 数据流

### 9.1 用户发消息

```
用户输入 → ChatInput → im.send WS → Hub WS → 广播到 Room
                                                    ↓
                                         所有在线客户端收到
                                                    ↓
                                         im-store 写入本地 SQLite
                                                    ↓
                                         MessageList 更新 UI
```

### 9.2 @Agent 执行任务

**@检测在发送方客户端完成**，发送方负责创建 Session 并将 Agent 输出通过 Hub 广播。

```
发送方客户端检测 mentionedAgents (只含自己的分身)
                    ↓
        为每个被@的自己的 Agent 创建/复用 Claude Session
                    ↓
        Agent 输入: 被提到的消息 + quote 上下文
                    ↓
        Agent 输出流式 → im.agent_delta WS → Hub 广播到 Room
                    ↓
        所有客户端收到 → 显示 AgentCard（折叠状态，流式更新摘要）

并发限制: 复用现有 ClaudeSessionManager 的队列机制，
每个客户端同时最多 N 个 Agent Session（复用 TaskWorker 的 MAX_CONCURRENT 策略）
```

### 9.3 群聊 ↔ 会话联动

```
AgentCard 展开 → 点击「私聊」
    ↓
检查是否已有该 Agent 的 1v1 会话
    ↓
没有 → 创建新会话（复用 workspace session）
    ↓
跳转到会话 tab，加载该会话历史
    ↓
用户在会话中继续多轮对话
```

## 10. MVP 范围

### P0 — 核心闭环

1. 本地 `im_messages` 表 + `im-store`
2. Hub WS `im.*` 消息路由
3. 群聊列表 + 聊天窗口
4. @提及选择器（只显示自己的分身）
5. Agent 折叠卡片（流式输出）
6. 卡片内群聊/私聊按钮
7. 会话 tab（Agent 1v1 列表）

### P1 — 增强体验

8. 离线同步（Hub 7 天缓存 + 增量拉取）
9. 文件/图片附件
10. 在线状态 + 正在输入提示
11. 消息引用 (Quote/Reply)
12. 「nk 的工作台」自言自语模式

### P2 — 高级功能

13. 模糊派活（Agent 自主分析聊天记录）
14. 多 Agent 并行输出交错展示优化
15. 消息搜索
16. @提及通知

## 11. 不做的事

- 不引入 MongoDB/Redis 等新依赖（Hub 缓存用纯内存）
- 不做消息已读回执
- 不做消息撤回/编辑
- 不做语音/视频通话
- 不做群组权限管理（公开/私有 Room 复用 Hub 现有机制）
- 不做消息加密（依赖 Hub 现有 TLS）

## 12. 旧 Group 迁移

旧版 Group 系统（`group-store.ts` + `groups`/`group_tasks`/`group_subtasks` 三张表）将被 IM 完全替代。迁移策略：
- IM 上线后，旧 Group UI 入口替换为 IM 入口
- 旧数据不迁移（Group 是纯本地旧方案，无历史数据价值）
- 旧表保留 1 个版本后删除
