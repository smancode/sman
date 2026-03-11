# SMAN 全量迁移实施文档（审核版）

## 1. 结论先说

当前 `~/projects/sman` 只有 **ClaudeCode-only 编排最小骨架**，不等于“设计已全部落地”。

本文件定义的是：从 `~/projects/smanclaw/smanclaw-desktop` 迁移到 `~/projects/sman` 的完整方案，覆盖前端、桌面壳层、核心编排、测试与发布门禁，粒度细到目录和文件名，供你先审核。

---

## 2. 迁移目标与边界

### 2.1 目标

1. 目标仓：`~/projects/sman`
2. 技术基线：TypeScript 优先，调用 ClaudeCode 统一通过 ACPX。
3. 前端：完整迁移 SvelteKit 页面、组件、store、类型与测试。
4. 桌面壳：保留 Tauri 壳与必要命令桥接，但业务逻辑逐步转移到 TS 侧。
5. 迁移后保证每阶段可运行、可测试、可回滚。

### 2.2 本次明确不做

1. 不接 OpenCode。
2. 不做多引擎自动回退。
3. 不做多租户。
4. 不做额外平台端（Android/iOS）扩展。

---

## 3. 源仓关键目录（迁移输入）

源基线：`~/projects/smanclaw/smanclaw-desktop/crates/smanclaw-desktop`

### 3.1 前端源码（必须迁移）

- `src/routes/+layout.svelte`
- `src/routes/+page.svelte`
- `src/routes/settings/+page.svelte`
- `src/components/layout/Header.svelte`
- `src/components/layout/MainLayout.svelte`
- `src/components/layout/Sidebar.svelte`
- `src/components/chat/ChatWindow.svelte`
- `src/components/chat/CodeBlock.svelte`
- `src/components/chat/InputArea.svelte`
- `src/components/chat/MermaidRenderer.svelte`
- `src/components/chat/MessageBubble.svelte`
- `src/components/chat/SkillPicker.svelte`
- `src/components/project/ProjectCard.svelte`
- `src/components/project/ProjectList.svelte`
- `src/components/task/FileTree.svelte`
- `src/components/task/SubTaskProgress.svelte`
- `src/components/task/TaskProgress.svelte`
- `src/lib/api/tauri.ts`
- `src/lib/chat/assistantContent.ts`
- `src/lib/stores/projects.ts`
- `src/lib/stores/settings.ts`
- `src/lib/stores/tasks.ts`
- `src/lib/types/index.ts`
- `src/lib/__tests__/assistantContent.test.ts`
- `src/lib/__tests__/messageUpdate.test.ts`
- `src/lib/__tests__/tasksStoreEvents.test.ts`
- `src/app.css`
- `src/app.html`

### 3.2 桌面壳与命令桥（按优先级迁移）

- `src-tauri/src/commands/chat_execution.rs`
- `src-tauri/src/commands/conversation_commands.rs`
- `src-tauri/src/commands/history_runtime.rs`
- `src-tauri/src/commands/orchestration_decompose.rs`
- `src-tauri/src/commands/project_commands.rs`
- `src-tauri/src/commands/settings_commands.rs`
- `src-tauri/src/commands/task_commands.rs`
- `src-tauri/src/commands/utility_commands.rs`
- `src-tauri/src/orchestration/api.rs`
- `src-tauri/src/orchestration/mod.rs`
- `src-tauri/src/orchestration/remediation.rs`
- `src-tauri/src/orchestration/runtime.rs`
- `src-tauri/src/orchestration/status.rs`
- `src-tauri/src/orchestration/task_outcome.rs`
- `src-tauri/src/commands.rs`
- `src-tauri/src/error.rs`
- `src-tauri/src/events.rs`
- `src-tauri/src/lib.rs`
- `src-tauri/src/main.rs`
- `src-tauri/src/setup.rs`
- `src-tauri/src/state.rs`

---

## 4. 目标仓目录设计（审核后按此创建）

目标根目录：`~/projects/sman`

```text
sman/
├── package.json
├── tsconfig.json
├── vite.config.ts
├── svelte.config.js
├── src/
│   ├── core/
│   │   ├── orchestrator.ts
│   │   ├── registry.ts
│   │   ├── types.ts
│   │   ├── acpx.ts
│   │   └── claudecode-engine.ts
│   ├── ui/
│   │   ├── routes/
│   │   │   ├── +layout.svelte
│   │   │   ├── +page.svelte
│   │   │   └── settings/+page.svelte
│   │   ├── components/
│   │   │   ├── layout/
│   │   │   │   ├── Header.svelte
│   │   │   │   ├── MainLayout.svelte
│   │   │   │   └── Sidebar.svelte
│   │   │   ├── chat/
│   │   │   │   ├── ChatWindow.svelte
│   │   │   │   ├── CodeBlock.svelte
│   │   │   │   ├── InputArea.svelte
│   │   │   │   ├── MermaidRenderer.svelte
│   │   │   │   ├── MessageBubble.svelte
│   │   │   │   └── SkillPicker.svelte
│   │   │   ├── project/
│   │   │   │   ├── ProjectCard.svelte
│   │   │   │   └── ProjectList.svelte
│   │   │   └── task/
│   │   │       ├── FileTree.svelte
│   │   │       ├── SubTaskProgress.svelte
│   │   │       └── TaskProgress.svelte
│   │   ├── lib/
│   │   │   ├── api/tauri.ts
│   │   │   ├── chat/assistantContent.ts
│   │   │   ├── stores/projects.ts
│   │   │   ├── stores/settings.ts
│   │   │   ├── stores/tasks.ts
│   │   │   └── types/index.ts
│   │   ├── tests/
│   │   │   ├── assistantContent.test.ts
│   │   │   ├── messageUpdate.test.ts
│   │   │   └── tasksStoreEvents.test.ts
│   │   ├── app.css
│   │   └── app.html
│   └── bridge/
│       ├── tauri-events.ts
│       ├── tauri-commands.ts
│       └── runtime-gateway.ts
├── src-tauri/
│   ├── src/
│   │   ├── commands/
│   │   ├── orchestration/
│   │   ├── commands.rs
│   │   ├── error.rs
│   │   ├── events.rs
│   │   ├── lib.rs
│   │   ├── main.rs
│   │   ├── setup.rs
│   │   └── state.rs
│   └── tauri.conf.json
└── docs/
    └── migration/
        ├── file-mapping.csv
        ├── cutover-checklist.md
        └── rollback-guide.md
```

说明：
- `src/core` 只放编排核心与执行器。
- `src/ui` 只放 Svelte 前端。
- `src/bridge` 只放 Tauri 与前端的 TS 桥接。
- 每层职责单一，方便按模块并行迁移。

---

## 5. 文件级迁移映射（源 -> 目标）

### 5.1 前端路由与页面

1. `.../src/routes/+layout.svelte` -> `sman/src/ui/routes/+layout.svelte`
2. `.../src/routes/+page.svelte` -> `sman/src/ui/routes/+page.svelte`
3. `.../src/routes/settings/+page.svelte` -> `sman/src/ui/routes/settings/+page.svelte`

### 5.2 前端组件

1. `.../src/components/layout/Header.svelte` -> `sman/src/ui/components/layout/Header.svelte`
2. `.../src/components/layout/MainLayout.svelte` -> `sman/src/ui/components/layout/MainLayout.svelte`
3. `.../src/components/layout/Sidebar.svelte` -> `sman/src/ui/components/layout/Sidebar.svelte`
4. `.../src/components/chat/ChatWindow.svelte` -> `sman/src/ui/components/chat/ChatWindow.svelte`
5. `.../src/components/chat/CodeBlock.svelte` -> `sman/src/ui/components/chat/CodeBlock.svelte`
6. `.../src/components/chat/InputArea.svelte` -> `sman/src/ui/components/chat/InputArea.svelte`
7. `.../src/components/chat/MermaidRenderer.svelte` -> `sman/src/ui/components/chat/MermaidRenderer.svelte`
8. `.../src/components/chat/MessageBubble.svelte` -> `sman/src/ui/components/chat/MessageBubble.svelte`
9. `.../src/components/chat/SkillPicker.svelte` -> `sman/src/ui/components/chat/SkillPicker.svelte`
10. `.../src/components/project/ProjectCard.svelte` -> `sman/src/ui/components/project/ProjectCard.svelte`
11. `.../src/components/project/ProjectList.svelte` -> `sman/src/ui/components/project/ProjectList.svelte`
12. `.../src/components/task/FileTree.svelte` -> `sman/src/ui/components/task/FileTree.svelte`
13. `.../src/components/task/SubTaskProgress.svelte` -> `sman/src/ui/components/task/SubTaskProgress.svelte`
14. `.../src/components/task/TaskProgress.svelte` -> `sman/src/ui/components/task/TaskProgress.svelte`

### 5.3 前端逻辑与状态

1. `.../src/lib/api/tauri.ts` -> `sman/src/ui/lib/api/tauri.ts`
2. `.../src/lib/chat/assistantContent.ts` -> `sman/src/ui/lib/chat/assistantContent.ts`
3. `.../src/lib/stores/projects.ts` -> `sman/src/ui/lib/stores/projects.ts`
4. `.../src/lib/stores/settings.ts` -> `sman/src/ui/lib/stores/settings.ts`
5. `.../src/lib/stores/tasks.ts` -> `sman/src/ui/lib/stores/tasks.ts`
6. `.../src/lib/types/index.ts` -> `sman/src/ui/lib/types/index.ts`

### 5.4 前端测试

1. `.../src/lib/__tests__/assistantContent.test.ts` -> `sman/src/ui/tests/assistantContent.test.ts`
2. `.../src/lib/__tests__/messageUpdate.test.ts` -> `sman/src/ui/tests/messageUpdate.test.ts`
3. `.../src/lib/__tests__/tasksStoreEvents.test.ts` -> `sman/src/ui/tests/tasksStoreEvents.test.ts`

### 5.5 资源与样式

1. `.../src/app.css` -> `sman/src/ui/app.css`
2. `.../src/app.html` -> `sman/src/ui/app.html`

### 5.6 Tauri 命令桥（逐步）

1. `.../src-tauri/src/commands/*.rs` -> `sman/src-tauri/src/commands/*.rs`
2. `.../src-tauri/src/orchestration/*.rs` -> `sman/src-tauri/src/orchestration/*.rs`
3. `.../src-tauri/src/{commands,error,events,lib,main,setup,state}.rs` -> 同名迁移到 `sman/src-tauri/src/`

---

## 6. 分阶段实施（可执行清单）

## S0：冻结与基线

1. 冻结源仓前端功能变更，仅允许修 bug。
2. 固定迁移基线 commit，记录在 `docs/migration/file-mapping.csv`。
3. 生成源仓文件快照（含 hash）。

验收：
- 基线 commit 与文件清单可复现。

## S1：工程骨架搭建（目标仓）

1. 在 `sman` 引入 SvelteKit + Vite + Vitest + Tauri 依赖。
2. 建立第 4 节目录结构。
3. 配置脚本：`dev/build/test/check/lint/tauri:dev/tauri:build`。

验收：
- `npm run check`、`npm run test` 可在空壳下通过。

## S2：前端静态迁移

1. 先迁移 `app.css/app.html`。
2. 迁移 `routes` 与 `components`，修复 import 路径。
3. 页面可编译但暂不接后端真实数据。

验收：
- 页面可启动，路由可进入，组件无编译错误。

## S3：状态与类型迁移

1. 迁移 `lib/types/index.ts`。
2. 迁移 `stores/projects.ts`、`stores/settings.ts`、`stores/tasks.ts`。
3. 对接现有 `src/core` 的类型，去掉重复定义。

验收：
- store 初始化和订阅行为正常，类型检查通过。

## S4：ACPX + Tauri 网关对接

1. 迁移 `lib/api/tauri.ts` 到 `src/ui/lib/api/tauri.ts`。
2. 新增 `src/bridge/runtime-gateway.ts`，统一调用 `src/core/claudecode-engine.ts`。
3. 将 UI 执行入口统一改为 ACPX runner。

验收：
- 前端发起执行后，能经 ACPX 到 ClaudeCode 并回显状态。

## S5：测试迁移与补齐

1. 迁移 3 个前端测试文件。
2. 新增 ACPX 异常链路测试：超时、401、5xx、网络断开。
3. 新增状态机回归测试：`running -> failed` 覆盖非法引擎和运行时异常。

验收：
- 测试全绿；新增异常用例覆盖率达成。

## S6：Tauri 壳迁移

1. 复制 `src-tauri` 必需文件。
2. 先保留命令签名，逐步把业务逻辑替换成 TS/ACPX 网关调用。
3. 清理不再使用的 Rust 业务实现。

验收：
- `tauri:dev` 可运行，核心交互可用。

## S7：联调与灰度

1. 联调核心流程：创建任务 -> 执行 -> 状态更新 -> 历史记录。
2. 仅启用 claudecode 引擎。
3. 灰度发布内部版本，记录缺陷并修复。

验收：
- 核心流程稳定，无阻断级缺陷。

## S8：切换与回滚预案

1. 切换入口到 `sman`。
2. 保留 `smanclaw-desktop` 只读备份分支。
3. 发布后 24 小时内可一键回滚至旧版本。

验收：
- 切换成功，且回滚演练成功一次。

---

## 7. 门禁与验收标准（强制）

每个阶段合并前必须通过：

1. `npm run test`
2. `npm run typecheck` 或 `npm run check`
3. `npm run lint`
4. `npm run build`
5. `npm run tauri:build`（S6 后强制）

总体验收：

1. 前端页面、状态、聊天执行、设置页、任务进度完整可用。
2. 执行链仅走 claudecode + ACPX。
3. 旧仓同功能点在新仓可复现。
4. 回滚预案验证通过。

---

## 8. 风险与控制

1. 风险：UI 组件路径大改导致引用断裂。  
   控制：先批量迁移，再用路径别名统一修复。

2. 风险：Tauri 命令与 TS 类型不一致。  
   控制：先定义桥接层 DTO，再逐命令对齐。

3. 风险：ACPX 接口异常影响主链路。  
   控制：增加超时、重试、降噪错误提示和失败状态落盘。

4. 风险：迁移跨度大，测试滞后。  
   控制：按 S2/S3/S4 同步迁移测试，不允许最后补测。

---

## 9. 审核后立即执行顺序

1. 批准本文件目录结构与阶段拆分。
2. 锁定 `S0` 基线 commit。
3. 按 `S1 -> S2 -> S3` 连续推进，先把前端完整搬进 `sman` 并跑通。
4. 再进入 `S4+` 完成 ACPX 与 Tauri 的完整闭环。
