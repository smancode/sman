---
name: knowledge-technical
description: "技术知识：API 细节、数据库 schema、三方集成、基础设施、算法。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "4db35f24f89dda0c11aa6aad83ba7bb7f8df368a"
  scannedAt: "2026-05-06T00:00:00.000Z"
  branch: "master"
---

# Technical Knowledge

> 贡献者: nasakim | 验证时间: 2026-05-06

## 项目架构与目录结构
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L60-120
- **后端（server/）**：Node.js，WebSocket + HTTP 双协议入口，SQLite 存储，Claude Agent SDK 会话管理
- **前端（src/）**：React + Zustand 状态管理，按功能模块划分
- **桌面端（electron/）**：Electron 封装
- **Stardom 服务器（stardom/）**：独立包边界

## 扩展能力体系
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L210-220
- **MCP Servers**：Web 搜索、浏览器操作（CDP 协议）
- **Capabilities**：按需激活能力包，通过 Gateway MCP 注入每个会话
- **Plugins**：Superpowers、Gstack 等插件扩展

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
- chatbot_sessions, chatbot_messages
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
✅ [已验证] server/code-viewer-handler.ts, server/git-handler.ts, src/features/code-viewer/CodePanel.tsx:L4,8
- Handler 文件分离：`server/code-viewer-handler.ts` 和 `server/git-handler.ts`，各自处理对应域的 WebSocket 消息
- `code.*` 命名空间（5 个）：listDir, readFile, searchSymbols, saveFile, searchFiles
- `git.*` 命名空间（13 个）：status, diff, commit, push, log, checkout, fetch 等完整 Git 操作
- SDK 版本：`0.2.110` / `2.1.110`；代码编辑器使用 CodeMirror 6

## Meta Skills 注入机制
> by nasakim | 验证: 2026-05
✅ [已验证] server/init/init-manager.ts:L239,266
- `server/init/init-manager.ts` 中 `META_SKILLS` 数组管理需注入的元技能（如 `clarify-requirements`、`skill-auto-updater`）
- `injectMetaSkills(workspace)` 从 `server/init/templates/{skill-name}/` 复制到 `{workspace}/.claude/skills/{skill-name}/`
- 注入策略：只注入一次，已存在则跳过（不覆盖用户自定义版本），确保新建会话自动具备对应能力

## User Prompt 结构中的任务前提区块位置
> by nasakim | 验证: 2026-05
✅ [已验证] server/claude-session.ts:L346
- 文件：`server/claude-session.ts`，任务前提章节位于"交付要求"与"需求澄清"之间
- 检查链条：用户发需求 → AI 检查任务前提四条件 → 不满足则立即问 → 满足则执行 → 执行中走偏触发需求澄清
