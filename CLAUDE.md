# Sman - 智能业务系统平台

> 此文件给 Claude Code 提供项目上下文

## 项目定位

Sman 是一个简化的智能业务平台，用户只需选择项目目录即可开始对话，无需预先配置业务系统。支持桌面端（Electron）、WeCom Bot、飞书 Bot 三端交互。

## 核心架构

```
用户 (桌面端 / WeCom Bot / 飞书 Bot)
         ↓
    Sman 后端 (Express + WebSocket)
         ↓
    Claude Agent SDK (V2 Session)
         ↓
    项目目录 (用户选择) + MCP Servers + Plugins
```

### 三端入口

| 入口 | 连接方式 | 文件 |
|------|---------|------|
| 桌面端 | WebSocket (`ws://localhost:5880/ws`) | `electron/main.ts` |
| WeCom Bot | WebSocket 长连接 (`wss://openws.work.weixin.qq.com`) | `server/chatbot/wecom-bot-connection.ts` |
| 飞书 Bot | 飞书 SDK 事件监听 | `server/chatbot/feishu-bot-connection.ts` |

## 使用流程

1. **新建会话** → 点击"新建会话"按钮 → 选择项目目录 → 开始对话
2. **会话管理** → 按目录分组显示 → 点击切换会话
3. **WeCom/飞书** → 直接在 Bot 对话中发消息，用 `//cd <项目名>` 切换工作目录

**设计理念**：越简单越好，不要让用户看不懂。

## 目录结构

```
├── server/                  # Node.js 后端
│   ├── index.ts             # 后端入口，WebSocket + HTTP + 所有 handler 注册
│   ├── claude-session.ts    # Claude Agent SDK V2 会话管理（生命周期、idle 清理、resume）
│   ├── session-store.ts     # SQLite 会话和消息存储
│   ├── settings-manager.ts  # ~/.sman/config.json 读写
│   ├── skills-registry.ts   # Skills 注册和加载
│   ├── mcp-config.ts        # MCP Server 自动配置（Brave/Tavily/Bing 搜索）
│   ├── types.ts             # 共享 TypeScript 类型（SmanConfig, CronTask, BatchTask 等）
│   ├── cron-scheduler.ts    # Cron 定时任务调度器
│   ├── cron-executor.ts     # Cron 任务执行器（调用 Claude session）
│   ├── cron-task-store.ts   # Cron 任务 SQLite 存储
│   ├── batch-engine.ts      # 批量任务执行引擎（信号量并发控制）
│   ├── batch-store.ts       # 批量任务 SQLite 存储
│   ├── batch-utils.ts       # 批量任务工具函数
│   ├── semaphore.ts         # 并发控制原语（暂停/恢复/停止）
│   ├── chatbot/             # Chatbot 子模块（WeCom + 飞书）
│   │   ├── chatbot-session-manager.ts  # Chatbot 会话管理（消息路由、命令处理）
│   │   ├── chatbot-store.ts            # Chatbot 用户状态 SQLite 存储
│   │   ├── chat-command-parser.ts      # //cd, //pwd, //help 等命令解析
│   │   ├── wecom-bot-connection.ts     # WeCom Bot WebSocket 连接（心跳、重连、流式推送）
│   │   ├── feishu-bot-connection.ts    # 飞书 Bot 连接
│   │   └── types.ts                    # Chatbot 类型定义
│   ├── web-access/          # Web Access 子模块（浏览器自动化）
│   │   ├── web-access-service.ts  # Web Access 服务层
│   │   ├── cdp-engine.ts          # Chrome DevTools Protocol 引擎（DOM 稳定性检测、页面快照）
│   │   ├── browser-engine.ts      # 浏览器引擎抽象
│   │   ├── mcp-server.ts          # MCP Server（暴露 web_access_* 工具）
│   │   ├── chrome-sites.ts        # Chrome 书签/历史自动发现企业站点
│   │   └── index.ts               # 导出
│   └── utils/
│       └── logger.ts              # 日志工具
├── src/                     # React 前端
│   ├── app/
│   │   ├── App.tsx          # 顶层应用组件
│   │   └── routes.tsx       # 路由定义 (/chat, /settings, /cron-tasks, /batch-tasks, /bazaar)
│   ├── features/
│   │   ├── chat/            # 聊天功能
│   │   │   ├── index.tsx          # 聊天页面主组件
│   │   │   ├── ChatInput.tsx      # 消息输入框
│   │   │   ├── ChatMessage.tsx    # 消息渲染（Markdown、代码高亮、tool_use 展示）
│   │   │   ├── ChatToolbar.tsx    # 聊天工具栏
│   │   │   ├── message-utils.ts   # 消息处理工具
│   │   │   └── highlighter.ts     # 代码高亮（Shiki）
│   │   ├── settings/        # 设置页面
│   │   │   ├── index.tsx          # 设置页面主入口（Tab 面板）
│   │   │   ├── LLMSettings.tsx    # LLM 配置（API Key, Model, BaseURL）
│   │   │   ├── WebSearchSettings.tsx  # Web 搜索提供商配置（Brave/Tavily/Bing）
│   │   │   ├── ChatbotSettings.tsx    # Chatbot 配置（WeCom/飞书 Bot）
│   │   │   ├── CronTaskSettings.tsx   # Cron 任务管理
│   │   │   ├── BatchTaskSettings.tsx  # 批量任务管理
│   │   │   └── BackendSettings.tsx    # 后端服务 URL 配置
│   │   ├── cron-tasks/      # Cron 任务页面（占位）
│   │   ├── batch-tasks/     # 批量任务页面（占位）
│   │   └── bazaar/          # Agent集市页面（仪表盘 + 像素世界）
│   ├── components/          # 通用组件
│   │   ├── SessionTree.tsx         # 会话树（按目录分组、内置目录选择器）
│   │   ├── DirectorySelectorDialog.tsx  # 目录选择对话框
│   │   ├── SkillPicker.tsx         # Skill 选择器
│   │   ├── layout/                 # 布局组件（MainLayout 隐藏 sidebar 用于 /bazaar 等路由）
│   │   ├── common/                 # 通用组件
│   │   └── ui/                     # Radix UI 基础组件
│   ├── stores/              # Zustand 状态管理
│   │   ├── chat.ts          # 聊天状态（消息、会话管理）
│   │   ├── settings.ts      # 设置状态（同步后端配置）
│   │   ├── bazaar.ts        # Agent集市状态（连接、任务、Agent列表、世界坐标）
│   │   ├── cron.ts          # Cron 任务状态
│   │   ├── batch.ts         # 批量任务状态
│   │   └── ws-connection.ts # WebSocket 连接状态
│   ├── lib/                 # 工具库
│   │   ├── ws-client.ts     # WebSocket 客户端（自动重连、认证）
│   │   ├── auth.ts          # Auth token 工具
│   │   └── utils.ts         # 通用工具函数
│   └── types/               # TypeScript 类型
│       ├── chat.ts          # 聊天类型（ContentBlock, Message 等）
│       └── settings.ts      # 设置类型（前端镜像后端类型）
├── electron/                # Electron 桌面应用
│   ├── main.ts              # 主进程（窗口管理、IPC、后端启动）
│   └── preload.ts           # 预加载脚本（暴露 selectDirectory API）
├── plugins/                 # Claude Code 插件
│   ├── web-access/          # Web Access 插件（浏览器操作 Skill）
│   ├── superpowers/         # Superpowers 插件（TDD、调试、规划等）
│   └── gstack -> /Users/nasakim/projects/gstack  # Gstack 插件（符号链接）
├── tests/                   # 测试文件
│   └── server/
│       ├── claude-session.test.ts
│       ├── mcp-config.test.ts
│       ├── settings-manager.test.ts
│       ├── session-store.test.ts
│       ├── semaphore.test.ts
│       ├── batch-engine.test.ts
│       ├── batch-store.test.ts
│       ├── batch-utils.test.ts
│       ├── chatbot/         # Chatbot 测试
│       │   ├── chatbot-session-manager.test.ts
│       │   ├── chatbot-store.test.ts
│       │   ├── chat-command-parser.test.ts
│       │   ├── wecom-bot-connection.test.ts
│       │   └── feishu-bot-connection.test.ts
│       └── web-access/      # Web Access 测试
│           ├── cdp-engine.test.ts
│           ├── mcp-server.test.ts
│           └── web-access-service.test.ts
├── scripts/                 # 工具脚本
│   ├── init-skills.ts       # Skills 初始化
│   ├── init-system.ts       # 系统初始化
│   └── patch-sdk.mjs        # Claude Agent SDK postinstall 补丁
├── bazaar/                  # Agent集市服务器（独立包边界）
│   ├── package.json         # 独立依赖
│   ├── tsconfig.json        # 独立编译配置
│   └── src/
│       ├── index.ts         # 集市服务器入口（HTTP API + WebSocket）
│       ├── message-router.ts # WS 消息分发
│       ├── agent-store.ts   # Agent 注册/心跳
│       ├── project-index.ts # 项目能力索引
│       ├── task-engine.ts   # 任务路由/排队
│       ├── capability-search.ts # 能力发现
│       ├── capability-store.ts  # 通用能力包存储（CRUD + 搜索）
│       ├── reputation.ts    # 声望计算
│       ├── audit-log.ts     # 审计日志
│       ├── world-state.ts   # 世界状态
│       └── protocol.ts      # 消息协议定义
├── server/bazaar/           # Agent集市桥接层（主项目 ↔ 集市服务器）
│   ├── bazaar-store.ts      # 本地存储（tasks, learned_routes, pair_history, chat）
│   ├── bazaar-bridge.ts     # 连接管理 + 消息处理（经验提取、磨合记录、上下文注入）
│   ├── bazaar-client.ts     # WebSocket 客户端（注册、心跳、重连）
│   ├── bazaar-mcp.ts        # MCP 工具（bazaar_search, bazaar_collaborate）
│   └── bazaar-session.ts    # 协作会话管理（Claude Agent SDK 集成）
├── shared/                  # 共享类型（Sman + Bazaar 共用）
│   └── bazaar-types.ts      # Agent集市消息协议类型
├── docs/                    # 文档
│   ├── windows-packaging.md
│   └── superpowers/         # 设计文档和规格
└── build/                   # 构建产物
```

## 用户数据目录 (`~/.sman/`)

```
~/.sman/
├── config.json          # LLM + WebSearch + Chatbot + Auth 配置
├── registry.json        # Skills 注册表
├── sman.db              # SQLite 数据库（会话、消息、Cron、Batch、Chatbot 状态）
├── skills/              # 全局 Skills（预制通用技能）
└── logs/                # 日志文件
```

## Skills 机制

### Skills 加载顺序

1. **全局 Skills**: `~/.sman/skills/` - 预制的通用技能，所有项目可用
2. **项目 Skills**: `{workspace}/.claude/skills/` - 项目特定的技能
3. **Plugins**: `plugins/` 目录下的插件（web-access, superpowers, gstack）

### Skills 工作流程

```
通用 Skills (项目分析)
         ↓
分析业务系统代码
         ↓
生成业务专用 Skills
         ↓
存放到 {workspace}/.claude/skills/
```

## 关键文件速查

| 文件 | 说明 |
|------|------|
| `server/index.ts` | 后端入口，所有 WebSocket handler 注册、服务初始化 |
| `server/claude-session.ts` | Claude Agent SDK V2 会话管理（创建、resume、idle 清理、流式推送） |
| `server/chatbot/chatbot-session-manager.ts` | Chatbot 消息路由（命令解析 → Claude 查询） |
| `server/chatbot/wecom-bot-connection.ts` | WeCom Bot WebSocket（心跳、重连、流式消息节流） |
| `server/chatbot/feishu-bot-connection.ts` | 飞书 Bot 连接 |
| `server/web-access/cdp-engine.ts` | Chrome DevTools Protocol 引擎 |
| `server/web-access/mcp-server.ts` | Web Access MCP Server（9 个工具） |
| `server/mcp-config.ts` | Web Search MCP 自动配置 |
| `server/session-store.ts` | SQLite 会话和消息存储 |
| `electron/main.ts` | Electron 主进程（窗口、后端启动、GPU 兼容） |
| `src/features/chat/` | 聊天功能组件 |
| `src/features/bazaar/BazaarPage.tsx` | Agent集市页面（仪表盘 + 像素世界双视图） |
| `src/features/bazaar/world/` | 像素世界渲染引擎（Canvas、交互、精灵） |
| `src/features/settings/` | 设置页面组件 |
| `src/stores/chat.ts` | 聊天状态管理 |
| `src/stores/bazaar.ts` | Agent集市状态管理 |
| `src/components/SessionTree.tsx` | 会话树 + 目录选择器 |
| `server/bazaar/bazaar-bridge.ts` | Agent集市桥接层（连接管理、经验提取、磨合机制） |
| `server/bazaar/bazaar-store.ts` | Agent集市本地存储（SQLite） |
| `server/bazaar/bazaar-mcp.ts` | Agent集市 MCP 工具（bazaar_search, bazaar_collaborate） |
| `bazaar/src/capability-store.ts` | 集市通用能力包存储 |

## WebSocket API

### 会话管理

| 类型 | 说明 |
|------|------|
| `session.create` | 创建会话，参数: `{ workspace: string }` |
| `session.list` | 列出所有会话 |
| `session.delete` | 删除会话，参数: `{ sessionId: string }` |
| `session.history` | 获取会话历史 |

### 聊天

| 类型 | 方向 | 说明 |
|------|------|------|
| `chat.send` | 客户端→服务端 | 发送消息，参数: `{ sessionId, content }` |
| `chat.abort` | 客户端→服务端 | 中止当前查询 |
| `chat.start` | 服务端→客户端 | 开始流式响应 |
| `chat.delta` | 服务端→客户端 | 流式文本/thinking/tool_use 增量 |
| `chat.tool_start` | 服务端→客户端 | 工具调用开始 |
| `chat.tool_delta` | 服务端→客户端 | 工具调用参数增量 |
| `chat.done` | 服务端→客户端 | 响应完成（含 cost, usage） |
| `chat.error` | 服务端→客户端 | 错误 |

### 设置

| 类型 | 说明 |
|------|------|
| `settings.get` | 获取配置 |
| `settings.update` | 更新配置 |
| `skills.list` | 列出所有 Skills |

### Cron / Batch

| 类型 | 说明 |
|------|------|
| `cron.create/update/delete/list` | Cron 任务 CRUD |
| `cron.run` | 手动触发 Cron 任务 |
| `batch.create/generate/test/save/run` | 批量任务生命周期 |
| `batch.pause/resume/cancel/retry` | 批量任务控制 |

### Agent集市

| 类型 | 说明 |
|------|------|
| `bazaar.status` | 连接状态（connected/disconnected） |
| `bazaar.task.list` | 获取协作任务列表 |
| `bazaar.agent.list` | 获取在线 Agent 列表 |
| `bazaar.leaderboard` | 获取声望排行榜 |
| `bazaar.task.accept/reject` | 接受/拒绝协作任务 |
| `bazaar.config.update` | 更新协作模式（auto/notify/manual） |
| `bazaar.world.move` | 发送 Agent 世界坐标更新 |
| `bazaar.notify` | 收到协作请求通知 |
| `bazaar.task.chat.delta` | 协作对话增量消息 |

## Chatbot 命令（WeCom/飞书）

| 命令 | 别名 | 说明 |
|------|------|------|
| `//cd <项目名或路径>` | - | 切换工作目录（支持 `~` 路径和数字序号） |
| `//pwd` | - | 显示当前工作目录 |
| `//workspaces` | `//wss` | 列出桌面端已打开的项目 |
| `//status` | `//sts` | 显示连接状态 |
| `//help` | - | 显示帮助信息 |

## Web Access 工具（MCP）

| 工具 | 说明 |
|------|------|
| `web_access_navigate` | 导航到 URL |
| `web_access_snapshot` | 获取页面无障碍树（可检测登录状态） |
| `web_access_screenshot` | 截图 |
| `web_access_click` | 点击元素 |
| `web_access_fill` | 填写表单字段 |
| `web_access_press_key` | 按键 |
| `web_access_evaluate` | 执行 JavaScript |
| `web_access_list_tabs` | 列出浏览器标签页 |
| `web_access_close_tab` | 关闭标签页 |

## Web Search MCP

根据设置自动配置对应 MCP Server：

| 提供商 | MCP Server | 说明 |
|--------|-----------|------|
| `builtin` | 无 | 使用 Claude Code 内置搜索 |
| `brave` | `brave-search` | Brave Search API |
| `tavily` | `tavily-search` | Tavily Search API |
| `bing` | `bing-search` | Bing Search API |

## 构建和运行

### 开发模式

```bash
# 一键启动 (后端 + 前端 + Electron)
./dev.sh

# 或分别启动
pnpm dev           # 前端 (5881)
pnpm dev:server    # 后端 (5880)
```

### 生产构建

```bash
pnpm build         # 构建前端 + 后端
pnpm build:electron # 编译 Electron 主进程
pnpm electron:build # 一键构建+打包 (build + build:electron + electron-builder)
```

### Windows 打包

详细指南见 `docs/windows-packaging.md`

### 环境要求

- **Node.js 22 LTS** (better-sqlite3 预编译二进制，无需本地编译)
- **pnpm** 包管理器

### 关键技术点

1. **CJS/ESM 兼容**: 服务端编译为 ESM (`module: "ES2022"`)，`dist/server/package.json` 声明 `"type": "module"`
2. **ASAR 已禁用**: `better-sqlite3` 原生模块在 ASAR 内路径解析失败
3. **ESM __dirname**: 服务端用 `path.dirname(fileURLToPath(import.meta.url))` 替代 `__dirname`
4. **Windows GPU**: VDI 环境需 `app.disableHardwareAcceleration()` 防白屏
5. **Auth 边界**: 只有 `/api/` 路径需要 Bearer auth，静态文件直接放行
6. **安装包选择**: NSIS 安装包启动快（推荐）；Portable 每次启动要解压，VDI 环境较慢

## 端口使用

| 端口 | 用途 |
|------|------|
| 5880 | HTTP 服务 + WebSocket (生产模式固定) |
| 5881 | Vite 开发服务器 (仅开发模式) |

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `PORT` | 5880 | HTTP 服务端口 |
| `SMANBASE_HOME` | `~/.sman` | 用户数据目录 |

## 技术栈

- **前端**: React 19 + TypeScript + TailwindCSS + Radix UI + Zustand
- **后端**: Node.js + TypeScript + Express + WebSocket (ws)
- **桌面**: Electron + electron-vite
- **数据库**: SQLite (better-sqlite3)
- **AI**: Claude Agent SDK (`@anthropic-ai/claude-agent-sdk` v0.1 + `@anthropic-ai/claude-code` v2.1)
- **代码高亮**: Shiki
- **Schema 校验**: Zod

## 注意事项

1. **目录选择**: Electron 使用原生对话框，Web 模式使用 API 浏览
2. **Skills 加载**: 同时加载全局和项目特定 Skills + Plugins
3. **会话分组**: 按目录名分组显示，目录名即为显示名称
4. **无预配置**: 用户无需预先配置业务系统，直接选择目录即可
5. **离线部署**: 设置页配置内网模型 URL + API Key + Model 即可使用，支持 `ANTHROPIC_BASE_URL`
6. **Chatbot 多轮对话**: WeCom/飞书 Bot 支持多轮对话，Claude 可在回复中询问用户信息，用户补充后继续
7. **WeCom 流式推送**: 通过 `aibot_respond_msg` + `msgtype: 'stream'` 实现流式回复，2 秒节流
8. **Web Access 浏览器**: 通过 CDP 协议控制 Chrome，自动发现书签/历史中的企业站点
9. **V2 Session 持久化**: SDK session_id 持久化到 SQLite，支持进程重启后恢复会话
10. **Agent集市三层架构**: 前端 (`src/features/bazaar/`) → 桥接层 (`server/bazaar/`) → 集市服务器 (`bazaar/src/`)
11. **集市全屏模式**: `/bazaar` 路由隐藏侧边栏，世界视图全屏 Canvas + 浮动控件
12. **Agent进化机制**: 对话经验自动提取 → learned_routes.experience 字段；磨合记录 → pair_history 表；搜索排序优先级：老搭档 > 历史协作 > 有经验 > 远程
13. **能力包**: 通过 sman CLI 子命令管理（`sman capabilities search/install/list`），集市服务器提供 HTTP API (`/api/capabilities/*`)
