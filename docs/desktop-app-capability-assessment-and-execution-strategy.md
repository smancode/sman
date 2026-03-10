# SmanClaw 能力评估与执行层策略（UI + 编排层 + 可插拔执行层）

## 1. 目标与结论

### 1.1 目标

围绕以下三层能力做一次面向落地的评估与规划：

1. 自定义 UI 层（可观测、可解释、可操作）
2. 编排层（贴近业务的拆解、依赖、验收、补救）
3. 执行层（编码工具可插拔，支持 ClaudeCode/OpenCode 等快速切换）

### 1.2 核心结论

- 当前项目已经具备“可运行的编排产品雏形”，不是从 0 到 1 阶段。
- 强项在编排链路、任务文件协议、经验沉淀、事件流。
- 关键短板是后端入口未统一、状态主数据源未统一、执行引擎可插拔仍停留在单一 ZeroClaw 实现。
- 执行层应尽快升级为“统一 Executor SPI + Adapter Registry”，将 ClaudeCode/OpenCode 视为可热插拔执行器，而不是硬编码依赖。

---

## 2. 当前能力盘点（按三层）

## 2.1 UI 层（自定义能力）

### 已有能力

- 聊天窗口具备过程反馈与轮询收敛机制。
- 子任务进度与并行分组可视化已可用。
- 失败总结已支持按 task.md checklist 做“分析/设计/开发/测试”阶段拆解。

对应实现位置：

- `src/components/chat/ChatWindow.svelte`
- `src/components/task/SubTaskProgress.svelte`
- `src-tauri/src/orchestration/remediation.rs`

### 能力评分

- 当前成熟度：**70/100**
- 主要缺口：
  - “阶段说明 + 证据汇总”仍未形成稳定结构化面板；
  - 目前偏事件流拼接，不是强 schema 驱动的可审计视图。

## 2.2 编排层（业务贴合能力）

### 已有能力

- 路由能力已上线：`direct / orchestrated` 自动判定。
- Orchestrator 状态机与 DAG 执行链路已具备主流程。
- 子任务执行支持并行分组调度，支持失败补救与再验收。
- 经验沉淀链路（skills / paths / memory）已接通。

对应实现位置：

- `src-tauri/src/commands/conversation_commands.rs`
- `crates/smanclaw-core/src/orchestrator.rs`
- `src-tauri/src/orchestration/runtime.rs`
- `crates/smanclaw-core/src/sub_claw_executor.rs`

### 能力评分

- 当前成熟度：**75/100**
- 主要缺口：
  - direct 与 orchestrated 后端入口仍分叉；
  - `tasks.db` 与 `main-task.md` 双写，主数据源未统一；
  - 验收深度仍需从规则匹配进一步走向场景级验证证据。

## 2.3 执行层（编码工具可插拔能力）

### 已有能力

- 已有 StepExecutor 抽象，SubClawExecutor 可注入执行器。
- 当前默认执行器为 `ZeroclawStepExecutor`，经由 `ZeroclawBridge` 执行。
- 已存在 Claude skill run script 调用入口（`run.py/run.sh/run`）。
- 已落地 `ClaudeCodeStepExecutor` 与 `build_step_executor`，支持自动发现 Claude skill script。
- 编排主执行与补救执行都已改为统一走执行器工厂，具备“ClaudeCode 优先、ZeroClaw 回退”能力。

对应实现位置：

- `crates/smanclaw-core/src/sub_claw_executor.rs`（`StepExecutor` trait）
- `crates/smanclaw-ffi/src/zeroclaw_step_executor.rs`
- `crates/smanclaw-ffi/src/zeroclaw_bridge.rs`
- `crates/smanclaw-ffi/src/claude_code_step_executor.rs`
- `crates/smanclaw-ffi/src/step_executor_factory.rs`
- `crates/smanclaw-desktop/src-tauri/src/orchestration/runtime.rs`
- `crates/smanclaw-desktop/src-tauri/src/orchestration/remediation.rs`
- `crates/smanclaw-core/src/skill_store.rs`（`find_claude_skill_run_script`）

### 能力评分

- 当前成熟度：**68/100**
- 主要缺口：
  - 尚未形成完整注册中心（目前为工厂路由，非策略化 registry）；
  - 缺少能力声明、健康探测与可观测 SLA 指标；
  - 缺少 ACP/CLI 执行器统一协议层。

---

## 3. 关键问题：我们“真的能做到”吗？

结论：**能做到，但当前处于 60%~70% 的可交付基础阶段，不是 90% 的平台化阶段。**

### 已达到的“可做到”

- 可以完成真实的多步骤任务拆解与执行；
- 可以在失败后进入补救闭环；
- 可以把执行经验回流到技能库；
- 可以给出过程性进度与阶段性反馈。

### 仍未达到的“平台级做到”

- 单一后端入口状态机（当前仍双路径）；
- 单一事实源状态模型（当前 DB/Markdown 双写）；
- 执行器真正可插拔（当前逻辑上可注入，架构上未注册化）；
- 编排过程证据结构化展示（当前以文本事件为主）。

---

## 4. ClaudeCode 如何“打包封装进来”

## 4.1 目标形态

不是把 ClaudeCode 直接硬塞进主流程，而是把它作为执行层里的一个标准 Adapter：

- 编排层不感知“具体是谁执行代码”
- 只感知“执行能力契约 + 输出证据契约”

## 4.2 推荐封装方案（ACP 优先，CLI 兜底）

### A. 抽象统一协议（新增）

在 `smanclaw-core` 定义执行器 SPI：

- `ExecutionEngine`（执行引擎标识与能力）
- `ExecutionRequest`（prompt、workspace、constraints、timeout）
- `ExecutionResult`（output、artifacts、evidence、error）
- `EngineHealth`（ready/version/capabilities）

### B. Adapter 分层（新增）

在 `smanclaw-ffi` 增加：

- `acp_executor.rs`：通过 ACP 协议调用 ClaudeCode/OpenCode
- `cli_executor.rs`：通过命令行子进程调用（兼容 run.py/run.sh）
- `zeroclaw_executor.rs`：现有 ZeroClaw 执行器适配为统一接口

### C. 注册中心（新增）

新增 `EngineRegistry`：

- 按项目配置选择默认执行器（`claudecode` / `opencode` / `zeroclaw`）
- 执行失败自动按策略回退（如 ACP -> CLI -> ZeroClaw）
- 记录每次执行器选择与回退证据，写入事件流

### D. ClaudeCode 封装建议

- 输入：标准化 `ExecutionRequest`
- 过程：通过 ACP 调用 ClaudeCode（优先）
- 输出：标准化 `ExecutionResult`（必须包含命令、文件改动、测试结果）
- 安全：工作目录白名单、命令 allowlist、敏感信息屏蔽

## 4.3 本轮已落地实现（最小可用闭环）

- 新增 `ClaudeCodeStepExecutor`，具备两级后端：
  - 优先执行 `.claude/skills/*/run.py|run.sh|run`（当前默认检索 `code/coder/implement/coding`）
  - 可通过环境变量走命令模式（`SMANCLAW_CLAUDE_CODE_CMD` + `SMANCLAW_CLAUDE_CODE_ARGS`）
- 新增 `build_step_executor` 工厂：
  - `SMANCLAW_STEP_EXECUTOR=auto|claudecode|claude`
  - `auto` 下优先 ClaudeCode，不可用时自动回退 ZeroClaw
- `runtime` 与 `remediation` 两条执行链都已接入同一工厂，避免再次分叉执行器注入逻辑。
- 已补充执行器单测：skill script 路径、命令路径、失败路径。

---

## 5. 目标架构（你定义的三层）

## 5.1 自定义 UI

职责：

- 展示“阶段时间线 + 证据面板 + 补救轮次”
- 支持按阶段过滤证据（分析/设计/开发/测试）
- 支持查看执行器选择与回退记录

## 5.2 编排层（核心业务能力）

职责：

- 根据业务语义拆解任务并形成 DAG
- 定义验收标准并驱动补救闭环
- 提供“业务模板化编排能力”（行业/团队定制）

## 5.3 执行层（可插拔编码工具）

职责：

- 屏蔽底层执行工具差异（ClaudeCode/OpenCode/ZeroClaw）
- 提供统一能力声明与健康检查
- 对上游暴露稳定契约，支持快速替换

---

## 6. 改进清单（按优先级）

## P0（必须优先完成）

1. 统一后端执行入口为单一状态机入口。
2. 统一状态主数据源（定义 DB 与 Markdown 的权威关系）。
3. 建立执行器 SPI + Registry（先接 ZeroClaw 与 ClaudeCode 两种实现）。
4. 在 UI 落地阶段化证据面板（至少支持四阶段和补救轮次）。

## P1（增强可插拔与工程化）

1. 接入 OpenCode Adapter，验证多执行器切换。
2. 增加执行器健康检查、冷启动检测与预热机制。
3. 增加执行器 SLA 指标（成功率、平均耗时、回退率）。
4. 增加编排模板（按业务域差异化拆解策略）。

## P2（平台化）

1. 支持执行器灰度发布和 A/B 对比。
2. 支持多租户策略（不同项目默认执行器不同）。
3. 形成执行证据标准并用于审计与复盘。

---

## 7. 里程碑与验收标准（建议）

## M1：统一入口与状态（2 周）

- 验收：
  - direct/orchestrated 从同一入口进入；
  - 任一任务的状态可从单一来源回放；
  - 旧入口仅保留兼容转发。

## M2：执行器可插拔最小闭环（2 周）

- 验收：
  - ZeroClaw 与 ClaudeCode 可通过同一 SPI 执行；
  - 项目级可切换默认执行器；
  - 执行失败可自动回退并记录证据。

## M3：结构化可视化（2 周）

- 验收：
  - UI 展示分析/设计/开发/测试阶段；
  - 每阶段至少一条结构化证据；
  - 补救轮次可查看失败原因、修复动作、回归结果。

---

## 8. 风险与应对

- 风险：执行器抽象过度设计导致推进变慢。  
  应对：先做最小 SPI，只承载当前必需字段。

- 风险：多执行器输出格式不一致。  
  应对：强制 `ExecutionResult` schema，适配器做归一化。

- 风险：引入 ACP 后运维复杂度上升。  
  应对：保留 CLI fallback，先灰度到单项目。

---

## 9. 最终判断

你们的核心定位是正确的：

- **自定义 UI**：承载解释性与信任；
- **编排层**：承载业务差异化能力；
- **执行层可插拔**：承载技术路线灵活性与供应商解耦。

当前不缺方向，缺的是把“已有能力”收敛成“平台契约”。
下一阶段最关键的不是继续加功能，而是完成：**单一入口 + 单一状态源 + 执行器注册化** 三件事。
