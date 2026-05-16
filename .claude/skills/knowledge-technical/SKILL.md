---
name: knowledge-technical
description: "技术知识：API 细节、数据库 schema、三方集成、基础设施、算法。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "57e98c308c1cd0fc5693b3ebab5282836e02a241"
  scannedAt: "2026-05-17T00:00:00.000Z"
  branch: "master"
---

# Technical Knowledge

> 贡献者: nasakim | 验证时间: 2026-05-17

## 项目架构与目录结构
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L60-120
- **后端（server/）**：Node.js，WebSocket + HTTP 双协议入口，SQLite 存储，Claude Agent SDK 会话管理
- **前端（src/）**：React + Zustand 状态管理，按功能模块划分
- **桌面端（electron/）**：Electron 封装
- **Stardom 服务器（stardom/）**：独立包边界

## Hub 多 Agent 协作平台 ⚠️ NEW
> by nasakim | 验证: 2026-05-17
✅ [已验证] server/hub/*.ts
- **服务器发现**: 自动探测配置 URL / 环境变量 / 默认服务器，健康检查 + 缓存
- **加密通信**: AES-256-GCM，PSK 密钥分级加载（环境变量 > 用户配置 > 打包密钥 > 默认）
- **心跳协议**: 15 分钟间隔，上报版本、活跃会话、工作区列表
- **广播同步**: 客户端拉取服务端推送的消息和配置
- **任务分发**: 评估任务获取 → 执行 → 上报结果
- **本地触发**: 通过 `/api/mcp/tools/trigger` 触发本地技能

## Web Access Ref-Based 交互 ⚠️ NEW
> by nasakim | 验证: 2026-05-17
✅ [已验证] server/web-access/cdp-engine.ts
- **AX 树缓存**: 每次 snapshot 缓存可访问性节点，按 tab 隔离
- **Ref 解析**: AX nodeId → backendDOMNodeId → DOM objectId（Runtime.callFunctionOn）
- **优先级**: Ref 优先，CSS selector 兜底
- **交互方法**: click/fill 通过 ref 定位元素，自动滚动 + 事件触发
- **稳定性**: DOM 变化后 ref 失效，需重新 snapshot

## Chatbot 多模式架构 ⚠️ NEW
> by nasakim | 验证: 2026-05-17
✅ [已验证] server/chatbot/chatbot-session-manager.ts
- **三种模式**: full（全功能）、query（只读答疑）、collect（反馈收集）
- **会话隔离**: Session ID 包含 botProfileId，userKey 格式 `{platform}:{botId}:{userId}`
- **并发控制**: 全局最多 2 个 bot 请求同时处理，队列 + 位置反馈
- **工作区绑定**: query/collect 模式固定工作区，full 模式用户可切换
- **命令限制**: 各模式支持不同命令子集（cd/workspaces 等）
- **配置迁移**: 自动从单 bot 配置迁移到多 bot 数组格式

## Claude Agent SDK 集成
> by nasakim | 验证: 2026-05
✅ [已验证] server/claude-session.ts
- **SDK 版本**：@anthropic-ai/claude-agent-sdk v0.2
- **会话持久化**：SDK session_id 存储到 SQLite
- **消息排队**：await streamDone 排队机制
- **Idle 清理**：30 分钟无活动自动清理

## 数据库设计（SQLite）
> by nasakim | 验证: 2026-05
✅ [已验证] 各 store 文件
- **数据库文件**：~/.sman/sman.db
- **连接方式**：better-sqlite3（预编译二进制）
- **WAL 模式**：启用 Write-Ahead Logging

### 主要表结构
- sessions, messages, cron_tasks, batch_tasks, batch_runs
- chatbot_sessions, chatbot_users
- stardom_tasks, stardom_learned_routes, stardom_pair_history
- knowledge_extraction_progress

## WebSocket 消息协议
> by nasakim | 验证: 2026-05
✅ [已验证] server/index.ts
- **端口**：5880
- **认证**：首条消息 auth.verify + Bearer token
- **消息格式**：JSON with type field
- **消息隔离**：会话特定消息不广播

## 会话初始化流程
> by nasakim | 验证: 2026-05
✅ [已验证] server/init/init-manager.ts
1. workspace-scanner → 扫描项目
2. skill-injector → 注入 Skills
3. capability-matcher → 匹配 Capabilities
4. claude-init-runner → 执行初始化对话

## 知识提取机制
> by nasakim | 验证: 2026-05
✅ [已验证] server/knowledge-extractor.ts
- **触发**：每 10 分钟空闲时
- **存储**：{workspace}/.sman/knowledge/{category}-{username}.md
- **共享**：git push
- **聚合**：skill-auto-updater 每 2 小时
- **去重**：hash 标记 + 增量提取

## 地球路径存储
> by nasakim | 验证: 2026-05
✅ [已验证] server/smart-path-store.ts
- **存储方式**：文件系统（非 SQLite）
- **路径定义**：{workspace}/.sman/paths/{pathId}/path.md
- **执行记录**：{pathId}/runs/{runId}.json
- **执行机制**：逐步骤执行，每步纯内存临时会话

## 星域三层架构
> by nasakim | 验证: 2026-05
✅ [已验证] server/stardom/, src/features/stardom/, stardom/src/
- **前端层**：React UI
- **桥接层**：连接管理、经验提取
- **星域服务器**：独立包

### Agent 进化机制
- 对话经验 → learned_routes.experience
- 磨合记录 → pair_history
- 搜索排序：老搭档 > 历史协作 > 有经验 > 远程

## WebSocket 客户端↔会话双向映射机制
> by nasakim | 验证: 2026-05
✅ [已验证] server/index.ts:L146-214
- `server/index.ts` 维护两个 Map：`clientToSessions`(WebSocket→Set<sessionId>) 和 `sessionToClients`(sessionId→Set<WebSocket>)
- 核心函数：subscribeClientToSession / unsubscribeClientFromSession / getSessionClients / sendToSessionClients
- 客户端断开时必须双向清理映射，防止内存泄漏和幽灵订阅

## 代码查看器与 Git 面板的 WebSocket API 设计
> by nasakim | 验证: 2026-05
✅ [已验证] server/code-viewer-handler.ts, server/git-handler.ts
- Handler 文件分离：`server/code-viewer-handler.ts` 和 `server/git-handler.ts`
- `code.*` 命名空间（5 个）：listDir, readFile, searchSymbols, saveFile, searchFiles
- `git.*` 命名空间（13 个）：status, diff, commit, push, log, checkout, fetch 等
- SDK 版本：`0.2.110` / `2.1.110`；代码编辑器使用 CodeMirror 6

## Meta Skills 注入机制
> by nasakim | 验证: 2026-05
✅ [已验证] server/init/init-manager.ts:L239,266
- `META_SKILLS` 数组管理需注入的元技能
- 从 `server/init/templates/{skill-name}/` 复制到 `{workspace}/.claude/skills/{skill-name}/`
- 注入策略：只注入一次，已存在则跳过

## User Prompt 结构中的任务前提区块位置
> by nasakim | 验证: 2026-05
✅ [已验证] server/claude-session.ts:L346
- 文件：`server/claude-session.ts`，任务前提章节位于"交付要求"与"需求澄清"之间
- 检查链条：用户发需求 → AI 检查任务前提四条件 → 不满足则立即问 → 满足则执行 → 执行中走偏触发需求澄清

## MCP HTTP API 端点与实现
> by nasakim | 验证: 2026-05
✅ [已验证] server/index.ts:L872-1035
- `POST /api/mcp/tools/list`：从数据库获取有会话的活跃 workspaces，返回 skills + paths 列表
- `POST /api/mcp/tools/invoke`：参数含 workspace、toolType(skill/path)、toolId，path 类型走 smartPathEngine.run()，skill 类型创建临时会话执行
- `POST /api/mcp/tools/trigger`：异步触发本地技能，立即返回
- 活跃 workspace 查询方法 `getActiveWorkspaces()` 在 `server/session-store.ts`

## Claude SDK 深度绑定清单
> by nasakim | 验证: 2026-05
✅ [已验证] server/claude-session.ts
- **Session 管理**：依赖 `unstable_v2_createSession`、`SDKSession`、`SDKMessage` 类型
- **MCP 框架**：6 个 MCP Server 依赖 `McpSdkServerConfigWithInstance`、`createSdkMcpServer`、`tool`
- **流式输出与工具调用**：`await streamDone` 消息队列、SDK tool system
- **多模型支持**：已可通过环境变量 `ANTHROPIC_BASE_URL` 实现，无需改动代码

## LLM prompt 语言硬编码问题与国际化改造点
> by nasakim | 验证: 2026-05
✅ [已验证] server/claude-session.ts:L323-325
- `buildSmanContext()` 硬编码了"始终中文回复"
- **问题**：语言切换后已有会话仍强制中文，新建会话也继承硬编码
- **改造方案**：从 config 读取 language 字段，动态生成语言指令注入 prompt（已部分实现，L324 有读取逻辑）

## 国际化涉及的关键前端文件清单
> by nasakim | 验证: 2026-05
✅ [已验证] src/locales/, src/features/settings/
- **翻译文件**：`zh-CN.json`、`en-US.json`（150+ 翻译键，覆盖设置、Git、CodeViewer）
- **已改造组件**：LLMSettings、WebSearchSettings、ChatbotSettings、BackendSettings、App、Sidebar、ChatInput、SessionTree
- **易遗漏点**：常量文件中的选项标签（如 `WEB_SEARCH_PROVIDER_OPTIONS`）、Git 页面、CodeViewer 页面

## Smart Path path.md 文件格式与类型系统
> by nasakim | 验证: 2026-05
✅ [已验证] server/smart-path-store.ts, src/types/settings.ts:L205-216
- **YAML front matter**：name、description、workspace、created_at、updated_at、status、cron_expression、steps
- **body 区域**：markdown 内容（标题 + 描述文本）
- **TypeScript 类型**：`SmartPath` 接口含 `description?` 可选字段
- **前端组件**：`src/features/smart-paths/index.tsx` 负责新建/编辑/详情页的 description 展示与编辑

## 技术债务：skill-auto-updater 迁移到 .sman 体系
> by nasakim | 验证: 2026-05
⚠️ [待实施] .sman/knowledge/technical-nasakim.md
- **现状**：原 skill-auto-updater 在 `.claude` 目录下工作（往 `.claude/skills/` 写入）
- **目标**：迁移到 `.sman` 体系，使 skill 自动更新写入 `.sman/skills/`
- **意义**：这是自我进化能力的核心组件，迁移时不能影响现有 skill-injector 等模块的正常运行
- **涉及文件**：`skill-injector.ts:18`、`cron-scheduler.ts:189`、`index.ts:1223`、`capability-matcher.ts:327`
- **兼容策略**：新项目初始化时 skills 放 `.sman/skills/`，旧项目 `.claude/skills/` 保留不动（向后兼容）

## 时区陷阱修复：Git 统计脚本
> by nasakim | 验证: 2026-05
✅ [已验证] tmp/daily-code-stats.sh
- **陷阱**：使用 `--since="today"` 导致统计结果为空（Git 时区兼容问题）
- **修复**：改为 `--since="$TODAY 00:00:00" --until="$TODAY 23:59:59"` 显式指定日期范围
- **逻辑改进**：所有 commit 的 diff 统一传给 awk 处理，而非逐 commit 处理导致重复计算

## 详细参考文档
- **Hub 集成**: `references/hub-integration.md`
- **Web Access Ref**: `references/web-access-ref-based-interaction.md`
- **Chatbot 多模式**: `references/chatbot-multi-mode.md`
