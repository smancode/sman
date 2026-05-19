---
_scanned.commitHash: "c63e3fcf76ba9e8b362d9d73ebccab934d1d998c"
_scanned.scannedAt: "2026-05-20T00:00:00.000Z"
_scanned.branch: "master"
---

# Development Conventions

Top 8 conventions from incremental scan (commit c63e3fcf76ba9e8b362d9d73ebccab934d1d998c).

## 1. Git 操作必须异步化

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/git-handler.ts

所有 Git 操作必须使用异步 `execFile` 而非同步 `execSync`：
- 使用 `promisify(execFile)` 包装成 Promise
- 参数拆分数组传入，避免 shell 注入
- 所有导出函数改为 `async`：`handleGitStatus`, `handleGitDiff`, `handleGitCommit` 等
- WebSocket 处理器用 `.then()/.catch()` 处理异步结果
- AI 冲突解决：git push 遇到冲突时创建 ephemeral session 自动解决

## 2. SDK 会话清理必须完整

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/chatbot/chatbot-session-manager.ts, server/claude-session.ts

Chatbot 会话超时或重置时，必须完整清理 V2 SDK 进程和会话 ID：
1. `abort()` 停止正在进行的流式响应
2. `closeV2Session()` 终止 V2 SDK 进程并删除映射
3. `clearSdkSessionId()` 清除 SDK 会话 ID，防止下次复用旧上下文
- 三步缺一不可，否则会导致上下文残留或僵尸进程

## 3. 脚本文件扩展名白名单

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/index.ts: SCRIPT_EXTENSIONS

Reference 文件只能保存脚本文件，禁止保存数据文件：
- 白名单扩展名：`.py, .sh, .bash, .zsh, .js, .ts, .mjs, .cjs, .bat, .cmd, .ps1, .sql, .r, .rb, .go, .java, .pl, .lua, .php, .rs, .dart, .kt, .scala, .clj`
- 应用场景：Smart Path 提取 `[REFERENCE:filename.ext]` 时过滤，Earth Path 步骤规则明确
- 禁止保存 `.json, .csv, .txt, .xlsx, .xml, .yaml, .yml` 等数据文件
- 设计原因：脚本可复用，数据文件应放在 `tmp/` 中避免耦合

## 4. Smart Path 规则放在 User Prompt

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/smart-path-engine.ts: buildStepPrompt

Smart Path 的步骤执行规则必须放在 **user prompt** 的 `[规则]` 段落，**绝对不能**修改 system prompt：
- `STEP_SYSTEM_PROMPT` 设为空字符串，规则全部移到 user prompt
- System prompt 是 session 级的，path 不应该污染它
- 步骤级别的限制（workspace skills 限制、脚本文件限制等）放在 user prompt 的 `[规则]` 段落

## 5. 步骤级 Skills 注入

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/smart-path-engine.ts: buildSkillsContext

Smart Path 步骤可以指定需要使用的 skills，动态注入到 prompt 中：
- 步骤定义中新增 `skills?: string[]` 字段
- 执行时读取 `workspace/.claude/skills/{skillId}/SKILL.md` 内容
- 直接注入到 user prompt 的 `[规则]` 段落之后
- 未指定 skills 时明确告知：`不使用 workspace/.claude/skills 中的 skill`

## 6. 指南内容持久化与注入

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/smart-path-engine.ts: guideChat, saveGuide

Smart Path 步骤指南必须持久化到 `references/guide{n}.md` 并在下次执行时注入：
- 指南文件命名：`{workspace}/.sman/paths/{pathId}/references/guide{stepIndex}.md`
- 首次执行后生成指南，后续执行自动注入
- 支持多轮对话调整指南内容
- 指南内容放在 `[规则]` 之前，确保优先级

## 7. 目录展开安全限制

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/git-handler.ts: expandDirFiles

Git status 中展开未跟踪目录时，必须限制深度和文件数量：
- 跳过常见大型目录：`node_modules, .git, dist, build, out, .next, .nuxt, target, vendor, __pycache__, .venv, venv, Pods, .gradle, .idea, .cache, coverage`
- 限制递归深度最多 3 层（`MAX_EXPAND_DEPTH=3`）
- 限制展开文件总数最多 500 个（`MAX_EXPAND_FILES=500`）
- 达到限制时直接返回，不抛异常

## 8. Group 会话隔离

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/group-store.ts, src/stores/group.ts

Group 功能的会话必须与普通会话完全隔离，避免上下文串：
- SQLite 表独立：`groups`, `group_tasks`, `group_subtasks`
- 外键级联删除：删除 group → 删除 tasks → 删除 subtasks
- Group 会话 ID 前缀：`chatbot-{botProfileId}-...`（复用 chatbot 会话管理）
- Group workspace 目录：`~/.sman/group/{groupId}/CLAUDE.md`
- 前端状态独立管理（Zustand），不影响普通会话树
