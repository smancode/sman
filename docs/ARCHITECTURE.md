# Sman 架构全景图

> 生成时间: 2026-04-16 | 项目版本: 0.2.6

---

## 1. 系统全景架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        用户交互层 (3 端入口)                         │
│                                                                     │
│   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐           │
│   │  桌面端       │   │  企微 Bot     │   │  飞书 Bot     │           │
│   │  Electron    │   │  WeCom WS     │   │  Feishu SDK  │           │
│   │  :5880/:5881 │   │  wss://wecom  │   │  Long Conn   │           │
│   └──────┬───────┘   └──────┬───────┘   └──────┬───────┘           │
│          │                  │                   │                    │
└──────────┼──────────────────┼───────────────────┼───────────────────┘
           │ WebSocket        │ WebSocket         │ Event Listener
           │ ws://localhost   │ (长连接)           │ (长连接)
┌──────────┼──────────────────┼───────────────────┼───────────────────┐
│          ▼                  ▼                   ▼                   │
│                      Sman 主服务器 (:5880)                          │
│                     Express + WebSocket (ws)                        │
│                                                                     │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                  server/index.ts (入口)                      │   │
│   │  HTTP 静态文件 | CORS | Bearer Auth | 50+ WS 消息类型路由    │   │
│   └─────┬───────────────────────────────────────────────────────┘   │
│         │                                                           │
│   ┌─────┼─────────────────────────────────────────────────────┐     │
│   │     │              核心子系统                               │     │
│   │     │                                                      │     │
│   │  ┌──┴──────────┐  ┌──────────────┐  ┌─────────────────┐   │     │
│   │  │ Claude 会话  │  │  Chatbot     │  │  Cron / Batch   │   │     │
│   │  │ 管理器       │  │  路由器      │  │  任务引擎       │   │     │
│   │  │             │  │              │  │                 │   │     │
│   │  │ Agent SDK   │  │ WeCom连接    │  │ node-cron       │   │     │
│   │  │ V2 Session  │  │ 飞书连接     │  │ Semaphore并发   │   │     │
│   │  │ 流式推送     │  │ 微信连接     │  │ crontab.md解析  │   │     │
│   │  └──────┬──────┘  └──────────────┘  └─────────────────┘   │     │
│   │         │                                                  │     │
│   │  ┌──────┴──────────────────────────────────────────────┐   │     │
│   │  │              MCP Server 层                           │   │     │
│   │  │                                                      │   │     │
│   │  │  Web Search    Web Access    Bazaar    Capability   │   │     │
│   │  │  (Brave/       (CDP Chrome)  (协作)    Gateway      │   │     │
│   │  │   Tavily/                                         │   │     │
│   │  │   Bing)                                           │   │     │
│   │  └──────────────────────────────────────────────────────┘   │     │
│   │                                                              │     │
│   │  ┌────────────────────┐  ┌─────────────────────────────┐   │     │
│   │  │ Skills / Plugins   │  │ Settings / Profile / Model  │   │     │
│   │  │ 全局 + 项目 + 插件  │  │ LLM配置 / 能力探测 / Auth  │   │     │
│   │  └────────────────────┘  └─────────────────────────────┘   │     │
│   └──────────────────────────────────────────────────────────────┘     │
│         │                          │                                  │
│   ┌─────┴──────────┐        ┌──────┴──────────┐                      │
│   │  SQLite 存储    │        │  Agent 集市桥接  │                     │
│   │  ~/.sman/       │        │  BazaarBridge   │                      │
│   │  sman.db        │        │  (WebSocket客户端)│                     │
│   └────────────────┘        └──────┬──────────┘                      │
│                                     │ WebSocket                       │
└─────────────────────────────────────┼────────────────────────────────┘
                                      │ ws://bazaar:5890
┌─────────────────────────────────────┼────────────────────────────────┐
│                      Bazaar 集市服务器 (:5890)                        │
│                    Express + WebSocket (独立部署)                      │
│                                                                      │
│   ┌────────────┐ ┌──────────┐ ┌───────────┐ ┌─────────────────┐     │
│   │ AgentStore │ │TaskEngine│ │Reputation │ │ WorldState      │     │
│   │ 注册/心跳   │ │路由/排队  │ │声望计算    │ │ 像素世界坐标    │     │
│   └─────┬──────┘ └────┬─────┘ └─────┬─────┘ └────────┬────────┘     │
│         │              │             │                 │              │
│   ┌─────┴──────────────┴─────────────┴─────────────────┴────────┐   │
│   │              3x SQLite (bazaar.db / tasks.db / caps.db)      │   │
│   └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 2. 技术栈一览

| 层级 | 技术 | 用途 |
|------|------|------|
| **前端** | React 19 + TypeScript | SPA 界面 |
| **状态管理** | Zustand | 轻量级 store |
| **UI 组件** | Radix UI + TailwindCSS | 无障碍 + 样式 |
| **代码高亮** | Shiki | Markdown 渲染 |
| **后端** | Express + TypeScript | HTTP + 静态文件 |
| **WebSocket** | ws (Node.js) | 实时双向通信 |
| **桌面端** | Electron + electron-vite | 原生窗口 + 系统集成 |
| **AI SDK** | @anthropic-ai/claude-agent-sdk v0.1 + claude-code v2.1 | Claude 会话管理 |
| **数据库** | SQLite (better-sqlite3) | 所有持久化 (WAL 模式) |
| **校验** | Zod | Schema 验证 |
| **定时任务** | node-cron | Cron 调度 |
| **浏览器控制** | Chrome DevTools Protocol (CDP) | Web Access 自动化 |

---

## 3. 目录结构（关键文件）

```
smanbase/
├── server/                         # 后端 (Node.js + TypeScript)
│   ├── index.ts                    # ★ 入口: HTTP + WebSocket + 50+ handler 注册
│   ├── claude-session.ts           # ★ 核心: Claude Agent SDK V2 会话生命周期
│   ├── session-store.ts            # SQLite 会话/消息持久化
│   ├── settings-manager.ts         # ~/.sman/config.json 读写
│   ├── skills-registry.ts          # Skills 注册和加载
│   ├── mcp-config.ts               # Web Search MCP 自动配置
│   ├── types.ts                    # 共享类型 (SmanConfig, CronTask, BatchTask...)
│   ├── user-profile.ts             # 用户画像自动维护
│   ├── model-capabilities.ts       # 模型能力探测 (API → 映射表 → 探针)
│   ├── cron-scheduler.ts           # Cron 调度器 (crontab.md 发现 + 同步)
│   ├── cron-executor.ts            # Cron 任务执行器
│   ├── cron-task-store.ts          # Cron SQLite 存储
│   ├── batch-engine.ts             # 批量任务引擎 (信号量并发)
│   ├── batch-store.ts              # 批量任务 SQLite 存储
│   ├── semaphore.ts                # 并发控制原语 (暂停/恢复/停止)
│   ├── batch-utils.ts              # 模板渲染 + 解释器检测
│   ├── chatbot/                    # Chatbot 子模块
│   │   ├── chatbot-session-manager.ts  # 消息路由 + 命令处理
│   │   ├── chatbot-store.ts            # 用户/会话/工作区 SQLite
│   │   ├── chat-command-parser.ts      # //cd //pwd //help 解析
│   │   ├── wecom-bot-connection.ts     # 企微 WebSocket (心跳/重连/流式)
│   │   ├── feishu-bot-connection.ts    # 飞书 SDK 事件监听
│   │   ├── weixin-bot-connection.ts    # 个人微信 (iLink Bot API + QR登录)
│   │   ├── weixin-api.ts              # iLink HTTP 客户端
│   │   ├── weixin-store.ts            # 微信账号文件持久化
│   │   ├── wecom-media.ts             # AES-256-CBC 媒体解密
│   │   └── types.ts                   # Chatbot 类型定义
│   ├── web-access/                 # 浏览器自动化子模块
│   │   ├── web-access-service.ts      # 服务层 (Tab 隔离, 引擎选择)
│   │   ├── cdp-engine.ts              # ★ Chrome DevTools Protocol (985行)
│   │   ├── browser-engine.ts          # 引擎接口 + 错误类型
│   │   ├── mcp-server.ts              # 11 个 MCP 工具
│   │   ├── chrome-sites.ts            # 书签/历史 URL 发现
│   │   ├── url-experience-store.ts    # URL 经验持久化
│   │   └── index.ts                   # 导出
│   ├── bazaar/                    # Agent 集市桥接层
│   │   ├── bazaar-bridge.ts           # ★ 消息路由 + 经验提取 + 磨合
│   │   ├── bazaar-client.ts           # WebSocket 客户端 (注册/心跳/重连)
│   │   ├── bazaar-session.ts          # 协作会话 (Claude SDK)
│   │   ├── bazaar-store.ts            # 本地 SQLite (routes/pairs/chat)
│   │   ├── bazaar-mcp.ts              # 2 个 MCP 工具 (search/collaborate)
│   │   ├── types.ts                   # 桥接层类型
│   │   └── index.ts                   # 单例初始化
│   ├── capabilities/              # 能力注册系统
│   │   ├── init-registry.ts           # 35 个能力初始化
│   │   ├── capability-registry.ts     # 能力注册/加载
│   │   └── generic-instruction-runner.ts  # 通用指令注入
│   ├── utils/
│   │   ├── logger.ts                  # 结构化 JSON 日志
│   │   └── content-blocks.ts          # Claude 内容块构建
│   └── web-access/                    # (同上 web-access)
├── src/                            # React 前端
│   ├── app/
│   │   ├── App.tsx                    # 顶层组件
│   │   └── routes.tsx                 # 路由 (/chat, /settings, /cron, /batch, /bazaar)
│   ├── features/
│   │   ├── chat/                      # 聊天功能
│   │   │   ├── index.tsx              # 聊天页面主组件
│   │   │   ├── ChatInput.tsx          # 消息输入 (拖拽文件/截图/语音)
│   │   │   ├── ChatMessage.tsx        # 消息渲染 (Markdown + 代码高亮)
│   │   │   ├── ChatToolbar.tsx        # 工具栏
│   │   │   ├── message-utils.ts       # 消息处理
│   │   │   └── highlighter.ts         # Shiki 高亮
│   │   ├── settings/                  # 设置 (6 个 Tab)
│   │   ├── cron-tasks/                # Cron 任务页面
│   │   ├── batch-tasks/               # 批量任务页面
│   │   └── bazaar/                    # Agent 集市 (仪表盘 + 像素世界)
│   ├── components/                    # 通用组件
│   │   ├── SessionTree.tsx            # 会话树 (按目录分组)
│   │   ├── DirectorySelectorDialog.tsx # 目录选择
│   │   ├── SkillPicker.tsx            # Skill 选择器
│   │   ├── layout/                    # MainLayout (sidebar 可隐藏)
│   │   ├── common/                    # 通用组件
│   │   └── ui/                        # Radix UI 基础组件
│   ├── stores/                        # Zustand 状态管理
│   │   ├── chat.ts                    # 聊天状态 (消息/会话)
│   │   ├── settings.ts               # 设置状态 (后端同步)
│   │   ├── bazaar.ts                  # 集市状态 (连接/任务/世界坐标)
│   │   ├── cron.ts                    # Cron 任务状态
│   │   ├── batch.ts                   # 批量任务状态
│   │   └── ws-connection.ts           # WebSocket 连接状态
│   ├── lib/
│   │   ├── ws-client.ts              # ★ WebSocket 客户端 (自动重连/认证)
│   │   ├── auth.ts                   # Auth token 管理
│   │   └── utils.ts                  # 通用工具
│   └── types/
│       ├── chat.ts                   # ContentBlock, Message 类型
│       └── settings.ts               # 设置类型 (前端镜像后端)
├── electron/                       # Electron 桌面应用
│   ├── main.ts                       # ★ 主进程 (窗口/IPC/后端启动/GPU兼容)
│   └── preload.ts                    # 预加载 (selectDirectory/window API)
├── bazaar/                         # Agent 集市独立服务器
│   ├── package.json                  # 独立依赖 (better-sqlite3/express/ws)
│   └── src/
│       ├── index.ts                  # ★ 入口 (HTTP :5890 + WebSocket)
│       ├── message-router.ts         # WS 消息分发 (ACK + 类型路由)
│       ├── agent-store.ts            # Agent 注册/心跳 SQLite
│       ├── task-engine.ts            # ★ 任务路由/排队 (10种状态流转)
│       ├── capability-store.ts       # 能力包 CRUD + 搜索
│       ├── capability-search.ts      # 能力发现
│       ├── reputation.ts             # 声望计算 (防作弊日上限)
│       ├── world-state.ts            # 像素世界 (6 个区域 + 区域事件)
│       ├── audit-log.ts              # 审计日志
│       ├── project-index.ts          # 项目能力索引
│       └── protocol.ts              # 消息协议 (类型白名单 + 必填字段)
├── shared/                         # 共享类型
│   └── bazaar-types.ts              # Agent集市消息协议
├── plugins/                        # 插件目录 (16 个插件)
│   ├── superpowers/                  # 开发方法论 (TDD/调试/规划...)
│   ├── dev-workflow/                 # 完整开发流程
│   ├── web-access/                   # 浏览器操作 Skill
│   ├── office-skills/                # Office 文档处理 (Python venv)
│   ├── gstack-skills/                # 25 个子插件
│   └── ...                           # 其他 11 个插件
├── tests/                          # 测试
│   └── server/                       # 后端测试 (按模块组织)
├── scripts/                        # 工具脚本
│   ├── init-skills.ts               # Skills 初始化
│   ├── init-system.ts               # 系统初始化
│   └── patch-sdk.mjs                # SDK postinstall 补丁
└── docs/                           # 文档
```

---

## 4. 核心调用链（最关键 6 条）

### 4.1 桌面端聊天流（主流程）

```
用户输入消息
    │
    ▼
ChatInput.tsx ──ws.send──▶ ws-client.ts ──▶ server/index.ts (chat.send)
    │
    ▼
ClaudeSessionManager.sendMessage()
    │
    ├── 1. abort 现有流（如有）
    ├── 2. 存储用户消息到 SQLite
    ├── 3. getOrCreateV2Session(sessionId)
    │       │
    │       ├── process.chdir(workspace)   ← 序列化防竞态
    │       ├── unstable_v2_createSession(options)
    │       │       │
    │       │       ├── MCP Servers: web-search + web-access + bazaar + capability-gateway
    │       │       ├── Plugins: superpowers + dev-workflow
    │       │       └── System prompt: claude_code 预设
    │       │
    │       └── 缓存 V2SessionInfo
    │
    ├── 4. 注入 Profile 前缀 + Sman 上下文
    ├── 5. buildContentBlocks(text, media, capabilities)
    ├── 6. v2Session.send(content)
    │
    ├── 7. for await (sdkMsg of v2Session.stream())
    │       │
    │       ├── assistant     → chat.delta (文本/thinking)
    │       ├── tool_start    → chat.tool_start (工具调用)
    │       ├── tool_progress → chat.tool_delta
    │       ├── result        → chat.segment (结果片段)
    │       └── done          → chat.done (含 cost/usage)
    │
    ├── 8. 自动重试 (stall/dead/lost/server_error 最多2次)
    └── 9. updateProfile() (异步, 不阻塞)
    │
    ▼
ws-client.ts ◀──ws.on('message')◀── server 推送
    │
    ▼
chatStore.onMessage() → React 重渲染 → ChatMessage.tsx 显示
```

### 4.2 企微 Bot 聊天流

```
用户在企微发消息
    │
    ▼
wss://openws.work.enterprise.qq.com
    │
    ▼
WeComBotConnection (心跳30s, 指数退避重连)
    │  解密: AES-256-CBC (wecom-media.ts)
    ▼
ChatbotSessionManager.handleMessage(msg, sender)
    │
    ├── 命令? → parseChatCommand() → //cd //pwd //help //status //workspaces //new
    │
    └── 对话? → executeChatQuery()
                │
                ▼
            ClaudeSessionManager.sendMessageForChatbot()
                │ 5分钟超时
                ▼
            onResponse(chunk) → ChatResponseSender.sendChunk()
                │
                ▼
            WeComBotConnection.send() (1秒节流, 30msg/min 限制)
```

### 4.3 Cron 定时任务流

```
crontab.md 文件 (workspace/.claude/skills/)
    │
    ▼ 30分钟自动扫描
CronScheduler.scanAndSync()
    │  parseCrontabMd() → 5段 cron + 命令
    ▼
node-cron 定时触发 → CronScheduler.executeTask()
    │
    ▼
CronExecutor.execute(task)
    │
    ├── 1. canExecute(task) ← 检查 lock 文件 + 僵尸检测 (30min)
    ├── 2. createSessionWithId() ← "cron-{project}-{skill}-{timestamp}"
    ├── 3. sendMessageForCron() ← 无 UI 推送, 仅写 DB
    └── 4. updateRun(status) → 广播 cron.runStatusChanged
```

### 4.4 批量任务执行流

```
batch.md 模板 + 数据源
    │
    ▼
BatchEngine Pipeline:
    │
    ├── 1. generateCode() → Claude SDK 生成数据获取脚本
    ├── 2. testCode()     → 执行脚本, 解析 JSON, 验证数组
    ├── 3. save()         → 持久化任务 + 数据项
    └── 4. execute()      → Semaphore(并发数) 控制执行
            │
            ├── 每个数据项: createSession → sendMessageForCron
            ├── emitProgress() → 广播 batch.progress
            └── pause/resume/cancel/retry via Semaphore
```

### 4.5 Web Access 浏览器自动化流

```
Claude 调用 MCP 工具: web_access_navigate(url)
    │
    ▼
WebAccessMcpServer (11个工具, 进程内 MCP)
    │
    ▼
WebAccessService.navigateOrCreateTab(sessionId, url)
    │
    ├── Tab 复用? → 查找已有 session tab (最多5个)
    │
    └── 新 Tab:
        │
        ▼
    CdpEngine.newTab()
        │
        ├── ensureConnected()
        │   ├── discoverChromePort() ← DevToolsActivePort / 端口扫描
        │   └── launchChrome() ← 复制 profile 到 ~/.sman/chrome-profile/
        │
        ├── sendCDP('Target.createTarget')
        ├── waitForDomStable() ← MutationObserver + Network 空闲
        └── takeSnapshot() → 无障碍树 + 登录检测
```

### 4.6 Agent 集市协作流

```
Claude 调用 MCP 工具: bazaar_collaborate(question, target?)
    │
    ▼
BazaarMcpServer
    │
    ├── bazaar_search → 搜索本地经验 + 远程 Agent (排序: 老搭档 > 历史 > 有经验 > 远程)
    │
    └── bazaar_collaborate
        │
        ▼
    BazaarBridge → BazaarClient → ws://bazaar:5890
        │
        ▼
    Bazaar Server TaskEngine
        │
        ├── 创建 task (searching)
        ├── 关键词匹配在线 Agent (按声望排序)
        ├── 发送 task.incoming → 目标 Agent
        │
        ▼ 目标 Agent 接受
    BazaarBridge 收到 task.accept
        │
        ▼
    BazaarSession.startCollaboration()
        │
        ├── 创建独立 Claude session (bazaar-{taskId})
        ├── 注入协作上下文 (搭档历史 + 经验路由)
        ├── sendMessageForCron() → 获取答案
        └── task.complete → 经验提取 (Claude API 总结 100 字)
            │
            ▼
        learned_routes 表 + pair_history 表 (Agent 进化)
```

---

## 5. 数据库 Schema 总览

### 5.1 主数据库 `~/.sman/sman.db`

```sql
-- 会话
sessions (id PK, system_id, workspace, label, sdk_session_id, is_cron,
          created_at, last_active_at, deleted_at)  -- 软删除

-- 消息
messages (id PK AUTOINCREMENT, session_id FK, role, content,
          content_blocks TEXT, created_at)

-- Cron 任务
cron_tasks (id PK, workspace, skill_name, cron_expression, source,
            enabled, created_at, updated_at)
cron_runs (id PK AUTOINCREMENT, task_id FK, session_id, status,
           started_at, finished_at, last_activity_at, error_message)

-- 批量任务
batch_tasks (id PK, workspace, skill_name, md_content, exec_template,
             generated_code, env_vars, concurrency, retry_on_failure,
             status, total_items, success_count, failed_count, ...)
batch_items (id PK AUTOINCREMENT, task_id FK, item_data, item_index,
             status, session_id, error_message, cost, retries, ...)

-- Chatbot
chatbot_users (user_id PK, platform, platform_uid, current_workspace)
chatbot_sessions (user_id + workspace PK, session_id)
chatbot_workspaces (workspace PK, last_used_at)
```

### 5.2 集市本地 `~/.sman/bazaar.db`

```sql
identity (agent_id PK, hostname, username, name, server)
tasks (task_id PK, direction, helper_*, requester_*, question, status, rating)
chat_messages (id AUTO, task_id, from_agent, text)
learned_routes (capability + agent_id PK, experience)     -- Agent 进化
pair_history (partner_id PK, task_count, avg_rating)      -- 磨合记录
cached_results (task_id PK, result_text, from_agent)
```

### 5.3 集市服务器 `~/.bazaar/`

```sql
-- bazaar.db
agents (id PK, username UNIQUE, hostname, name, description, status,
        reputation, last_seen_at)
audit_log (id AUTO, event_type, agent_id, detail JSON)
reputation_log (id AUTO, agent_id, task_id, delta, reason)

-- tasks.db
tasks (id PK, requester_id, helper_id, question, status, ...)
chat_messages (id AUTO, task_id, from_id, content)

-- capabilities.db
capabilities (name PK, description, version, category, package_url)
```

---

## 6. WebSocket 消息协议

### 客户端 → 服务端

| 类型 | 参数 | 说明 |
|------|------|------|
| `auth.verify` | `{token}` | 认证 |
| `session.create` | `{workspace}` | 创建会话 |
| `session.list` | - | 列出会话 |
| `session.delete` | `{sessionId}` | 删除会话 (软删除) |
| `session.history` | `{sessionId}` | 获取历史消息 |
| `chat.send` | `{sessionId, content, media?}` | 发送消息 |
| `chat.abort` | `{sessionId}` | 中止当前响应 |
| `settings.get/update` | `{config}` | 配置读写 |
| `skills.list` | - | 全局 Skills |
| `skills.listProject` | `{workspace}` | 项目 Skills |
| `cron.*` | 各异 | Cron CRUD + 执行 |
| `batch.*` | 各异 | Batch 全生命周期 |
| `bazaar.*` | 各异 | 集市操作 (转发到 Bridge) |

### 服务端 → 客户端 (流式)

```
chat.start  ──▶ chat.delta (×N) ──▶ chat.tool_start ──▶ chat.tool_delta (×N)
                                                     ──▶ chat.segment
                                  ──▶ ... (多轮工具调用) ...
                                                                  ──▶ chat.done
```

| 类型 | 数据 | 说明 |
|------|------|------|
| `chat.start` | `{sessionId}` | 开始响应 |
| `chat.delta` | `{content, type: text/thinking}` | 文本/thinking 增量 |
| `chat.tool_start` | `{tool, input}` | 工具调用开始 |
| `chat.tool_delta` | `{tool, inputDelta}` | 工具参数增量 |
| `chat.segment` | `{content}` | 结果片段 |
| `chat.done` | `{cost, usage, duration}` | 响应完成 |
| `chat.error` | `{message, code}` | 错误 |

---

## 7. Claude Agent SDK V2 会话管理

```
┌─────────────────────────────────────────────────────────┐
│            ClaudeSessionManager 核心状态                  │
│                                                          │
│  sessions:      Map<sessionId, ActiveSession>            │
│  v2Sessions:    Map<sessionId, V2SessionInfo>            │
│  activeStreams: Map<sessionId, AbortController>          │
│  sdkSessionIds: Map<sessionId, SDK session_id>  ← 持久化 │
│  preheatPromises: Map<sessionId, Promise>     ← 预创建   │
│  v2CreateChain: Promise                      ← 串行化锁  │
│                                                          │
│  超时: IDLE=30min | STALL=3min | TOOL=2h                │
│  清理: 每60秒扫描 idle session                           │
│  重试: stall/dead/lost/server_error 最多2次              │
└─────────────────────────────────────────────────────────┘

三种发送模式:
┌───────────────────┬──────────────┬────────────────┐
│ sendMessage       │ 桌面端       │ 完整流式推送 UI │
│ sendMessageForCron│ Cron/Batch  │ 仅写 DB        │
│ sendMessageForChatbot│ 企微/飞书  │ chunk回调+节流 │
└───────────────────┴──────────────┴────────────────┘
```

### V2 Session 构建选项

```typescript
buildSessionOptions(workspace) {
  return {
    model: config.llm.model,
    apiKey: config.llm.apiKey,
    baseURL: config.llm.baseURL,
    preset: 'claude_code',            // 系统提示词预设
    bypassPermissions: true,           // 非 root 用户
    plugins: ['superpowers', 'dev-workflow'],
    mcpServers: {
      'brave-search' | 'tavily-search' | 'bing-search',  // Web 搜索
      'web-access',       // 浏览器自动化 (11 工具)
      'bazaar',           // Agent 集市 (2 工具)
      'capability-gateway' // 能力网关
    },
    skills: globalSkills + projectSkills
  }
}
```

---

## 8. MCP 工具清单

### 8.1 Web Access (11 个工具)

| 工具 | 说明 | 关键实现 |
|------|------|---------|
| `web_access_find_url` | 搜索 Chrome 历史 + 经验映射 | chrome-sites.ts + url-experience-store.ts |
| `web_access_remember_url` | 保存 URL 映射 | ~/.sman/web-access-experiences.json |
| `web_access_navigate` | 导航到 URL | CdpEngine → Target.createTarget + Page.navigate |
| `web_access_snapshot` | 无障碍树 | CdpEngine → Runtime.evaluate (DOM稳定性检测) |
| `web_access_screenshot` | 截图 PNG | CdpEngine → Page.captureScreenshot |
| `web_access_click` | 点击元素 | CdpEngine → Runtime.evaluate (dispatchEvent) |
| `web_access_fill` | 填写表单 | CdpEngine → Runtime.evaluate (value setter) |
| `web_access_press_key` | 按键 | CdpEngine → Input.dispatchKeyEvent |
| `web_access_evaluate` | 执行 JS | CdpEngine → Runtime.evaluate |
| `web_access_list_tabs` | 列出标签页 | CdpEngine → Target.getTargets |
| `web_access_close_tab` | 关闭标签页 | CdpEngine → Target.closeTarget |

### 8.2 Bazaar (2 个工具)

| 工具 | 说明 | 排序逻辑 |
|------|------|---------|
| `bazaar_search` | 搜索经验 + Agent | 老搭档 > 历史协作 > 有经验 > 远程 |
| `bazaar_collaborate` | 发起协作请求 | 自动创建 task → 匹配 → 通信 |

### 8.3 Web Search (3 选 1)

| 提供商 | MCP Server | 命令 |
|--------|-----------|------|
| Brave | brave-search | `npx @anthropic-ai/mcp-server-brave` |
| Tavily | tavily-search | `npx @anthropic-ai/mcp-server-tavily` |
| Bing | bing-search | `npx @anthropic-ai/mcp-server-bing` |

---

## 9. 插件体系

### 加载流程

```
server/index.ts 启动
    │
    ▼
CapabilityRegistry.initCapabilities()
    │
    ├── 扫描 plugins/ 目录 (16 个插件)
    ├── 每个能力: { id, name, executionMode, triggers, runnerModule, pluginPath }
    │
    ├── executionMode = "instruction-inject"
    │   └── SKILL.md 内容注入到 Claude 对话上下文
    │
    └── executionMode = "mcp-dynamic"
        └── 启动独立 MCP Server (如 office-skills)
    │
    ▼
写入 ~/.sman/capabilities.json (含 enabled/disabled 状态)
```

### 关键插件

| 插件 | 类型 | 说明 |
|------|------|------|
| **superpowers** | instruction-inject | 15 个 Skill: brainstorming, writing-plans, TDD, debugging... |
| **dev-workflow** | instruction-inject | 完整开发流程链 |
| **web-access** | instruction-inject | 浏览器操作 Skill |
| **office-skills** | mcp-dynamic | PPTX/DOCX/XLSX/PDF (Python venv) |
| **gstack-skills** | instruction-inject | 25 个子插件: review, careful, investigate, qa... |
| **skill-creator** | instruction-inject | Skill 创建工具 |

---

## 10. Agent 进化机制

```
协作完成 (rating >= 3)
    │
    ▼
BazaarBridge 提取经验
    │
    ├── Claude API 直接调用 → 生成 100 字经验摘要
    │
    ▼
写入本地 SQLite:
    │
    ├── learned_routes (capability, agent_id, experience)
    │   └── 下次搜索 bazaar_search 时优先匹配
    │
    └── pair_history (partner_id, task_count, avg_rating)
        └── 搜索排序: 老搭档 > 历史协作 > 有经验 > 远程
```

---

## 11. 模型能力探测 (三层降级)

```
Layer 1: API 查询
    Anthropic /v1/models/{model}
        │ 失败?
        ▼
Layer 2: 映射表
    MODEL_CAPABILITIES_MAP (模糊匹配模型名)
        │ 未命中?
        ▼
Layer 3: 探针
    发送红色图片 → 检查模型是否正确识别颜色
    → 确定 text/image/pdf/audio/video 能力
```

---

## 12. 关键设计决策

| 决策 | 原因 |
|------|------|
| SQLite 而非 PG/MySQL | 单机部署零运维，better-sqlite3 同步 API 性能优 |
| WAL 模式 | 允许并发读，写不阻塞读 |
| 软删除 sessions | 用户可恢复误删会话 |
| V2 Session 序列化创建 | `process.chdir` 非线程安全，串行化防竞态 |
| V2 Session ID 持久化 | 进程重启后可 resume 会话 |
| 流式节流 (企微1s/飞书3s) | 平台消息频率限制 |
| Chrome Profile 复制 | 复用已登录的 Cookie/Session，避免重复登录 |
| CDP 而非 Puppeteer | 直接协议控制更轻量，无额外依赖 |
| 插件 SKILL.md 注入 | 最简实现，Claude 直接理解自然语言指令 |
| 集市独立部署 | Agent 可跨实例协作，解耦主服务 |

---

## 13. 端口与服务

```
┌──────────┐   ┌──────────────────────────────────────────┐
│ Electron │   │  :5880  主服务 (HTTP + WebSocket)        │
│ Browser  │──▶│  :5881  Vite Dev (仅开发模式)            │
│ Window   │   │  :5890  Bazaar Server (独立部署)          │
└──────────┘   │  :9333  Chrome CDP (Web Access 自动启动)  │
               └──────────────────────────────────────────┘
```

---

## 14. 用户数据目录 `~/.sman/`

```
~/.sman/
├── config.json              # 主配置 (LLM/WebSearch/Chatbot/Auth/Bazaar)
├── registry.json            # Skills 注册表
├── capabilities.json        # 能力启用状态
├── sman.db                  # 主数据库 (会话/消息/Cron/Batch/Chatbot)
├── bazaar.db                # 集市本地数据 (identity/tasks/routes/pairs)
├── user-profile.md          # 用户画像 (LLM 自动维护)
├── web-access-experiences.json  # URL 经验映射
├── chrome-profile/          # Chrome Profile 复制 (Cookie/书签)
├── weixin/accounts/         # 微信账号数据
├── skills/                  # 全局 Skills
└── logs/                    # 日志文件
```
