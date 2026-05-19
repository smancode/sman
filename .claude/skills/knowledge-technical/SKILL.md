---
name: knowledge-technical
description: "技术知识：API 细节、数据库 schema、三方集成、基础设施、算法。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "c63e3fcf76ba9e8b362d9d73ebccab934d1d998d"
  scannedAt: "2026-05-20T00:00:00.000Z"
  branch: "master"
---

# Technical Knowledge

> 贡献者: nasakim | 验证时间: 2026-05-20

## 核心架构
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L60-120
- **后端**：Node.js + WebSocket/HTTP + SQLite + Claude Agent SDK v0.2.110
- **前端**：React 19 + Zustand，**桌面端**：Electron

## Claude Agent SDK 集成
> by nasakim | 验证: 2026-05
✅ [已验证] server/claude-session.ts
- `unstable_v2_createSession`，session_id 持久化，`await streamDone` 排队，30 分钟 idle 清理

## Group 组合功能数据结构 ⚠️ NEW
> by nasakim | 验证: 2026-05-20
✅ [已验证] server/group-store.ts (308行), src/schemas/group.ts, src/stores/group.ts
- **数据库表**：groups, group_tasks, group_subtasks 三张表（外键约束 + 级联删除）
- **Schema 定义**：Group（id, name, workspaceIds, status）, GroupTask（含 autoDispatch）, GroupSubtask（sessionId 关联）
- **WebSocket API**：group.* 和 group-task.* 消息类型（create/list/delete/dispatch）
- **状态管理**：Zustand store with WebSocket sync pattern（单次监听器注册去重）
- **JSON 字段陷阱**：workspaceIds 存储为 TEXT（JSON 字符串），返回时需 JSON.parse

## Git 异步化重构 ⚠️ UPDATED
> by nasakim | 验证: 2026-05-19
✅ [已验证] server/git-handler.ts (66行变更)
- **execSync → execFile**：`promisify(execFile)` 替代同步调用，args 从字符串改为数组
- **并行优化**：`handleGitStatus()` 中 `rev-parse` 和 `status --porcelain` 并发执行
- **目录展开保护**：`SKIP_DIRS`（node_modules 等）、`MAX_EXPAND_DEPTH`（3 层）、`MAX_EXPAND_FILES`（500 个）

## Smart Path 步骤指南生成系统 ⚠️ NEW
> by nasakim | 验证: 2026-05-20
✅ [已验证] server/smart-path-engine.ts (84行变更), server/smart-path-store.ts
- **guideChat()**：步骤执行后的多轮对话系统，生成操作指南（初始确认 + 用户调整）
- **指南存储**：`references/guide{n}.md`（getGuide/saveGuideFile API）
- **步骤集成**：buildStepPrompt 注入 guideContent，runWithResults 加载已有指南
- **前端组件**：GuideChatPanel（自动展开、流式输出、保存按钮）
- **输出限制**：prompt 明确禁止输出大段代码，代码块最多 10 行

## Chatbot SDK 会话 ID 清除机制 ⚠️ NEW
> by nasakim | 验证: 2026-05-20
✅ [已验证] server/chatbot/chatbot-session-manager.ts (107行变更), server/claude-session.ts
- **clearSdkSessionId()**：清除 SDK session ID（内存 + 数据库），防止下次复用旧上下文
- **调用链**：abort() → closeV2Session() → clearSdkSessionId() → setIdleReset()
- **触发场景**：空闲超时（15分钟）、用户手动重置、会话切换
- **解决痛点**：V2 SDK 会话复用导致上下文残留、旧 session 影响新对话

## Smart Path Skills 注入与 Reference 白名单 ⚠️ UPDATED
> by nasakim | 验证: 2026-05-19
✅ [已验证] server/smart-path-engine.ts, server/index.ts
- **Workspace Skills**：步骤配置 `skills?: string[]`，从 `{workspace}/.claude/skills/{skillId}/SKILL.md` 读取并注入到 `[可使用的 Skills]` 章节
- **脚本文件白名单**：`SCRIPT_EXTENSIONS` 定义 19 种扩展名（.py/.sh/.js/.ts/.bat/.sql 等）
- **Reference 安全**：`[REFERENCE:filename.ext]` 只保存脚本文件，禁止保存 .json/.csv/.txt 等数据文件

## Hub/WebAccess/Chatbot 核心机制 ⚠️ UPDATED
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

## macOS Auto-Updater 签名绕过
> by nasakim | 验证: 2026-05-19
✅ [已验证] electron/main.ts
- Squirrel.Mac 验证签名导致 unsigned 应用更新失败，手动 fetch zip，unzip + codesign + rm + cp 替换

## 参考文档
- Hub：`references/hub-integration.md`，WebAccess：`references/web-access-ref-based-interaction.md`，Chatbot：`references/chatbot-multi-mode.md`
- Git 异步化：`references/WS-git-async-refactor.md`，Smart Path 指南：`references/WS-smartpath.generateStep.md`
