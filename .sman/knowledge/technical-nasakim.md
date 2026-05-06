# Technical — nasakim

> Last extracted: 2026-05-06T09:38:56.860Z

## WebSocket 客户端↔会话双向映射机制
<!-- hash: b7c8d9 -->
- `server/index.ts` 维护两个 Map：`clientToSessions`(WebSocket→Set<sessionId>) 和 `sessionToClients`(sessionId→Set<WebSocket>)
- 核心函数：subscribeClientToSession / unsubscribeClientFromSession / getSessionClients / sendToSessionClients
- 客户端断开时必须双向清理映射，防止内存泄漏和幽灵订阅
<!-- end: b7c8d9 -->

## 代码查看器与 Git 面板的 WebSocket API 设计
<!-- hash: e2c4f8 -->
- Handler 文件分离：`server/code-viewer-handler.ts` 和 `server/git-handler.ts`，各自处理对应域的 WebSocket 消息
- `code.*` 命名空间（5 个）：listDir, readFile, searchSymbols, saveFile, searchFiles
- `git.*` 命名空间（13 个）：status, diff, commit, push, log, checkout, fetch 等完整 Git 操作
- SDK 版本：`0.2.110` / `2.1.110`；代码编辑器使用 CodeMirror 6
<!-- end: e2c4f8 -->

## Meta Skills 注入机制
<!-- hash: 6t3r8p -->
- `server/init/init-manager.ts` 中 `META_SKILLS` 数组管理需注入的元技能（如 `clarify-requirements`、`skill-auto-updater`）
- `injectMetaSkills(workspace)` 从 `server/init/templates/{skill-name}/` 复制到 `{workspace}/.claude/skills/{skill-name}/`
- 注入策略：只注入一次，已存在则跳过（不覆盖用户自定义版本），确保新建会话自动具备对应能力
<!-- end: 6t3r8p -->