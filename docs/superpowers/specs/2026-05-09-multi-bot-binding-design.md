# 多 Bot 绑定 + queryBot 只读 + Session 隔离设计

> 日期: 2026-05-09

## 背景

当前 Sman 企微 Bot 只能绑定一个 botId，所有用户共享同一种权限，无法满足以下场景：

- 在企微群中 @queryAbot，只对系统 A 有查询权限，不能修改任何文件
- 不同 Bot 绑定不同业务系统，各自独立的 skill 和权限
- Bot session 和本地桌面 session 完全隔离，互不影响

## 核心设计决策

| 决策点 | 结论 |
|--------|------|
| 多 Bot 配置 | `wecom.bots[]` 数组，每个 Bot 独立配置 |
| Bot 模式 | `full`（完全权限）/ `query`（只读 + skill 白名单） |
| queryBot workspace | 绑定单个 workspace，不可切换 |
| Skill 白名单来源 | 仅 `{workspace}/.claude/skills/` 目录，不含全局插件 |
| 只读实现 | `canUseTool` 回调拦截 Write/Edit/Bash 等修改类工具 |
| Session 隔离 | 三级隔离：本地 vs Bot 之间、Bot 与 Bot 之间、同一 Bot 不同用户之间 |
| 群聊 session | 按 userId 隔离，同一群内不同人各自独立 session |
| 并发限制 | 全局最多 2 个 Bot session 同时处理请求，超出的排队 |
| Session 分组 | 侧边栏 "Bot 会话" 区域，按 Bot label 二级分组 |
| 配置 UI | 留在设置页，扩展为 Bot 列表管理 |

## 1. 数据模型

### 1.1 ChatbotConfig 改造

```ts
// before
wecom: { enabled: boolean; botId: string; secret: string }

// after
wecom: {
  enabled: boolean;
  bots: WeComBotProfile[];
}
```

### 1.2 WeComBotProfile

```ts
interface WeComBotProfile {
  id: string;                  // uuid，内部唯一标识
  label: string;               // 显示名，如 "@queryAbot"
  botId: string;               // 企微 Bot ID
  secret: string;              // 企微 Bot Secret
  mode: 'full' | 'query';     // full=完全权限, query=只读+skill白名单
  workspace: string;           // 绑定的 workspace 路径（固定）
  allowedSkills: string[];     // query 模式的 skill 白名单（空数组=所有 skill）
  enabled: boolean;
}
```

- `mode === 'query'`：只读，workspace 不可切换，skill 受限
- `mode === 'full'`：保持现有行为，支持 `//cd` 切换
- `allowedSkills` 只在 `query` 模式下生效

### 1.3 IncomingMessage 扩展

```ts
interface IncomingMessage {
  platform: 'wecom' | 'feishu' | 'weixin';
  userId: string;
  content: string;
  requestId: string;
  chatType: 'single' | 'group' | 'p2p';
  chatId: string;
  media?: MediaAttachment[];
  botProfileId: string;        // 新增：来自哪个 Bot Profile
}
```

### 1.4 Session ID 格式

```
本地 session:    {timestamp}-{random}              (现有格式)
Bot session:     bot-{botProfileId}-{timestamp}-{random}
```

前缀 `bot-` + botProfileId 确保 ID 空间永不重叠。

### 1.5 userKey 设计

```
wecom:{botProfileId}:{userId}
```

无论单聊还是群聊，都按 userId 隔离。不同 Bot 的同一用户也是不同 session。

## 2. Session 三级隔离

这是最核心的设计约束，贯穿整个生命周期。

### 2.1 隔离层级

```
┌─────────────────────────────────────────────┐
│  Sman 实例                                   │
│                                              │
│  ┌─── 本地 Session 空间 ───┐                 │
│  │  session ID: 无 bot 前缀 │                │
│  │  存储: 主 session 表     │                │
│  │  创建: 桌面端 WebSocket  │                │
│  │  V2 进程: 独立           │                │
│  └──────────────────────────┘                │
│                                              │
│  ┌─── Bot Session 空间 ──────────────────┐   │
│  │                                       │   │
│  │  ┌─ @queryAbot ──────────────────┐   │   │
│  │  │ userKey: wecom:{id}:zhangsan   │   │   │
│  │  │ session: bot-{id}-{ts}-{rand}  │   │   │
│  │  │ V2 进程: 独立                  │   │   │
│  │  │ 存储: chatbot_sessions 表      │   │   │
│  │  ├───────────────────────────────┤   │   │
│  │  │ userKey: wecom:{id}:lisi       │   │   │
│  │  │ session: bot-{id}-{ts}-{rand2} │   │   │
│  │  │ V2 进程: 独立                  │   │   │
│  │  └───────────────────────────────┘   │   │
│  │                                       │   │
│  │  ┌─ @devBot ─────────────────────┐   │   │
│  │  │ userKey: wecom:{id2}:wangwu    │   │   │
│  │  │ session: bot-{id2}-{ts}-{rand} │   │   │
│  │  │ V2 进程: 独立                  │   │   │
│  │  └───────────────────────────────┘   │   │
│  └───────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

### 2.2 隔离规则

| 维度 | 本地 vs Bot | Bot vs Bot | 同 Bot 不同用户 |
|------|------------|-----------|----------------|
| Session ID 前缀 | 无 vs `bot-` | `bot-{profileId1}-` vs `bot-{profileId2}-` | `bot-{profileId}-{ts1}` vs `bot-{profileId}-{ts2}` |
| 存储表 | 主 session 表 | chatbot_sessions（同一张表，userKey 含 profileId 区分） | userKey 含 userId 区分 |
| V2 进程 | 独立 | 独立 | 独立 |
| 创建路径 | 桌面端 WS | ChatbotSessionManager | ChatbotSessionManager |
| 删除/清理 | 桌面端 WS | ChatbotSessionManager | ChatbotSessionManager |
| 崩溃影响 | 互不影响 | 互不影响 | 互不影响 |
| 并发槽位 | 不共享 | 共享（全局 2 个） | 共享（全局 2 个） |

### 2.3 隔离保障措施

1. **ID 前缀强制**：Bot session 创建时 ID 格式硬编码为 `bot-{botProfileId}-`，本地 session 创建时不允许 `bot-` 前缀
2. **数据表分离**：`chatbot_sessions` 表独立于本地 session 表，查询时从不交叉
3. **V2 进程独立**：每个 Bot session 的 V2 SDK 进程完全独立，一个崩溃不影响其他
4. **清理独立**：关闭某个 Bot session 只清理其 V2 进程和表记录，不碰其他 session
5. **`session.list` 标记**：后端返回 session 列表时带 `source: 'local' | 'bot'` 和 `botLabel`，前端据此分组

## 3. 并发控制

### 3.1 全局 Bot 并发槽位

```ts
// ChatbotSessionManager
private maxBotConcurrency = 2;
private activeBotCount = 0;
private botQueue: Array<{ execute: () => Promise<void>; userKey: string }> = [];
```

### 3.2 调度逻辑

```
消息到达 → 检查 activeBotCount
  < 2 → 立即执行，activeBotCount++
  >= 2 → 入队，回复用户"排队中，前面还有 N 个请求"

请求完成 → activeBotCount-- → 从队列取出下一个执行
```

### 3.3 排队通知

排队时通过 sender 回复："当前有 N 个请求在处理中，请稍候..."，当开始处理时再发一条"开始处理你的问题..."。

## 4. 权限控制（query 模式）

### 4.1 只读工具拦截

`sendMessageForChatbot()` 增加 `mode` 参数，在 `canUseTool` 回调中：

```ts
const READ_BLOCKED_TOOLS = new Set(['Write', 'Edit', 'Bash', 'NotebookEdit']);

canUseTool: async (params) => {
  if (params.toolName === 'AskUserQuestion') {
    return bridgeToFrontend(params);  // 现有逻辑
  }
  if (mode === 'query' && READ_BLOCKED_TOOLS.has(params.toolName)) {
    return { behavior: 'deny', reason: '当前为只读模式，不允许执行修改操作' };
  }
  return { behavior: 'allow' };
}
```

### 4.2 Skill 白名单过滤

`buildSessionOptions()` 阶段：

- 扫描 `{workspace}/.claude/skills/` 目录获取 skill 列表（仅业务系统本身的 skill，不含全局 superpowers/dev-workflow 等插件）
- 如果 `allowedSkills.length > 0`：只加载白名单中的 skill
- 如果 `allowedSkills.length === 0`：加载所有 skill（不限制）

### 4.3 System Prompt 注入

query 模式下追加 system prompt：

```
你是 {label}，一个只读答疑助手。
你可以回答关于 {workspace} 的问题，但不能修改任何文件。
你可用的技能: {allowedSkills 列表}
```

### 4.4 命令限制

query 模式下禁用 `//cd`、`//workspaces`，只保留 `//new`、`//help`、`//status`。

## 5. 后端改造

### 5.1 连接管理

```
startChatbotConnections():
  遍历 wecom.bots[]
    每个 enabled 的 bot → 创建 WeComBotConnection({ botProfileId: bot.id, ... })
    所有连接共享同一个 ChatbotSessionManager 实例
```

### 5.2 消息路由

```
WeComBotConnection 收到消息
  → IncomingMessage.botProfileId = this.botProfileId
  → ChatbotSessionManager.handleMessage(msg, sender)
    → 查找 botProfile (通过 botProfileId)
    → 构建 userKey = wecom:{botProfileId}:{userId}
    → mode === 'query': workspace 固定，禁用切换命令
    → mode === 'full': 保持现有行为
```

### 5.3 ChatbotStore 改造

`chatbot_sessions` 表的 `user_key` 列改为包含 botProfileId 的格式，确保不同 Bot 的 session 天然隔离。

增加 `bot_label` 列，方便前端分组展示：

```sql
CREATE TABLE chatbot_sessions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_key TEXT NOT NULL,           -- wecom:{botProfileId}:{userId}
  workspace TEXT NOT NULL,
  session_id TEXT NOT NULL,          -- bot-{botProfileId}-{ts}-{rand}
  sdk_session_id TEXT,
  bot_label TEXT,                    -- "@queryAbot"
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  last_active_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(user_key, workspace)
);
```

## 6. 前端改造

### 6.1 侧边栏 Session 分组

`SessionTree.tsx` 三级分组：

```
本地会话（按 workspace 分组，现有逻辑）
  systemA/
    会话1
    会话2
  systemB/
    会话3

Bot 会话（独立区域，按 botLabel 分组）
  @queryAbot
    张三: 订单查询
    李四: 用户表结构
  @devBot
    王五: 接口开发
```

实现：
- `ChatSession` 增加 `source: 'local' | 'bot'` 和 `botLabel?: string`
- 前端分组逻辑：先按 source 分两大区域，Bot 区域内按 botLabel 二级分组
- Bot session 的 label 格式：`{userName}: {首条消息摘要}`

### 6.2 设置页 Bot 管理

`ChatbotSettings.tsx` 改造为 Bot 列表：

- 企微区域显示 Bot 列表，支持增/删/改
- 每个 Bot 展开配置：
  - label（显示名）
  - botId + secret
  - mode 切换（full / query）
  - workspace 选择（下拉列表，来源：桌面端已打开的项目）
  - skill 勾选（选择 workspace 后自动加载 `.claude/skills/` 目录下的 skill 列表）
  - enabled 开关

### 6.3 Session 标签样式

Bot session 在侧边栏中用 Bot 图标 + label 前缀区分，与本地 session 的视觉风格不同。

## 7. 向后兼容

- 旧配置 `wecom: { enabled, botId, secret }` 自动迁移为 `wecom: { enabled, bots: [{ id, label: '默认Bot', botId, secret, mode: 'full', ... }] }`
- 迁移在 `SettingsManager` 读取配置时自动执行，无需用户操作
- 现有的非企微 Bot（飞书、微信）不受影响

## 8. 涉及文件

| 文件 | 改动类型 |
|------|----------|
| `server/chatbot/types.ts` | 重构：WeComBotProfile、IncomingMessage 扩展 |
| `server/chatbot/chatbot-store.ts` | 改造：表结构增加 bot_label，userKey 格式变更 |
| `server/chatbot/chatbot-session-manager.ts` | 重构：多 Bot 路由、并发控制、权限判断、命令限制 |
| `server/chatbot/wecom-bot-connection.ts` | 小改：构造函数增加 botProfileId |
| `server/index.ts` | 改造：startChatbotConnections 循环创建多连接 |
| `server/claude-session.ts` | 改造：sendMessageForChatbot 增加 mode 参数 + canUseTool 拦截 |
| `server/settings-manager.ts` | 小改：配置迁移逻辑 |
| `src/types/chat.ts` | 小改：ChatSession 增加 source、botLabel |
| `src/types/settings.ts` | 重构：ChatbotConfig 新结构 |
| `src/components/SessionTree.tsx` | 改造：三级分组逻辑 |
| `src/features/settings/ChatbotSettings.tsx` | 重构：Bot 列表管理 UI |
| `src/stores/settings.ts` | 小改：updateChatbot 适配新结构 |
| `src/stores/chat.ts` | 小改：loadSessions 适配 source/botLabel |
