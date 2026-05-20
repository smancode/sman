---
name: knowledge-technical
description: "技术知识：API 细节、数据库 schema、三方集成、基础设施、算法。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "353989234d641c959d8c0aa37aea150735c4ccd8"
  scannedAt: "2026-05-21T00:00:00.000Z"
  branch: "master"
---

# Technical Knowledge

> 贡献者: nasakim | 验证时间: 2026-05-21

## 核心架构
> by nasakim | 验证: 2026-05
✅ [已验证] CLAUDE.md:L60-120
- **后端**：Node.js + WebSocket/HTTP + SQLite + Claude Agent SDK v0.2.110
- **前端**：React 19 + Zustand，**桌面端**：Electron
- **SDK 集成**：`unstable_v2_createSession`，session_id 持久化，`await streamDone` 排队，30 分钟 idle 清理

## 成就系统架构 ⚠️ NEW
> by nasakim | 验证: 2026-05-21
✅ [已验证] server/achievement-*.ts (4 files, 1100+ lines)
- **事件驱动**：EventEmitter，14 种事件（session_created/message_sent/cron_executed/smartpath_run/bot_*）
- **加权积分**：12 维度权重求和（会话 3 分，Token 0.000005 分，Smart Path 5 分）
- **等级系统**：10 个 Tier（bronze 0 分 → eternal 10000 分），线性插值进度
- **数据库**：4 张表（achievement_progress/stats/streaks/board），WAL + 外键
- **排行榜**：每小时上传 Hub，支持按维度排序（json_extract）
- **数据回填**：首次启动扫描 sessions/messages/cron_runs，30 分钟定时对账

## Hub 成就排行榜 API ⚠️ NEW
> by nasakim | 验证: 2026-05-21
✅ [已验证] server/achievement-engine.ts
- **POST /api/achievement-report**：上报总分、等级、解锁数、维度原始值（JSON）
- **GET /api/achievement-leaderboard?dimension=xxx**：按维度排序
- **AES-256-GCM 加密**：复用 Hub PSK，10s 超时，失败静默降级

## Smart Path 文件命名规则 ⚠️ UPDATED
> by nasakim | 验证: 2026-05-21
✅ [已验证] server/smart-path-engine.ts
- **步骤前缀强制**：所有生成文件必须以 `step{N}_` 开头（覆盖 tmp/ 和 references/）
- **成就集成**：完成路径触发 `smartpath_run` 事件

## Smart Path 步骤指南系统
> by nasakim | 验证: 2026-05-20
✅ [已验证] server/smart-path-engine.ts
- **guideChat()**：多轮对话生成操作指南，存储 `references/guide{n}.md`
- **前端**：GuideChatPanel 流式输出，代码块最多 10 行
- **Skills 注入**：步骤配置 `skills?: string[]`，注入到 `[可使用的 Skills]`
- **Reference 白名单**：`SCRIPT_EXTENSIONS` 定义 19 种扩展名，只保存脚本文件

## Group 组合功能数据结构
> by nasakim | 验证: 2026-05-20
✅ [已验证] server/group-store.ts
- **数据库**：groups/group_tasks/group_subtasks 三张表（外键 + 级联删除）
- **JSON 字段**：workspaceIds 存储 TEXT，返回需 JSON.parse
- **WebSocket**：group.* 和 group-task.* 消息，Zustand store 同步

## Chatbot SDK 会话清除机制
> by nasakim | 验证: 2026-05-20
✅ [已验证] server/chatbot/chatbot-session-manager.ts
- **clearSdkSessionId()**：清除 SDK session ID（内存 + 数据库）
- **调用链**：abort() → closeV2Session() → clearSdkSessionId() → setIdleReset()
- **解决痛点**：V2 SDK 会话复用导致上下文残留

## Git 异步化重构
> by nasakim | 验证: 2026-05-19
✅ [已验证] server/git-handler.ts
- **异步化**：`promisify(execFile)` 替代 execSync，并行执行 git 命令
- **保护机制**：SKIP_DIRS、MAX_EXPAND_DEPTH（3 层）、MAX_EXPAND_FILES（500 个）

## Hub/WebAccess/Chatbot 核心机制
> by nasakim | 验证: 2026-05-17
✅ [已验证] server/hub/*.ts, server/web-access/cdp-engine.ts, server/chatbot/*.ts
- **Hub**：AES-256-GCM 加密，15 分钟心跳
- **WebAccess**：AX 树缓存，Ref 解析（nodeId → DOM objectId）
- **Chatbot**：full/query/collect 三模式，Session ID 含 botProfileId

## 基础设施
> by nasakim | 验证: 2026-05
✅ [已验证] server/index.ts, electron/main.ts
- **SQLite**：~/.sman/sman.db，better-sqlite3（WAL 模式）
- **WebSocket**：端口 5880，`clientToSessions`/`sessionToClients` 双向映射
- **macOS Auto-Updater**：Squirrel.Mac 签名绕过，手动 fetch zip + codesign + cp 替换

## 参考文档
- Hub：`references/hub-integration.md`，WebAccess：`references/web-access-ref-based-interaction.md`
- Chatbot：`references/chatbot-multi-mode.md`，Git 异步化：`references/WS-git-async-refactor.md`
- Smart Path：`references/WS-smartpath.generateStep.md`
