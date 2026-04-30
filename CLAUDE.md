# Sman - 智能业务系统平台

> 此文件给 Claude Code 提供项目上下文

## 项目定位

Sman 是一个简化的智能业务平台，用户只需选择项目目录即可开始对话，无需预先配置业务系统。支持桌面端（Electron）、企业微信 Bot、飞书 Bot、微信 Bot 四端交互。

## 核心架构

```
用户 (桌面端 / 企业微信 / 飞书 / 微信)
         ↓
    Sman 后端 (Express + WebSocket)
         ↓
    Claude Agent SDK (V2 Session)
         ↓
    项目目录 (用户选择) + MCP Servers + Plugins + Capabilities
         ↕
    星域 (多 Agent 协作网络)
```

### 四端入口

| 入口 | 连接方式 | 文件 |
|------|---------|------|
| 桌面端 | WebSocket (`ws://localhost:5880/ws`) | `electron/main.ts` |
| 企业微信 | WebSocket 长连接 (`wss://openws.work.weixin.qq.com`) | `server/chatbot/wecom-bot-connection.ts` |
| 飞书 | 飞书 SDK 事件监听 | `server/chatbot/feishu-bot-connection.ts` |
| 微信 | 微信 Bot 连接 | `server/chatbot/weixin-bot-connection.ts` |

## 核心功能（侧边栏入口）

1. **新建会话** → 选择项目目录 → 开始对话 → 按目录分组显示会话
2. **协作星图** → 多 Agent 协作网络（仪表盘 + 像素世界）
3. **定时任务** → Cron 表达式驱动的自动化任务
4. **地球路径** → 多步骤自动化工作流（逐步骤执行，前一步结果作为下一步上下文）
5. **设置** → LLM、Web 搜索、Chatbot、用户画像等配置

**设计理念**：越简单越好，不要让用户看不懂。

## 目录结构

```
├── server/                  # Node.js 后端
│   ├── index.ts             # 后端入口，WebSocket + HTTP + 所有 handler 注册
│   ├── claude-session.ts    # Claude Agent SDK V2 会话管理（生命周期、idle 清理、resume、消息排队）
│   ├── session-store.ts     # SQLite 会话和消息存储
│   ├── settings-manager.ts  # ~/.sman/config.json 读写
│   ├── skills-registry.ts   # Skills 注册和加载
│   ├── mcp-config.ts        # MCP Server 自动配置（Brave/Tavily/Bing 搜索）
│   ├── model-capabilities.ts # 模型能力检测（支持的特性、上下文窗口等）
│   ├── user-profile.ts      # 用户画像管理（注入到 system prompt）
│   ├── knowledge-extractor.ts # 知识提取器（从对话提取业务/规范/技术知识）
│   ├── knowledge-extractor-store.ts # 知识提取进度存储（SQLite）
│   ├── types.ts             # 共享 TypeScript 类型（SmanConfig, CronTask, BatchTask 等）
│   ├── cron-scheduler.ts    # Cron 定时任务调度器
│   ├── cron-executor.ts     # Cron 任务执行器（调用 Claude session）
│   ├── cron-task-store.ts   # Cron 任务 SQLite 存储
│   ├── batch-engine.ts      # 批量任务执行引擎（信号量并发控制）
│   ├── batch-store.ts       # 批量任务 SQLite 存储
│   ├── batch-utils.ts       # 批量任务工具函数
│   ├── semaphore.ts         # 并发控制原语（暂停/恢复/停止）
│   ├── chatbot/             # Chatbot 子模块（企业微信 + 飞书 + 微信）
│   │   ├── chatbot-session-manager.ts  # Chatbot 会话管理（消息路由、命令处理）
│   │   ├── chatbot-store.ts            # Chatbot 用户状态 SQLite 存储
│   │   ├── chat-command-parser.ts      # //cd, //pwd, //help 等命令解析
│   │   ├── wecom-bot-connection.ts     # 企业微信 Bot WebSocket（心跳、重连、流式推送）
│   │   ├── feishu-bot-connection.ts    # 飞书 Bot 连接
│   │   ├── weixin-bot-connection.ts    # 微信 Bot 连接
│   │   ├── weixin-store.ts             # 微信用户状态存储
│   │   ├── weixin-api.ts               # 微信 API 封装
│   │   ├── weixin-types.ts             # 微信类型定义
│   │   ├── wecom-media.ts              # 企业微信媒体文件处理
│   │   └── types.ts                    # Chatbot 类型定义
│   ├── web-access/          # Web Access 子模块（浏览器自动化）
│   │   ├── web-access-service.ts  # Web Access 服务层
│   │   ├── cdp-engine.ts          # Chrome DevTools Protocol 引擎（DOM 稳定性检测、页面快照）
│   │   ├── browser-engine.ts      # 浏览器引擎抽象
│   │   ├── mcp-server.ts          # MCP Server（暴露 web_access_* 工具）
│   │   ├── chrome-sites.ts        # Chrome 书签/历史自动发现企业站点
│   │   └── index.ts               # 导出
│   ├── capabilities/        # Capabilities 系统（按需发现和激活的能力包）
│   │   ├── gateway-mcp-server.ts  # Capability Gateway MCP（注入每个会话）
│   │   ├── registry.ts            # 能力注册表（搜索、匹配、加载）
│   │   ├── types.ts               # Capability 类型定义
│   │   ├── project-scanner.ts     # 项目能力扫描
│   │   ├── scanner-prompts.ts     # 扫描提示词
│   │   ├── experience-learner.ts  # 经验学习（从对话中提取知识）
│   │   ├── office-skills-runner.ts    # Office 技能执行器
│   │   ├── frontend-slides-runner.ts  # 前端幻灯片生成器
│   │   ├── generic-instruction-runner.ts # 通用指令执行器
│   │   ├── frontmatter-utils.ts   # Frontmatter 解析工具
│   │   └── init-registry.ts       # 初始化注册表
│   ├── init/                # 会话初始化（新建会话时的自动流程）
│   │   ├── init-manager.ts        # 初始化流程编排
│   │   ├── workspace-scanner.ts   # 工作区扫描
│   │   ├── skill-injector.ts      # Skill 注入
│   │   ├── capability-matcher.ts  # 能力匹配
│   │   ├── claude-init-runner.ts  # Claude 初始化执行
│   │   ├── init-types.ts          # 初始化类型
│   │   └── templates/             # 初始化模板（含 knowledge-business/conventions/technical 知识 skill 占位）
│   ├── stardom/             # 星域桥接层（主项目 ↔ 星域服务器）
│   │   ├── stardom-store.ts        # 本地存储（tasks, learned_routes, pair_history, chat）
│   │   ├── stardom-bridge.ts       # 连接管理 + 消息处理（经验提取、磨合记录、上下文注入）
│   │   ├── stardom-client.ts       # WebSocket 客户端（注册、心跳、重连）
│   │   ├── stardom-mcp.ts          # MCP 工具（stardom_search, stardom_collaborate）
│   │   ├── stardom-session.ts      # 协作会话管理（Claude Agent SDK 集成）
│   │   ├── types.ts                # 桥接层类型
│   │   └── index.ts                # 导出
│   ├── smart-path-store.ts     # 地球路径存储（文件存储：{workspace}/.sman/paths/{id}.md）
│   ├── smart-path-engine.ts    # 地球路径执行引擎（逐步骤执行，纯内存临时会话）
│   └── smart-path-scheduler.ts # 地球路径定时调度（cron 表达式驱动自动执行）
│   └── utils/
│       ├── logger.ts              # 日志工具
│       └── content-blocks.ts      # 消息内容块构建（文本、图片、媒体）
├── src/                     # React 前端
│   ├── app/
│   │   ├── App.tsx          # 顶层应用组件
│   │   └── routes.tsx       # 路由定义 (/chat, /settings, /cron-tasks, /batch-tasks, /smart-paths, /stardom)
│   ├── features/
│   │   ├── chat/            # 聊天功能
│   │   │   ├── index.tsx          # 聊天页面主组件
│   │   │   ├── ChatInput.tsx      # 消息输入框
│   │   │   ├── ChatMessage.tsx    # 消息渲染（Markdown、代码高亮、tool_use 展示）
│   │   │   ├── ChatToolbar.tsx    # 聊天工具栏
│   │   │   ├── AskUserCard.tsx    # 用户交互卡片（Claude 提问时显示）
│   │   │   ├── InitBanner.tsx     # 初始化进度横幅
│   │   │   ├── message-utils.ts   # 消息处理工具
│   │   │   ├── highlighter.ts     # 代码高亮（Shiki）
│   │   │   ├── streamdown-components.tsx # Streamdown 自定义组件
│   │   │   └── streamdown-plugins.ts     # Streamdown 插件配置
│   │   ├── settings/        # 设置页面
│   │   │   ├── index.tsx          # 设置页面主入口（Tab 面板）
│   │   │   ├── LLMSettings.tsx    # LLM 配置（API Key, Model, BaseURL）
│   │   │   ├── WebSearchSettings.tsx  # Web 搜索提供商配置（Brave/Tavily/Bing）
│   │   │   ├── ChatbotSettings.tsx    # Chatbot 配置（企业微信/飞书/微信 Bot）
│   │   │   ├── UserProfileSettings.tsx # 用户画像配置
│   │   │   ├── CronTaskSettings.tsx   # Cron 任务管理
│   │   │   ├── BatchTaskSettings.tsx  # 批量任务管理
│   │   │   └── BackendSettings.tsx    # 后端服务 URL 配置
│   │   ├── cron-tasks/      # Cron 任务页面
│   │   ├── batch-tasks/     # 批量任务页面
│   │   ├── smart-paths/     # 地球路径页面（多步骤自动化工作流）
│   │   └── stardom/          # 星域页面（仪表盘 + 像素世界）
│   ├── components/          # 通用组件
│   │   ├── SessionTree.tsx         # 会话树（按目录分组、内置目录选择器）
│   │   ├── DirectorySelectorDialog.tsx  # 目录选择对话框
│   │   ├── SkillPicker.tsx         # Skill 选择器
│   │   ├── layout/                 # 布局组件（MainLayout 隐藏 sidebar 用于 /stardom 等路由）
│   │   ├── common/                 # 通用组件
│   │   └── ui/                     # Radix UI 基础组件
│   ├── stores/              # Zustand 状态管理
│   │   ├── chat.ts          # 聊天状态（消息、会话管理、流式渲染、消息排队）
│   │   ├── settings.ts      # 设置状态（同步后端配置）
│   │   ├── stardom.ts        # 星域状态（连接、任务、Agent 列表、世界坐标）
│   │   ├── cron.ts          # Cron 任务状态
│   │   ├── batch.ts         # 批量任务状态
│   │   ├── smart-path.ts    # 地球路径状态（路径 CRUD、步骤执行、流式进度）
│   │   └── ws-connection.ts # WebSocket 连接状态
│   ├── lib/                 # 工具库
│   │   ├── ws-client.ts     # WebSocket 客户端（自动重连、认证）
│   │   ├── auth.ts          # Auth token 工具
│   │   ├── session-cache.ts # 会话消息缓存
│   │   ├── cron-cache.ts    # Cron 缓存
│   │   ├── streamdown-plugins.ts # Streamdown 插件
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
│       ├── model-capabilities.test.ts
│       ├── settings-manager.test.ts
│       ├── session-store.test.ts
│       ├── skills-registry.test.ts
│       ├── user-profile.test.ts
│       ├── content-blocks.test.ts
│       ├── semaphore.test.ts
│       ├── batch-engine.test.ts
│       ├── batch-store.test.ts
│       ├── batch-utils.test.ts
│       ├── cron-scheduler.test.ts
│       ├── cron-task-store.test.ts
│       ├── stardom/          # Stardom 桥接层测试
│       │   ├── stardom-client.test.ts
│       │   ├── stardom-mcp-ranking.test.ts
│       │   ├── stardom-session.test.ts
│       │   ├── stardom-store.test.ts
│       │   └── bridge-integration.test.ts
│       ├── capabilities/    # Capabilities 测试
│       │   ├── registry.test.ts
│       │   ├── registry-search.test.ts
│       │   ├── project-scanner.test.ts
│       │   ├── scanner-prompts.test.ts
│       │   ├── scanner-v2-integration.test.ts
│       │   ├── experience-learning.test.ts
│       │   ├── frontmatter-utils.test.ts
│       │   └── usage-tracking.test.ts
│       ├── chatbot/         # Chatbot 测试
│       │   ├── chatbot-session-manager.test.ts
│       │   ├── chatbot-store.test.ts
│       │   ├── chat-command-parser.test.ts
│       │   ├── wecom-bot-connection.test.ts
│       │   ├── fecom-media.test.ts
│       │   ├── feishu-bot-connection.test.ts
│       │   └── weixin-bot-connection.test.ts
│       ├── init/            # 初始化流程测试
│       │   ├── init-manager.test.ts
│       │   ├── workspace-scanner.test.ts
│       │   ├── skill-injector.test.ts
│       │   └── capability-matcher.test.ts
│       └── web-access/      # Web Access 测试
│           ├── cdp-engine.test.ts
│           ├── mcp-server.test.ts
│           ├── url-experience-store.test.ts
│           └── web-access-service.test.ts
├── scripts/                 # 工具脚本
│   ├── init-skills.ts       # Skills 初始化
│   ├── init-system.ts       # 系统初始化
│   └── patch-sdk.mjs        # Claude Agent SDK postinstall 补丁
├── stardom/                 # 星域服务器（独立包边界）
│   ├── package.json         # 独立依赖
│   ├── tsconfig.json        # 独立编译配置
│   └── src/
│       ├── index.ts         # 星域服务器入口（HTTP API + WebSocket）
│       ├── message-router.ts # WS 消息分发
│       ├── agent-store.ts   # Agent 注册/心跳
│       ├── task-engine.ts   # 任务路由/排队
│       ├── task-store.ts    # 任务持久化
│       ├── capability-store.ts  # 通用能力包存储（CRUD + 搜索）
│       ├── reputation.ts    # 声望计算
│       ├── world-state.ts   # 世界状态
│       ├── protocol.ts      # 消息协议定义
│       └── utils/           # 星域工具函数
├── shared/                  # 共享类型（Sman + Stardom 共用）
│   └── stardom-types.ts     # 星域消息协议类型
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
├── sman.db              # SQLite 数据库（会话、消息、Cron、Batch、Chatbot 状态、知识提取进度）
├── claude-config/       # 隔离的 Claude CLI 配置目录（防止全局 settings.json 干扰）
├── skills/              # 全局 Skills（预制通用技能）
└── logs/                # 日志文件
```

## 项目工作区目录 (`{workspace}/.sman/`)

```
{workspace}/.sman/
├── INIT.md              # 初始化结果（项目类型、tech stack、注入的 skills）
├── knowledge/           # 团队知识（每人独立文件，git push 共享）
│   ├── business-{username}.md    # 业务知识（需求、规则、流程）
│   ├── conventions-{username}.md # 开发规范（命名、架构决策）
│   └── technical-{username}.md   # 技术知识（API、schema、集成）
└── paths/               # 地球路径（文件存储，非 SQLite）
    └── {pathId}/
        ├── path.md      # 路径定义（frontmatter: name, workspace, steps, status, cron_expression）
        ├── runs/        # 执行记录（JSON: status, stepResults, startedAt, finishedAt）
        ├── reports/     # 执行报告（Markdown: 每步骤输入+结果）
        └── references/  # 复用资源库
            ├── run.md   # 复用指南（每次执行完自动维护）
            ├── *.sh     # 执行生成的脚本
            └── *.md     # 执行生成的知识文档
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

## Capabilities 系统

按需发现和激活的能力包，通过 Gateway MCP Server 注入每个会话：

| 模块 | 说明 |
|------|------|
| `registry.ts` | 能力注册表（搜索、匹配、加载） |
| `project-scanner.ts` | 扫描项目发现可用能力 |
| `experience-learner.ts` | 从对话中提取经验知识 |
| `office-skills-runner.ts` | Office 文档技能执行器 |
| `frontend-slides-runner.ts` | 前端幻灯片生成 |
| `generic-instruction-runner.ts` | 通用指令执行器 |

## 会话初始化流程

新建会话时自动执行（`server/init/`）：

1. **workspace-scanner** → 扫描项目结构
2. **skill-injector** → 注入匹配的 Skills
3. **capability-matcher** → 匹配可用的 Capabilities
4. **claude-init-runner** → 执行 Claude 初始化对话

## 关键文件速查

| 文件 | 说明 |
|------|------|
| `server/index.ts` | 后端入口，所有 WebSocket handler 注册、服务初始化 |
| `server/claude-session.ts` | Claude Agent SDK V2 会话管理（创建、resume、idle 清理、流式推送、消息排队） |
| `server/capabilities/gateway-mcp-server.ts` | Capability Gateway MCP（注入每个会话） |
| `server/capabilities/registry.ts` | Capabilities 注册表 |
| `server/init/init-manager.ts` | 会话初始化流程编排 |
| `server/user-profile.ts` | 用户画像（注入到 system prompt） |
| `server/knowledge-extractor.ts` | 知识提取器（从对话提取业务/规范/技术知识到 `.sman/knowledge/`） |
| `server/knowledge-extractor-store.ts` | 知识提取进度 SQLite 存储 |
| `server/model-capabilities.ts` | 模型能力检测 |
| `server/chatbot/chatbot-session-manager.ts` | Chatbot 消息路由（命令解析 → Claude 查询） |
| `server/chatbot/wecom-bot-connection.ts` | 企业微信 Bot WebSocket（心跳、重连、流式消息节流） |
| `server/chatbot/feishu-bot-connection.ts` | 飞书 Bot 连接 |
| `server/chatbot/weixin-bot-connection.ts` | 微信 Bot 连接 |
| `server/web-access/cdp-engine.ts` | Chrome DevTools Protocol 引擎 |
| `server/web-access/mcp-server.ts` | Web Access MCP Server（9 个工具） |
| `server/mcp-config.ts` | Web Search MCP 自动配置 |
| `server/session-store.ts` | SQLite 会话和消息存储 |
| `electron/main.ts` | Electron 主进程（窗口、后端启动、GPU 兼容） |
| `src/features/chat/` | 聊天功能组件 |
| `src/features/stardom/StardomPage.tsx` | 星域页面（仪表盘 + 像素世界双视图） |
| `src/features/stardom/world/` | 像素世界渲染引擎（Canvas、交互、精灵） |
| `src/features/settings/` | 设置页面组件 |
| `src/stores/chat.ts` | 聊天状态管理（流式渲染、消息排队） |
| `src/stores/stardom.ts` | 星域状态管理 |
| `src/components/SessionTree.tsx` | 会话树 + 目录选择器 |
| `src/lib/session-cache.ts` | 会话消息缓存层 |
| `server/stardom/stardom-bridge.ts` | 星域桥接层（连接管理、经验提取、磨合机制） |
| `server/stardom/stardom-store.ts` | 星域本地存储（SQLite） |
| `server/stardom/stardom-mcp.ts` | 星域 MCP 工具（stardom_search, stardom_collaborate） |
| `stardom/src/capability-store.ts` | 星域通用能力包存储 |
| `server/smart-path-store.ts` | 地球路径存储（文件系统：路径定义 MD + runs JSON + reports MD） |
| `server/smart-path-engine.ts` | 地球路径执行引擎（逐步骤执行，纯内存临时会话） |
| `server/smart-path-scheduler.ts` | 地球路径定时调度（cron 表达式驱动） |
| `src/features/smart-paths/index.tsx` | 地球路径页面组件 |
| `src/stores/smart-path.ts` | 地球路径 Zustand 状态管理 |

## WebSocket API

### 会话管理

| 类型 | 说明 |
|------|------|
| `session.create` | 创建会话，参数: `{ workspace: string }` |
| `session.list` | 列出所有会话 |
| `session.delete` | 删除会话，参数: `{ sessionId: string }` |
| `session.history` | 获取会话历史 |
| `session.updateLabel` | 更新会话标签，参数: `{ sessionId, label }` |

### 聊天

| 类型 | 方向 | 说明 |
|------|------|------|
| `chat.send` | 客户端→服务端 | 发送消息，参数: `{ sessionId, content }` |
| `chat.abort` | 客户端→服务端 | 中止当前查询 |
| `chat.start` | 服务端→客户端 | 开始流式响应 |
| `chat.delta` | 服务端→客户端 | 流式文本/thinking/tool_use 增量 |
| `chat.tool_start` | 服务端→客户端 | 工具调用开始 |
| `chat.tool_delta` | 服务端→客户端 | 工具调用参数增量 |
| `chat.tool_end` | 服务端→客户端 | 工具调用结束 |
| `chat.done` | 服务端→客户端 | 响应完成（含 cost, usage） |
| `chat.aborted` | 服务端→客户端 | 响应被中止（含已流式内容） |
| `chat.error` | 服务端→客户端 | 错误 |
| `chat.ask_user` | 服务端→客户端 | Claude 向用户提问 |
| `chat.answer_question` | 客户端→服务端 | 回答 Claude 提问 |

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

### 星域

| 类型 | 说明 |
|------|------|
| `stardom.status` | 连接状态（connected/disconnected） |
| `stardom.task.list` | 获取协作任务列表 |
| `stardom.agent.list` | 获取在线 Agent 列表 |
| `stardom.leaderboard` | 获取声望排行榜 |
| `stardom.task.accept/reject` | 接受/拒绝协作任务 |
| `stardom.config.update` | 更新协作模式（auto/notify/manual） |
| `stardom.world.move` | 发送 Agent 世界坐标更新 |
| `stardom.notify` | 收到协作请求通知 |
| `stardom.task.chat.delta` | 协作对话增量消息 |

### 地球路径

| 类型 | 说明 |
|------|------|
| `smartpath.list` | 列出指定 workspace 下的所有路径，参数: `{ workspaces: string[] }` |
| `smartpath.create` | 创建路径，参数: `{ name, workspace, steps }` |
| `smartpath.update` | 更新路径（名称变更时自动迁移文件和 runs），参数: `{ pathId, workspace, ...updates }` |
| `smartpath.delete` | 删除路径（含 runs/reports 子目录），参数: `{ pathId, workspace }` |
| `smartpath.run` | 执行路径（逐步骤，每步纯内存临时会话，流式推送进度） |
| `smartpath.runs` | 获取路径的执行记录和报告列表，参数: `{ pathId, workspace }` |
| `smartpath.report` | 获取执行报告内容，参数: `{ pathId, workspace, fileName }` |
| `smartpath.references` | 获取路径的复用资源列表，参数: `{ pathId, workspace }` |
| `smartpath.reference.read` | 获取复用资源文件内容，参数: `{ pathId, workspace, fileName }` |
| `smartpath.generateStep` | AI 生成步骤方案或执行单个步骤，参数: `{ userInput, workspace, previousSteps, execute?, pathId?, stepIndex? }` |

## Chatbot 命令（企业微信/飞书/微信）

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
7. **环境隔离**: `getCleanEnv()` 清除 `ANTHROPIC_*/OPENAI_*/CLAUDE_*` 环境变量，使用隔离的 `CLAUDE_CONFIG_DIR` 防止全局配置干扰
8. **消息排队**: SDK 不支持打断正在执行的 turn，后端通过 `await streamDone` 排队，前端等待 `chat.done` 后再发新消息

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
- **AI**: Claude Agent SDK (`@anthropic-ai/claude-agent-sdk` v0.2 + `@anthropic-ai/claude-code` v2.1)
- **渲染**: Shiki + Streamdown
- **Schema 校验**: Zod

## 注意事项

1. **目录选择**: Electron 使用原生对话框，Web 模式使用 API 浏览
2. **Skills 加载**: 同时加载全局和项目特定 Skills + Plugins
3. **会话分组**: 按目录名分组显示，目录名即为显示名称
4. **无预配置**: 用户无需预先配置业务系统，直接选择目录即可
5. **离线部署**: 设置页配置内网模型 URL + API Key + Model 即可使用，支持 `ANTHROPIC_BASE_URL`
6. **Chatbot 多轮对话**: 企业微信/飞书/微信 Bot 支持多轮对话，Claude 可在回复中询问用户信息，用户补充后继续
7. **企业微信流式推送**: 通过 `aibot_respond_msg` + `msgtype: 'stream'` 实现流式回复，2 秒节流
8. **Web Access 浏览器**: 通过 CDP 协议控制 Chrome，自动发现书签/历史中的企业站点
9. **V2 Session 持久化**: SDK session_id 持久化到 SQLite，支持进程重启后恢复会话
10. **星域三层架构**: 前端 (`src/features/stardom/`) → 桥接层 (`server/stardom/`) → 星域服务器 (`stardom/src/`)
11. **星域全屏模式**: `/stardom` 路由隐藏侧边栏，世界视图全屏 Canvas + 浮动控件
12. **Agent 进化机制**: 对话经验自动提取 → learned_routes.experience 字段；磨合记录 → pair_history 表；搜索排序优先级：老搭档 > 历史协作 > 有经验 > 远程
13. **Capabilities 系统**: Gateway MCP 注入每个会话，按需发现和激活能力包（Office 技能、PPT 生成等）
14. **会话初始化**: 新建会话自动扫描项目 → 注入 Skills → 匹配 Capabilities → 执行初始化对话
15. **消息隔离**: 多会话并行不串 — 后端 Map 全部以 sessionId 为 key，前端 handler 过滤 sessionId，streamingBlocksMap 按 sessionId 独立存储
16. **知识提取**: 每 10 分钟空闲时从对话提取业务知识/开发规范/技术知识 → 存入 `{workspace}/.sman/knowledge/{category}-{username}.md`（每人独立文件，push 到 git 共享）→ skill-auto-updater 聚合所有用户文件生成 `knowledge-business/conventions/technical` 三个 skill。用 hash 标记去重，支持增量提取（记录 `last_extracted_message_id`）
17. **地球路径存储**: 文件存储（非 SQLite），路径定义存在 `{workspace}/.sman/paths/{pathId}/path.md`（frontmatter 格式，只含设计时信息不含 executionResult），执行记录在 `{pathId}/runs/{runId}.json`，报告在 `{pathId}/reports/report-{timestamp}.md`，复用资源在 `{pathId}/references/`。路径 ID 格式：`{项目名}-{路径名}-{8位随机字符}`
18. **地球路径执行**: 逐步骤执行，每步创建纯内存临时会话（不写 SQLite、不污染主会话列表），前一步结果作为下一步输入上下文。支持 cron 表达式定时自动执行。执行时自动注入 `references/` 中的复用资源，步骤输出中 `[REFERENCE:filename.ext]` 标注的文件会被自动保存到 references。每次执行完自动维护 `references/run.md` 复用指南
19. **UI 响应性优先（动画先表演，后台再做事）**: 所有用户交互（打字、点击、回车）必须立即得到 UI 反馈，不允许任何同步阻塞导致掉帧或卡顿。具体规则：
    - **输入框打字零联动**: `handleInputChange` 里不做任何资源操作（不发 WS、不调 IPC、不触发 store 副作用）。`preheatSession` 和 `gitBranchRefresh` 等操作推迟到用户点击发送时异步执行
    - **发送不卡 UI**: `handleSend` 里先同步清空输入框（`setInput('')`），再用 `setTimeout(0)` 把 `onSend` 推到下一帧。store 的 `sendMessage` 在 `set({ sending: true })` 后也必须 `await setTimeout(0)` 让 React 先渲染用户消息和动画，再注册 stream handlers 和发 WS
    - **后端立即确认**: 后端 `sendMessage` 收到请求后立刻发 `chat.start`，不要等 preheat 或 `getOrCreateV2Session` 完成后再发
    - **避免不必要的 re-render**: ChatInput 不要订阅 `messages` 数组（流式输出时每 50ms 变化），改为在 keyDown handler 里 `useChatStore.getState()` 按需读取。`useEffect` 必须有正确的 deps 数组，用 ref 追踪最新值避免每次渲染都执行
    - **思考块折叠时展示进度**: ThinkingBlock 折叠状态必须显示内容摘要（最后有意义的行），200ms 轮询更新，不能看起来像卡死
