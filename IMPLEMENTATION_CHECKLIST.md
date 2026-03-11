# SMAN 重建实施清单（ClaudeCode-Only，详细版）

> 目标：在新仓 `~/projects/sman` 先做 **ClaudeCode 单执行器闭环**，不接 OpenCode，不依赖 ZeroClaw；ClaudeCode 调用协议统一走 **ACPX（Agent Client Protocol）**。

## 0. 当前已完成

- 已建立 TS 工程骨架（Vitest + TypeScript）。
- 已实现单一编排入口：`src/orchestrator.ts`。
- 已实现执行器注册与约束：`src/registry.ts`。
- 已实现 ClaudeCode 适配器：`src/claudecode-engine.ts`。
- 已引入 ACPX 客户端协议类型：`src/acpx.ts`。
- 已有测试覆盖：成功路径、执行异常路径、非法执行器路径、ACPX 调用路径。

## 1. 本周必须完成（P0）

### 1.1 执行器能力收敛（ClaudeCode Only）

1. 固化唯一执行器 ID：`claudecode`。
2. Registry 层拒绝所有非 `claudecode` 的 preferred_engine。
3. 执行器未注册时，返回明确错误（不可静默降级）。
4. 健康检查输出固定字段：`ready/version`。
5. 执行请求协议固定：`taskId/prompt`。

验收：

- 传入 `opencode` 必须报错；
- 未注册引擎必须报错；
- `claudecode` 正常执行时状态从 `running` -> `succeeded`。

### 1.2 单一状态流转与失败收敛

1. 任务进入执行前必须写 `running`。
2. 执行成功写 `succeeded`。
3. 执行异常写 `failed` 并抛出错误。
4. 非法引擎请求写 `failed` 并抛出错误。
5. 任务不存在返回 `task not found`。

验收：

- 任意异常场景不允许任务状态停留 `running`。

### 1.3 ACPX 调用契约（新增，必须先定）

1. 固化 ACPX 请求结构：`agent/taskId/prompt`。
2. 固化 ACPX 响应结构：`output`。
3. 固化 agent 常量值：`claudecode`。
4. 在 `createAcpxClaudeCodeRunner` 中统一做请求拼装。
5. 所有 ClaudeCode 调用禁止绕过 ACPX 直连。

验收：

- runner 调用必须命中 `client.invoke({ agent: "claudecode", ... })`；
- 非法 agent 不允许进入执行链；
- ACPX 异常必须传递到编排层并落 `failed` 状态。

### 1.4 最小可运行策略

1. 默认 runner 使用环境变量 `SMAN_CLAUDECODE_STUB_OUTPUT`。
2. 未配置 stub 时抛出清晰错误。
3. 支持注入 ACPX runner（优先）与自定义 runner（测试用途）。

验收：

- 注入 ACPX runner 时不依赖 stub 可执行；
- 未注入且未配置 stub 会抛出预期错误。

## 2. 两周内要完成（P1）

### 2.1 真实 ClaudeCode ACPX Client

1. 落地 `AcpxHttpClient`（或 IPC Client）实现。
2. 支持连接配置：`SMAN_ACPX_ENDPOINT`、`SMAN_ACPX_TOKEN`。
3. 增加请求超时：`SMAN_ACPX_TIMEOUT_MS`。
4. 增加重试策略（仅幂等请求）。
5. 标准化错误码映射（超时/鉴权/服务不可用）。

验收：

- mock ACPX 服务可联通；
- 超时与 401/500 错误能稳定映射；
- 调用链中不出现直连 Claude CLI 的旁路。

### 2.2 观测与证据

1. 为每次执行生成 execution_id。
2. 记录开始时间、结束时间、耗时。
3. 记录 engine id、task id、是否成功。
4. 记录 ACPX request_id 与错误摘要（不落敏感 prompt 原文）。
5. 统一证据结构，后续 UI 可直接消费。

验收：

- 一次执行结束后至少有 1 条结构化证据可查询。

### 2.3 持久化（先单文件）

1. 新增简单 JSON Store（`data/tasks.json`）。
2. 状态变更后落盘。
3. 启动时可恢复最新任务状态。
4. 加入损坏文件保护（备份 + 重建）。

验收：

- 重启后可读取最近状态；
- 文件损坏不崩溃。

## 3. 月内完成（P2）

### 3.1 API 层

1. 增加任务创建接口。
2. 增加任务执行接口（仅 claudecode，经 ACPX 调用）。
3. 增加任务查询接口。
4. 增加健康检查接口。
5. 增加执行证据查询接口。

验收：

- 可通过 API 完成“创建 -> 执行 -> 查询 -> 取证据”全链路。

### 3.2 UI 最小面板

1. 展示任务列表与状态。
2. 展示执行耗时、执行器、输出摘要。
3. 展示失败原因。
4. 提供重试按钮。

验收：

- 失败任务可重试并刷新状态。

## 4. TDD 执行规则（强制）

1. 先写测试再写实现。
2. 每个新增分支必须有测试。
3. 至少覆盖成功、失败、超时、非法输入四类场景。
4. ACPX 相关至少覆盖：成功、超时、鉴权失败、服务异常。
5. 回归测试不得跳过。

## 5. 发布门禁（必须全绿）

1. `npm run test`
2. `npm run typecheck`
3. `npm run lint`

门禁标准：

- 任一失败禁止发布。

## 6. 日常推进节奏

1. 每天开始先跑门禁。
2. 每完成一个任务小项就补测试。
3. 每天下班前更新本文件“完成项”打勾。
4. 每周做一次失败案例复盘并回写改进项。

## 7. 禁止事项

3. 暂不做多引擎回退。
4. 暂不做多租户。
5. 暂不允许绕过 ACPX 直接调用 ClaudeCode。

## 8. 当前下一步（按顺序执行）

1. 实现 `AcpxHttpClient` 并接入 `createAcpxClaudeCodeRunner`。
2. 增加超时测试、鉴权失败测试、服务异常测试。
3. 引入 execution evidence 结构并落本地文件。
4. 暴露最小 API：execute/status。
5. 接 UI 只读状态页。
