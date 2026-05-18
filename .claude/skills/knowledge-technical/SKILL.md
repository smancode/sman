---
name: knowledge-technical
description: "技术知识：API 细节、数据库 schema、三方集成、基础设施、算法。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "1ddac60bf3f5dbec4ced87ea1a0b7b680267f41c"
  scannedAt: "2026-05-19T00:00:00.000Z"
  branch: "master"
---

# Technical Knowledge

> 贡献者: nasakim | 验证时间: 2026-05-19

## 核心架构
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L60-120
- **后端**：Node.js + WebSocket/HTTP + SQLite + Claude Agent SDK v0.2.110
- **前端**：React 19 + Zustand，**桌面端**：Electron

## Claude Agent SDK 集成
> by nasakim | 验证: 2026-05
✅ [已验证] server/claude-session.ts
- `unstable_v2_createSession`，session_id 持久化，`await streamDone` 排队，30 分钟 idle 清理

## Git 异步化重构 ⚠️ NEW
> by nasakim | 验证: 2026-05-19
✅ [已验证] server/git-handler.ts
- **execSync → execFile**：`promisify(execFile)` 替代同步调用，所有 handler 改为 async
- **并行优化**：`handleGitStatus()` 中 `rev-parse` 和 `status --porcelain` 并行执行
- **目录展开保护**：`SKIP_DIRS`（node_modules 等）、`MAX_EXPAND_DEPTH`（3 层）、`MAX_EXPAND_FILES`（500 个）

## Smart Path Skills 注入与 Reference 白名单 ⚠️ NEW
> by nasakim | 验证: 2026-05-19
✅ [已验证] server/smart-path-engine.ts, server/index.ts
- **Workspace Skills**：步骤配置 `skills?: string[]`，从 `{workspace}/.claude/skills/{skillId}/SKILL.md` 读取并注入到 `[可使用的 Skills]` 章节
- **脚本文件白名单**：`SCRIPT_EXTENSIONS` 定义 19 种扩展名（.py/.sh/.js/.ts/.bat/.sql 等）
- **Reference 安全**：`[REFERENCE:filename.ext]` 只保存脚本文件，禁止保存 .json/.csv/.txt 等数据文件

## Hub/WebAccess/Chatbot 核心机制 ⚠️ NEW
> by nasakim | 验证: 2026-05-17
✅ [已验证] server/hub/*.ts, server/web-access/cdp-engine.ts, server/chatbot/*.ts
- **Hub**：AES-256-GCM 加密，15 分钟心跳，任务分发评估
- **WebAccess**：AX 树缓存，Ref 解析（nodeId → DOM objectId），Ref 优先 CSS selector 兜底
- **Chatbot**：full/query/collect 三模式，Session ID 含 botProfileId，全局最多 2 个 bot 并发

## 数据库与 WebSocket
> by nasakim | 验证: 2026-05
✅ [已验证] server/index.ts, 各 store 文件
- **SQLite**：~/.sman/sman.db，better-sqlite3（WAL 模式）
- **WebSocket**：端口 5880，`clientToSessions`/`sessionToClients` 双向映射

## macOS Auto-Updater 签名绕过 ⚠️ NEW
> by nasakim | 验证: 2026-05-19
✅ [已验证] electron/main.ts
- Squirrel.Mac 验证签名导致 unsigned 应用更新失败，手动 fetch zip，unzip + codesign + rm + cp 替换

## Group 组合功能前端组件 ⚠️ NEW (未提交)
> by nasakim | 验证: 2026-05-19
⚠️ [未提交代码] src/components/{CreateGroupDialog,CreateTaskDialog,GroupItem}.tsx, src/features/group-tasks/
- **CreateGroupDialog**：新建组合对话框（名称 + workspace 多选）
- **CreateTaskDialog**：新建任务对话框（名称、描述、细节、交付标准）
- **GroupItem**：组合列表项（悬浮显示操作按钮）
- **GroupTaskPage**：任务详情和分析页面（workspace 切换器）
- **SessionTree 集成**：并排显示"新建会话"和"新建组合"按钮

## Group 组合功能数据结构 ⚠️ NEW (未提交)
> by nasakim | 验证: 2026-05-19
⚠️ [未提交代码] server/group-store.ts, src/schemas/group.ts, src/stores/group.ts
- **数据库表**：groups, group_tasks, workspace_tasks 三张表
- **Schema 定义**：Group（id, name, workspaceIds, status）, GroupTask（含 acceptanceCriteria）, WorkspaceTask（sessionId 关联）
- **WebSocket API**：group.* 和 group-task.* 消息类型
- **状态管理**：Zustand store with WebSocket sync pattern

## 参考文档
- Hub：`references/hub-integration.md`，WebAccess：`references/web-access-ref-based-interaction.md`，Chatbot：`references/chatbot-multi-mode.md`
