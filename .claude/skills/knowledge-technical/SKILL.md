---
name: knowledge-technical
description: "技术知识：API 细节、数据库 schema、三方集成、基础设施、算法。经代码验证，由 skill-auto-updater 聚合。"
_scanned:
  commitHash: "70d53baa472e0b2f87d9b0080e3239118c1f1ec7"
  scannedAt: "2026-05-22T00:00:00.000Z"
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

## IM 即时通讯系统 ⚠️ NEW
> by nasakim | 验证: 2026-05-22
✅ [已验证] server/im/*.ts (4 files, 500+ lines)
- **核心架构**：IMStore（SQLite）、IMWsHandler（WebSocket）、IMAgentBridge（Agent 激活）
- **数据库**：im_messages（11 字段含 mentioned_agents/quote_id/status/session_id）、im_rooms（7 字段含 members/last_message）
- **Agent 激活**：@mention → createAgentBridge → Claude 会话 → 流式响应 → 广播 delta（im.agent_delta），同一 workspace 复用 session

## 成就系统架构 ⚠️ UPDATED
> by nasakim | 验证: 2026-05-22
✅ [已验证] server/achievement-*.ts (4 files, 1100+ lines)
- **事件驱动**：EventEmitter，14 种事件，12 维度加权积分（会话 3 分，Token 0.000005 分，Smart Path 5 分）
- **等级系统**：10 个 Tier（bronze 0 → eternal 15000），阈值上调（star 2500/king 4000/legend 6000/epic 9000/eternal 15000）
- **数据库**：4 张表（progress/stats/streaks/board），每小时上传 Hub，支持按维度排序

## Smart Path 执行追踪与数据库 ⚠️ UPDATED
> by nasakim | 验证: 2026-05-22
✅ [已验证] server/smart-path-store.ts, server/smart-path-engine.ts
- **新增表**：smartpath_run_log（11 字段），full/stepping 模式，状态追踪（running/completed/failed），历史查询 LIMIT 50
- **文件命名**：步骤文件强制前缀 `step{N}_`

## Cron 定时任务手动触发 ⚠️ NEW
> by nasakim | 验证: 2026-05-22
✅ [已验证] server/cron-executor.ts, server/index.ts
- **手动触发**：`WS cron.trigger { taskId }`，lockFile.json 防重复，僵尸检测 30 分钟，状态回调通知前端

## Smart Path 步骤指南系统
> by nasakim | 验证: 2026-05-20
✅ [已验证] server/smart-path-engine.ts
- **guideChat()**：多轮对话生成操作指南（references/guide{n}.md），GuideChatPanel 流式输出，代码块最多 10 行
- **Skills 注入**：步骤配置 `skills?: string[]`，注入到 `[可使用的 Skills]`
- **Reference 白名单**：`SCRIPT_EXTENSIONS` 定义 19 种扩展名，只保存脚本文件

## Group 组合功能数据结构
> by nasakim | 验证: 2026-05-20
✅ [已验证] server/group-store.ts
- **数据库**：groups/group_tasks/group_subtasks 三张表（外键 + 级联删除），JSON 字段需 JSON.parse
- **WebSocket**：group.* 和 group-task.* 消息，Zustand store 同步

## Chatbot SDK 会话清除机制
> by nasakim | 验证: 2026-05-20
✅ [已验证] server/chatbot/chatbot-session-manager.ts
- **调用链**：abort() → closeV2Session() → clearSdkSessionId() → setIdleReset()，解决 V2 SDK 会话复用上下文残留

## Git 异步化重构
> by nasakim | 验证: 2026-05-19
✅ [已验证] server/git-handler.ts
- **异步化**：`promisify(execFile)` 替代 execSync，SKIP_DIRS/MAX_EXPAND_DEPTH(3)/MAX_EXPAND_FILES(500)

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
- Hub：`references/hub-integration.md`
- WebAccess：`references/web-access-ref-based-interaction.md`
- Chatbot：`references/chatbot-multi-mode.md`
- Git：`references/WS-git-async-refactor.md`
- Smart Path：`references/WS-smartpath.generateStep.md`
